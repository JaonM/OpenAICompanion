package com.openai.companion.kmp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface MobileBackend {
    suspend fun initialize()
    suspend fun sessions(): List<MobileSession>
    suspend fun createSession(): Long
    suspend fun openSession(id: Long): List<MobileMessage>
    suspend fun deleteSession(id: Long)
    suspend fun send(text: String, onText: (String) -> Unit)
    suspend fun send(text: String, onText: (String) -> Unit, onReasoning: (String) -> Unit) = send(text, onText)
    fun cancel()
    suspend fun configureMcp(endpoint: String)
    suspend fun authorizeMcp(clientId: String)
    suspend fun refreshMcp()
    val deviceToolsEnabled: Boolean get() = false
    suspend fun setDeviceToolsEnabled(enabled: Boolean) = Unit
    suspend fun importModel()
    val mealReminderEnabled: Boolean get() = false
    val commuteReminderEnabled: Boolean get() = false
    val mealTime: String get() = "19:00"
    val commuteTime: String get() = "18:30"
    suspend fun saveProactiveSettings(mealEnabled: Boolean, mealTime: String,
        commuteEnabled: Boolean, commuteTime: String) = Unit
    val proactiveTasks: List<ProactiveTask> get() = emptyList()
    val proactiveSettings: ProactiveSettings get() = ProactiveSettings()
    val backgroundReminderStatus: StateFlow<String>? get() = null
    val reminderSettingsAvailable: Boolean get() = false
    fun openReminderSettings() = Unit
    suspend fun saveProactiveConfig(settings: ProactiveSettings) = Unit
    val memorySyncEndpoint: String get() = ""
    val memorySyncStatus: String get() = "未配置"
    val memorySyncStatusUpdates: StateFlow<String>? get() = null
    suspend fun configureMemorySync(endpoint: String, token: String) = Unit
    suspend fun syncMemories() = Unit
    val proactiveTaskUpdates: StateFlow<List<ProactiveTask>>? get() = null
    suspend fun saveProactiveTask(task: ProactiveTask) = Unit
    suspend fun deleteProactiveTask(id: String) = Unit
    val deviceEndpoint: String get() = ""
    val deviceName: String get() = ""
    val deviceAcceptsTasks: Boolean get() = false
    val deviceStatus: StateFlow<String>? get() = null
    suspend fun configureDevices(endpoint: String, token: String, name: String, accepts: Boolean) = Unit
    suspend fun pairDevice(endpoint: String, code: String, name: String, accepts: Boolean) = Unit
    val a2aAgents: StateFlow<List<A2aAgent>>? get() = null
    val a2aTasks: StateFlow<List<A2aTask>>? get() = null
    suspend fun addA2aAgent(cardUrl: String) = Unit
    suspend fun disableA2aAgent(id: String) = Unit
    suspend fun setA2aBearerToken(id: String, token: String) = Unit
    suspend fun refreshA2aTasks() = Unit
    suspend fun replyToA2aTask(id: Long, text: String) = Unit
    suspend fun declineA2aTask(id: Long) = Unit
    suspend fun cancelA2aTask(id: Long) = Unit
    val mcpEndpoint: String
    val mcpStatus: String
    val mcpStatusUpdates: StateFlow<String>? get() = null
    val modelStatus: String
    val modelEngines: List<String> get() = emptyList()
    val modelEngine: String get() = "llama.cpp"
    val modelImportLabel: String get() = "导入 GGUF"
    suspend fun selectModelEngine(engine: String) = Unit
}

