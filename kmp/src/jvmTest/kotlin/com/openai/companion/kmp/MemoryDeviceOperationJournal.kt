package com.openai.companion.kmp

import com.openai.companion.kmp.device.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Test fixture only. Production hosts must use the SQLite-backed bindings adapter. */
class MemoryDeviceOperationJournal : DeviceOperationJournal {
    private data class Entry(val request: String, val operationId: String, var record: DeviceOperationRecord)
    private val gate = Mutex()
    private val entries = mutableMapOf<String, Entry>()

    private fun record(key: DeviceOperationKey): DeviceOperationRecord {
        val entry = entries["${key.tool}/${key.requestId}"] ?: return DeviceOperationRecord.Absent
        return if (Json.parseToJsonElement(entry.request) != Json.parseToJsonElement(key.requestJson))
            DeviceOperationRecord.Conflict else entry.record
    }

    override suspend fun lookup(key: DeviceOperationKey): DeviceOperationRecord = gate.withLock { record(key) }

    override suspend fun claim(key: DeviceOperationKey): DeviceOperationClaim = gate.withLock {
        val previous = record(key)
        if (previous != DeviceOperationRecord.Absent) return@withLock DeviceOperationClaim.Existing(previous)
        val operationId = (entries.size + 1).toString(16).padStart(32, '0')
        entries["${key.tool}/${key.requestId}"] = Entry(key.requestJson, operationId, DeviceOperationRecord.Pending(operationId))
        DeviceOperationClaim.Acquired(operationId)
    }

    override suspend fun finish(operationId: String, result: DeviceToolResult): DeviceToolResult = gate.withLock {
        val entry = entries.values.single { it.operationId == operationId }
        val previous = entry.record
        if (previous is DeviceOperationRecord.Succeeded) return@withLock previous.result
        entry.record = if (result.status == "ok") DeviceOperationRecord.Succeeded(result) else DeviceOperationRecord.Unknown(operationId)
        result
    }
}
