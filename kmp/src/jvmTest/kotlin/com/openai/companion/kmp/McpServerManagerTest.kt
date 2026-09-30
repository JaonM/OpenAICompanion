package com.openai.companion.kmp

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class McpServerManagerTest {
    @Test
    fun replacingServerDoesNotRouteOldToolToNewConnection() = runBlocking {
        val manager = McpServerManager()
        manager.attach("remote", FakeConnection(listOf(McpToolDescriptor("old", "", "{}"))))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val replacement = object : McpServerConnection {
            override suspend fun listTools(): List<McpToolDescriptor> {
                entered.complete(Unit)
                release.await()
                return listOf(McpToolDescriptor("new", "", "{}"))
            }
            override suspend fun callTool(name: String, argumentsJson: String): McpCallResult {
                error("Old tool must never be routed here")
            }
            override suspend fun close() = Unit
        }
        val attaching = async(start = CoroutineStart.UNDISPATCHED) { manager.attach("remote", replacement) }
        entered.await()
        assertTrue(manager.tools().isEmpty())
        assertFailsWith<IllegalStateException> { manager.callTool("old", "{}") }
        release.complete(Unit)
        attaching.await()
        assertEquals(listOf("new"), manager.tools().map { it.name })
    }

    @Test
    fun inFlightRefreshCannotRestoreToolsAfterDetach() = runBlocking {
        val manager = McpServerManager()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var block = false
        val connection = object : McpServerConnection {
            override suspend fun listTools(): List<McpToolDescriptor> {
                if (block) {
                    entered.complete(Unit)
                    release.await()
                }
                return listOf(McpToolDescriptor("stale", "", "{}"))
            }
            override suspend fun callTool(name: String, argumentsJson: String) = McpCallResult("{}", false)
            override suspend fun close() = Unit
        }
        manager.attach("remote", connection)
        block = true
        val refreshing = async { manager.refresh() }
        entered.await()
        val detaching = async(start = CoroutineStart.UNDISPATCHED) { manager.detach("remote") }
        release.complete(Unit)
        detaching.await()
        refreshing.await()
        assertTrue(manager.tools().isEmpty())
    }

    @Test
    fun failedEarlierAttachCannotRemoveLaterReplacement() = runBlocking {
        val manager = McpServerManager()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val failing = object : McpServerConnection {
            override suspend fun listTools(): List<McpToolDescriptor> {
                entered.complete(Unit)
                release.await()
                error("first connection failed")
            }
            override suspend fun callTool(name: String, argumentsJson: String): McpCallResult =
                error("first connection must not receive a call")
            override suspend fun close() = Unit
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { manager.attach("remote", failing) }
        }
        entered.await()
        val later = FakeConnection(listOf(McpToolDescriptor("later", "", "{}")))
        val second = async(start = CoroutineStart.UNDISPATCHED) { manager.attach("remote", later) }
        release.complete(Unit)
        assertTrue(first.await().isFailure)
        second.await()
        assertEquals(listOf("later"), manager.tools().map { it.name })
        assertEquals(false, manager.callTool("later", "{}").isError)
    }

    @Test
    fun cancelledAttachDoesNotLeaveHalfConnectedServer() = runBlocking {
        val manager = McpServerManager()
        val entered = CompletableDeferred<Unit>()
        var closed = false
        val waiting = object : McpServerConnection {
            override suspend fun listTools(): List<McpToolDescriptor> {
                entered.complete(Unit)
                CompletableDeferred<Unit>().await()
                return emptyList()
            }
            override suspend fun callTool(name: String, argumentsJson: String): McpCallResult =
                error("cancelled connection must not receive a call")
            override suspend fun close() { closed = true }
        }
        val attaching = launch { manager.attach("remote", waiting) }
        entered.await()
        attaching.cancelAndJoin()
        assertTrue(closed)
        assertTrue(manager.tools().isEmpty())
        withTimeout(1_000) { manager.refresh() }
        manager.attach("remote", FakeConnection(listOf(McpToolDescriptor("healthy", "", "{}"))))
        assertEquals(listOf("healthy"), manager.tools().map { it.name })
    }

    private class FakeConnection(
        private val available: List<McpToolDescriptor> = emptyList(),
        private val failure: Boolean = false,
    ) : McpServerConnection {
        var closed = false
        var calls = 0

        override suspend fun listTools(): List<McpToolDescriptor> {
            if (failure) error("server unavailable")
            return available
        }

        override suspend fun callTool(name: String, argumentsJson: String): McpCallResult {
            calls += 1
            return McpCallResult("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}", false)
        }

        override suspend fun close() { closed = true }
    }

    @Test
    fun failedReplacementRetainsWorkingServerAndToolSnapshot() = runBlocking {
        val manager = McpServerManager()
        val original = FakeConnection(listOf(McpToolDescriptor("echo", "Echo", "{}")))
        manager.attach("remote", original)
        val failed = FakeConnection(failure = true)

        assertFailsWith<IllegalStateException> { manager.attach("remote", failed) }
        assertTrue(failed.closed)
        assertTrue(!original.closed)
        assertEquals(listOf("echo"), manager.tools().map { it.name })
        assertEquals(false, manager.callTool("echo", "{}").isError)
    }

    @Test
    fun toolExecutionRequiresExplicitApproval() = runBlocking {
        val manager = McpServerManager()
        val remote = FakeConnection(listOf(McpToolDescriptor("echo", "Echo", "{}")))
        manager.attach("remote", remote)
        val denied = McpToolProvider(manager) { _, _ -> false }
        assertFailsWith<ToolExecutionException> { denied.callTool("echo", "{}") }
        assertEquals(0, remote.calls)

        val approved = McpToolProvider(manager) { name, arguments ->
            name == "echo" && arguments == "{}"
        }
        assertTrue(approved.callTool("echo", "{}").contentJson.contains("ok"))
        assertEquals(1, remote.calls)
    }

    @Test
    fun cancelledToolCallKeepsCancellationSemantics() = runBlocking {
        val manager = McpServerManager()
        manager.attach("remote", object : McpServerConnection {
            override suspend fun listTools() = listOf(McpToolDescriptor("slow", "", "{}"))
            override suspend fun callTool(name: String, argumentsJson: String): McpCallResult =
                throw CancellationException("request cancelled")
            override suspend fun close() = Unit
        })

        val provider = McpToolProvider(manager) { _, _ -> true }
        assertFailsWith<CancellationException> { provider.callTool("slow", "{}") }
        Unit
    }

    @Test
    fun approvalCannotExecuteSameNamedToolOnReplacementServer() = runBlocking {
        val manager = McpServerManager()
        val original = FakeConnection(listOf(McpToolDescriptor("erase", "Original", "{}")))
        manager.attach("remote", original)
        val approvalShown = CompletableDeferred<Unit>()
        val approvalAnswer = CompletableDeferred<Boolean>()
        val provider = McpToolProvider(manager) { _, _ ->
            approvalShown.complete(Unit)
            approvalAnswer.await()
        }
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { provider.callTool("erase", "{}") }
        }
        approvalShown.await()
        val replacement = FakeConnection(listOf(McpToolDescriptor("erase", "Replacement", "{}")))
        manager.attach("remote", replacement)
        approvalAnswer.complete(true)

        val failure = pending.await().exceptionOrNull() as ToolExecutionException
        assertEquals(ToolExecutionErrorCode.PERMISSION_DENIED, failure.code)
        assertEquals(0, original.calls)
        assertEquals(0, replacement.calls)
    }
}
