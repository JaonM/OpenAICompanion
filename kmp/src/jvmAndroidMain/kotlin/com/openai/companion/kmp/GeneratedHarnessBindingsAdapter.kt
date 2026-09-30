package com.openai.companion.kmp

import kotlinx.coroutines.CancellationException

import uniffi.harness.McpTool as GeneratedMcpTool
import uniffi.harness.ModelServeCallback as GeneratedModelServeCallback
import uniffi.harness.ModelStreamCallback as GeneratedModelStreamCallback
import uniffi.harness.AgentEventSink as GeneratedAgentEventSink
import uniffi.harness.ToolProvider as GeneratedToolProvider
import uniffi.harness.ToolCallReply as GeneratedToolCallReply
import uniffi.harness.ToolListReply as GeneratedToolListReply
import uniffi.harness.registerModelServeCallback as registerModelServeCallbackNative
import uniffi.harness.registerToolProvider as registerToolProviderNative
import uniffi.harness.unregisterModelServeCallback as unregisterModelServeCallbackNative
import uniffi.harness.unregisterToolProvider as unregisterToolProviderNative
import uniffi.harness.registerAgentEventSink as registerAgentEventSinkNative
import uniffi.harness.unregisterAgentEventSink as unregisterAgentEventSinkNative
import uniffi.harness.cancelAgentLoop as cancelAgentLoopNative
import uniffi.harness.clearContextDirectories as clearContextDirectoriesNative
import uniffi.harness.configureContextDirectories as configureContextDirectoriesNative
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Adapter over the JVM binding generated from harness.udl. */
class GeneratedHarnessBindingsAdapter : GeneratedHarnessBindings {
    override fun registerModelServeCallback(provider: AppModelServe) {
        registerModelServeCallbackNative(object : GeneratedModelServeCallback {
            override suspend fun complete(requestJson: String, callback: GeneratedModelStreamCallback) = try {
                provider.complete(requestJson, object : ModelStreamCallback {
                    override fun onChunk(chunkJson: String) = callback.onChunk(chunkJson)
                })
            } catch (error: Exception) {
                // UniFFI flat foreign errors cannot be lifted on the Rust side.
                // Deliver request failures through the stream instead.
                callback.onChunk(buildJsonObject {
                    put("error", JsonPrimitive(error.message ?: error.toString()))
                }.toString())
            }
        })
    }

    override fun registerToolProvider(provider: RustToolProvider) {
        registerToolProviderNative(object : GeneratedToolProvider {
            override suspend fun getTools(): GeneratedToolListReply = try {
                GeneratedToolListReply(provider.getTools().map {
                    GeneratedMcpTool(it.name, it.description, it.inputSchemaJson)
                }, null, null)
            } catch (error: Throwable) {
                GeneratedToolListReply(emptyList(), error.callbackCode(), error.message)
            }

            override suspend fun callTool(name: String, argumentsJson: String): GeneratedToolCallReply = try {
                val result = provider.callTool(name, argumentsJson)
                GeneratedToolCallReply(result.contentJson, result.isError, null, null)
            } catch (error: Throwable) {
                GeneratedToolCallReply("", false, error.callbackCode(), error.message)
            }
        })
    }

    override fun updateMcpTools(tools: List<McpTool>) {
        uniffi.harness.updateMcpTools(
            tools.map { GeneratedMcpTool(it.name, it.description, it.inputSchemaJson) },
        )
    }

    override fun unregisterToolProvider() {
        unregisterToolProviderNative()
    }

    override fun unregisterModelServeCallback() {
        unregisterModelServeCallbackNative()
    }

    override fun registerAgentEventSink(sink: AppAgentEventSink) {
        registerAgentEventSinkNative(object : GeneratedAgentEventSink {
            override fun onReasoningDelta(text: String) = sink.onReasoningDelta(text)
            override fun onTextDelta(text: String) = sink.onTextDelta(text)
            override fun onCompleted(finalText: String) = sink.onCompleted(finalText)
            override fun onError(errorJson: String) = sink.onError(errorJson)
        })
    }

    override fun unregisterAgentEventSink() {
        unregisterAgentEventSinkNative()
    }

    override fun configureContextDirectories(agentsDirectory: String, personaDirectory: String) {
        configureContextDirectoriesNative(agentsDirectory, personaDirectory)
    }

    override fun clearContextDirectories() {
        clearContextDirectoriesNative()
    }

    override fun cancelAgentLoop() {
        cancelAgentLoopNative()
    }
}

private fun Throwable.callbackCode(): String =
    when (this) {
        is ToolExecutionException -> code.name
        is CancellationException -> ToolExecutionErrorCode.CANCELLED.name
        else -> ToolExecutionErrorCode.UNKNOWN.name
    }
