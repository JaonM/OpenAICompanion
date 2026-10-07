package com.openai.companion.kmp.device.calendar

import com.openai.companion.kmp.*
import com.openai.companion.kmp.device.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.*
import kotlin.random.Random

/** One contract for all platforms. Cursors address bounded, short-lived snapshots, not native IDs. */
class CalendarListTool(
    private val source: CalendarEventDataSource,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : DeviceTool {
    override val policy = DeviceToolPolicies.PersonalRead
    override val descriptor = McpToolDescriptor(
        "device_calendar_list_events",
        "Query this device's calendar. Requires user approval and calendar permission. " +
            "First page requires start/end ISO 8601 timestamps with offsets (maximum 31 days). " +
            "Continue with only cursor; snapshots expire after two minutes. " +
            "Default fields: title,start,end,all_day. Location and notes require explicit fields. " +
            "Results may be sent to the configured model service.",
        objectSchema(buildJsonObject {
            for (key in listOf("start", "end", "query", "cursor")) put(key, buildJsonObject { put("type", "string") })
            put("limit", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 100) })
            put("fields", buildJsonObject {
                put("type", "array"); put("maxItems", 7); put("uniqueItems", true)
                put("items", buildJsonObject { put("type", "string"); put("enum", JsonArray(FIELDS.map(::JsonPrimitive))) })
            })
        }),
    )
    private data class Snapshot(val expires: Long, val pages: List<List<JsonObject>>)
    private val gate = Mutex()
    private val snapshots = linkedMapOf<String, Snapshot>()

    override suspend fun execute(arguments: JsonObject): DeviceToolResult {
        arguments.only("start", "end", "query", "cursor", "fields", "limit")
        val cursor = arguments["cursor"]?.let { string(it, "cursor") }
        if (cursor != null) {
            require(arguments.keys.all { it == "cursor" }) { "Continue with only cursor" }
            // Authorization is rechecked even when serving cached pages.
            source.checkPermission()
            return gate.withLock { page(cursor) }
        }
        val start = Instant.parse(string(arguments["start"], "start")).toEpochMilliseconds()
        val end = Instant.parse(string(arguments["end"], "end")).toEpochMilliseconds()
        require(end > start && end - start in 1..31L * 86_400_000) { "Range must be positive and at most 31 days" }
        val limit = arguments["limit"]?.let {
            require(it is JsonPrimitive && !it.isString) { "limit must be an integer" }
            it.intOrNull ?: throw IllegalArgumentException("limit must be an integer")
        } ?: 20
        require(limit in 1..100) { "limit must be between 1 and 100" }
        val fields = arguments["fields"]?.let { raw ->
            require(raw is JsonArray && raw.size <= FIELDS.size) { "Invalid fields" }
            raw.map { string(it, "fields") }.also { require(it.distinct().size == it.size) { "Duplicate fields" } }.toSet().also { require(it.all { field -> field in FIELDS }) { "Unknown field" } }
        } ?: setOf("title", "start", "end", "all_day")
        val query = arguments["query"]?.let { string(it, "query").trim().also { text -> require(text.length <= 200) } }
        val events = source.getEvents(CalendarQuery(start, end, query, 501,
            includeLocation = "location" in fields, includeNotes = "notes" in fields))
            .filter { it.endTimeMs > start && it.startTimeMs < end && (query.isNullOrBlank() || it.title.contains(query, true)) }
            .sortedWith(compareBy({ it.startTimeMs }, { it.endTimeMs }, { it.id }))
        require(events.size <= 500) { "More than 500 events; query a smaller date range" }
        val id = Random.nextBytes(16).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        val encoded = events.mapIndexed { index, event -> buildJsonObject {
            put("event_ref", "$id:$index")
            put("truncated_fields", JsonArray(listOfNotNull(
                "title".takeIf { it in fields && event.title.length > 500 },
                "calendar".takeIf { it in fields && (event.calendarName?.length ?: 0) > 200 },
                "location".takeIf { it in fields && (event.location?.length ?: 0) > 1000 },
                "notes".takeIf { it in fields && (event.notes?.length ?: 0) > 2000 },
            ).map(::JsonPrimitive)))
            if ("title" in fields) put("title", event.title.take(500))
            if ("start" in fields) put("start", Instant.fromEpochMilliseconds(event.startTimeMs).toString())
            if ("end" in fields) put("end", Instant.fromEpochMilliseconds(event.endTimeMs).toString())
            if ("all_day" in fields) put("all_day", event.allDay)
            if ("calendar" in fields) put("calendar", event.calendarName?.take(200)?.let(::JsonPrimitive) ?: JsonNull)
            if ("location" in fields) put("location", event.location?.take(1000)?.let(::JsonPrimitive) ?: JsonNull)
            if ("notes" in fields) put("notes", event.notes?.take(2000)?.let(::JsonPrimitive) ?: JsonNull)
        } }
        return gate.withLock {
            purge()
            while (snapshots.size >= 3) snapshots.remove(snapshots.keys.first())
            val pages = mutableListOf<List<JsonObject>>()
            var page = mutableListOf<JsonObject>()
            var characters = 0
            for (event in encoded) {
                val size = event.toString().length
                if (page.isNotEmpty() && (page.size >= limit || characters + size > 16_000)) {
                    pages += page; page = mutableListOf(); characters = 0
                }
                page += event; characters += size
            }
            if (page.isNotEmpty() || pages.isEmpty()) pages += page
            snapshots[id] = Snapshot(nowMillis() + 120_000, pages)
            page("$id:0")
        }
    }

    private fun purge() {
        val now = nowMillis()
        snapshots.entries.removeAll { it.value.expires <= now }
    }
    private fun page(cursor: String): DeviceToolResult {
        purge()
        val parts = cursor.split(':')
        require(parts.size == 2) { "Invalid cursor" }
        val snapshot = snapshots[parts[0]] ?: return DeviceToolResult("error", code = "CURSOR_EXPIRED", message = "Repeat the original query")
        val index = parts[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid cursor")
        require(index in snapshot.pages.indices) { "Invalid cursor" }
        return DeviceToolResult(data = buildJsonObject { put("events", JsonArray(snapshot.pages[index])) },
            nextCursor = if (index + 1 < snapshot.pages.size) "${parts[0]}:${index + 1}" else null)
    }

    private fun string(value: JsonElement?, name: String): String {
        require(value is JsonPrimitive && value.isString) { "$name must be a string" }
        return value.content
    }
    private companion object { val FIELDS = listOf("title", "start", "end", "all_day", "calendar", "location", "notes") }
}
