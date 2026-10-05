package com.openai.companion.kmp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/** Commit notifications wake uploads; periodic exchanges also pull other devices' changes.
 * The database owns pending state. Losing this in-process channel never loses work.
 */
class MemorySyncWorker(
    private val enabled: () -> Boolean,
    private val pending: suspend () -> Boolean,
    private val sync: suspend () -> Unit,
    private val pullIntervalMillis: Long = 15 * 60_000L,
    private val firstRetryMillis: Long = 1_000L,
    private val maxRetryMillis: Long = 60_000L,
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    fun request() { wake.trySend(Unit) }

    suspend fun run() {
        var retryMillis = firstRetryMillis
        while (currentCoroutineContext().isActive) {
            try {
                if (enabled()) {
                    sync()
                    // A write during the HTTP request must be sent in a new exchange.
                    if (pending()) continue
                }
                retryMillis = firstRetryMillis
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Do not consume the durable pending version on any failure. New writes
                // cannot bypass the backoff when the service is unavailable.
                delay(retryMillis)
                retryMillis = (retryMillis * 2).coerceAtMost(maxRetryMillis)
                continue
            }
            withTimeoutOrNull(pullIntervalMillis) { wake.receive() }
        }
    }
}
