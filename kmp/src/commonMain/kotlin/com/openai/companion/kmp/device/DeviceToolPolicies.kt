package com.openai.companion.kmp.device

import com.openai.companion.kmp.ToolPolicy

/** Named host-owned defaults; published descriptors and execution use the same policy model. */
object DeviceToolPolicies {
    val PersonalRead = ToolPolicy(origin = "device", effect = "read", dataClass = "personal")
    val PersonalWrite = ToolPolicy(origin = "device", effect = "write", dataClass = "personal")
    val PublicRead = ToolPolicy(origin = "device", effect = "read", dataClass = "public",
        requiresForeground = false, backgroundEligible = true, requiresApproval = false, retryMode = "safe_read")
}
