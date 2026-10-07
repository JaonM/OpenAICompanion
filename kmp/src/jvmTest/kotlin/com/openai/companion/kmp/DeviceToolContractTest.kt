package com.openai.companion.kmp

import com.openai.companion.kmp.device.*
import com.openai.companion.kmp.device.calendar.CalendarListTool
import kotlinx.coroutines.*
import kotlinx.datetime.Instant
import kotlinx.serialization.json.*
import kotlin.test.*

class DeviceToolContractTest {
    private val start = Instant.parse("2026-10-07T00:00:00Z").toEpochMilliseconds()
    private val args = """{"start":"2026-10-07T00:00:00Z","end":"2026-10-08T00:00:00Z","limit":1}"""
    private class Source(var events: List<CalendarEvent>) : CalendarEventDataSource {
        var permitted = true
        var calls = 0
        var lastQuery: CalendarQuery? = null
        override suspend fun checkPermission() {
            if (!permitted) throw ToolExecutionException(ToolExecutionErrorCode.PERMISSION_DENIED, "Grant access")
        }
        override suspend fun getEvents(query: CalendarQuery): List<CalendarEvent> {
            checkPermission(); calls++; lastQuery = query
            return events
        }
    }
    private fun source() = Source(listOf(
        CalendarEvent("recurring", "Later", start + 5000, start + 6000, "Work", "Private place", "Private notes"),
        CalendarEvent("recurring", "Earlier", start + 1000, start + 2000, "Work", "Private place", "Private notes"),
    ))
    private fun McpCallResult.body() = Json.parseToJsonElement(contentJson).jsonObject
    private fun JsonObject.events() = getValue("data").jsonObject.getValue("events").jsonArray
    private fun next(body: JsonObject) = buildJsonObject { put("cursor", body.getValue("next_cursor")) }.toString()

    @Test fun allHostsExposeIdenticalContractsAndVersionedContext() = runBlocking {
        val registries = listOf("ios", "macos", "android").map { createDeviceTools(it, { "zh-CN" }, source(), { true }) }
        assertEquals(registries[0].descriptors(), registries[1].descriptors())
        assertEquals(registries[1].descriptors(), registries[2].descriptors())
        registries.forEach {
            val body = it.call("device_get_context", "{}").body()
            assertEquals(1, body.getValue("version").jsonPrimitive.int)
            assertEquals(it.platform, body.getValue("source").jsonObject.getValue("platform").jsonPrimitive.content)
            assertEquals("zh-CN", body.getValue("data").jsonObject.getValue("language").jsonPrimitive.content)
            assertTrue("timezone" in body.getValue("data").jsonObject)
        }
    }

    @Test fun paginationUsesStableSnapshotAndMinimalFields() = runBlocking {
        val source = source()
        val registry = createDeviceTools("ios", { "en" }, source, { true })
        val first = registry.call("device_calendar_list_events", args).body()
        val firstEvent = first.events().single().jsonObject
        assertEquals("Earlier", firstEvent.getValue("title").jsonPrimitive.content)
        assertFalse("notes" in firstEvent); assertFalse("location" in firstEvent); assertFalse("calendar" in firstEvent)
        assertFalse(source.lastQuery!!.includeLocation); assertFalse(source.lastQuery!!.includeNotes)
        source.events = emptyList()
        val second = registry.call("device_calendar_list_events", next(first)).body()
        assertEquals("Later", second.events().single().jsonObject.getValue("title").jsonPrimitive.content)
        assertNotEquals(firstEvent.getValue("event_ref"), second.events().single().jsonObject.getValue("event_ref"))
        assertEquals(JsonNull, second.getValue("next_cursor")); assertEquals(1, source.calls)
        source.permitted = false
        val denied = registry.call("device_calendar_list_events", next(first))
        assertTrue(denied.isError)
        assertEquals("requires_user_action", denied.body().getValue("status").jsonPrimitive.content)
    }

    @Test fun cursorExpiresAndCannotChangeProjection() = runBlocking {
        var now = 0L
        val tool = CalendarListTool(source()) { now }
        val first = tool.execute(Json.parseToJsonElement(args).jsonObject)
        assertFailsWith<IllegalArgumentException> {
            tool.execute(buildJsonObject { put("cursor", first.nextCursor); put("fields", JsonArray(listOf(JsonPrimitive("notes")))) })
        }
        now = 120_000
        assertEquals("CURSOR_EXPIRED", tool.execute(buildJsonObject { put("cursor", first.nextCursor) }).code)
    }

    @Test fun invalidArgumentsNeverReachCalendarAndClippingIsExplicit() = runBlocking {
        val source = source()
        val registry = createDeviceTools("android", { "en" }, source, { true })
        val invalid = listOf("[]", "not json", "{}", args.dropLast(1) + ",\"cursor\":null}", args.replace("\"limit\":1", "\"limit\":101"),
            args.replace("2026-10-08", "2026-12-08"), args.dropLast(1) + ",\"unexpected\":true}",
            args.dropLast(1) + ",\"fields\":[\"notes\",\"notes\"]}")
        invalid.forEach { assertTrue(registry.call("device_calendar_list_events", it).isError, it) }
        assertEquals(0, source.calls)
        source.events = listOf(source.events.first().copy(notes = "x".repeat(2100)))
        val result = registry.call("device_calendar_list_events", args.dropLast(1) + ",\"fields\":[\"notes\"]}").body()
        val event = result.events().single().jsonObject
        assertEquals(2000, event.getValue("notes").jsonPrimitive.content.length)
        assertEquals(JsonArray(listOf(JsonPrimitive("notes"))), event.getValue("truncated_fields"))
        assertTrue(source.lastQuery!!.includeNotes)
    }

