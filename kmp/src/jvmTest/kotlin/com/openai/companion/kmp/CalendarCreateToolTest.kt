package com.openai.companion.kmp

import com.openai.companion.kmp.device.*
import com.openai.companion.kmp.device.calendar.CalendarCreateTool
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CalendarCreateToolTest {
    private val args = """{"request_id":"create-001","calendar_id":"work","calendar_title":"Work","title":"Review","start":"2026-10-07T09:00:00+08:00","end":"2026-10-07T10:00:00+08:00","time_zone":"Asia/Shanghai"}"""
    private class Source : CalendarWriteDataSource {
        var calls = 0
        var permission = true
        var writable = true
        var failure = false
        var draft: CalendarEventDraft? = null
        var beforeSave: suspend () -> Unit = {}
        override suspend fun getEvents(query: CalendarQuery) = emptyList<CalendarEvent>()
        override suspend fun listCalendars() = listOf(DeviceCalendar("work", "Work", writable))
        override suspend fun ensureWritePermission() {
            if (!permission) throw ToolExecutionException(ToolExecutionErrorCode.PERMISSION_DENIED, "Grant write access")
        }
        override suspend fun createEvent(draft: CalendarEventDraft): String {
            calls++; this.draft = draft; beforeSave()
            if (failure) error("OS save failed")
            return "event-001"
        }
    }
    private fun json(value: String = args) = Json.parseToJsonElement(value).jsonObject
    private fun McpCallResult.body() = Json.parseToJsonElement(contentJson).jsonObject

    @Test fun threeHostsAdvertiseSameReadAndWriteContracts() = runBlocking {
        val tools = listOf("ios", "macos", "android").map { createDeviceTools(it, { "en" }, Source(), { true }, MemoryDeviceOperationJournal()) }
        assertEquals(tools[0].descriptors(), tools[1].descriptors())
        assertEquals(tools[1].descriptors(), tools[2].descriptors())
        assertEquals(setOf("device_get_context", "device_calendar_list_events", "device_calendar_list_calendars", "device_calendar_create_event"), tools[0].descriptors().map { it.name }.toSet())
        val catalog = tools[0].call("device_calendar_list_calendars", "{}").body()
        assertEquals("work", catalog.getValue("data").jsonObject.getValue("calendars").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
    }

    @Test fun retryReturnsOriginalResultAndChangedArgumentsCannotReuseKey() = runBlocking {
        val source = Source()
        val tool = CalendarCreateTool(source, MemoryDeviceOperationJournal()) { true }
        val first = tool.execute(json())
        assertEquals("ok", first.status)
        assertEquals(first, tool.execute(json()))
        assertEquals(1, source.calls)
        assertFailsWith<IllegalArgumentException> { tool.execute(json(args.replace("Review", "Changed"))) }
        assertEquals(1, source.calls)
    }

    @Test fun concurrentRetriesSaveOnceAndCancellationKeepsOutcome() = runBlocking {
        val source = Source()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        source.beforeSave = { entered.complete(Unit); release.await() }
        val tool = CalendarCreateTool(source, MemoryDeviceOperationJournal()) { true }
        val first = async { tool.execute(json()) }
        entered.await()
        val second = async { tool.execute(json()) }
        first.cancel()
        release.complete(Unit)
        first.join()
        assertEquals("ok", second.await().status)
        assertEquals(1, source.calls)
        assertEquals("ok", tool.execute(json()).status)
    }

    @Test fun uncertainNativeOutcomeIsNeverRetried() = runBlocking {
        val source = Source().apply { failure = true }
        val tool = CalendarCreateTool(source, MemoryDeviceOperationJournal()) { true }
        assertEquals("CREATE_OUTCOME_UNKNOWN", tool.execute(json()).code)
        assertEquals("CREATE_OUTCOME_UNKNOWN", tool.execute(json()).code)
        assertEquals(1, source.calls)
    }

    @Test fun permissionsReadonlyTargetsAndApprovalPreventWrites() = runBlocking {
        val source = Source().apply { permission = false }
        val registry = createDeviceTools("android", { "en" }, source, { true }, MemoryDeviceOperationJournal())
        assertEquals("requires_user_action", registry.call("device_calendar_create_event", args).body().getValue("status").jsonPrimitive.content)
        source.permission = true; source.writable = false
        assertTrue(registry.call("device_calendar_create_event", args).isError)
        source.writable = true
        val manager = McpServerManager()
        manager.attach("device", DeviceToolConnection(registry))
        val provider = McpToolProvider(manager) { _, _ -> false }
        assertFailsWith<ToolExecutionException> { provider.callTool("device_calendar_create_event", args) }
        assertEquals(0, source.calls)
        assertEquals("FOREGROUND_REQUIRED", CalendarCreateTool(source, MemoryDeviceOperationJournal()) { false }.execute(json()).code)
        assertEquals(0, source.calls)
    }

    @Test fun badDatesTimezonesAndUnsupportedFieldsNeverSave() = runBlocking {
        val source = Source()
        val registry = createDeviceTools("ios", { "en" }, source, { true }, MemoryDeviceOperationJournal())
        listOf(args.replace("Asia/Shanghai", "Invalid/Zone"), args.replace("Asia/Shanghai", "+08:00"), args.replace("10:00:00", "08:00:00"),
            args.replace("create-001", "x"), args.dropLast(1) + ",\"attendees\":[\"a@b.com\"]}",
            args.replace("Review", " "), args.replace("Work", "Other calendar"), args.dropLast(1) + ",\"all_day\":\"true\"}").forEach {
            assertTrue(registry.call("device_calendar_create_event", it).isError)
        }
        assertEquals(0, source.calls)
    }

    @Test fun allDayUsesLocalDateBoundariesAcrossDst() = runBlocking {
        val source = Source()
        val tool = CalendarCreateTool(source, MemoryDeviceOperationJournal()) { true }
        val input = args.replace("2026-10-07T09:00:00+08:00", "2026-03-08")
            .replace("2026-10-07T10:00:00+08:00", "2026-03-09")
            .replace("Asia/Shanghai", "America/New_York").dropLast(1) + ",\"all_day\":true}"
        assertEquals("ok", tool.execute(json(input)).status)
        assertTrue(source.draft!!.allDay)
        assertEquals(23L * 3_600_000, source.draft!!.endTimeMs - source.draft!!.startTimeMs)
    }

    @Test fun leavingForegroundDuringCommittedSaveDoesNotTurnSuccessIntoFailure() = runBlocking {
        var foreground = true
        val source = Source().apply { beforeSave = { foreground = false } }
        val registry = createDeviceTools("macos", { "en" }, source, { foreground }, MemoryDeviceOperationJournal())
        assertFalse(registry.call("device_calendar_create_event", args).isError)
        assertEquals(1, source.calls)
    }
}
