package com.openai.companion.kmp

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalendarQueryTest {
    @Test
    fun filters_by_title_sorts_and_limits_events() {
        val events = listOf(
            CalendarEvent("1", "Project planning", 300, 400),
            CalendarEvent("2", "Lunch", 100, 200),
            CalendarEvent("3", "Planning review", 200, 250),
        )

        val result = events.applyCalendarQuery(
            CalendarQuery(
                startTimeMs = 0,
                endTimeMs = 1_000,
                query = "planning",
                limit = 1,
                sortOrder = CalendarSortOrder.DESC,
            ),
        )

        assertEquals(listOf("1"), result.map { it.id })
    }
}

class DeviceCalendarToolTest {
    @Test
    fun dynamic_tool_changes_are_propagated_from_mcp_server() = runBlocking {
        val manager = McpServerManager()
        lateinit var server: DeviceMcpServer
        val handle = startDeviceToolsServer(
            manager = manager,
            configure = {
                server = this
            },
        )

        try {
            server.registerTool(
                name = "late_tool",
                description = "Added after startup",
                inputSchema = io.modelcontextprotocol.kotlin.sdk.types.ToolSchema(),
            ) { "ok" }
            withTimeout(1_000) {
                while (manager.tools().none { it.name == "late_tool" }) delay(10)
            }

            assertTrue(manager.tools().any { it.name == "late_tool" })
            assertTrue(server.removeTool("late_tool"))
            withTimeout(1_000) {
                while (manager.tools().any { it.name == "late_tool" }) delay(10)
            }
            assertTrue(manager.tools().none { it.name == "late_tool" })
        } finally {
            handle.close()
        }
    }

    @Test
    fun calendar_tool_uses_in_process_mcp_transport() = runBlocking {
        val manager = McpServerManager()
        val handle = startDeviceToolsServer(
            manager = manager,
            configure = {
                registerCalendarTool(object : CalendarEventDataSource {
                    override suspend fun getEvents(query: CalendarQuery): List<CalendarEvent> = listOf(
                        CalendarEvent("event-1", "Planning", query.startTimeMs, query.endTimeMs),
                    )
                })
                registerTool("device_echo", "Echo device input", io.modelcontextprotocol.kotlin.sdk.types.ToolSchema()) {
                    "ok"
                }
            },
        )

        try {
            assertEquals(
                listOf("get_events", "device_echo"),
                manager.tools().map { it.name },
            )
            val calendarSchema = manager.tools().first { it.name == "get_events" }.inputSchemaJson
            assertTrue(calendarSchema.trimStart().startsWith("{"))
            assertTrue(calendarSchema.contains("\"properties\""))
            assertTrue(calendarSchema.contains("ISO 8601 开始时间"))
            assertTrue(calendarSchema.contains("限制返回的日程数量"))
            assertFalse(calendarSchema.contains("ToolSchema("))
            val result = manager.callTool(
                "get_events",
                "{\"start_date\":\"2026-01-01T00:00:00Z\",\"end_date\":\"2026-01-02T00:00:00Z\",\"query\":\"plan\",\"limit\":20,\"sort_order\":\"asc\"}",
            )
            assertFalse(result.isError)
            assertEquals(true, result.contentJson.contains("Planning"))
        } finally {
            handle.close()
        }
    }
}