    @Test fun outputBudgetPaginatesLongNotesWithoutDroppingEvents() = runBlocking {
        val source = source()
        source.events = (0 until 30).map { source.events.first().copy(id = "event-$it", notes = "x".repeat(2000)) }
        val tool = CalendarListTool(source)
        var page = tool.execute(Json.parseToJsonElement(args.replace("\"limit\":1", "\"limit\":100").dropLast(1) + ",\"fields\":[\"notes\"]}").jsonObject)
        val references = mutableSetOf<String>()
        var pages = 0
        while (true) {
            assertTrue(page.data.toString().length < 16_500)
            page.data.jsonObject.getValue("events").jsonArray.forEach {
                assertTrue(references.add(it.jsonObject.getValue("event_ref").jsonPrimitive.content))
            }
            pages++
            val cursor = page.nextCursor ?: break
            page = tool.execute(buildJsonObject { put("cursor", cursor) })
        }
        assertTrue(pages > 1); assertEquals(30, references.size); assertEquals(1, source.calls)
    }

    @Test fun foregroundApprovalAndRemovalProtectExecution() = runBlocking {
        val source = source()
        var foreground = false
        val registry = createDeviceTools("android", { "en" }, source, { foreground })
        val manager = McpServerManager()
        manager.attach("device", DeviceToolConnection(registry))
        var approvalName = ""
        val provider = McpToolProvider(manager, { name, _ -> approvalName = name; true })
        assertTrue(provider.callTool("device_calendar_list_events", args).isError)
        assertTrue(approvalName.startsWith("本机 · ")); assertEquals(0, source.calls)
        foreground = true
        assertFalse(provider.callTool("device_calendar_list_events", args).isError)
        val rejecting = McpToolProvider(manager, { _, _ -> false })
        assertFailsWith<ToolExecutionException> { rejecting.callTool("device_calendar_list_events", args) }
        assertEquals(1, source.calls)
        val removing = McpToolProvider(manager, { _, _ -> registry.remove("device_calendar_list_events"); true })
        assertFailsWith<ToolExecutionException> { removing.callTool("device_calendar_list_events", args) }
        assertFailsWith<IllegalStateException> { registry.register(CalendarListTool(source)) }
        manager.detach("device")
        assertTrue(manager.tools().isEmpty())
        assertTrue(registry.call("device_get_context", "{}").isError)
    }

    @Test fun dynamicRegistrationAndRemovalUpdateHarnessSnapshot() = runBlocking {
        val registry = DeviceToolRegistry("macos") { true }
        val manager = McpServerManager()
        manager.attach("device", DeviceToolConnection(registry))
        val provider = McpToolProvider(manager) { _, _ -> true }
        assertTrue(provider.getTools().isEmpty())
        registry.register(DeviceContextTool("macos") { "en" })
        assertEquals(listOf("device_get_context"), provider.getTools().map { it.name })
        assertFalse(provider.callTool("device_get_context", "{}").isError)
        registry.remove("device_get_context")
        assertTrue(provider.getTools().isEmpty())
        manager.detach("device")
    }

    @Test fun titleFilteringAndOrderingAreAppliedBeforePagination() = runBlocking {
        val source = Source(listOf(
            CalendarEvent("1", "Project planning", start + 300, start + 400),
            CalendarEvent("2", "Lunch", start + 100, start + 200),
            CalendarEvent("3", "Planning review", start + 200, start + 250),
        ))
        val registry = createDeviceTools("ios", { "en" }, source, { true })
        val first = registry.call("device_calendar_list_events", args.dropLast(1) + ",\"query\":\"planning\"}").body()
        assertEquals("Planning review", first.events().single().jsonObject.getValue("title").jsonPrimitive.content)
        val second = registry.call("device_calendar_list_events", next(first)).body()
        assertEquals("Project planning", second.events().single().jsonObject.getValue("title").jsonPrimitive.content)
        assertEquals(JsonNull, second.getValue("next_cursor"))
    }

    @Test fun cancellationPropagatesWithoutFabricatingToolFailure() = runBlocking {
        val registry = DeviceToolRegistry("ios") { true }
        registry.register(object : DeviceTool {
            override val descriptor = McpToolDescriptor("device_wait", "Test", """{"type":"object"}""")
            override val policy = DeviceToolPolicies.PersonalRead
            override suspend fun execute(arguments: JsonObject): DeviceToolResult { awaitCancellation() }
        })
        assertNull(withTimeoutOrNull(50) { registry.call("device_wait", "{}") })
    }
}
