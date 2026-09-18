package com.openai.companion.kmp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidCalendarEventDataSource(
    private val context: Context,
) : CalendarEventDataSource {
    override suspend fun getEvents(query: CalendarQuery): List<CalendarEvent> = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            throw ToolExecutionException(
                ToolExecutionErrorCode.PERMISSION_DENIED,
                "READ_CALENDAR permission is required",
            )
        }

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(query.startTimeMs.toString())
            .appendPath(query.endTimeMs.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.STATUS,
        )
        val events = mutableListOf<CalendarEvent>()
        context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            val eventId = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
            val title = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val begin = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val end = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val location = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
            val notes = cursor.getColumnIndexOrThrow(CalendarContract.Instances.DESCRIPTION)
            val status = cursor.getColumnIndexOrThrow(CalendarContract.Instances.STATUS)
            while (cursor.moveToNext()) {
                if (cursor.getInt(status) == CalendarContract.Instances.STATUS_CANCELED) continue
                events += CalendarEvent(
                    id = cursor.getString(eventId),
                    title = cursor.getString(title).orEmpty(),
                    startTimeMs = cursor.getLong(begin),
                    endTimeMs = cursor.getLong(end),
                    calendarName = null,
                    location = cursor.getString(location),
                    notes = cursor.getString(notes),
                )
            }
        }
        events
    }
}
