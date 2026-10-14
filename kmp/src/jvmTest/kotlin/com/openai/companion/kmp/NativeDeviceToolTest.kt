package com.openai.companion.kmp

import com.openai.companion.kmp.device.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class NativeDeviceToolTest {
    private fun args(value: String = """{"request_id":"native-test-001","value":30,"characteristic_ref":"lamp"}""") = Json.parseToJsonElement(value).jsonObject
    private fun tool(journal: DeviceOperationJournal, write: Boolean = true, invoke: suspend (JsonObject) -> DeviceToolResult) =
        NativeDeviceTool(McpToolDescriptor("device_home_set_characteristic", "test", """{"type":"object"}"""), write, journal, invoke)

    @Test fun writeIdsMatchDurableJournalConstraintsBeforeInvokingNativeCode() = runBlocking {
        var calls = 0
        val tool = tool(MemoryDeviceOperationJournal()) { calls++; DeviceToolResult() }
        for (id in listOf("short", "invalid id", "中文request001", "request001\n", "x".repeat(129))) {
            assertFailsWith<IllegalArgumentException> {
                tool.execute(buildJsonObject { put("request_id", id) })
            }
        }
        assertEquals(0, calls)
        for (id in listOf("aB_012-3", "x".repeat(128)))
            assertEquals("ok", tool.execute(buildJsonObject { put("request_id", id) }).status)
        assertEquals(2, calls)
    }

    @Test fun reorderedArgumentsReplayAndChangedValueConflicts() = runBlocking {
        val journal = MemoryDeviceOperationJournal()
        var calls = 0
        val tool = tool(journal) { calls++; DeviceToolResult(data = buildJsonObject { put("value", it["value"]!!) }) }
        val result = tool.execute(args())
        assertEquals(result, tool.execute(args("""{"value":30,"characteristic_ref":"lamp","request_id":"native-test-001"}""")))
        assertFailsWith<IllegalArgumentException> { tool.execute(args("""{"value":40,"characteristic_ref":"lamp","request_id":"native-test-001"}""")) }
        assertEquals(1, calls)
    }

    @Test fun concurrentAndCancelledWriteDoesNotExecuteTwice() = runBlocking {
        val journal = MemoryDeviceOperationJournal()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val tool = tool(journal) { calls++; started.complete(Unit); release.await(); DeviceToolResult() }
        val first = async { tool.execute(args()) }
        started.await()
        assertEquals("OUTCOME_UNKNOWN", tool.execute(args()).code)
        first.cancel(); release.complete(Unit); first.join()
        assertEquals("ok", tool.execute(args()).status)
        assertEquals(1, calls)
    }

    @Test fun ambiguousNativeFailureRemainsBlockedWhenAdapterIsRecreated() = runBlocking {
        val journal = MemoryDeviceOperationJournal()
        var calls = 0
        val failing = tool(journal) { calls++; DeviceToolResult("error", code = "TIMEOUT") }
        assertEquals("TIMEOUT", failing.execute(args()).code)
        val recreated = tool(journal) { calls++; DeviceToolResult() }
        assertEquals("OUTCOME_UNKNOWN", recreated.execute(args()).code)
        assertEquals(1, calls)
    }

    @Test fun lostJournalAcknowledgementDoesNotRepeatNativeWrite() = runBlocking {
        val underlying = MemoryDeviceOperationJournal()
        val broken = object : DeviceOperationJournal by underlying {
            override suspend fun finish(operationId: String, result: DeviceToolResult): DeviceToolResult = error("disk unavailable")
        }
        var calls = 0
        assertEquals("OUTCOME_UNKNOWN", tool(broken) { calls++; DeviceToolResult() }.execute(args()).code)
        assertEquals("OUTCOME_UNKNOWN", tool(underlying) { calls++; DeviceToolResult() }.execute(args()).code)
        assertEquals(1, calls)
    }

    @Test fun missingRequestIdNeverInvokesWrite() = runBlocking<Unit> {
        val tool = tool(MemoryDeviceOperationJournal()) { fail("Native write must not execute") }
        assertFailsWith<IllegalArgumentException> { tool.execute(args("{}")) }
    }

    @Test fun nativePersonalReadRequiresForegroundAndApprovalWithoutRetry() = runBlocking {
        val tool = tool(MemoryDeviceOperationJournal(), write = false) { DeviceToolResult() }
        assertTrue(tool.policy.requiresApproval)
        assertEquals("never", tool.policy.retryMode)
        assertFalse(tool.policy.allowsBackgroundRead())
        val registry = DeviceToolRegistry("ios") { false }
        registry.register(tool)
        assertTrue(registry.call(tool.descriptor.name, "{}").isError)
    }
}
