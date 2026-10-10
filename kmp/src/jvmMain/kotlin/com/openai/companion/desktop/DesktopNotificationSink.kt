package com.openai.companion.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import java.io.File

/** Only a native system-delivery record can transition a reminder to delivered. */
internal class DesktopNotificationSink(private val send: ((String, String, String) -> Int)? = null) {
    private interface Bridge : Library {
        fun companion_notification_deliver(identifier: String, title: String, body: String): Int
    }
    private val bridge by lazy {
        val path = System.getenv("COMPANION_NOTIFICATION_LIBRARY_PATH")
            ?: System.getProperty("compose.application.resources.dir")?.let { File(it, "libcompanion_notifications.dylib").absolutePath }
            ?: File("appResources/macos-arm64/libcompanion_notifications.dylib").absolutePath
        try { Native.load(path, Bridge::class.java) }
        catch (error: LinkageError) { throw IllegalStateException("通知组件不可用，请使用 scripts/macos-app.sh 构建的 App", error) }
    }
    fun deliver(identifier: String, title: String, body: String): Boolean = when (
        send?.invoke(identifier, title, body) ?: bridge.companion_notification_deliver(identifier, title, body)) {
        1 -> false
        2 -> true
        -4 -> error("系统通知权限未获允许，请在系统设置 → 通知中允许 OpenAICompanion")
        -2 -> error("通知无法识别 App 身份，请运行正确打包的 OpenAICompanion.app")
        -3 -> error("系统通知接口超时，请稍后重试")
        else -> error("系统通知接口不可用，请运行打包 App 并检查系统通知设置")
    }
}
