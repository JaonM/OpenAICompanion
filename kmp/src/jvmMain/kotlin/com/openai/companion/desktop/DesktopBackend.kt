package com.openai.companion.desktop

import com.openai.companion.kmp.GeneratedHarnessBindingsAdapter
import com.openai.companion.kmp.AppAgentEventSink
import com.openai.companion.kmp.CompanionConversationCodec
import com.openai.companion.kmp.KotlinSdkMcpClient
import com.openai.companion.kmp.McpServerManager
import com.openai.companion.kmp.McpTool
import com.openai.companion.kmp.ProactiveTask
import com.openai.companion.kmp.registerMcpProvider
import java.io.File
import java.net.URI
import java.util.prefs.Preferences
import java.util.concurrent.atomic.AtomicLong
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.harness.AppResult
import uniffi.harness.appDeleteSession
import uniffi.harness.appListSessions
import uniffi.harness.appLoadSession
import uniffi.harness.appOpenStore
import uniffi.harness.appResumeSession
import uniffi.harness.appSendMessage
import uniffi.harness.appStartSession
import uniffi.harness.appListProactiveRules
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
import uniffi.harness.cancelAgentLoop

data class DesktopSession(val id: Long, val preview: String)
data class DesktopMessage(val role: String, val content: String)
sealed interface DesktopStreamEvent {
    data class Reasoning(val text: String) : DesktopStreamEvent
    data class Text(val text: String) : DesktopStreamEvent
    data class Completed(val text: String) : DesktopStreamEvent
    data class Error(val json: String) : DesktopStreamEvent
}

data class McpApprovalRequest(val id: Long, val toolName: String, val argumentsJson: String)

class DesktopBackend(val modelServe: DesktopModelServe = DesktopModelServe()) {
    private val proactiveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val proactiveWake = Channel<Unit>(Channel.CONFLATED)
    private val notificationSink = DesktopNotificationSink()
    private val codec = CompanionConversationCodec()
    private val bindings = GeneratedHarnessBindingsAdapter()
    private val manager = McpServerManager()
    private val preferences = Preferences.userNodeForPackage(DesktopBackend::class.java)
    private val approvalGate = Mutex()
    private val approvalIDs = AtomicLong()
    private val approvalState = MutableStateFlow<McpApprovalRequest?>(null)
    val pendingMcpApproval = approvalState.asStateFlow()
    private var approvalReply: Pair<Long, CompletableDeferred<Boolean>>? = null
    private var initialized = false
    @Volatile var mealReminderEnabled: Boolean = false
        private set
    @Volatile var commuteReminderEnabled: Boolean = false
        private set
    @Volatile var mealTime: String = "19:00"
        private set
    @Volatile var commuteTime: String = "18:30"
        private set
    @Volatile var proactiveTasks: List<ProactiveTask> = emptyList()
        private set
    @Volatile var mcpEndpoint: String = preferences.get("mcpEndpoint", "")
        private set
    @Volatile var mcpStatus: String = "未连接 MCP"
        private set
    @Volatile private var mcpConnected = false

    suspend fun initialize() {
        if (initialized) return
        withContext(Dispatchers.IO) {
            val library = System.getenv("HARNESS_LIBRARY_PATH")
                ?: System.getProperty("compose.application.resources.dir")?.let {
                    File(it, "libharness.dylib").absolutePath
                }
                ?: File("../harness/target/release/libharness.dylib").absolutePath
            require(File(library).isFile) { "找不到 Rust Harness 动态库：$library" }
            System.setProperty("uniffi.component.harness.libraryOverride", library)
            bindings.registerModelServeCallback(modelServe)
            val support = System.getenv("COMPANION_DATA_DIR")?.takeIf(String::isNotBlank)
                ?.let(::File)
                ?: File(System.getProperty("user.home"),
                    "Library/Application Support/OpenAICompanion")
            require(support.isDirectory || support.mkdirs()) { "无法创建应用数据目录" }
            appOpenStore(File(support, "companion.sqlite").absolutePath).value()
        }
        registerMcpProvider(bindings, manager, ::approveMcpTool)
        initialized = true
        loadProactiveRules()
        proactiveScope.launch {
            while (isActive) {
                try {
                    processProactiveTasks()
                    val nextRun = nextProactiveWakeAt()
                    if (nextRun == null) {
                        proactiveWake.receive()
                    } else {
                        val waitMillis = ((nextRun - Instant.now().epochSecond) * 1_000)
                            .coerceAtLeast(1)
                        withTimeoutOrNull(waitMillis) { proactiveWake.receive() }
                    }
                } catch (error: Exception) {
                    System.err.println("主动任务暂缓：${error.message}")
                    withTimeoutOrNull(60_000) { proactiveWake.receive() }
                }
            }
        }
        if (mcpEndpoint.isNotEmpty()) {
            try {
                attachMcp(mcpEndpoint)
            } catch (error: Exception) {
                mcpStatus = "连接失败：${error.message ?: error}"
            }
        }
        proactiveScope.launch { processPendingProactivePlans() }
        proactiveScope.launch {
            while (isActive) {
                discoverProactiveTasks()
                delay(30 * 60 * 1_000L)
            }
        }
    }

