package com.openai.companion.kmp

/**
 * Small adapter around the generated UniFFI object. Keeping generated names at
 * this edge lets the rest of KMP remain independent of bindgen package details.
 */
interface GeneratedHarnessBindings {
    fun registerToolProvider(provider: RustToolProvider)

    fun unregisterToolProvider()
}

fun registerMcpProvider(
    bindings: GeneratedHarnessBindings,
    manager: McpServerManager,
) {
    bindings.registerToolProvider(McpToolProvider(manager))
}
