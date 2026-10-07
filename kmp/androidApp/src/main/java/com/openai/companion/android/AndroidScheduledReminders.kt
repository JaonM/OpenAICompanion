package com.openai.companion.android

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.openai.companion.kmp.ScheduledReminder
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Alarms carry identifiers only. Private task titles stay in the app's persistent settings. */
class AndroidScheduledReminders(private val context: Context) {
    private val preferences = context.getSharedPreferences("scheduled_reminders", Context.MODE_PRIVATE)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    val exact: Boolean get() = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    fun replace(reminders: List<ScheduledReminder>) = synchronized(lock) {
        val old = load()
        check(preferences.edit().putString("plans", Json.encodeToString(reminders)).commit())
        old.forEach { alarms.cancel(operation(it.id)) }
        reminders.forEach(::schedule)
    }

    fun restore() = synchronized(lock) { load().forEach(::schedule) }

    private fun load(): List<ScheduledReminder> = Json.decodeFromString(preferences.getString("plans", null) ?: "[]")

    private fun operation(id: String, at: Long = 0) = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderReceiver::class.java).setData(Uri.parse("openai-companion://reminder/" + Uri.encode(id)))
            .putExtra("id", id).putExtra("at", at), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun schedule(reminder: ScheduledReminder) {
        val now = Instant.now()
        val at = reminder.atEpochSeconds?.let { Instant.ofEpochSecond(it) } ?: run {
            val local = now.atZone(ZoneId.systemDefault())
            val dayOffset = (reminder.weekday!! - (local.dayOfWeek.value - 1) + 7) % 7
            var next = local.plusDays(dayOffset.toLong()).withHour(reminder.localMinute!! / 60)
                .withMinute(reminder.localMinute!! % 60).withSecond(0).withNano(0)
            if (!next.toInstant().isAfter(now)) next = next.plusWeeks(1)
            next.toInstant()
        }
        if (!at.isAfter(now)) return
        val millis = at.toEpochMilli()
        check(preferences.edit().putLong("trigger:${reminder.id}", millis).commit())
        val intent = operation(reminder.id, millis)
        try {
            if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
        } catch (_: SecurityException) { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent) }
    }

    fun fire(id: String, at: Long) = synchronized(lock) {
        val reminder = load().firstOrNull { it.id == id } ?: return@synchronized
        if (preferences.getLong("trigger:$id", -1) != at || preferences.getLong("delivered:$id", -1) == at) return@synchronized
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("scheduled", "计划提醒", NotificationManager.IMPORTANCE_DEFAULT))
        val permitted = manager.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        if (permitted) {
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify("$id:$at", 0, Notification.Builder(context, "scheduled")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(reminder.title)
                .setContentText("计划提醒：请打开 App 查看并处理；尚未检查实时条件。")
                .setContentIntent(open).setOnlyAlertOnce(true).setAutoCancel(true).build())
        }
        check(preferences.edit().putLong("delivered:$id", at).commit())
        if (reminder.weekday != null) schedule(reminder)
    }

    companion object { private val lock = Any() }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminders = AndroidScheduledReminders(context)
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) {
            reminders.restore()
        } else intent.getStringExtra("id")?.let { reminders.fire(it, intent.getLongExtra("at", -1)) }
    }
}
