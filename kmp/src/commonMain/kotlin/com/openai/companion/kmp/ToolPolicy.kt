package com.openai.companion.kmp

/** Host-authored policy. Unknown/remote tools default to foreground approval with no retry. */
data class ToolPolicy(
    val version: UInt = 1u,
    val origin: String = "remote",
    val effect: String = "unknown",
    val dataClass: String = "unknown",
    val requiresForeground: Boolean = true,
    val backgroundEligible: Boolean = false,
    val requiresApproval: Boolean = true,
    val retryMode: String = "never",
) {
    fun valid(): Boolean = version == 1u && origin in setOf("device", "remote") &&
        effect in setOf("read", "write", "unknown") && dataClass in setOf("public", "personal", "unknown") &&
        retryMode in setOf("never", "safe_read") && (retryMode != "safe_read" || effect == "read") &&
        (effect != "write" || requiresApproval && requiresForeground && !backgroundEligible)
    fun allowsBackgroundRead(): Boolean = valid() && effect == "read" && dataClass == "public" &&
        backgroundEligible && !requiresForeground && !requiresApproval
}

fun McpToolDescriptor.toHarnessTool() = McpTool(name, description, inputSchemaJson, policy)
