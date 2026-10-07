package com.openai.companion.kmp

import com.openai.companion.kmp.device.*
import com.openai.companion.kmp.device.calendar.CalendarCreateTool
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*
import uniffi.harness.appOpenStore

class DeviceOperationPersistenceTest {
    private val args = Json.parseToJsonElement("""{"request_id":"durable-001","calendar_id":"work","calendar_title":"Work","title":"Review","start":"2026-10-07T09:00:00+08:00","end":"2026-10-07T10:00:00+08:00","time_zone":"Asia/Shanghai"}""").jsonObject
    private class Calendar : CalendarWriteDataSource {
        var saves = 0
        var failBeforeSave = false
        var denyRecovery = false
        val events = mutableMapOf<String, String>()
        override suspend fun checkPermission() = Unit
        override suspend fun getEvents(query: CalendarQuery) = emptyList<CalendarEvent>()
        override suspend fun ensureWritePermission() = Unit
        override suspend fun listCalendars() = listOf(DeviceCalendar("work", "Work", true))
        override suspend fun createEvent(draft: CalendarEventDraft): String {
            saves++
            if (failBeforeSave) error("Interrupted before system save")
            return "event-$saves".also { events[draft.operationUrl()] = it }
        }
        override suspend fun findCreatedEvent(draft: CalendarEventDraft): String? {
            if (denyRecovery) throw ToolExecutionException(ToolExecutionErrorCode.PERMISSION_DENIED, "Calendar permission revoked")
            return events[draft.operationUrl()]
        }
    }
    private fun setupLibrary() {
        System.setProperty("uniffi.component.harness.libraryOverride", System.getenv("HARNESS_LIBRARY_PATH"))
    }

    @Test fun successReplaysAfterReopeningDatabaseAndRecreatingTools() = runBlocking<Unit> {
        setupLibrary()
        val path = Files.createTempFile("device-journal-success-", ".sqlite")
        try {
            assertTrue(appOpenStore(path.toString()).ok)
            val source = Calendar()
            val journal = BindingsDeviceOperationJournal(GeneratedHarnessBindingsAdapter())
            val first = CalendarCreateTool(source, journal) { true }.execute(args)
            assertEquals("ok", first.status)
            assertTrue(appOpenStore(path.toString()).ok)
            val reopened = CalendarCreateTool(source, BindingsDeviceOperationJournal(GeneratedHarnessBindingsAdapter())) { true }
            assertEquals(first, reopened.execute(args))
            assertEquals(1, source.saves)
            assertFailsWith<IllegalArgumentException> { reopened.execute(JsonObject(args + ("title" to JsonPrimitive("Different")))) }
        } finally { Files.deleteIfExists(path) }
    }

    @Test fun crashAfterNativeSaveIsRecoveredByMarkerWithoutASecondWrite() = runBlocking<Unit> {
        setupLibrary()
        val path = Files.createTempFile("device-journal-recovery-", ".sqlite")
        try {
            assertTrue(appOpenStore(path.toString()).ok)
            val source = Calendar()
            val durable = BindingsDeviceOperationJournal(GeneratedHarnessBindingsAdapter())
            val interrupted = object : DeviceOperationJournal by durable {
                override suspend fun finish(operationId: String, result: DeviceToolResult): DeviceToolResult = error("Process lost before acknowledgement")
            }
            assertEquals("CREATE_OUTCOME_UNKNOWN", CalendarCreateTool(source, interrupted) { true }.execute(args).code)
            assertEquals(1, source.saves)
            assertTrue(appOpenStore(path.toString()).ok)
            source.denyRecovery = true
            val denied = assertFailsWith<ToolExecutionException> {
                CalendarCreateTool(source, durable) { true }.execute(args)
            }
            assertEquals(ToolExecutionErrorCode.PERMISSION_DENIED, denied.code)
            assertEquals(1, source.saves)
            source.denyRecovery = false
            val recovered = CalendarCreateTool(source, BindingsDeviceOperationJournal(GeneratedHarnessBindingsAdapter())) { true }.execute(args)
            assertEquals("ok", recovered.status)
            assertEquals("event-1", recovered.data.jsonObject.getValue("event_id").jsonPrimitive.content)
            assertEquals(1, source.saves)
        } finally { Files.deleteIfExists(path) }
    }

    @Test fun unresolvedWriteStaysBlockedAcrossRestart() = runBlocking<Unit> {
        setupLibrary()
        val path = Files.createTempFile("device-journal-unknown-", ".sqlite")
        try {
            assertTrue(appOpenStore(path.toString()).ok)
            val source = Calendar().apply { failBeforeSave = true }
            val journal = BindingsDeviceOperationJournal(GeneratedHarnessBindingsAdapter())
            assertEquals("CREATE_OUTCOME_UNKNOWN", CalendarCreateTool(source, journal) { true }.execute(args).code)
            source.failBeforeSave = false
            assertTrue(appOpenStore(path.toString()).ok)
            assertEquals("CREATE_OUTCOME_UNKNOWN", CalendarCreateTool(source, journal) { true }.execute(args).code)
            assertEquals(1, source.saves)
        } finally { Files.deleteIfExists(path) }
    }

    @Test fun unavailableJournalPreventsNativeWrite() = runBlocking<Unit> {
        val source = Calendar()
        val unavailable = object : DeviceOperationJournal {
            override suspend fun lookup(key: DeviceOperationKey): DeviceOperationRecord =
                error("Database unavailable")
            override suspend fun claim(key: DeviceOperationKey): DeviceOperationClaim = error("Database unavailable")
            override suspend fun finish(operationId: String, result: DeviceToolResult): DeviceToolResult =
                error("Database unavailable")
        }
        assertFailsWith<IllegalStateException> { CalendarCreateTool(source, unavailable) { true }.execute(args) }
        assertEquals(0, source.saves)
    }
}
