package com.openai.companion.kmp

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class UniFfiBridgeTest {
    @Test
    fun toolSnapshotAndDisplayedCountFollowConnectionChanges() = runBlocking {
        val bindings = RecordingBindings()
        val manager = McpServerManager()
        val counts = mutableListOf<Int>()
        registerMcpProvider(bindings, manager, approve = { _, _ -> true }, onToolCountChanged = { counts += it })
        assertNotNull(bindings.provider)
        assertEquals(emptyList(), bindings.tools)
        assertEquals(listOf(0), counts)

        val connection = MutableConnection(listOf(McpToolDescriptor("first", "", "{}")))
        manager.attach("remote", connection)
        assertEquals(listOf("first"), bindings.tools.map { it.name })
        assertEquals(1, counts.last())

        connection.available = listOf(
            McpToolDescriptor("first", "", "{}"), McpToolDescriptor("second", "", "{}"),
        )
        connection.changed()
        assertEquals(listOf("first", "second"), bindings.tools.map { it.name })
        assertEquals(2, counts.last())

        manager.detach("remote")
        assertEquals(emptyList(), bindings.tools)
        assertEquals(0, counts.last())
    }

    private class MutableConnection(var available: List<McpToolDescriptor>) : McpServerConnection {
        lateinit var changed: suspend () -> Unit
        override fun setToolsChangedListener(listener: suspend () -> Unit) { changed = listener }
        override suspend fun listTools() = available
        override suspend fun callTool(name: String, argumentsJson: String) = McpCallResult("{}", false)
        override suspend fun close() = Unit
    }

    private class RecordingBindings : GeneratedHarnessBindings {
        var provider: RustToolProvider? = null
        var tools: List<McpTool> = emptyList()
        override fun registerToolProvider(provider: RustToolProvider) { this.provider = provider }
        override fun registerModelServeCallback(provider: AppModelServe) = Unit
        override fun updateMcpTools(tools: List<McpTool>) { this.tools = tools }
        override fun unregisterToolProvider() { provider = null }
        override fun unregisterModelServeCallback() = Unit
        override fun registerAgentEventSink(sink: AppAgentEventSink) = Unit
        override fun unregisterAgentEventSink() = Unit
        override fun configureContextDirectories(agentsDirectory: String, personaDirectory: String) = Unit
        override fun clearContextDirectories() = Unit
        override fun cancelAgentLoop() = Unit
    }
}
