package com.openai.companion.kmp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class MemorySyncWorkerTest {
    @Test
    fun startsImmediatelyAndWakesForCommittedWrites() = runBlocking {
        val exchanges = Channel<Unit>(Channel.UNLIMITED)
        val worker = MemorySyncWorker({ true }, { false }, { exchanges.send(Unit) })
        val job = launch { worker.run() }
        try {
            withTimeout(2_000) { exchanges.receive() }
            worker.request()
            withTimeout(2_000) { exchanges.receive() }
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun retriesFailureAndDrainsChangesWrittenDuringUpload() = runBlocking {
        var calls = 0
        var pending = true
        val complete = CompletableDeferred<Unit>()
        val worker = MemorySyncWorker(
            enabled = { true }, pending = { pending },
            sync = {
                calls++
                when (calls) {
                    1 -> error("offline")
                    2 -> Unit // successful old batch; a newer write is still pending
                    else -> { pending = false; complete.complete(Unit) }
                }
            }, firstRetryMillis = 1, maxRetryMillis = 4,
        )
        val job = launch { worker.run() }
        try {
            withTimeout(2_000) { complete.await() }
            assertEquals(3, calls)
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun configurationWakeStartsDisabledWorkerAndTimerPullsRemoteChanges() = runBlocking {
        var enabled = false
        val exchanges = Channel<Unit>(Channel.UNLIMITED)
        val worker = MemorySyncWorker({ enabled }, { false }, { exchanges.send(Unit) }, pullIntervalMillis = 10)
        val job = launch { worker.run() }
        try {
            enabled = true
            worker.request()
            withTimeout(2_000) { exchanges.receive(); exchanges.receive() }
        } finally { job.cancelAndJoin() }
    }
}
