package com.openai.companion.kmp

import uniffi.harness.McpTool as GeneratedMcpTool
import uniffi.harness.ToolProvider as GeneratedToolProvider
import uniffi.harness.registerToolProvider
import uniffi.harness.unregisterToolProvider

/** Adapter over the JVM binding generated from harness.udl. */
class GeneratedHarnessBindingsAdapter : GeneratedHarnessBindings {
    override fun registerToolProvider(provider: RustToolProvider) {
        registerToolProvider(object : GeneratedToolProvider {
            override suspend fun getTools(): List<GeneratedMcpTool> = provider.getTools().map {
                GeneratedMcpTool(it.name, it.description, it.inputSchemaJson)
            }

            override suspend fun callTool(name: String, argumentsJson: String): String =
                provider.callTool(name, argumentsJson)
        })
    }

    override fun unregisterToolProvider() {
        unregisterToolProvider()
    }
}
