package com.openai.companion.kmp

import kotlinx.coroutines.*
import kotlin.test.*

class AgentExecutionGateTest {
    @Test
    fun cancelWhileDeviceTaskRunsDoesNotCancelItOrStartQueuedChat() = runBlocking {
        val gate = AgentExecutionGate()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var nativeCancels = 0
        var chatStarted = false
        val device = async { gate.withLock { started.complete(Unit); release.await(); "device result" } }
        started.await()
        val chat = async { gate.foreground { chatStarted = true } }
        yield()
        gate.cancelForeground { nativeCancels++ }
        release.complete(Unit)
        assertEquals("device result", device.await())
        assertFailsWith<CancellationException> { chat.await() }
        assertEquals(0, nativeCancels)
        assertFalse(chatStarted)
        gate.foreground { gate.cancelForeground { nativeCancels++ } }
        assertEquals(1, nativeCancels)
    }
}
