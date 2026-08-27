package com.openai.companion.kmp

/**
 * Small adapter around the generated UniFFI object. Keeping generated names at
 * this edge lets the rest of KMP remain independent of bindgen package details.
 */
interface GeneratedHarnessBindings {
    fun registerToolProvider(provider: RustToolProvider)

    fun registerModelServeCallback(provider: AppModelServe)

    fun updateMcpTools(tools: List<McpTool>)

    fun unregisterToolProvider()

    fun unregisterModelServeCallback()

    fun registerAgentEventSink(sink: AppAgentEventSink)

    fun unregisterAgentEventSink()
}

suspend fun registerMcpProvider(
    bindings: GeneratedHarnessBindings,
    manager: McpServerManager,
) {
    bindings.registerToolProvider(McpToolProvider(manager))
    manager.setToolsChangedListener {
        bindings.updateMcpTools(manager.tools().map { tool ->
            McpTool(
                name = tool.name,
                description = tool.description,
                inputSchemaJson = tool.inputSchemaJson,
            )
        })
    }
    bindings.updateMcpTools(manager.tools().map { tool ->
        McpTool(tool.name, tool.description, tool.inputSchemaJson)
    })
}
