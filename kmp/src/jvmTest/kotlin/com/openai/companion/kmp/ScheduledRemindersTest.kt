package com.openai.companion.kmp

import kotlin.test.*

class ScheduledRemindersTest {
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
