package com.openai.companion.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidNotificationSink(private val activity: ComponentActivity) {
    fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= 31) activity.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:${activity.packageName}")))
    }
    private val manager = activity.getSystemService(NotificationManager::class.java)
    val permitted: Boolean get() = manager.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
        activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    private var pending: CompletableDeferred<Boolean>? = null
    private val permission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pending?.complete(granted)
    }

    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "主动建议", NotificationManager.IMPORTANCE_DEFAULT))
    }

    suspend fun requestPermission(): Boolean = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT < 33 || activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            return@withContext manager.areNotificationsEnabled()
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@withContext false
        pending?.let { return@withContext it.await() && manager.areNotificationsEnabled() }
        val reply = CompletableDeferred<Boolean>()
        pending = reply
        try {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            reply.await() && manager.areNotificationsEnabled()
        } finally { pending = null }
    }

    fun deliver(id: Long, title: String, body: String): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            return false
        val openApp = PendingIntent.getActivity(activity, id.toInt(),
            Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return try {
            manager.notify(id.toInt(), Notification.Builder(activity, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build())
            true
        } catch (_: SecurityException) { false }
    }

    private companion object { const val CHANNEL_ID = "proactive" }
}
