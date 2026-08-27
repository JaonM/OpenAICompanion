package com.openai.companion.kmp

/** Executes a Chat Completions request on the APP side. */
interface AppModelServe {
    suspend fun complete(requestJson: String, callback: ModelStreamCallback)
}

interface ModelStreamCallback {
    fun onChunk(chunkJson: String)
}

/** Receives Harness output events for incremental APP rendering. */
interface AppAgentEventSink {
    fun onReasoningDelta(text: String)
    fun onTextDelta(text: String)
    fun onCompleted(finalText: String)
    fun onError(errorJson: String)
}
