package com.openai.companion.kmp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** Serializes the shared model and binds the chat stop button to its current owner. */
class AgentExecutionGate {
    private val mutex = Mutex()
    private val owner = MutableStateFlow("idle")
    private val cancelEpoch = MutableStateFlow(0L)

    suspend fun <T> foreground(block: suspend () -> T): T {
        val epoch = cancelEpoch.value
        return locked("foreground") {
            if (epoch != cancelEpoch.value) throw CancellationException("生成已取消")
            block()
        }
    }

    /** Background jobs and other store operations cannot be canceled by the chat stop button. */
    suspend fun <T> withLock(block: suspend () -> T): T = locked("background", block)

    private suspend fun <T> locked(mode: String, block: suspend () -> T): T {
        mutex.lock()
        try { owner.value = mode; return block() }
        finally { release() }
    }

    fun cancelForeground(cancel: () -> Unit) {
        cancelEpoch.update { it + 1 }
        if (owner.compareAndSet("foreground", "canceling")) {
            try { cancel() }
            finally { owner.compareAndSet("canceling", "foreground") }
        }
    }

    private suspend fun release() = withContext(NonCancellable) {
        // A synchronous native cancel must finish before the next model request starts.
        while (owner.value == "canceling") yield()
        owner.value = "idle"
        mutex.unlock()
    }
}
