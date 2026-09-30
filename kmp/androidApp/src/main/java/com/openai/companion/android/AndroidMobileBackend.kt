package com.openai.companion.android

import android.content.Context
import com.openai.companion.kmp.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import uniffi.harness.*

/** Android host for the shared Compose screen, controller, MCP protocol and Rust Harness. */
class AndroidMobileBackend(
    private val context: Context,
    private val model: AndroidLocalLlamaModel,
    private val notifications: AndroidNotificationSink,
    oauthBrowser: AndroidMcpOAuthBrowser,
    approve: suspend (String, String) -> Boolean,
    approveA2a: suspend (A2aDelegation) -> Boolean,
    requestInput: suspend (String, String, JsonObject) -> JsonObject,
) : MobileBackend {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val discoveryWake = Channel<Unit>(Channel.CONFLATED)
    private val gate = Mutex()
    private val syncGate = Mutex()
    private val bindings = GeneratedHarnessBindingsAdapter()
    private val codec = CompanionConversationCodec()
    private val mcp = AndroidMcpService(context, oauthBrowser, bindings, approve, requestInput)
    private val a2aHttp = HttpClient(OkHttp) {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    }
    private val memorySyncClient = MemorySyncClient(a2aHttp)
    private val syncPreferences = context.getSharedPreferences("memory_sync", Context.MODE_PRIVATE)
    private val syncSecrets = AndroidEncryptedPreferences(context)
    override var memorySyncEndpoint: String = syncPreferences.getString("endpoint", "") ?: ""
        private set
    private val mutableMemorySyncStatus = MutableStateFlow("未配置")
    override val memorySyncStatusUpdates: StateFlow<String> = mutableMemorySyncStatus.asStateFlow()
    override val memorySyncStatus: String get() = mutableMemorySyncStatus.value
    private val a2a = A2aClient(a2aHttp, AndroidA2aStore(), AndroidA2aTokenStore(context),
        scope, approveA2a)
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
    override val modelStatus: String get() = model.status()
    override val a2aAgents: StateFlow<List<A2aAgent>> get() = a2a.agents
    override val a2aTasks: StateFlow<List<A2aTask>> get() = a2a.tasks

    override suspend fun initialize() {
        if (initialized) return
        bindings.registerModelServeCallback(model)
        withContext(Dispatchers.IO) {
            appOpenStore(File(context.filesDir, "companion.sqlite").absolutePath).value()
        }
        mcp.start()
        a2a.start()
        registerA2aProvider(object : A2aProvider {
            override suspend fun listAgents() = a2a.listForModel()
            override suspend fun delegate(agentId: String, taskText: String) = a2a.delegate(agentId, taskText)
        })
        loadRules()
        proactiveSettings = Json.decodeFromString(appGetProactiveSettings().value())
        initialized = true
        requestPermissionForEnabledTasks()
        scope.launch {
            while (isActive) {
                try {
                    processDue()
                    val next = nextWake()
                    if (next == null) wake.receive()
                    else withTimeoutOrNull(((next - Instant.now().epochSecond) * 1_000).coerceAtLeast(1)) {
                        wake.receive()
                    }
                } catch (error: Exception) {
                    android.util.Log.w("Companion", "Proactive task deferred", error)
                    withTimeoutOrNull(60_000) { wake.receive() }
                }
            }
        }
        scope.launch { processPendingTurns() }
        scope.launch {
            while (isActive) {
                if (memorySyncEndpoint.isNotBlank()) runCatching { syncMemories() }
                delay(15 * 60 * 1_000L)
            }
        }
        scope.launch {
            while (isActive) {
                if (proactiveSettings.enabled) discover()
                if (proactiveSettings.enabled) {
                    withTimeoutOrNull(proactiveSettings.discoveryIntervalMinutes * 60_000L) {
                        discoveryWake.receive()
                    }
                } else discoveryWake.receive()
            }
        }
    }

    override suspend fun sessions(): List<MobileSession> = withContext(Dispatchers.IO) {
        codec.sessions(appListSessions().value()).map { MobileSession(it.id, it.preview) }
    }
    override suspend fun createSession(): Long = withContext(Dispatchers.IO) {
        codec.sessionId(appStartSession().value())
    }
    override suspend fun openSession(id: Long): List<MobileMessage> = withContext(Dispatchers.IO) {
        appResumeSession(id).value()
        codec.messages(appLoadSession(id).value()).map { MobileMessage(it.role, it.content) }
    }
    override suspend fun deleteSession(id: Long) = withContext(Dispatchers.IO) {
        appDeleteSession(id).value()
        Unit
    }
    override suspend fun send(text: String, onText: (String) -> Unit) = withContext(Dispatchers.IO) {
        gate.withLock {
            bindings.registerAgentEventSink(object : AppAgentEventSink {
                override fun onReasoningDelta(text: String) = Unit
                override fun onTextDelta(text: String) = onText(text)
                override fun onCompleted(finalText: String) = Unit
                override fun onError(errorJson: String) = Unit
            })
            try { codec.output(appSendMessage(text).value()) }
            finally { bindings.unregisterAgentEventSink() }
        }
        scope.launch { processPendingTurns() }
        if (memorySyncEndpoint.isNotBlank()) scope.launch {
            delay(15_000)
            runCatching { syncMemories() }
        }
        Unit
    }
    override fun cancel() {
        bindings.cancelAgentLoop()
        model.cancel()
    }
    fun onActivityResumed() {
        if (initialized) scope.launch { requestPermissionForEnabledTasks() }
    }
    private suspend fun requestPermissionForEnabledTasks() {
        if (proactiveSettings.enabled && proactiveTasks.any { it.enabled }) notifications.requestPermission()
    }
    override suspend fun saveProactiveConfig(settings: ProactiveSettings) {
        if (settings.enabled && !notifications.requestPermission()) error("请先允许 App 发送通知")
        proactiveSettings = Json.decodeFromString(appSetProactiveSettings(
            settings.enabled, settings.discoveryIntervalMinutes).value())
        wake.trySend(Unit)
        discoveryWake.trySend(Unit)
        if (settings.enabled) {
            scope.launch { processPendingTurns() }
            requestPermissionForEnabledTasks()
        }
    }
    override suspend fun configureMemorySync(endpoint: String, token: String) {
        if (endpoint.isBlank()) {
            syncPreferences.edit().remove("endpoint").apply()
            syncSecrets.remove("memory-sync-token")
            memorySyncEndpoint = ""
            mutableMemorySyncStatus.value = "未配置"
            return
        }
        val credential = token.ifBlank { syncSecrets.read("memory-sync-token") ?: "" }
        exchangeMemories(endpoint.trim(), credential)
        check(syncPreferences.edit().putString("endpoint", endpoint.trim()).commit())
        if (token.isNotBlank()) syncSecrets.write("memory-sync-token", token)
        memorySyncEndpoint = endpoint.trim()
    }

    override suspend fun syncMemories() {
        val endpoint = memorySyncEndpoint.takeIf(String::isNotBlank) ?: return
        val token = syncSecrets.read("memory-sync-token") ?: error("未设置记忆同步令牌")
        exchangeMemories(endpoint, token)
    }

    private suspend fun exchangeMemories(endpoint: String, token: String) = syncGate.withLock {
        mutableMemorySyncStatus.value = "同步中…"
        try {
            val local = gate.withLock { appExportMemorySync().value() }
            val remote = memorySyncClient.exchange(endpoint, token, local)
            val merged = gate.withLock { appMergeMemorySync(remote).value() }
            if (Json.parseToJsonElement(merged).jsonObject.getValue("changed").jsonPrimitive.content.toInt() > 0
                && proactiveSettings.enabled) scope.launch { discover() }
            mutableMemorySyncStatus.value = "已同步"
        } catch (error: Exception) {
            mutableMemorySyncStatus.value = "同步失败：${error.message ?: error}"
            throw error
        }
    }
    override suspend fun configureMcp(endpoint: String) {
        mcp.connect(endpoint)
        scope.launch { discover() }
    }
    override suspend fun authorizeMcp(clientId: String) = mcp.authorize(clientId)
    override suspend fun refreshMcp() {
        mcp.refresh()
        scope.launch { discover() }
    }
    override suspend fun importModel() {
        model.importModel()
        scope.launch { processPendingTurns() }
        scope.launch { discover() }
    }

    override suspend fun saveProactiveSettings(mealEnabled: Boolean, mealTime: String,
        commuteEnabled: Boolean, commuteTime: String) {
        if (proactiveSettings.enabled && (mealEnabled || commuteEnabled) && !notifications.requestPermission()) error("请先允许 App 发送通知")
        withContext(Dispatchers.IO) {
            val offset = timezoneOffset()
            appPutProactiveRule("meal", mealEnabled, minute(mealTime), 127, 70, offset).value()
            appPutProactiveRule("commute", commuteEnabled, minute(commuteTime), 31, 50, offset).value()
        }
        loadRules(); wake.trySend(Unit)
    }
    override suspend fun saveProactiveTask(task: ProactiveTask) {
        if (proactiveSettings.enabled && task.enabled && !notifications.requestPermission()) error("请先允许 App 发送通知")
        withContext(Dispatchers.IO) {
            appPutProactiveTask(Json.encodeToString(task.copy(timezoneOffsetMinutes = timezoneOffset(),
                nextRunAt = null, nextEventAt = null))).value()
        }
        loadRules(); wake.trySend(Unit)
    }
    override suspend fun deleteProactiveTask(id: String) {
        withContext(Dispatchers.IO) { appDeleteProactiveTask(id).value() }
        loadRules(); wake.trySend(Unit)
    }

    override suspend fun addA2aAgent(cardUrl: String) = a2a.addAgent(cardUrl)
    override suspend fun disableA2aAgent(id: String) = a2a.disableAgent(id)
    override suspend fun setA2aBearerToken(id: String, token: String) = a2a.setBearerToken(id, token)
    override suspend fun refreshA2aTasks() = a2a.refresh()
    override suspend fun replyToA2aTask(id: Long, text: String) = a2a.reply(id, text)
    override suspend fun declineA2aTask(id: Long) = a2a.decline(id)
    override suspend fun cancelA2aTask(id: Long) = a2a.cancel(id)

    private suspend fun loadRules() = withContext(Dispatchers.IO) {
        proactiveTasks = Json.decodeFromString(appListProactiveRules().value())
        mutableProactiveTasks.value = proactiveTasks
        proactiveTasks.forEach { rule ->
            val time = "%02d:%02d".format(rule.localMinute / 60, rule.localMinute % 60)
            when (rule.scenario) {
                "meal" -> { mealReminderEnabled = rule.enabled; mealTime = time }
                "commute" -> { commuteReminderEnabled = rule.enabled; commuteTime = time }
            }
        }
    }
    private fun timezoneOffset(): Long = ZoneId.systemDefault().rules
        .getOffset(Instant.now()).totalSeconds.toLong() / 60
    private fun minute(value: String): Long {
        require(Regex("^(?:[01][0-9]|2[0-3]):[0-5][0-9]$").matches(value)) { "时间须为 HH:mm" }
        return value.substring(0, 2).toLong() * 60 + value.substring(3, 5).toLong()
    }
    private fun nextWake(): Long? = Json.parseToJsonElement(appNextProactiveWakeAt().value())
        .jsonObject.getValue("at").jsonPrimitive.content.toLongOrNull()
    private suspend fun processDue() = gate.withLock {
        appRebaseProactiveRules(timezoneOffset()).value()
        appRunDueProactive().value()
        Json.parseToJsonElement(appReadyProactiveNotifications().value()).jsonArray.forEach { item ->
            val data = item.jsonObject
            val id = data.getValue("id").jsonPrimitive.content.toLong()
            if (notifications.deliver(id, data.getValue("title").jsonPrimitive.content,
                    data.getValue("body").jsonPrimitive.content)) {
                appMarkProactiveDelivered(id).value()
            }
        }
    }
    private suspend fun processPendingTurns() {
        try {
            val result = gate.withLock { Json.parseToJsonElement(
                appProcessPendingProactivePlans(timezoneOffset()).value()).jsonObject }
            if (result.getValue("changed").jsonPrimitive.content.toInt() > 0) {
                loadRules(); wake.trySend(Unit)
            }
            if (result.getValue("enabled").jsonPrimitive.content.toInt() > 0) notifications.requestPermission()
        } catch (error: Exception) { android.util.Log.w("Companion", "Turn planning deferred", error) }
    }
    private suspend fun discover() {
        try {
            val result = gate.withLock { Json.parseToJsonElement(
                appDiscoverProactiveTasks(timezoneOffset()).value()).jsonObject }
            if (result.getValue("changed").jsonPrimitive.content.toInt() > 0) {
                loadRules(); wake.trySend(Unit)
            }
            if (result.getValue("enabled").jsonPrimitive.content.toInt() > 0) notifications.requestPermission()
        } catch (error: Exception) { android.util.Log.w("Companion", "Discovery deferred", error) }
    }

    fun shutdown() {
        scope.cancel()
        a2aHttp.close()
        bindings.unregisterAgentEventSink()
        bindings.unregisterToolProvider()
        bindings.unregisterModelServeCallback()
        unregisterA2aProvider()
    }

    private fun AppResult.value(): String { check(ok) { error }; return valueJson }
}
