package com.openai.companion.ios

import com.openai.companion.kmp.AppAgentEventSink
import com.openai.companion.kmp.AgentExecutionGate
import com.openai.companion.kmp.CrossDeviceService
import com.openai.companion.kmp.A2aAgent
import com.openai.companion.kmp.A2aClient
import com.openai.companion.kmp.A2aDelegation
import com.openai.companion.kmp.A2aTask
import com.openai.companion.kmp.AppModelServe
import com.openai.companion.kmp.CompanionConversationCodec
import com.openai.companion.kmp.IosMcpService
import com.openai.companion.kmp.IosA2aTokenStore
import com.openai.companion.kmp.MobileBackend
import com.openai.companion.kmp.MobileMessage
import com.openai.companion.kmp.MobileSession
import com.openai.companion.kmp.MemorySyncWorker
import com.openai.companion.kmp.MemorySyncCredentials
import com.openai.companion.kmp.MemorySyncClient
import com.openai.companion.kmp.ProactiveTask
import com.openai.companion.kmp.ProactiveSettings
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSUserDefaults
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusProvisional
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import uniffi.harness.AppResult
import uniffi.harness.appDeleteSession
import uniffi.harness.appListSessions
import uniffi.harness.appLoadSession
import uniffi.harness.appOpenStore
import uniffi.harness.appResumeSession
import uniffi.harness.appSendMessage
import uniffi.harness.appStartSession
import uniffi.harness.appListProactiveRules
import uniffi.harness.appGetProactiveSettings
import uniffi.harness.appSetProactiveSettings
import uniffi.harness.appNextProactiveWakeAt
import uniffi.harness.appPutProactiveRule
import uniffi.harness.appPutProactiveTask
import uniffi.harness.appDeleteProactiveTask
import uniffi.harness.appProcessPendingProactivePlans
import uniffi.harness.appDiscoverProactiveTasks
import uniffi.harness.appRebaseProactiveRules
import uniffi.harness.appRunDueProactive
import uniffi.harness.appReadyProactiveNotifications
import uniffi.harness.appMarkProactiveDelivered
import uniffi.harness.MemorySyncWake
import uniffi.harness.registerMemorySyncWake
import uniffi.harness.appPrepareMemorySync
import uniffi.harness.appAcknowledgeMemorySync
import uniffi.harness.appMemorySyncPending
import uniffi.harness.appMergeMemorySync

