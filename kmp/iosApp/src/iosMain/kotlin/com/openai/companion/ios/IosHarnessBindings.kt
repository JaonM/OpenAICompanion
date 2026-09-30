package com.openai.companion.ios

import com.openai.companion.kmp.AppAgentEventSink
import com.openai.companion.kmp.AppModelServe
import com.openai.companion.kmp.GeneratedHarnessBindings
import com.openai.companion.kmp.McpTool
import com.openai.companion.kmp.ModelStreamCallback
import com.openai.companion.kmp.RustToolProvider
import com.openai.companion.kmp.ToolExecutionErrorCode
import com.openai.companion.kmp.ToolExecutionException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import uniffi.harness.AgentEventSink as NativeAgentEventSink
import uniffi.harness.A2aProvider as NativeA2aProvider
import uniffi.harness.McpTool as NativeMcpTool
import uniffi.harness.ModelServeCallback as NativeModelServeCallback
import uniffi.harness.ModelStreamCallback as NativeModelStreamCallback
import uniffi.harness.ToolCallReply as NativeToolCallReply
import uniffi.harness.ToolListReply as NativeToolListReply
import uniffi.harness.ToolProvider as NativeToolProvider
import uniffi.harness.cancelAgentLoop as cancelNativeAgentLoop
import uniffi.harness.clearContextDirectories as clearNativeContextDirectories
import uniffi.harness.configureContextDirectories as configureNativeContextDirectories
import uniffi.harness.registerAgentEventSink as registerNativeAgentEventSink
import uniffi.harness.registerA2aProvider as registerNativeA2aProvider
import uniffi.harness.registerModelServeCallback as registerNativeModelServeCallback
import uniffi.harness.registerToolProvider as registerNativeToolProvider
import uniffi.harness.unregisterAgentEventSink as unregisterNativeAgentEventSink
import uniffi.harness.unregisterA2aProvider as unregisterNativeA2aProvider
import uniffi.harness.unregisterModelServeCallback as unregisterNativeModelServeCallback
import uniffi.harness.unregisterToolProvider as unregisterNativeToolProvider
import uniffi.harness.updateMcpTools as updateNativeMcpTools

/** iOS Kotlin/Native implementation of the same Harness boundary used on JVM. */
class IosHarnessBindings : GeneratedHarnessBindings {
    private var toolProvider: NativeToolProvider? = null
    private var modelServe: NativeModelServeCallback? = null
    private var eventSink: NativeAgentEventSink? = null
    private var a2aProvider: NativeA2aProvider? = null

    fun registerA2aProvider(list: suspend () -> String, sendTask: suspend (String, String) -> String) {
        val native = object : NativeA2aProvider {
            override suspend fun listAgents(): String = list()
            override suspend fun delegate(agentId: String, taskText: String): String = sendTask(agentId, taskText)
        }
        registerNativeA2aProvider(native)
        a2aProvider = native
    }

    fun unregisterA2aProvider() {
        unregisterNativeA2aProvider()
        a2aProvider = null
    }

    override fun registerToolProvider(provider: RustToolProvider) {
        val native = object : NativeToolProvider {
            override suspend fun getTools(): NativeToolListReply = try {
                NativeToolListReply(
                    provider.getTools().map { NativeMcpTool(it.name, it.description, it.inputSchemaJson) },
                    null, null,
                )
            } catch (error: Throwable) {
                NativeToolListReply(emptyList(), error.callbackCode(), error.message)
            }

            override suspend fun callTool(name: String, argumentsJson: String): NativeToolCallReply = try {
                val result = provider.callTool(name, argumentsJson)
                NativeToolCallReply(result.contentJson, result.isError, null, null)
            } catch (error: Throwable) {
                NativeToolCallReply("", false, error.callbackCode(), error.message)
            }
        }
        registerNativeToolProvider(native)
        toolProvider = native
    }

    override fun registerModelServeCallback(provider: AppModelServe) {
        val native = object : NativeModelServeCallback {
            override suspend fun complete(requestJson: String, callback: NativeModelStreamCallback) {
                try {
                    provider.complete(requestJson, object : ModelStreamCallback {
                        override fun onChunk(chunkJson: String) = callback.onChunk(chunkJson)
                    })
                } catch (error: Exception) {
                    callback.onChunk(buildJsonObject {
                        put("error", JsonPrimitive(error.message ?: error.toString()))
                    }.toString())
                }
            }
        }
        registerNativeModelServeCallback(native)
        modelServe = native
    }

    override fun updateMcpTools(tools: List<McpTool>) {
        updateNativeMcpTools(tools.map { NativeMcpTool(it.name, it.description, it.inputSchemaJson) })
    }

    override fun unregisterToolProvider() {
        unregisterNativeToolProvider()
        toolProvider = null
    }

    override fun unregisterModelServeCallback() {
        unregisterNativeModelServeCallback()
        modelServe = null
    }

    override fun registerAgentEventSink(sink: AppAgentEventSink) {
        val native = object : NativeAgentEventSink {
            override fun onReasoningDelta(text: String) = sink.onReasoningDelta(text)
            override fun onTextDelta(text: String) = sink.onTextDelta(text)
            override fun onCompleted(finalText: String) = sink.onCompleted(finalText)
            override fun onError(errorJson: String) = sink.onError(errorJson)
        }
        registerNativeAgentEventSink(native)
        eventSink = native
    }

    override fun unregisterAgentEventSink() {
        unregisterNativeAgentEventSink()
        eventSink = null
    }

    override fun configureContextDirectories(agentsDirectory: String, personaDirectory: String) =
        configureNativeContextDirectories(agentsDirectory, personaDirectory)

    override fun clearContextDirectories() = clearNativeContextDirectories()

    override fun cancelAgentLoop() = cancelNativeAgentLoop()
}

private fun Throwable.callbackCode(): String =
    when (this) {
        is ToolExecutionException -> code.name
        is CancellationException -> ToolExecutionErrorCode.CANCELLED.name
        else -> ToolExecutionErrorCode.UNKNOWN.name
    }
