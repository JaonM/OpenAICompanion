package com.openai.companion.kmp

import uniffi.harness.McpTool as GeneratedMcpTool
import uniffi.harness.ModelServeCallback as GeneratedModelServeCallback
import uniffi.harness.ModelStreamCallback as GeneratedModelStreamCallback
import uniffi.harness.AgentEventSink as GeneratedAgentEventSink
import uniffi.harness.ToolProvider as GeneratedToolProvider
import uniffi.harness.ToolExecutionException as GeneratedToolExecutionException
import uniffi.harness.ModelServeException as GeneratedModelServeException
import uniffi.harness.registerModelServeCallback as registerModelServeCallbackNative
import uniffi.harness.registerToolProvider
import uniffi.harness.unregisterModelServeCallback as unregisterModelServeCallbackNative
import uniffi.harness.unregisterToolProvider
import uniffi.harness.registerAgentEventSink as registerAgentEventSinkNative
import uniffi.harness.unregisterAgentEventSink as unregisterAgentEventSinkNative
import uniffi.harness.cancelAgentLoop as cancelAgentLoopNative
import uniffi.harness.clearContextDirectories as clearContextDirectoriesNative
import uniffi.harness.configureContextDirectories as configureContextDirectoriesNative
import kotlinx.coroutines.CancellationException

/** Adapter over the JVM binding generated from harness.udl. */
class GeneratedHarnessBindingsAdapter : GeneratedHarnessBindings {
    override fun registerModelServeCallback(provider: AppModelServe) {
        registerModelServeCallbackNative(object : GeneratedModelServeCallback {
            override suspend fun complete(requestJson: String, callback: GeneratedModelStreamCallback) = try {
                provider.complete(requestJson, object : ModelStreamCallback {
                    override fun onChunk(chunkJson: String) = callback.onChunk(chunkJson)
                })
            } catch (error: GeneratedModelServeException) {
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                throw GeneratedModelServeException.Unknown(error.message.orEmpty())
            }
        })
    }

    override fun registerToolProvider(provider: RustToolProvider) {
        registerToolProvider(object : GeneratedToolProvider {
            override suspend fun getTools(): List<GeneratedMcpTool> = try {
                provider.getTools().map {
                    GeneratedMcpTool(it.name, it.description, it.inputSchemaJson)
                }
            } catch (error: ToolExecutionException) {
                throw error.toGeneratedError()
            } catch (error: Throwable) {
                throw GeneratedToolExecutionException.Unknown(error.message.orEmpty())
            }

            override suspend fun callTool(name: String, argumentsJson: String): String = try {
                provider.callTool(name, argumentsJson)
            } catch (error: ToolExecutionException) {
                throw error.toGeneratedError()
            } catch (error: Throwable) {
                throw GeneratedToolExecutionException.Unknown(error.message.orEmpty())
            }
        })
    }

    override fun updateMcpTools(tools: List<McpTool>) {
        uniffi.harness.updateMcpTools(
            tools.map { GeneratedMcpTool(it.name, it.description, it.inputSchemaJson) },
        )
    }

    override fun unregisterToolProvider() {
        unregisterToolProvider()
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

private fun ToolExecutionException.toGeneratedError(): GeneratedToolExecutionException =
    when (code) {
        ToolExecutionErrorCode.TIMEOUT -> GeneratedToolExecutionException.Timeout(message.orEmpty())
        ToolExecutionErrorCode.PERMISSION_DENIED -> GeneratedToolExecutionException.PermissionDenied(message.orEmpty())
        ToolExecutionErrorCode.NETWORK_UNREACHABLE -> GeneratedToolExecutionException.NetworkUnreachable(message.orEmpty())
        ToolExecutionErrorCode.INVALID_ARGUMENTS -> GeneratedToolExecutionException.InvalidArguments(message.orEmpty())
        ToolExecutionErrorCode.RESOURCE_NOT_FOUND -> GeneratedToolExecutionException.ResourceNotFound(message.orEmpty())
        ToolExecutionErrorCode.SERVER_INTERNAL_ERROR -> GeneratedToolExecutionException.ServerInternalException(message.orEmpty())
        ToolExecutionErrorCode.CANCELLED -> GeneratedToolExecutionException.Cancelled(message.orEmpty())
        ToolExecutionErrorCode.UNKNOWN -> GeneratedToolExecutionException.Unknown(message.orEmpty())
    }
