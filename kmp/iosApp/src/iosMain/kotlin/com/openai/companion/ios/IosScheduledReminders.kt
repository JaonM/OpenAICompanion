@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.openai.companion.ios

import com.openai.companion.kmp.ScheduledReminder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Foundation.NSDateComponents
import kotlinx.datetime.Clock
import platform.UserNotifications.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal class IosScheduledReminders {
    private val gate = Mutex()
    suspend fun permitted(): Boolean = suspendCoroutine { continuation ->
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            continuation.resume(settings?.authorizationStatus == UNAuthorizationStatusAuthorized ||
                settings?.authorizationStatus == UNAuthorizationStatusProvisional)
        }
    }
    suspend fun replace(reminders: List<ScheduledReminder>) = gate.withLock {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        val old: List<UNNotificationRequest> = suspendCoroutine { continuation ->
            center.getPendingNotificationRequestsWithCompletionHandler { requests ->
                continuation.resume(requests.orEmpty().filterIsInstance<UNNotificationRequest>())
            }
        }
        for (reminder in reminders) {
            val content = UNMutableNotificationContent().apply {
                setTitle(reminder.title)
                setBody("计划提醒：请打开 App 查看并处理；尚未检查实时条件。")
            }
            val trigger: UNNotificationTrigger = if (reminder.atEpochSeconds != null) {
                val delay = reminder.atEpochSeconds!! - Clock.System.now().epochSeconds.toDouble()
                UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(delay.coerceAtLeast(1.0), false)
            } else {
                val date = NSDateComponents().apply {
                    weekday = ((reminder.weekday!! + 1) % 7 + 1).toLong()
                    hour = (reminder.localMinute!! / 60).toLong()
                    minute = (reminder.localMinute!! % 60).toLong()
                }
                UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(date, true)
            }
            val error: String? = suspendCoroutine { continuation ->
                center.addNotificationRequest(UNNotificationRequest.requestWithIdentifier(reminder.id, content, trigger)) {
                    continuation.resume(it?.localizedDescription)
                }
            }
            check(error == null) { "系统未能安排后台提醒：$error" }
        }
        val current = reminders.map { it.id }.toSet()
        center.removePendingNotificationRequestsWithIdentifiers(old.map { it.identifier }
            .filter { it.startsWith("scheduled:") && it !in current })
        val installed: Set<String> = suspendCoroutine { continuation ->
            center.getPendingNotificationRequestsWithCompletionHandler { requests ->
                continuation.resume(requests.orEmpty().filterIsInstance<UNNotificationRequest>()
                    .map { it.identifier }.filter { it.startsWith("scheduled:") }.toSet())
            }
        }
        check(installed == current) { "系统提醒排程未同步，请重试" }
        installed.size
    }
}
