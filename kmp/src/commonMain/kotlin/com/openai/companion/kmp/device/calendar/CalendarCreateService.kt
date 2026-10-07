package com.openai.companion.kmp.device.calendar

import com.openai.companion.kmp.*
import com.openai.companion.kmp.device.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns the save/recovery state machine, including cancellation and durable claim boundaries. */
internal class CalendarCreateService(
    private val source: CalendarWriteDataSource,
    private val journal: DeviceOperationJournal,
    private val isForeground: suspend () -> Boolean,
) {
    private val gate = Mutex()

    suspend fun create(request: CalendarCreateRequest): DeviceToolResult = gate.withLock {
        val key = DeviceOperationKey(CalendarCreateTool.NAME, request.requestId, request.canonicalJson)
        val previous = journal.lookup(key)
        if (previous != DeviceOperationRecord.Absent) return@withLock recover(request, previous)
        preflight(request)?.let { return@withLock it }
        currentCoroutineContext().ensureActive()
        if (!isForeground()) return@withLock DeviceToolResult("requires_user_action",
            code = "FOREGROUND_REQUIRED", message = "Return to the app before creating an event")
        // Once claimed, cancellation must not separate a native save from recording its outcome.
        withContext(NonCancellable) {
            when (val claim = journal.claim(key)) {
                is DeviceOperationClaim.Acquired -> save(request, claim.operationId)
                is DeviceOperationClaim.Existing -> recover(request, claim.record)
            }
        }
    }

    private suspend fun preflight(request: CalendarCreateRequest): DeviceToolResult? {
        source.ensureWritePermission()
        val calendar = source.listCalendars().firstOrNull { it.id == request.draft.calendarId }
            ?: return DeviceToolResult("error", code = "CALENDAR_NOT_FOUND", message = "List calendars again and select a target")
        if (calendar.title.take(500) != request.calendarTitle) return DeviceToolResult("requires_user_action",
            code = "CALENDAR_CHANGED", message = "Calendar title changed; list calendars and confirm the target again")
        if (!calendar.writable) return DeviceToolResult("requires_user_action",
            code = "CALENDAR_READ_ONLY", message = "Choose a writable calendar")
        return null
    }

    private suspend fun recover(request: CalendarCreateRequest, record: DeviceOperationRecord): DeviceToolResult = when (record) {
        DeviceOperationRecord.Absent -> error("Claim returned an absent operation")
        DeviceOperationRecord.Conflict -> throw IllegalArgumentException("request_id was already used with different event details")
        is DeviceOperationRecord.Succeeded -> record.result
        is DeviceOperationRecord.Unresolved -> reconcile(request, record.operationId)
    }

    private suspend fun reconcile(request: CalendarCreateRequest, operationId: String): DeviceToolResult = try {
        val found = source.findCreatedEvent(request.draft.copy(operationId = operationId))
        if (found.isNullOrBlank()) unknownOutcome() else journal.finish(operationId, request.success(found))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: ToolExecutionException) {
        if (error.code == ToolExecutionErrorCode.PERMISSION_DENIED) throw error
        unknownOutcome()
    } catch (_: Exception) {
        unknownOutcome()
    }

    private suspend fun save(request: CalendarCreateRequest, operationId: String): DeviceToolResult {
        val result = try {
            val eventId = source.createEvent(request.draft.copy(operationId = operationId))
            check(eventId.isNotBlank()) { "Native calendar returned no event identifier" }
            request.success(eventId)
        } catch (_: Exception) { unknownOutcome() }
        // A failed journal completion leaves the durable claim in place, preventing a second write.
        return try { journal.finish(operationId, result) } catch (_: Exception) { unknownOutcome() }
    }

    private fun unknownOutcome() = DeviceToolResult("error", code = "CREATE_OUTCOME_UNKNOWN",
        message = "Save outcome is unresolved. Retry the SAME request_id to check its calendar marker; do not create a new request to repeat this write.")
}
