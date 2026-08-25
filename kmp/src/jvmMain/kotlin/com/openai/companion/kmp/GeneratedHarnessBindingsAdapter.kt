package com.openai.companion.kmp

import uniffi.harness.McpTool as GeneratedMcpTool
import uniffi.harness.ToolProvider as GeneratedToolProvider
import uniffi.harness.ToolExecutionException as GeneratedToolExecutionException
import uniffi.harness.registerToolProvider
import uniffi.harness.unregisterToolProvider

/** Adapter over the JVM binding generated from harness.udl. */
class GeneratedHarnessBindingsAdapter : GeneratedHarnessBindings {
    override fun registerToolProvider(provider: RustToolProvider) {
        registerToolProvider(object : GeneratedToolProvider {
            override suspend fun getTools(): List<GeneratedMcpTool> = try {
                provider.getTools().map {
                    GeneratedMcpTool(it.name, it.description, it.inputSchemaJson, it.retryable)
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

    override fun unregisterToolProvider() {
        unregisterToolProvider()
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
