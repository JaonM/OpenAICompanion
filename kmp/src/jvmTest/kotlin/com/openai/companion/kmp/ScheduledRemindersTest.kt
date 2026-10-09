package com.openai.companion.kmp

import kotlin.test.*
import kotlinx.serialization.json.*

class ScheduledRemindersTest {
    @Test fun taskWireFormatIncludesDefaultsRequiredByRustForBothToggleStates() {
        val task = ProactiveTask("once", "Reminder", "Open app", "", localMinute = 0,
            weekdayMask = 0, leadMinutes = 0, deadlineLeadMinutes = 0, oneShotAt = 7200)
        for (enabled in listOf(true, false)) {
            val json = Json.parseToJsonElement(task.copy(enabled = enabled).toWireJson()).jsonObject
            assertEquals(enabled, json.getValue("enabled").jsonPrimitive.boolean)
            assertEquals(JsonArray(emptyList()), json.getValue("allowed_tools"))
            assertEquals(JsonArray(emptyList()), json.getValue("required_tools"))
            assertEquals(0, json.getValue("timezone_offset_minutes").jsonPrimitive.int)
            assertEquals(JsonNull, json.getValue("next_run_at"))
            assertEquals(JsonNull, json.getValue("next_event_at"))
            assertTrue(json.keys.containsAll(setOf("scenario", "title", "instruction", "memory_query",
                "local_minute", "weekday_mask", "lead_minutes", "deadline_lead_minutes", "one_shot_at")))
        }
    }
    private val task = ProactiveTask("review", "Review", "Review", "", localMinute = 30,
        weekdayMask = 1, deadlineLeadMinutes = 60)

    @Test fun leadTimeRollsBackAcrossWeekBoundary() {
        val reminder = scheduledReminders(listOf(task), true, 0).single()
        assertEquals(6, reminder.weekday)
        assertEquals(1410, reminder.localMinute)
    }

    @Test fun disabledAndExpiredTasksNeverScheduleAndCapacityIsExplicit() {
        assertTrue(scheduledReminders(listOf(task), false, 0).isEmpty())
        assertTrue(scheduledReminders(listOf(task.copy(enabled = false)), true, 0).isEmpty())
        assertTrue(scheduledReminders(listOf(task.copy(oneShotAt = 3600)), true, 3600).isEmpty())
        val once = scheduledReminders(listOf(task.copy(oneShotAt = 7200)), true, 0).single()
        assertEquals(3600, once.atEpochSeconds)
        assertNull(once.weekday)
        assertFailsWith<IllegalArgumentException> {
            scheduledReminders((0..9).map { task.copy(scenario = "$it", weekdayMask = 127) }, true, 0)
        }
    }
}
