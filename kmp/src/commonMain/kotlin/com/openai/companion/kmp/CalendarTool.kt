package com.openai.companion.kmp

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

@Serializable
data class CalendarEvent(
    val id: String,
    val title: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val calendarName: String? = null,
    val location: String? = null,
    val notes: String? = null,
)

data class CalendarQuery(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val query: String? = null,
    val limit: Int = 20,
    val sortOrder: CalendarSortOrder = CalendarSortOrder.ASC,
)

enum class CalendarSortOrder {
    ASC,
    DESC,
}

interface CalendarEventDataSource {
    suspend fun getEvents(query: CalendarQuery): List<CalendarEvent>
}

class DeviceToolsServerHandle internal constructor(
    private val manager: McpServerManager,
    private val serverId: String,
    private val server: DeviceMcpServer,
) {
    suspend fun close() {
        manager.detach(serverId)
        server.stop()
    }
}

suspend fun startDeviceToolsServer(
    manager: McpServerManager,
    serverId: String = "device-tools",
    configure: DeviceMcpServer.() -> Unit,
): DeviceToolsServerHandle {
    val server = DeviceMcpServer("device-tools")
    server.configure()
    val connection = server.start()
    return try {
        manager.attach(serverId, connection)
        DeviceToolsServerHandle(manager, serverId, server)
    } catch (error: Throwable) {
        server.stop()
        throw error
    }
}

fun DeviceMcpServer.registerCalendarTool(
    dataSource: CalendarEventDataSource,
) {
    registerTool(
        name = "get_events",
        description = "Query calendar events by ISO 8601 date range",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("start_date", buildJsonObject {
                    put("type", "string")
                    put("description", "ISO 8601 开始时间，默认今天 0 点")
                })
                put("end_date", buildJsonObject {
                    put("type", "string")
                    put("description", "ISO 8601 结束时间，默认明天 0 点")
                })
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "按日程标题进行关键词匹配，可选")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "限制返回的日程数量，默认 20，范围 1 到 500")
                })
                put("sort_order", buildJsonObject {
                    put("type", "string")
                    put("description", "按日程开始时间排序，可选 asc 或 desc，默认 asc")
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("asc"))
                        add(JsonPrimitive("desc"))
                    })
                })
            },
        ),
        handler = { arguments ->
            val query = parseCalendarQuery(arguments)
            Json.encodeToString(dataSource.getEvents(query).applyCalendarQuery(query))
        },
    )
}

private fun parseCalendarQuery(arguments: JsonObject?): CalendarQuery {
    val now = Clock.System.now()
    val zone = TimeZone.currentSystemDefault()
    val today = now.toLocalDateTime(zone).date
    val defaultStart = today.atStartOfDayIn(zone)
    val defaultEnd = today.plus(DatePeriod(days = 1)).atStartOfDayIn(zone)
    val startTimeMs = parseIso8601(
        arguments?.get("start_date")?.jsonPrimitive?.content,
        defaultStart,
    ).toEpochMilliseconds()
    val endTimeMs = parseIso8601(
        arguments?.get("end_date")?.jsonPrimitive?.content,
        defaultEnd,
    ).toEpochMilliseconds()
    require(endTimeMs > startTimeMs) { "end_date must be after start_date" }
    val limit = arguments?.get("limit")?.jsonPrimitive?.intOrNull ?: 20
    require(limit in 1..500) { "limit must be between 1 and 500" }
    val sortOrder = when (arguments?.get("sort_order")?.jsonPrimitive?.content?.lowercase()) {
        null, "asc" -> CalendarSortOrder.ASC
        "desc" -> CalendarSortOrder.DESC
        else -> throw IllegalArgumentException("sort_order must be 'asc' or 'desc'")
    }
    return CalendarQuery(
        startTimeMs = startTimeMs,
        endTimeMs = endTimeMs,
        query = arguments?.get("query")?.jsonPrimitive?.content,
        limit = limit,
        sortOrder = sortOrder,
    )
}

private fun parseIso8601(value: String?, default: Instant): Instant {
    if (value.isNullOrBlank()) return default
    val zone = TimeZone.currentSystemDefault()
    return runCatching { Instant.parse(value) }.getOrElse {
        runCatching { LocalDateTime.parse(value).toInstant(zone) }.getOrElse {
            runCatching { LocalDate.parse(value).atStartOfDayIn(zone) }.getOrElse {
                throw IllegalArgumentException("date must be a valid ISO 8601 string: $value")
            }
        }
    }
}

internal fun List<CalendarEvent>.applyCalendarQuery(query: CalendarQuery): List<CalendarEvent> {
    val keyword = query.query?.trim()?.takeIf { it.isNotEmpty() }
    val filtered = asSequence()
        .filter { keyword == null || it.title.contains(keyword, ignoreCase = true) }
    val sorted = when (query.sortOrder) {
        CalendarSortOrder.ASC -> filtered.sortedBy { it.startTimeMs }
        CalendarSortOrder.DESC -> filtered.sortedByDescending { it.startTimeMs }
    }
    return sorted.take(query.limit).toList()
}
