package com.openai.companion.kmp

import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MobileControllerTest {
    @Test
    fun a2aDelegationApprovalIsPinnedToItsRequestAndRequiresAnExplicitAnswer() = runBlocking {
        lateinit var approveA2a: suspend (A2aDelegation) -> Boolean
        val controller = MobileController(this) { approve, _, a2a ->
            approveA2a = a2a
            FakeMobileBackend(approve)
        }
        controller.start().join()
        val agent = A2aAgent(
            "https://agent.example/card", "Research", "https://agent.example/card",
            buildJsonObject { put("name", "Research") },
        )
        val pending = async { approveA2a(A2aDelegation(agent, "Summarize these notes")) }
        val prompt = withTimeout(1_000) { controller.state.first { it.a2aApproval != null }.a2aApproval!! }
        assertEquals(agent.id, prompt.request.agent.id)
        assertEquals("Summarize these notes", prompt.request.taskText)
        controller.answerA2aApproval(prompt.id + 1, true)
        assertFalse(pending.isCompleted)
        controller.answerA2aApproval(prompt.id, false)
        assertFalse(pending.await())
        withTimeout(1_000) { controller.state.first { it.a2aApproval == null } }
        Unit
    }

    @Test
    fun backgroundMcpAuthorizationStatusUpdatesComposeState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val status = MutableStateFlow("已连接 · 1 个工具")
        val controller = MobileController(scope) { approve, _ ->
            val delegate = FakeMobileBackend(approve)
            object : MobileBackend by delegate {
                override val mcpStatusUpdates = status
                override val mcpStatus: String get() = status.value
            }
        }
        try {
            controller.start().join()
            status.value = "需要 OAuth 授权 · example.test"
            val updated = withTimeout(1_000) {
                controller.state.first { it.mcpStatus.startsWith("需要 OAuth") }
            }
            assertEquals(status.value, updated.mcpStatus)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun backgroundDiscoveredTaskAppearsInSharedScreenState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tasks = MutableStateFlow<List<ProactiveTask>>(emptyList())
        val controller = MobileController(scope) { approve, _ ->
            val delegate = FakeMobileBackend(approve)
            object : MobileBackend by delegate {
                override val proactiveTaskUpdates = tasks
                override val proactiveTasks: List<ProactiveTask> get() = tasks.value
            }
        }
        try {
            controller.start().join()
            tasks.value = listOf(ProactiveTask(
                scenario = "auto_memory_42", title = "下班前查看天气",
                instruction = "下雨时建议出行方式", memoryQuery = "下班",
                localMinute = 18 * 60 + 30,
            ))
            val updated = withTimeout(1_000) {
                controller.state.first { it.proactiveTasks.any { task -> task.scenario == "auto_memory_42" } }
            }
            assertEquals("下班前查看天气", updated.proactiveTasks.single().title)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun elicitationRequiresExplicitComposeAnswerAndRejectsOnCancel() = runBlocking {
        lateinit var requestInput: suspend (String, String, JsonObject) -> JsonObject
        val controller = MobileController(this) { approve, input ->
            requestInput = input
            FakeMobileBackend(approve)
        }
        controller.start().join()
        val params = buildJsonObject { put("message", "Your name?") }
        val pending = async { requestInput("example.test", "elicitation/create", params) }
        val prompt = withTimeout(1_000) { controller.state.first { it.inputPrompt != null }.inputPrompt!! }
        assertEquals("example.test", prompt.server)
        assertEquals("Your name?", prompt.params.getValue("message").jsonPrimitive.content)
        controller.answerInput(prompt.id, "not-json")
        assertFalse(pending.isCompleted)
        controller.answerInput(prompt.id + 1, "{}")
        assertFalse(pending.isCompleted)
        controller.answerInput(prompt.id, "{\"name\":\"Alice\"}")
        assertEquals("accept", pending.await().getValue("action").jsonPrimitive.content)
        assertNull(controller.state.value.inputPrompt)

        val second = async { requestInput("example.test", "elicitation/create", params) }
        withTimeout(1_000) { controller.state.first { it.inputPrompt != null } }
        controller.cancel()
        assertEquals("decline", second.await().getValue("action").jsonPrimitive.content)
        assertNull(controller.state.value.inputPrompt)
    }

    @Test
    fun approvalRequiresMatchingExplicitAnswerAndClearsPrompt() = runBlocking {
        lateinit var backend: FakeMobileBackend
        val controller = MobileController(this) { approve, _ ->
            FakeMobileBackend(approve).also { backend = it }
        }
        controller.start().join()
        val pending = async { backend.approve("calendar.create", "{\"title\":\"meeting\"}") }
        val prompt = withTimeout(1_000) { controller.state.first { it.approval != null }.approval!! }
        assertEquals("calendar.create", prompt.name)
        controller.answerApproval(prompt.id + 1, true)
        assertFalse(pending.isCompleted)
        controller.answerApproval(prompt.id, true)
        assertTrue(pending.await())
        withTimeout(1_000) { controller.state.first { it.approval == null } }
        assertNull(controller.state.value.approval)

        val second = async { backend.approve("calendar.delete", "{}") }
        withTimeout(1_000) { controller.state.first { it.approval != null } }
        controller.cancel()
        assertFalse(second.await())
        withTimeout(1_000) { controller.state.first { it.approval == null } }
        assertNull(controller.state.value.approval)
    }

    @Test
    fun sessionsAndMcpConfigurationUpdateSharedScreenState() = runBlocking {
        val controller = MobileController(this) { approve, _ -> FakeMobileBackend(approve) }
        controller.start().join()
        val active = withTimeout(1_000) { controller.state.first { it.activeSessionId == 1L } }
        assertEquals(1, active.sessions.size)
        controller.configureMcp("https://example.test/mcp")
        val configured = withTimeout(1_000) { controller.state.first { it.mcpEndpoint.isNotEmpty() } }
        assertEquals("已连接 · 1 个工具", configured.mcpStatus)
    }

    @Test
    fun mcpActionRejectsDuplicateConnectAndAuthorizeUntilFinished() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var connects = 0
        var authorizations = 0
        val controller = MobileController(this) { approve, _ ->
            val delegate = FakeMobileBackend(approve)
            object : MobileBackend by delegate {
                override suspend fun configureMcp(endpoint: String) {
                    connects++
                    entered.complete(Unit)
                    release.await()
                    delegate.configureMcp(endpoint)
                }
                override suspend fun authorizeMcp(clientId: String) { authorizations++ }
                override val mcpEndpoint: String get() = delegate.mcpEndpoint
                override val mcpStatus: String get() = delegate.mcpStatus
            }
        }
        controller.start().join()
        controller.configureMcp("https://example.test/mcp")
        entered.await()
        assertTrue(controller.state.value.mcpBusy)
        controller.configureMcp("https://other.test/mcp")
        controller.authorizeMcp("")
        assertEquals(1, connects)
        assertEquals(0, authorizations)
        release.complete(Unit)
        withTimeout(1_000) { controller.state.first { !it.mcpBusy && it.mcpEndpoint.isNotEmpty() } }
        controller.authorizeMcp("")
        assertEquals(1, authorizations)
        assertFalse(controller.state.value.mcpBusy)
    }

    @Test
    fun failedMcpActionReenablesControlsAndShowsError() = runBlocking {
        val controller = MobileController(this) { approve, _ ->
            val delegate = FakeMobileBackend(approve)
            object : MobileBackend by delegate {
                override suspend fun configureMcp(endpoint: String) {
                    error("MCP endpoint is unavailable")
                }
            }
        }
        controller.start().join()
        controller.configureMcp("https://example.test/mcp")
        val failed = withTimeout(1_000) {
            controller.state.first { !it.mcpBusy && it.error == "MCP endpoint is unavailable" }
        }
        assertFalse(failed.mcpBusy)
    }

    private class FakeMobileBackend(
        val approve: suspend (String, String) -> Boolean,
    ) : MobileBackend {
        private val stored = mutableListOf<MobileSession>()
        override var mcpEndpoint: String = ""
            private set
        override val mcpStatus: String get() = if (mcpEndpoint.isEmpty()) "未连接 MCP" else "已连接 · 1 个工具"
        override val modelStatus = "端侧模型已导入"
        override suspend fun initialize() = Unit
        override suspend fun sessions() = stored.toList()
        override suspend fun createSession(): Long = 1L.also { stored += MobileSession(it, "新会话") }
        override suspend fun openSession(id: Long) = emptyList<MobileMessage>()
        override suspend fun deleteSession(id: Long) { stored.removeAll { it.id == id } }
        override suspend fun send(text: String, onText: (String) -> Unit) { onText("ok") }
        override fun cancel() = Unit
        override suspend fun configureMcp(endpoint: String) { mcpEndpoint = endpoint }
        override suspend fun authorizeMcp(clientId: String) = Unit
        override suspend fun refreshMcp() = Unit
        override suspend fun importModel() = Unit
    }
}
