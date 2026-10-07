package com.openai.companion.kmp

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*

/** JVM desktop EventKit adapter; the Native macosMain source is not part of the desktop application. */
class DesktopCalendarEventDataSource : CalendarWriteDataSource {
    private interface Bridge : Library {
        fun companion_calendar_query(request: String): Pointer?
        fun companion_calendar_free(value: Pointer)
    }
    private val bridge by lazy {
        val path = System.getenv("COMPANION_CALENDAR_LIBRARY_PATH")
            ?: System.getProperty("compose.application.resources.dir")?.let { File(it, "libcompanion_calendar.dylib").absolutePath }
            ?: File("appResources/macos-arm64/libcompanion_calendar.dylib").absolutePath
        try { Native.load(path, Bridge::class.java) }
        catch (error: LinkageError) { throw ToolExecutionException(ToolExecutionErrorCode.RESOURCE_NOT_FOUND,
            "Calendar bridge missing; build with scripts/macos-app.sh", error) }
    }
    override suspend fun checkPermission() { call(buildJsonObject { put("checkOnly", true) }) }
    override suspend fun ensureWritePermission() { call(buildJsonObject { put("operation", "authorize") }) }
    override suspend fun listCalendars(): List<DeviceCalendar> = Json.decodeFromJsonElement(
        call(buildJsonObject { put("operation", "listCalendars") }).getValue("calendars"))
    override suspend fun createEvent(draft: CalendarEventDraft): String = call(buildJsonObject {
        put("operationUrl", draft.operationUrl())
        put("operation", "create"); put("calendarId", draft.calendarId); put("title", draft.title)
        put("start", draft.startTimeMs); put("end", draft.endTimeMs); put("timeZone", draft.timeZone); put("allDay", draft.allDay)
        draft.location?.let { put("location", it) }; draft.notes?.let { put("notes", it) }
    }).getValue("eventId").jsonPrimitive.content

    override suspend fun findCreatedEvent(draft: CalendarEventDraft): String? = call(buildJsonObject {
        put("operation", "recover"); put("operationUrl", draft.operationUrl()); put("calendarId", draft.calendarId)
        put("start", draft.startTimeMs - 86_400_000); put("end", draft.endTimeMs + 86_400_000)
    })["eventId"]?.jsonPrimitive?.contentOrNull

    override suspend fun getEvents(query: CalendarQuery): List<CalendarEvent> {
        val result = call(buildJsonObject {
            put("start", query.startTimeMs); put("end", query.endTimeMs); put("limit", query.limit)
            put("query", query.query.orEmpty()); put("includeLocation", query.includeLocation); put("includeNotes", query.includeNotes)
        })
        return Json.decodeFromJsonElement(result.getValue("events"))
    }
    private suspend fun call(arguments: JsonObject): JsonObject = runInterruptible(Dispatchers.IO) {
        val api = bridge
        val pointer = api.companion_calendar_query(arguments.toString())
            ?: throw ToolExecutionException(ToolExecutionErrorCode.SERVER_INTERNAL_ERROR, "Calendar bridge returned no result")
        val result = try { Json.parseToJsonElement(pointer.getString(0, "UTF-8")).jsonObject }
            finally { api.companion_calendar_free(pointer) }
        result["error"]?.jsonPrimitive?.content?.let { code ->
            throw ToolExecutionException(ToolExecutionErrorCode.entries.firstOrNull { it.name == code }
                ?: ToolExecutionErrorCode.UNKNOWN, result["message"]?.jsonPrimitive?.content ?: code)
        }
        result
    }
}
