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

    fun configureContextDirectories(agentsDirectory: String, personaDirectory: String)

    fun clearContextDirectories()

    fun deviceOperation(tool: String, requestId: String, requestJson: String, claim: Boolean): String =
        error("Persistent device operation store is unavailable")
    fun finishDeviceOperation(operationId: String, succeeded: Boolean, resultJson: String): String =
        error("Persistent device operation store is unavailable")

    fun answerDeviceQuestion(requestJson: String): String = "{\"answer\":null}"

    fun executeDeviceTask(requestJson: String): String = error("Device task executor unavailable")

    fun cancelAgentLoop()
}

suspend fun registerMcpProvider(
    bindings: GeneratedHarnessBindings,
    manager: McpServerManager,
    approve: suspend (String, String) -> Boolean,
    onToolCountChanged: (Int) -> Unit = {},
    remoteApprovalName: (String) -> String = { it },
) {
    bindings.registerToolProvider(McpToolProvider(manager, approve, remoteApprovalName))
    manager.setToolsChangedListener {
        val tools = manager.tools().map { tool ->
            tool.toHarnessTool()
        }
        bindings.updateMcpTools(tools)
        onToolCountChanged(tools.size)
    }
    val initialTools = manager.tools().map { tool ->
        tool.toHarnessTool()
    }
    bindings.updateMcpTools(initialTools)
    onToolCountChanged(initialTools.size)
}
