package com.openai.companion.kmp.device

import com.openai.companion.kmp.McpToolDescriptor
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Native adapters supply descriptors, but host policy and durable write claims stay shared. */
class NativeDeviceTool(
    override val descriptor: McpToolDescriptor,
    private val writes: Boolean,
    private val journal: DeviceOperationJournal,
    private val invoke: suspend (JsonObject) -> DeviceToolResult,
) : DeviceTool {
    override val policy = if (writes) DeviceToolPolicies.PersonalWrite else DeviceToolPolicies.PersonalRead

    override suspend fun execute(arguments: JsonObject): DeviceToolResult {
        if (!writes) return invoke(arguments)
        val requestId = arguments["request_id"]?.jsonPrimitive?.takeIf { it.isString }?.content
        require(requestId != null && Regex("[A-Za-z0-9_-]{8,128}").matches(requestId)) { "request_id must contain 8–128 ASCII letters, digits, hyphens or underscores" }
        val key = DeviceOperationKey(descriptor.name, requestId, canonical(arguments).toString())
        // No native write can start twice, including after restart or outer coroutine cancellation.
        return withContext(NonCancellable) {
            when (val claim = journal.claim(key)) {
                is DeviceOperationClaim.Existing -> when (val record = claim.record) {
                    is DeviceOperationRecord.Succeeded -> record.result
                    DeviceOperationRecord.Conflict -> throw IllegalArgumentException("request_id reused with different arguments")
                    else -> DeviceToolResult("requires_user_action", code = "OUTCOME_UNKNOWN",
                        message = "Previous operation may have executed. Inspect native state; do not repeat with a new request_id.")
                }
                is DeviceOperationClaim.Acquired -> {
                    try {
                        val result = invoke(arguments)
                        journal.finish(claim.operationId, result)
                    } catch (_: Exception) {
                        DeviceToolResult("requires_user_action", code = "OUTCOME_UNKNOWN",
                            message = "Native write or journal completion was interrupted. Inspect state; do not repeat with a new request_id.")
                    }
                }
            }
        }
    }

    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.keys.sorted().associateWith { canonical(value.getValue(it)) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }
}
