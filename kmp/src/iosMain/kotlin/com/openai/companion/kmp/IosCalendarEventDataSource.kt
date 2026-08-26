package com.openai.companion.kmp

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.EventKit.EKAuthorizationStatusAuthorized
import platform.EventKit.EKEntityTypeEvent
import platform.EventKit.EKEvent
import platform.EventKit.EKEventStore
import platform.EventKit.EKEventStatusCanceled
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import kotlin.coroutines.resume

class IosCalendarEventDataSource(
    private val eventStore: EKEventStore = EKEventStore(),
) : CalendarEventDataSource {
    override suspend fun getEvents(query: CalendarQuery): List<CalendarEvent> {
        ensurePermission()
        val start = NSDate.dateWithTimeIntervalSince1970(query.startTimeMs / 1000.0)
        val end = NSDate.dateWithTimeIntervalSince1970(query.endTimeMs / 1000.0)
        val predicate = eventStore.predicateForEventsWithStartDate(start, end, null)
        return eventStore.eventsMatchingPredicate(predicate)
            .toList()
            .filterIsInstance<EKEvent>()
            .asSequence()
            .filter { it.status != EKEventStatusCanceled }
            .map { it.toCalendarEvent() }
            .toList()
    }

    private suspend fun ensurePermission() {
        if (EKEventStore.authorizationStatusForEntityType(EKEntityTypeEvent) == EKAuthorizationStatusAuthorized) {
            return
        }
        val granted = suspendCancellableCoroutine { continuation ->
            eventStore.requestAccessToEntityType(EKEntityTypeEvent) { value, _ ->
                continuation.resume(value)
            }
        }
        if (!granted) {
            throw ToolExecutionException(
                ToolExecutionErrorCode.PERMISSION_DENIED,
                "calendar permission is required",
            )
        }
    }
}

private fun EKEvent.toCalendarEvent(): CalendarEvent = CalendarEvent(
    id = eventIdentifier.orEmpty(),
    title = title.orEmpty(),
    startTimeMs = (startDate?.timeIntervalSince1970?.times(1000.0))?.toLong() ?: 0,
    endTimeMs = (endDate?.timeIntervalSince1970?.times(1000.0))?.toLong() ?: 0,
    calendarName = calendar?.title,
    location = location,
    notes = notes,
)
