package com.openai.companion.kmp

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.EventKit.*
import platform.Foundation.NSTimeZone
import platform.Foundation.timeZoneWithName
import platform.Foundation.NSURL
import platform.Foundation.NSDate
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import kotlin.coroutines.resume

@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
class IosCalendarEventDataSource(private val eventStore: EKEventStore = EKEventStore()) : CalendarWriteDataSource {
    override suspend fun checkPermission() {
        if (EKEventStore.authorizationStatusForEntityType(EKEntityType.EKEntityTypeEvent) != EKAuthorizationStatusFullAccess)
            throw ToolExecutionException(ToolExecutionErrorCode.PERMISSION_DENIED,
                "Allow full Calendar access in Settings, then retry in the foreground")
    }

    private suspend fun ensurePermission() = withContext(Dispatchers.Main) {
        if (EKEventStore.authorizationStatusForEntityType(EKEntityType.EKEntityTypeEvent) == EKAuthorizationStatusNotDetermined) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                eventStore.requestFullAccessToEventsWithCompletion { granted, _ ->
                    if (continuation.isActive) continuation.resume(granted)
                }
            }
        }
        checkPermission()
    }

    override suspend fun ensureWritePermission() { ensurePermission() }

    override suspend fun listCalendars(): List<DeviceCalendar> = withContext(Dispatchers.Main) {
        ensurePermission()
        eventStore.calendarsForEntityType(EKEntityType.EKEntityTypeEvent).filterIsInstance<EKCalendar>()
            .map { DeviceCalendar(it.calendarIdentifier, it.title, it.allowsContentModifications) }
    }

    override suspend fun createEvent(draft: CalendarEventDraft): String = withContext(Dispatchers.Main) {
        checkPermission()
        val calendar = eventStore.calendarWithIdentifier(draft.calendarId)
            ?: throw ToolExecutionException(ToolExecutionErrorCode.RESOURCE_NOT_FOUND, "Calendar no longer exists")
        check(calendar.allowsContentModifications) { "Calendar is read-only" }
        val event = EKEvent.eventWithEventStore(eventStore)
        event.URL = NSURL.URLWithString(draft.operationUrl())
        event.calendar = calendar
        event.title = draft.title
        event.startDate = NSDate.create(timeIntervalSince1970 = draft.startTimeMs / 1000.0)
        event.endDate = NSDate.create(timeIntervalSince1970 = draft.endTimeMs / 1000.0)
        event.timeZone = NSTimeZone.timeZoneWithName(draft.timeZone) ?: error("Unsupported calendar timezone")
        event.allDay = draft.allDay
        event.location = draft.location
        event.notes = draft.notes
        check(eventStore.saveEvent(event, EKSpan.EKSpanThisEvent, true, null)) { "Calendar save failed" }
        event.eventIdentifier ?: error("Calendar returned no event identifier")
    }

    override suspend fun findCreatedEvent(draft: CalendarEventDraft): String? = withContext(Dispatchers.Main) {
        checkPermission()
        // Search the original interval with padding for platform all-day normalization.
        val start = NSDate.create(timeIntervalSince1970 = draft.startTimeMs / 1000.0 - 86400)
        val end = NSDate.create(timeIntervalSince1970 = draft.endTimeMs / 1000.0 + 86400)
        val calendar = eventStore.calendarWithIdentifier(draft.calendarId) ?: return@withContext null
        val matches = eventStore.eventsMatchingPredicate(eventStore.predicateForEventsWithStartDate(start, end, listOf(calendar)))
            .filterIsInstance<EKEvent>().filter { it.URL?.absoluteString == draft.operationUrl() && it.status != EKEventStatusCanceled }
        check(matches.size <= 1) { "Multiple events have the same operation marker" }
        checkPermission()
        matches.singleOrNull()?.eventIdentifier
    }

    override suspend fun getEvents(query: CalendarQuery): List<CalendarEvent> = withContext(Dispatchers.Main) {
        ensurePermission()
        val start = NSDate.create(timeIntervalSince1970 = query.startTimeMs / 1000.0)
        val end = NSDate.create(timeIntervalSince1970 = query.endTimeMs / 1000.0)
        val predicate = eventStore.predicateForEventsWithStartDate(start, end, null)
        val events = eventStore.eventsMatchingPredicate(predicate).filterIsInstance<EKEvent>()
            .filter { it.status != EKEventStatusCanceled }
            .map { event -> CalendarEvent(
                id = event.eventIdentifier.orEmpty(), title = event.title.orEmpty(),
                startTimeMs = ((event.startDate?.timeIntervalSince1970 ?: 0.0) * 1000).toLong(),
                endTimeMs = ((event.endDate?.timeIntervalSince1970 ?: 0.0) * 1000).toLong(),
                calendarName = event.calendar?.title,
                location = if (query.includeLocation) event.location else null,
                notes = if (query.includeNotes) event.notes else null, allDay = event.allDay,
            ) }
            .filter { it.endTimeMs > query.startTimeMs && it.startTimeMs < query.endTimeMs &&
                (query.query.isNullOrBlank() || it.title.contains(query.query, true)) }
            .sortedWith(compareBy({ it.startTimeMs }, { it.endTimeMs }, { it.id }))
        checkPermission()
        events.take(query.limit)
    }
}