    private fun timezoneOffsetMinutes(): Long =
        ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds.toLong() / 60

    private suspend fun loadProactiveRules() = withContext(Dispatchers.IO) {
        val raw = appListProactiveRules().value()
        proactiveTasks = Json.decodeFromString(raw)
        val rules = Json.parseToJsonElement(raw).jsonArray
        rules.forEach { item ->
            val rule = item.jsonObject
            val time = LocalTime.ofSecondOfDay(rule.getValue("local_minute").jsonPrimitive.content.toLong() * 60)
                .toString().take(5)
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

    suspend fun saveProactiveSettings(mealEnabled: Boolean, mealAt: String,
        commuteEnabled: Boolean, commuteAt: String) = withContext(Dispatchers.IO) {
        val mealMinute = LocalTime.parse(mealAt).toSecondOfDay().toLong() / 60
        val commuteMinute = LocalTime.parse(commuteAt).toSecondOfDay().toLong() / 60
        val offset = timezoneOffsetMinutes()
        appPutProactiveRule("meal", mealEnabled, mealMinute, 127, 70, offset).value()
        appPutProactiveRule("commute", commuteEnabled, commuteMinute, 31, 50, offset).value()
        mealReminderEnabled = mealEnabled
        commuteReminderEnabled = commuteEnabled
        mealTime = mealAt
        commuteTime = commuteAt
        proactiveWake.trySend(Unit)
        loadProactiveRules()
    }

    suspend fun saveProactiveTask(task: ProactiveTask) = withContext(Dispatchers.IO) {
        appPutProactiveTask(Json.encodeToString(task.copy(
            timezoneOffsetMinutes = timezoneOffsetMinutes(),
            nextRunAt = null, nextEventAt = null,
        ))).value()
        loadProactiveRules()
        proactiveWake.trySend(Unit)
    }

    suspend fun deleteProactiveTask(id: String) = withContext(Dispatchers.IO) {
        appDeleteProactiveTask(id).value()
        loadProactiveRules()
        proactiveWake.trySend(Unit)
    }

    private fun nextProactiveWakeAt(): Long? =
        Json.parseToJsonElement(appNextProactiveWakeAt().value()).jsonObject
            .getValue("at").jsonPrimitive.content.toLongOrNull()

    private fun processProactiveTasks() {
        appRebaseProactiveRules(timezoneOffsetMinutes()).value()
        appRunDueProactive().value()
        Json.parseToJsonElement(appReadyProactiveNotifications().value()).jsonArray.forEach { item ->
            val notification = item.jsonObject
            if (notificationSink.deliver(
                notification.getValue("title").jsonPrimitive.content,
                notification.getValue("body").jsonPrimitive.content,
            )) {
                appMarkProactiveDelivered(notification.getValue("id").jsonPrimitive.content.toLong()).value()
            }
        }
    }

    private fun processPendingProactivePlans() {
        if (!modelServe.isLocalEndpoint()) return
        try {
            val result = Json.parseToJsonElement(
                appProcessPendingProactivePlans(timezoneOffsetMinutes()).value()
            ).jsonObject
            if (result.getValue("changed").jsonPrimitive.content.toInt() > 0) {
                proactiveTasks = Json.decodeFromString(appListProactiveRules().value())
                proactiveWake.trySend(Unit)
            }
        } catch (error: Exception) {
            System.err.println("主动任务提取暂缓：${error.message}")
        }
    }

    private fun discoverProactiveTasks() {
        if (!modelServe.isLocalEndpoint()) return
        try {
            val result = Json.parseToJsonElement(
                appDiscoverProactiveTasks(timezoneOffsetMinutes()).value()
            ).jsonObject
            if (result.getValue("changed").jsonPrimitive.content.toInt() > 0) {
                proactiveTasks = Json.decodeFromString(appListProactiveRules().value())
                proactiveWake.trySend(Unit)
            }
        } catch (error: Exception) {
            System.err.println("主动机会发现暂缓：${error.message}")
        }
    }

    suspend fun sessions(): List<DesktopSession> = withContext(Dispatchers.IO) {
        codec.sessions(appListSessions().value()).map { session ->
            DesktopSession(session.id, session.preview)
        }
    }

    suspend fun newSession(): Long = withContext(Dispatchers.IO) {
        codec.sessionId(appStartSession().value())
    }

    suspend fun openSession(id: Long): List<DesktopMessage> = withContext(Dispatchers.IO) {
        appResumeSession(id).value()
        loadSession(id)
    }

    suspend fun loadSession(id: Long): List<DesktopMessage> = withContext(Dispatchers.IO) {
        codec.messages(appLoadSession(id).value()).map { message ->
            DesktopMessage(message.role, message.content)
        }
    }

    suspend fun send(message: String, onEvent: (DesktopStreamEvent) -> Unit = {}): String {
        refreshMcp()
        return withContext(Dispatchers.IO) {
        bindings.registerAgentEventSink(object : AppAgentEventSink {
            override fun onReasoningDelta(text: String) = onEvent(DesktopStreamEvent.Reasoning(text))
            override fun onTextDelta(text: String) = onEvent(DesktopStreamEvent.Text(text))
            override fun onCompleted(finalText: String) = onEvent(DesktopStreamEvent.Completed(finalText))
            override fun onError(errorJson: String) = onEvent(DesktopStreamEvent.Error(errorJson))
        })
        val output = try {
            codec.output(appSendMessage(message).value())
        } catch (error: Exception) {
            if (modelServe.lastError == "生成已取消" ||
                error.message?.contains("cancelled by user", ignoreCase = true) == true) {
                throw IllegalStateException("生成已取消", error)
            }
            throw IllegalStateException(modelServe.lastError ?: error.message.orEmpty(), error)
        } finally {
            bindings.unregisterAgentEventSink()
        }
        proactiveScope.launch { processPendingProactivePlans() }
        output
        }
    }

    suspend fun deleteSession(id: Long) = withContext(Dispatchers.IO) {
        appDeleteSession(id).value()
    }

    suspend fun connectMcp(endpoint: String) {
        if (endpoint.isBlank()) {
            manager.detach("remote")
            mcpEndpoint = ""
            mcpConnected = false
            mcpStatus = "未连接 MCP"
            preferences.remove("mcpEndpoint")
        } else {
            val normalized = endpoint.trim()
            validateMcpEndpoint(normalized)
            attachMcp(normalized)
            mcpEndpoint = normalized
            preferences.put("mcpEndpoint", normalized)
        }
    }

    private suspend fun attachMcp(endpoint: String) {
        manager.attach("remote", KotlinSdkMcpClient.create(endpoint))
        mcpConnected = true
        mcpStatus = "已连接 · ${manager.tools().size} 个工具"
        proactiveScope.launch { discoverProactiveTasks() }
    }

    private suspend fun refreshMcp() {
        val endpoint = mcpEndpoint
        if (endpoint.isEmpty()) return
        try {
            if (!mcpConnected) attachMcp(endpoint)
            else {
                manager.refresh()
                bindings.updateMcpTools(manager.tools().map {
                    McpTool(it.name, it.description, it.inputSchemaJson)
                })
                mcpStatus = "已连接 · ${manager.tools().size} 个工具"
            }
        } catch (error: Exception) {
            mcpStatus = "MCP 不可用：${error.message ?: error}"
            mcpConnected = false
            try { manager.detach("remote") }
            catch (_: Exception) { bindings.updateMcpTools(emptyList()) }
        }
    }

    private fun validateMcpEndpoint(value: String) {
        val uri = runCatching { URI.create(value) }.getOrNull()
            ?: error("MCP 地址无效")
        val host = uri.host?.lowercase() ?: error("MCP 地址缺少主机名")
        require(uri.userInfo == null && uri.fragment == null) { "MCP 地址不能包含凭据或片段" }
        require(uri.scheme == "https" || (uri.scheme == "http" && host in setOf("localhost", "127.0.0.1", "::1"))) {
            "远程 MCP 必须使用 HTTPS；只有本机地址允许 HTTP"
        }
    }

    fun cancel() {
        cancelAgentLoop()
        modelServe.cancelCurrentRequest()
    }

    fun answerMcpApproval(id: Long, approved: Boolean) {
        synchronized(this) {
            approvalReply?.takeIf { it.first == id }?.second?.complete(approved)
        }
    }

    private suspend fun approveMcpTool(name: String, argumentsJson: String): Boolean {
        approvalGate.lock()
        val id = approvalIDs.incrementAndGet()
        val reply = CompletableDeferred<Boolean>()
        try {
            synchronized(this) { approvalReply = id to reply }
            approvalState.value = McpApprovalRequest(id, name, argumentsJson)
            return withTimeoutOrNull(25_000) { reply.await() } ?: false
        } finally {
            approvalState.value = null
            synchronized(this) {
                if (approvalReply?.first == id) approvalReply = null
            }
            approvalGate.unlock()
        }
    }
}

private fun AppResult.value(): String {
    if (!ok) error(error)
    return valueJson
}
