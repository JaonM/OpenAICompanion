package com.openai.companion.kmp.device

/** Request payload remains JSON so each tool can version its canonical representation independently. */
data class DeviceOperationKey(val tool: String, val requestId: String, val requestJson: String)

sealed interface DeviceOperationRecord {
    data object Absent : DeviceOperationRecord
    data object Conflict : DeviceOperationRecord
    data class Succeeded(val result: DeviceToolResult) : DeviceOperationRecord
    sealed interface Unresolved : DeviceOperationRecord { val operationId: String }
    data class Pending(override val operationId: String) : Unresolved
    data class Unknown(override val operationId: String) : Unresolved
}

sealed interface DeviceOperationClaim {
    data class Acquired(val operationId: String) : DeviceOperationClaim
    data class Existing(val record: DeviceOperationRecord) : DeviceOperationClaim {
        init { require(record != DeviceOperationRecord.Absent) }
    }
}

interface DeviceOperationJournal {
    suspend fun lookup(key: DeviceOperationKey): DeviceOperationRecord
    /** Atomic acquisition; existing pending requests are never reclaimed. */
    suspend fun claim(key: DeviceOperationKey): DeviceOperationClaim
    suspend fun finish(operationId: String, result: DeviceToolResult): DeviceToolResult
}
