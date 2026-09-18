package com.openai.companion.kmp

import platform.EventKit.EKAuthorizationStatusAuthorized
import platform.EventKit.EKEntityTypeEvent
import platform.EventKit.EKEvent
import platform.EventKit.EKEventStore
import platform.EventKit.EKEventStatusCanceled
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MacCalendarEventDataSource(
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
            .map { event ->
                CalendarEvent(
                    id = event.eventIdentifier.orEmpty(),
                    title = event.title.orEmpty(),
                    startTimeMs = (event.startDate?.timeIntervalSince1970?.times(1000.0))?.toLong() ?: 0,
                    endTimeMs = (event.endDate?.timeIntervalSince1970?.times(1000.0))?.toLong() ?: 0,
                    calendarName = event.calendar?.title,
                    location = event.location,
                    notes = event.notes,
                )
            }
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
