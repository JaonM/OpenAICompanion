package com.openai.companion.kmp.device

import com.openai.companion.kmp.GeneratedHarnessBindings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Isolates the existing UniFFI JSON wire format; domain code only sees valid, typed states. */
class BindingsDeviceOperationJournal(private val bindings: GeneratedHarnessBindings) : DeviceOperationJournal {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override suspend fun lookup(key: DeviceOperationKey): DeviceOperationRecord = withContext(Dispatchers.Default) {
        val reply = exchange(key, claim = false)
        check(!reply.getValue("claimed").jsonPrimitive.boolean) { "Lookup must not claim an operation" }
        decodeRecord(reply)
    }

    override suspend fun claim(key: DeviceOperationKey): DeviceOperationClaim = withContext(Dispatchers.Default) {
        val reply = exchange(key, claim = true)
        val record = decodeRecord(reply)
        if (reply.getValue("claimed").jsonPrimitive.boolean) {
            check(record is DeviceOperationRecord.Pending) { "Only a pending operation can be acquired" }
            DeviceOperationClaim.Acquired(record.operationId)
        } else {
            DeviceOperationClaim.Existing(record)
        }
    }

    private fun exchange(key: DeviceOperationKey, claim: Boolean): JsonObject =
        json.parseToJsonElement(bindings.deviceOperation(key.tool, key.requestId, key.requestJson, claim)).jsonObject

    private fun decodeRecord(reply: JsonObject): DeviceOperationRecord {
        fun operationId(): String = reply.getValue("operation_id").jsonPrimitive.content.also {
            check(it.matches(Regex("[0-9a-f]{32}"))) { "Invalid operation identifier" }
        }
        return when (reply.getValue("state").jsonPrimitive.content) {
            "absent" -> DeviceOperationRecord.Absent
            "conflict" -> DeviceOperationRecord.Conflict
            "pending" -> DeviceOperationRecord.Pending(operationId())
            "unknown" -> DeviceOperationRecord.Unknown(operationId())
            "succeeded" -> DeviceOperationRecord.Succeeded(decodeResult(reply).also {
                check(it.status == "ok") { "Succeeded operation requires a successful result" }
            })
            else -> error("Unknown device operation state")
        }
    }

    private fun decodeResult(reply: JsonObject): DeviceToolResult =
        json.decodeFromString(reply.getValue("result_json").jsonPrimitive.content)

    override suspend fun finish(operationId: String, result: DeviceToolResult): DeviceToolResult = withContext(Dispatchers.Default) {
        decodeResult(json.parseToJsonElement(bindings.finishDeviceOperation(
            operationId, result.status == "ok", json.encodeToString(result),
        )).jsonObject)
    }
}
