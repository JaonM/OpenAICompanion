package com.openai.companion.kmp

import kotlinx.serialization.Serializable

/** Fixed schedule fallback, deliberately contains no model prediction or live-condition claim. */
@Serializable
data class ScheduledReminder(
    val id: String,
    val title: String,
    val weekday: Int? = null, // Monday=0, Sunday=6.
    val localMinute: Int? = null,
    val atEpochSeconds: Long? = null,
)

fun scheduledReminders(tasks: List<ProactiveTask>, enabled: Boolean, now: Long): List<ScheduledReminder> {
    if (!enabled) return emptyList()
    val reminders = tasks.filter { it.enabled }.flatMap { task ->
        val lead = task.deadlineLeadMinutes
        require(task.localMinute in 0..1439 && lead in 0..10080 && task.weekdayMask in 0..127)
        val at = task.oneShotAt
        if (at != null) {
            val remindAt = at - lead * 60
            if (remindAt <= now) emptyList() else listOf(ScheduledReminder(
                "scheduled:${task.scenario}:once", task.title.take(200), atEpochSeconds = remindAt))
        } else (0..6).filter { task.weekdayMask and (1L shl it) != 0L }.map { day ->
            val minute = day * 1440L + task.localMinute - lead
            val normalized = ((minute % 10080) + 10080) % 10080
            ScheduledReminder("scheduled:${task.scenario}:$day", task.title.take(200),
                weekday = (normalized / 1440).toInt(), localMinute = (normalized % 1440).toInt())
        }
    }
    require(reminders.size <= 64) { "后台提醒最多支持 64 个排程，请减少启用任务或重复日期" }
    return reminders
}
