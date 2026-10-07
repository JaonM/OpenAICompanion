package com.openai.companion.kmp

import android.Manifest
import android.content.Context
import android.content.ContentValues
import android.content.ContentUris
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import kotlinx.datetime.*
import android.content.pm.PackageManager
import android.provider.CalendarContract.Instances
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidCalendarEventDataSource(
    private val context: Context,
    private val requestPermission: suspend () -> Boolean = { false },
    private val requestWritePermission: suspend () -> Boolean = { false },
) : CalendarWriteDataSource {
    override suspend fun checkPermission() {
        if (!hasPermission()) denied()
    }

    private fun hasPermission() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    private fun denied(): Nothing = throw ToolExecutionException(ToolExecutionErrorCode.PERMISSION_DENIED,
        "Allow Calendar access in Android Settings, then retry in the foreground")

    override suspend fun ensureWritePermission() {
        if (context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED && !requestWritePermission()) denied()
        checkPermission()
        if (context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) denied()
    }

    override suspend fun listCalendars(): List<DeviceCalendar> {
        if (!hasPermission() && !requestPermission()) denied()
        return withContext(Dispatchers.IO) {
            try {
                checkPermission()
                val calendars = mutableListOf<DeviceCalendar>()
                val cursor = context.contentResolver.query(Calendars.CONTENT_URI,
                    arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.CALENDAR_ACCESS_LEVEL),
                    null, null, "${Calendars._ID} ASC") ?: error("Calendar provider unavailable")
                cursor.use {
                    while (it.moveToNext()) calendars += DeviceCalendar(it.getLong(0).toString(), it.getString(1).orEmpty(),
                        it.getInt(2) >= Calendars.CAL_ACCESS_CONTRIBUTOR)
                }
                calendars
            } catch (_: SecurityException) { denied() }
        }
    }

    override suspend fun createEvent(draft: CalendarEventDraft): String = withContext(Dispatchers.IO) {
        checkPermission()
        if (context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) denied()
        val calendarId = draft.calendarId.toLongOrNull() ?: error("Invalid calendar id")
        // Android requires UTC midnight for all-day events, independent of the local DST offset.
        fun providerTime(value: Long): Long = if (!draft.allDay) value else
            Instant.fromEpochMilliseconds(value).toLocalDateTime(TimeZone.of(draft.timeZone)).date
                .atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
        val values = ContentValues().apply {
            put(Events.CUSTOM_APP_URI, draft.operationUrl())
            put(Events.CUSTOM_APP_PACKAGE, context.packageName)
            put(Events.CALENDAR_ID, calendarId); put(Events.TITLE, draft.title)
            put(Events.DTSTART, providerTime(draft.startTimeMs)); put(Events.DTEND, providerTime(draft.endTimeMs))
            put(Events.EVENT_TIMEZONE, if (draft.allDay) "UTC" else draft.timeZone)
            put(Events.ALL_DAY, if (draft.allDay) 1 else 0)
            draft.location?.let { put(Events.EVENT_LOCATION, it) }
            draft.notes?.let { put(Events.DESCRIPTION, it) }
        }
        val uri = context.contentResolver.insert(Events.CONTENT_URI, values) ?: error("Calendar save returned no event")
        ContentUris.parseId(uri).toString()
    }

    override suspend fun findCreatedEvent(draft: CalendarEventDraft): String? = withContext(Dispatchers.IO) {
        checkPermission()
        val cursor = context.contentResolver.query(Events.CONTENT_URI, arrayOf(Events._ID),
            "${Events.CUSTOM_APP_URI}=? AND ${Events.CALENDAR_ID}=? AND ${Events.DELETED}=0",
            arrayOf(draft.operationUrl(), draft.calendarId), null) ?: error("Calendar recovery query failed")
        cursor.use {
            if (!it.moveToFirst()) return@withContext null
            val id = it.getLong(0).toString()
            check(!it.moveToNext()) { "Multiple events have the same operation marker" }
            checkPermission()
            id
        }
    }

    override suspend fun getEvents(query: CalendarQuery): List<CalendarEvent> {
        if (!hasPermission() && !requestPermission()) denied()
        checkPermission()
        return withContext(Dispatchers.IO) {
            try {
                val uri = Instances.CONTENT_URI.buildUpon().appendPath(query.startTimeMs.toString())
                    .appendPath(query.endTimeMs.toString()).build()
                val projection = mutableListOf(Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN,
                    Instances.END, Instances.CALENDAR_DISPLAY_NAME, Instances.ALL_DAY, Instances.STATUS)
                if (query.includeLocation) projection += Instances.EVENT_LOCATION
                if (query.includeNotes) projection += Instances.DESCRIPTION
                val events = mutableListOf<CalendarEvent>()
                val cursor = context.contentResolver.query(uri, projection.toTypedArray(), null, null,
                    "${Instances.BEGIN} ASC, ${Instances.END} ASC, ${Instances.EVENT_ID} ASC")
                    ?: throw ToolExecutionException(ToolExecutionErrorCode.SERVER_INTERNAL_ERROR, "Calendar provider unavailable")
                cursor.use {
                    fun text(column: String) = it.getString(it.getColumnIndexOrThrow(column))
                    fun number(column: String) = it.getLong(it.getColumnIndexOrThrow(column))
                    while (events.size < query.limit && it.moveToNext()) {
                        if (number(Instances.STATUS).toInt() == Instances.STATUS_CANCELED) continue
                        val title = text(Instances.TITLE).orEmpty()
                        if (!query.query.isNullOrBlank() && !title.contains(query.query, true)) continue
                        if (number(Instances.END) <= query.startTimeMs || number(Instances.BEGIN) >= query.endTimeMs) continue
                        events += CalendarEvent(text(Instances.EVENT_ID), title, number(Instances.BEGIN), number(Instances.END),
                            text(Instances.CALENDAR_DISPLAY_NAME),
                            if (query.includeLocation) text(Instances.EVENT_LOCATION) else null,
                            if (query.includeNotes) text(Instances.DESCRIPTION) else null,
                            number(Instances.ALL_DAY) != 0L)
                    }
                }
                checkPermission()
                events
            } catch (_: SecurityException) { denied() }
        }
    }
}