/** Kotlin/Native app backend: Compose → Harness, with the shared MCP provider registered once. */
class IosMobileBackend(
    approve: suspend (String, String) -> Boolean,
    approveA2a: suspend (A2aDelegation) -> Boolean,
    requestInput: suspend (String, String, JsonObject) -> JsonObject,
    private val modelServe: AppModelServe,
    private val importLocalModel: suspend () -> Unit,
    private val localModelStatus: () -> String,
    private val cancelLocalModel: () -> Unit,
) : MobileBackend {
    private val codec = CompanionConversationCodec()
    private val bindings = IosHarnessBindings()
    private val mcp = IosMcpService(bindings, approve, requestInput)
    private val a2aHttp = HttpClient(Darwin) {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    }
    private val a2a = A2aClient(a2aHttp, IosA2aStore(), IosA2aTokenStore(), CoroutineScope(SupervisorJob() + Dispatchers.Default), approveA2a)
    private val proactiveScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val proactiveWake = Channel<Unit>(Channel.CONFLATED)
    private val discoveryWake = Channel<Unit>(Channel.CONFLATED)
    private val agentGate = AgentExecutionGate()
    private val syncGate = Mutex()
    private val memorySyncWorker = MemorySyncWorker(
        enabled = { memorySyncEndpoint.isNotBlank() },
        pending = { appMemorySyncPending().value().toBooleanStrict() },
        sync = { syncMemories() },
    )
    private val syncDefaults = NSUserDefaults.standardUserDefaults
    private val syncTokens = IosA2aTokenStore()
    private val memorySyncClient = MemorySyncClient(a2aHttp)
    override var memorySyncEndpoint: String = syncDefaults.stringForKey("memorySyncEndpoint") ?: ""
        private set
    private val mutableMemorySyncStatus = MutableStateFlow("未配置")
    override val memorySyncStatusUpdates: StateFlow<String> = mutableMemorySyncStatus.asStateFlow()
    override val memorySyncStatus: String get() = mutableMemorySyncStatus.value
    private val devices = CrossDeviceService(a2aHttp, a2a, IosA2aTokenStore(),
        { syncDefaults.stringForKey("deviceTasksConfig") ?: "{}" },
        { syncDefaults.setObject(it, forKey = "deviceTasksConfig") }, "ios",
        { withContext(Dispatchers.Main) { platform.UIKit.UIApplication.sharedApplication.applicationState == platform.UIKit.UIApplicationState.UIApplicationStateActive } },
        mcp::tools, { request -> withContext(Dispatchers.Default) { agentGate.withLock { bindings.executeDeviceTask(request) } } },
        answerQuestion = { request -> withContext(Dispatchers.Default) { agentGate.withLock { bindings.answerDeviceQuestion(request) } } })
    override val deviceEndpoint get() = devices.endpoint
    override val deviceName get() = devices.deviceName
    override val deviceAcceptsTasks get() = devices.acceptsTasks
    override val deviceStatus get() = devices.status
    override suspend fun configureDevices(endpoint: String, token: String, name: String, accepts: Boolean) = devices.configure(endpoint, token, name, accepts)
    private var initialized = false
    override var mealReminderEnabled: Boolean = false
        private set
    override var commuteReminderEnabled: Boolean = false
        private set
    override var mealTime: String = "19:00"
        private set
    override var commuteTime: String = "18:30"
        private set
    override var proactiveTasks: List<ProactiveTask> = emptyList()
        private set
    override var proactiveSettings: ProactiveSettings = ProactiveSettings()
        private set
    private val mutableProactiveTasks = MutableStateFlow<List<ProactiveTask>>(emptyList())
    override val proactiveTaskUpdates: StateFlow<List<ProactiveTask>> = mutableProactiveTasks.asStateFlow()

    override val mcpEndpoint: String get() = mcp.endpoint
    override val mcpStatus: String get() = mcp.status
    override val mcpStatusUpdates: StateFlow<String> get() = mcp.statusUpdates
    override val modelStatus: String get() = localModelStatus()
    override val a2aAgents: StateFlow<List<A2aAgent>> get() = a2a.agents
    override val a2aTasks: StateFlow<List<A2aTask>> get() = a2a.tasks

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun initialize() {
        if (initialized) return
        bindings.registerModelServeCallback(modelServe)
        withContext(Dispatchers.Default) {
            val support = NSHomeDirectory() + "/Library/Application Support/OpenAICompanion"
            val created = NSFileManager.defaultManager.createDirectoryAtPath(
                support, withIntermediateDirectories = true, attributes = null, error = null,
            )
            require(created) { "无法创建应用数据目录" }
            appOpenStore("$support/companion.sqlite").value()
        }
        mcp.start()
        a2a.start()
        mcp.attachDeviceRouting(devices)
        devices.start(proactiveScope)
        bindings.registerA2aProvider(a2a::listForModel, a2a::delegate)
        loadProactiveRules()
        proactiveSettings = Json.decodeFromString(appGetProactiveSettings().value())
        initialized = true
        proactiveScope.launch {
            while (isActive) {
                try {
                    processProactiveTasks()
                    val nextRun = nextProactiveWakeAt()
                    if (nextRun == null) {
                        proactiveWake.receive()
                    } else {
                        val waitMillis = ((nextRun - Clock.System.now().epochSeconds) * 1_000)
                            .coerceAtLeast(1)
                        withTimeoutOrNull(waitMillis) { proactiveWake.receive() }
                    }
                } catch (error: Exception) {
                    println("主动任务暂缓：${error.message}")
                    withTimeoutOrNull(60_000) { proactiveWake.receive() }
                }
            }
        }
        proactiveScope.launch { processPendingProactivePlans() }
        registerMemorySyncWake(object : MemorySyncWake {
            override fun onPending() = memorySyncWorker.request()
        })
        proactiveScope.launch { memorySyncWorker.run() }
        proactiveScope.launch {
            while (isActive) {
                if (proactiveSettings.enabled) discoverProactiveTasks()
                if (proactiveSettings.enabled) {
                    withTimeoutOrNull(proactiveSettings.discoveryIntervalMinutes * 60_000L) {
                        discoveryWake.receive()
                    }
                } else discoveryWake.receive()
            }
        }
    }

    override suspend fun sessions(): List<MobileSession> = withContext(Dispatchers.Default) {
        codec.sessions(appListSessions().value()).map { MobileSession(it.id, it.preview) }
    }

    override suspend fun createSession(): Long = withContext(Dispatchers.Default) {
        codec.sessionId(appStartSession().value())
    }

    override suspend fun openSession(id: Long): List<MobileMessage> = withContext(Dispatchers.Default) {
        appResumeSession(id).value()
        codec.messages(appLoadSession(id).value()).map { MobileMessage(it.role, it.content) }
    }

    override suspend fun deleteSession(id: Long) = withContext(Dispatchers.Default) {
        appDeleteSession(id).value()
        Unit
    }

    override suspend fun send(text: String, onText: (String) -> Unit) = withContext(Dispatchers.Default) {
        agentGate.foreground {
        bindings.registerAgentEventSink(object : AppAgentEventSink {
            override fun onReasoningDelta(text: String) = Unit
            override fun onTextDelta(text: String) = onText(text)
            override fun onCompleted(finalText: String) = Unit
            override fun onError(errorJson: String) = Unit
        })
        try {
            codec.output(appSendMessage(text).value())
        } finally {
            bindings.unregisterAgentEventSink()
        }
        }
        proactiveScope.launch { processPendingProactivePlans() }

        Unit
    }

    private fun timezoneOffsetMinutes(): Long {
        val now = Clock.System.now()
        val local = now.toLocalDateTime(TimeZone.currentSystemDefault())
        val localSeconds = local.date.toEpochDays().toLong() * 86_400 +
            local.hour * 3_600 + local.minute * 60 + local.second
        return (localSeconds - now.epochSeconds) / 60
    }

    private suspend fun loadProactiveRules() = withContext(Dispatchers.Default) {
        val raw = appListProactiveRules().value()
        proactiveTasks = Json.decodeFromString(raw)
        mutableProactiveTasks.value = proactiveTasks
        Json.parseToJsonElement(raw).jsonArray.forEach { item ->
            val rule = item.jsonObject
            val minute = rule.getValue("local_minute").jsonPrimitive.content.toInt()
            val time = "${(minute / 60).toString().padStart(2, '0')}:${(minute % 60).toString().padStart(2, '0')}"
            when (rule.getValue("scenario").jsonPrimitive.content) {
                "meal" -> {
                    mealReminderEnabled = rule.getValue("enabled").jsonPrimitive.content.toBoolean()
                    mealTime = time
                }
                "commute" -> {
                    commuteReminderEnabled = rule.getValue("enabled").jsonPrimitive.content.toBoolean()
                    commuteTime = time
                }
            }
        }
    }

    override suspend fun saveProactiveSettings(mealEnabled: Boolean, mealTime: String,
        commuteEnabled: Boolean, commuteTime: String) {
        fun minute(value: String): Long {
            require(Regex("^(?:[01][0-9]|2[0-3]):[0-5][0-9]$").matches(value)) { "时间须为 HH:mm" }
            return value.substring(0, 2).toLong() * 60 + value.substring(3, 5).toLong()
        }
        val mealMinute = minute(mealTime)
        val commuteMinute = minute(commuteTime)
        if (proactiveSettings.enabled && (mealEnabled || commuteEnabled) && !requestNotificationPermission()) {
            error("请先允许 App 发送通知")
        }
        withContext(Dispatchers.Default) {
            val offset = timezoneOffsetMinutes()
            appPutProactiveRule("meal", mealEnabled, mealMinute, 127, 70, offset).value()
            appPutProactiveRule("commute", commuteEnabled, commuteMinute, 31, 50, offset).value()
        }
        mealReminderEnabled = mealEnabled
        commuteReminderEnabled = commuteEnabled
        this.mealTime = mealTime
        this.commuteTime = commuteTime
        proactiveWake.trySend(Unit)
        loadProactiveRules()
    }

    override suspend fun saveProactiveTask(task: ProactiveTask) {
        if (proactiveSettings.enabled && task.enabled && !requestNotificationPermission()) error("请先允许 App 发送通知")
        withContext(Dispatchers.Default) {
            appPutProactiveTask(Json.encodeToString(task.copy(
                timezoneOffsetMinutes = timezoneOffsetMinutes(),
                nextRunAt = null, nextEventAt = null,
            ))).value()
        }
        loadProactiveRules()
        proactiveWake.trySend(Unit)
    }

    override suspend fun saveProactiveConfig(settings: ProactiveSettings) {
        if (settings.enabled && !requestNotificationPermission()) error("请先允许 App 发送通知")
        proactiveSettings = Json.decodeFromString(appSetProactiveSettings(
            settings.enabled, settings.discoveryIntervalMinutes).value())
        proactiveWake.trySend(Unit)
        discoveryWake.trySend(Unit)
        if (settings.enabled) {
            proactiveScope.launch { processPendingProactivePlans() }
            if (proactiveTasks.any { it.enabled }) requestNotificationPermission()
        }
    }

    override suspend fun configureMemorySync(endpoint: String, token: String) = syncGate.withLock {
        if (endpoint.isBlank()) {
            syncDefaults.removeObjectForKey("memorySyncEndpoint")
            if (memorySyncEndpoint.isNotBlank()) syncTokens.delete(MemorySyncCredentials.key(memorySyncEndpoint))
            syncTokens.delete("memory-sync")
            memorySyncEndpoint = ""
            mutableMemorySyncStatus.value = "未配置"
            return@withLock
        }
        val credential = MemorySyncCredentials.resolve(endpoint, memorySyncEndpoint, token) { loadMemorySyncToken() }
        exchangeMemories(endpoint.trim(), credential)
        syncTokens.save(MemorySyncCredentials.key(endpoint), credential)
        syncTokens.delete("memory-sync")
        syncDefaults.setObject(endpoint.trim(), forKey = "memorySyncEndpoint")
        memorySyncEndpoint = endpoint.trim()
        memorySyncWorker.request()
    }

    private suspend fun loadMemorySyncToken(): String {
        val key = MemorySyncCredentials.key(memorySyncEndpoint)
        syncTokens.load(key)?.let { return it }
        val legacy = syncTokens.load("memory-sync") ?: error("未设置记忆同步令牌")
        syncTokens.save(key, legacy)
        syncTokens.delete("memory-sync")
        return legacy
    }

    override suspend fun syncMemories() = syncGate.withLock {
        val endpoint = memorySyncEndpoint.takeIf(String::isNotBlank) ?: return@withLock
        val token = loadMemorySyncToken()
        exchangeMemories(endpoint, token)
    }

    private suspend fun exchangeMemories(endpoint: String, token: String) {
        mutableMemorySyncStatus.value = "同步中…"
        try {
            val local = agentGate.withLock { appPrepareMemorySync().value() }
            val batch = Json.parseToJsonElement(local).jsonObject
            val generation = batch.getValue("generation").jsonPrimitive.content.toLong()
            val records = batch.getValue("records").toString()
            var changed = false
            memorySyncClient.exchange(endpoint, token, records) { page ->
                val merged = agentGate.withLock { appMergeMemorySync(page).value() }
                changed = changed || Json.parseToJsonElement(merged).jsonObject
                    .getValue("changed").jsonPrimitive.content.toInt() > 0
            }
            appAcknowledgeMemorySync(generation).value()
            if (changed
                && proactiveSettings.enabled) proactiveScope.launch { discoverProactiveTasks() }
            mutableMemorySyncStatus.value = "已同步"
        } catch (error: Exception) {
            mutableMemorySyncStatus.value = "同步失败：${error.message ?: error}"
            if (error !is kotlinx.coroutines.CancellationException) memorySyncWorker.request()
            throw error
        }
    }

    override suspend fun deleteProactiveTask(id: String) {
        withContext(Dispatchers.Default) { appDeleteProactiveTask(id).value() }
        loadProactiveRules()
        proactiveWake.trySend(Unit)
    }

    private fun nextProactiveWakeAt(): Long? =
        Json.parseToJsonElement(appNextProactiveWakeAt().value()).jsonObject
            .getValue("at").jsonPrimitive.content.toLongOrNull()

    private suspend fun requestNotificationPermission(): Boolean = withContext(Dispatchers.Main) {
        suspendCoroutine { continuation ->
            UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
                UNAuthorizationOptionAlert or UNAuthorizationOptionSound,
            ) { granted, _ -> continuation.resume(granted) }
        }
    }

    private suspend fun processProactiveTasks() = agentGate.withLock {
        appRebaseProactiveRules(timezoneOffsetMinutes()).value()
        appRunDueProactive().value()
        Json.parseToJsonElement(appReadyProactiveNotifications().value()).jsonArray.forEach { item ->
            val notification = item.jsonObject
            val id = notification.getValue("id").jsonPrimitive.content.toLong()
            if (deliverNotification(id,
                notification.getValue("title").jsonPrimitive.content,
                notification.getValue("body").jsonPrimitive.content)) {
                appMarkProactiveDelivered(id).value()
            }
        }
    }

    private suspend fun processPendingProactivePlans() {
        try {
            val result = agentGate.withLock {
                Json.parseToJsonElement(
                    appProcessPendingProactivePlans(timezoneOffsetMinutes()).value()
                ).jsonObject
            }
            if (result.getValue("changed").jsonPrimitive.content.toInt() > 0) {
                loadProactiveRules()
                proactiveWake.trySend(Unit)
            }
            if (result.getValue("enabled").jsonPrimitive.content.toInt() > 0) {
                requestNotificationPermission()
            }
        } catch (error: Exception) {
            println("主动任务提取暂缓：${error.message}")
        }
    }

    private suspend fun discoverProactiveTasks() {
        try {
            val result = agentGate.withLock {
                Json.parseToJsonElement(
                    appDiscoverProactiveTasks(timezoneOffsetMinutes()).value()
                ).jsonObject
            }
            if (result.getValue("changed").jsonPrimitive.content.toInt() > 0) {
                loadProactiveRules()
                proactiveWake.trySend(Unit)
            }
            if (result.getValue("enabled").jsonPrimitive.content.toInt() > 0) {
                requestNotificationPermission()
            }
        } catch (error: Exception) {
            println("主动机会发现暂缓：${error.message}")
        }
    }

    private suspend fun deliverNotification(id: Long, title: String, body: String): Boolean =
        suspendCoroutine { continuation ->
            val center = UNUserNotificationCenter.currentNotificationCenter()
            center.getNotificationSettingsWithCompletionHandler { settings ->
                if (settings == null || (settings.authorizationStatus != UNAuthorizationStatusAuthorized &&
                    settings.authorizationStatus != UNAuthorizationStatusProvisional)) {
                    continuation.resume(false)
                    return@getNotificationSettingsWithCompletionHandler
                }
            val content = UNMutableNotificationContent().apply {
                setTitle(title)
                setBody(body)
            }
            val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(1.0, false)
            val request = UNNotificationRequest.requestWithIdentifier("proactive-$id", content, trigger)
            center.addNotificationRequest(request) { error ->
                continuation.resume(error == null)
            }
            }
        }

    override fun cancel() {
        agentGate.cancelForeground {
            bindings.cancelAgentLoop()
            cancelLocalModel()
        }
    }

    override suspend fun configureMcp(endpoint: String) {
        mcp.connect(endpoint)
        proactiveScope.launch { discoverProactiveTasks() }
    }

    override suspend fun authorizeMcp(clientId: String) = mcp.authorize(clientId)

    override suspend fun refreshMcp() {
        mcp.refresh()
        proactiveScope.launch { discoverProactiveTasks() }
    }

    override suspend fun importModel() {
        importLocalModel()
        proactiveScope.launch { processPendingProactivePlans() }
        proactiveScope.launch { discoverProactiveTasks() }
    }

    override suspend fun addA2aAgent(cardUrl: String) = a2a.addAgent(cardUrl)
    override suspend fun disableA2aAgent(id: String) = a2a.disableAgent(id)
    override suspend fun setA2aBearerToken(id: String, token: String) = a2a.setBearerToken(id, token)
    override suspend fun refreshA2aTasks() = a2a.refresh()
    override suspend fun replyToA2aTask(id: Long, text: String) = a2a.reply(id, text)
    override suspend fun declineA2aTask(id: Long) = a2a.decline(id)
    override suspend fun cancelA2aTask(id: Long) = a2a.cancel(id)

    private fun AppResult.value(): String {
        check(ok) { error }
        return valueJson
    }
}