/** Shared presentation and approval lifecycle for the Compose mobile screen. */
class MobileController(
    private val scope: CoroutineScope,
    backendFactory: ((suspend (String, String) -> Boolean), (suspend (String, String, JsonObject) -> JsonObject), (suspend (A2aDelegation) -> Boolean)) -> MobileBackend,
) : MobileActions {
    constructor(
        scope: CoroutineScope,
        backendFactory: ((suspend (String, String) -> Boolean), (suspend (String, String, JsonObject) -> JsonObject)) -> MobileBackend,
    ) : this(scope, { approve, input, _ -> backendFactory(approve, input) })

    private val backend = backendFactory(::approveTool, ::requestInput, ::approveDelegation)
    private val mutableState = MutableStateFlow(MobileUiState())
    val state: StateFlow<MobileUiState> = mutableState.asStateFlow()
    private val approvalGate = Mutex()
    private var nextApprovalId = 1L
    private val pendingApproval = MutableStateFlow<Pair<Long, CompletableDeferred<Boolean>>?>(null)
    private val approvalGeneration = MutableStateFlow(0L)
    private val pendingInput = MutableStateFlow<Pair<Long, CompletableDeferred<String?>>?>(null)
    private val pendingA2aApproval = MutableStateFlow<Pair<Long, CompletableDeferred<Boolean>>?>(null)

    fun start() = scope.launch {
        backend.backgroundReminderStatus?.let { updates ->
            scope.launch { updates.collect { value -> mutableState.update { it.copy(backgroundReminderStatus = value) } } }
        }
        backend.deviceStatus?.let { updates ->
            scope.launch { updates.collect { value -> mutableState.update { it.copy(deviceStatus = value) } } }
        }
        backend.a2aAgents?.let { updates ->
            scope.launch { updates.collect { value -> mutableState.update { it.copy(a2aAgents = value) } } }
        }
        backend.a2aTasks?.let { updates ->
            scope.launch { updates.collect { value -> mutableState.update { it.copy(a2aTasks = value) } } }
        }
        backend.mcpStatusUpdates?.let { updates ->
            scope.launch {
                updates.collect { value -> mutableState.update { it.copy(mcpStatus = value) } }
            }
        }
        backend.proactiveTaskUpdates?.let { updates ->
            scope.launch {
                updates.collect { value -> mutableState.update { it.copy(proactiveTasks = value) } }
            }
        }
        backend.memorySyncStatusUpdates?.let { updates ->
            scope.launch { updates.collect { value ->
                mutableState.update { it.copy(memorySyncStatus = value) }
            } }
        }
        perform {
            backend.initialize()
            syncSettings()
            val sessions = backend.sessions()
            val id = sessions.firstOrNull()?.id ?: backend.createSession()
            val messages = backend.openSession(id)
            mutableState.update {
                it.copy(sessions = backend.sessions(), activeSessionId = id, messages = messages)
            }
        }
    }

    override fun createSession() {
        if (state.value.sending) return
        scope.launch {
            perform {
                val id = backend.createSession()
                mutableState.update {
                    it.copy(
                        sessions = backend.sessions(), activeSessionId = id,
                        messages = emptyList(), streamedText = "",
                    )
                }
            }
        }
    }

    override fun closeSession() {
        if (state.value.sending) return
        mutableState.update { it.copy(activeSessionId = null, messages = emptyList(), streamedText = "") }
    }

    override fun openSession(id: Long) {
        if (state.value.sending) return
        scope.launch {
            perform {
                val messages = backend.openSession(id)
                mutableState.update {
                    it.copy(activeSessionId = id, messages = messages, streamedText = "")
                }
            }
        }
    }

    override fun deleteSession(id: Long) {
        if (state.value.sending) return
        scope.launch {
            perform {
                backend.deleteSession(id)
                mutableState.update {
                    it.copy(
                        sessions = backend.sessions(),
                        activeSessionId = it.activeSessionId.takeUnless { active -> active == id },
                        messages = if (it.activeSessionId == id) emptyList() else it.messages,
                    )
                }
            }
        }
    }

    override fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || state.value.activeSessionId == null || state.value.sending || state.value.modelChanging) return
        val id = state.value.activeSessionId ?: return
        mutableState.update {
            it.copy(
                sending = true, error = null, streamedText = "", streamedReasoning = "",
                messages = it.messages + MobileMessage("user", trimmed),
            )
        }
        scope.launch {
            try {
                backend.refreshMcp()
                syncSettings()
                backend.send(trimmed, onText = { delta ->
                    mutableState.update { it.copy(streamedText = it.streamedText + delta) }
                }, onReasoning = { delta ->
                    mutableState.update { it.copy(streamedReasoning = boundedReasoning(it.streamedReasoning, delta)) }
                })
                mutableState.update {
                    it.copy(messages = backend.openSession(id), sessions = backend.sessions())
                }
            } catch (error: Exception) {
                mutableState.update { it.copy(error = error.message ?: error.toString()) }
                runCatching { backend.openSession(id) }.getOrNull()?.let { messages ->
                    mutableState.update { it.copy(messages = messages) }
                }
            } finally {
                syncSettings()
                mutableState.update { it.copy(sending = false, streamedText = "", streamedReasoning = "") }
            }
        }
    }

    override fun cancel() {
        approvalGeneration.update { it + 1 }
        pendingApproval.value?.second?.complete(false)
        pendingInput.value?.second?.complete(null)
        pendingA2aApproval.value?.second?.complete(false)
        backend.cancel()
    }

    override fun configureMcp(endpoint: String) {
        launchMcpAction { backend.configureMcp(endpoint) }
    }

    override fun openReminderSettings() = backend.openReminderSettings()

    override fun setDeviceToolsEnabled(enabled: Boolean) {
        scope.launch { perform { backend.setDeviceToolsEnabled(enabled); syncSettings() } }
    }

    override fun authorizeMcp(clientId: String) {
        launchMcpAction { backend.authorizeMcp(clientId) }
    }

    override fun selectModelEngine(engine: String) {
        if (state.value.sending || state.value.modelChanging) return
        mutableState.update { it.copy(modelChanging = true) }
        scope.launch {
            try { perform { backend.selectModelEngine(engine); syncSettings() } }
            finally { mutableState.update { it.copy(modelChanging = false) } }
        }
    }

    override fun importModel() {
        if (state.value.sending || state.value.modelChanging) return
        mutableState.update { it.copy(modelChanging = true) }
        scope.launch {
            try { perform { backend.importModel(); syncSettings() } }
            finally { mutableState.update { it.copy(modelChanging = false) } }
        }
    }

    override fun saveProactiveSettings(mealEnabled: Boolean, mealTime: String,
        commuteEnabled: Boolean, commuteTime: String) {
        scope.launch {
            perform { backend.saveProactiveSettings(mealEnabled, mealTime, commuteEnabled, commuteTime) }
            syncSettings()
        }
    }

    override fun saveProactiveTask(task: ProactiveTask) {
        scope.launch {
            perform { backend.saveProactiveTask(task) }
            syncSettings()
        }
    }

    override fun saveProactiveConfig(settings: ProactiveSettings) {
        scope.launch {
            perform { backend.saveProactiveConfig(settings) }
            syncSettings()
        }
    }

    override fun configureMemorySync(endpoint: String, token: String) {
        scope.launch { perform { backend.configureMemorySync(endpoint, token) }; syncSettings() }
    }

    override fun syncMemories() {
        scope.launch { perform { backend.syncMemories() }; syncSettings() }
    }

    override fun deleteProactiveTask(id: String) {
        scope.launch {
            perform { backend.deleteProactiveTask(id) }
            syncSettings()
        }
    }

    override fun configureDevices(endpoint: String, token: String, name: String, accepts: Boolean) {
        scope.launch { perform { backend.configureDevices(endpoint, token, name, accepts) }; syncSettings() }
    }

    override fun pairDevice(endpoint: String, code: String, name: String, accepts: Boolean) {
        scope.launch { perform { backend.pairDevice(endpoint, code, name, accepts); syncSettings() } }
    }

    override fun addA2aAgent(cardUrl: String) = launchA2aAction { backend.addA2aAgent(cardUrl) }
    override fun disableA2aAgent(id: String) = launchA2aAction { backend.disableA2aAgent(id) }
    override fun setA2aBearerToken(id: String, token: String) = launchA2aAction { backend.setA2aBearerToken(id, token) }
    override fun refreshA2aTasks() = launchA2aAction { backend.refreshA2aTasks() }
    override fun replyToA2aTask(id: Long, text: String) = launchA2aAction { backend.replyToA2aTask(id, text) }
    override fun declineA2aTask(id: Long) = launchA2aAction { backend.declineA2aTask(id) }
    override fun cancelA2aTask(id: Long) = launchA2aAction { backend.cancelA2aTask(id) }

    override fun answerA2aApproval(id: Long, allow: Boolean) {
        pendingA2aApproval.value?.takeIf { it.first == id }?.second?.complete(allow)
    }

    private suspend fun approveDelegation(request: A2aDelegation): Boolean {
        val generation = approvalGeneration.value
        return approvalGate.withLock {
            if (generation != approvalGeneration.value) return@withLock false
            val id = nextApprovalId++
            val reply = CompletableDeferred<Boolean>()
            pendingA2aApproval.value = id to reply
            mutableState.update { it.copy(a2aApproval = MobileA2aApproval(id, request)) }
            try {
                withTimeoutOrNull(120_000) { reply.await() } ?: false
            } finally {
                pendingA2aApproval.value = null
                mutableState.update { it.copy(a2aApproval = null) }
            }
        }
    }

    override fun answerApproval(id: Long, allow: Boolean) {
        pendingApproval.value?.takeIf { it.first == id }?.second?.complete(allow)
    }

    override fun answerInput(id: Long, contentJson: String?) {
        if (contentJson != null && runCatching { Json.parseToJsonElement(contentJson) is JsonObject }.getOrDefault(false).not()) {
            mutableState.update { it.copy(error = "MCP 回复必须是 JSON 对象") }
            return
        }
        pendingInput.value?.takeIf { it.first == id }?.second?.complete(contentJson)
    }

    private suspend fun requestInput(server: String, method: String, params: JsonObject): JsonObject {
        if (method != "elicitation/create" || params["mode"]?.toString() == "\"url\"") {
            throw McpProtocolException("Unsupported MCP input request: $method")
        }
        val generation = approvalGeneration.value
        return approvalGate.withLock {
            if (generation != approvalGeneration.value) return@withLock buildJsonObject { put("action", "decline") }
            val id = nextApprovalId++
            val reply = CompletableDeferred<String?>()
            pendingInput.value = id to reply
            mutableState.update { it.copy(inputPrompt = MobileInputPrompt(id, server, params)) }
            try {
                val content = withTimeoutOrNull(120_000) { reply.await() }
                if (content == null) buildJsonObject { put("action", "decline") }
                else buildJsonObject {
                    put("action", "accept")
                    put("content", Json.parseToJsonElement(content) as JsonObject)
                }
            } finally {
                pendingInput.value = null
                mutableState.update { it.copy(inputPrompt = null) }
            }
        }
    }

    private suspend fun approveTool(name: String, argumentsJson: String): Boolean {
        val generation = approvalGeneration.value
        return approvalGate.withLock {
            if (generation != approvalGeneration.value) return@withLock false
            val id = nextApprovalId++
            val reply = CompletableDeferred<Boolean>()
            pendingApproval.value = id to reply
            mutableState.update {
                it.copy(approval = MobileToolApproval(id, name, argumentsJson))
            }
            try {
                withTimeoutOrNull(25_000) { reply.await() } ?: false
            } finally {
                pendingApproval.value = null
                mutableState.update { it.copy(approval = null) }
            }
        }
    }

    private suspend fun perform(block: suspend () -> Unit) {
        try {
            block()
            mutableState.update { it.copy(error = null) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.update { it.copy(error = error.message ?: error.toString()) }
        }
    }

    private fun launchMcpAction(block: suspend () -> Unit) {
        while (true) {
            val current = mutableState.value
            if (current.mcpBusy) return
            if (mutableState.compareAndSet(current, current.copy(mcpBusy = true))) break
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                perform(block)
            } finally {
                syncSettings()
                mutableState.update { it.copy(mcpBusy = false) }
            }
        }
    }

    private fun launchA2aAction(block: suspend () -> Unit) {
        scope.launch { perform(block) }
    }

    private fun syncSettings() {
        mutableState.update {
            it.copy(
                mcpEndpoint = backend.mcpEndpoint,
                deviceToolsEnabled = backend.deviceToolsEnabled,
                mcpStatus = backend.mcpStatus,
                modelStatus = backend.modelStatus,
                modelEngines = backend.modelEngines,
                modelEngine = backend.modelEngine,
                modelImportLabel = backend.modelImportLabel,
                mealReminderEnabled = backend.mealReminderEnabled,
                commuteReminderEnabled = backend.commuteReminderEnabled,
                mealTime = backend.mealTime,
                commuteTime = backend.commuteTime,
                proactiveTasks = backend.proactiveTasks,
                proactiveSettings = backend.proactiveSettings,
                reminderSettingsAvailable = backend.reminderSettingsAvailable,
                deviceEndpoint = backend.deviceEndpoint,
                deviceName = backend.deviceName,
                deviceAcceptsTasks = backend.deviceAcceptsTasks,
                memorySyncEndpoint = backend.memorySyncEndpoint,
                memorySyncStatus = backend.memorySyncStatus,
            )
        }
    }
}

/** Bound live rendering while the full trace remains in Harness storage. */
internal fun boundedReasoning(current: String, delta: String): String {
    if (current.endsWith("…") && current.length >= 1200) return current
    val combined = current + delta
    if (combined.length <= 1200) return combined
    val end = if (combined[1199].isHighSurrogate()) 1199 else 1200
    return combined.take(end) + "…"
}
