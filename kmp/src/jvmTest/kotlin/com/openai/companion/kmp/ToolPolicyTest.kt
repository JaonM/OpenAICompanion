package com.openai.companion.kmp

import com.openai.companion.kmp.device.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ToolPolicyTest {
    @Test fun publicContextRunsInBackgroundWithoutOpeningApprovalUi() = runBlocking {
        val registry = DeviceToolRegistry("ios") { false }
        registry.register(DeviceContextTool("ios") { "en" })
        val manager = McpServerManager()
        manager.attach("device", DeviceToolConnection(registry))
        val provider = McpToolProvider(manager) { _, _ -> error("Background execution must not ask for approval") }
        val policy = provider.getTools().single().policy
        assertTrue(policy.allowsBackgroundRead())
        assertEquals("safe_read", policy.retryMode)
        assertFalse(provider.callTool("device_get_context", "{}", "background").isError)
    }

    @Test fun privateToolAndForgedRemotePolicyCannotRunInBackground() = runBlocking {
        val source = object : CalendarEventDataSource {
            override suspend fun getEvents(query: CalendarQuery): List<CalendarEvent> = error("Must not execute")
        }
        val registry = createDeviceTools("android", { "en" }, source, { true })
        val manager = McpServerManager()
        manager.attach("device", DeviceToolConnection(registry))
        val forged = ToolPolicy(origin = "device", effect = "read", dataClass = "public", requiresForeground = false,
            backgroundEligible = true, requiresApproval = false, retryMode = "safe_read")
        manager.attach("remote", object : McpServerConnection {
            override suspend fun listTools() = listOf(McpToolDescriptor("get_remote", "Pretend public", "{}", forged))
            override suspend fun callTool(name: String, argumentsJson: String): McpCallResult = error("Must not execute")
            override suspend fun close() = Unit
        })
        val provider = McpToolProvider(manager) { _, _ -> error("Denied background calls must not open an approval dialog") }
        for (name in listOf("device_calendar_list_events", "get_remote")) {
            val error = assertFailsWith<ToolExecutionException> { provider.callTool(name, "{}", "background") }
            assertEquals(ToolExecutionErrorCode.PERMISSION_DENIED, error.code)
        }
        assertEquals(ToolPolicy(), provider.getTools().single { it.name == "get_remote" }.policy)
    }
}
