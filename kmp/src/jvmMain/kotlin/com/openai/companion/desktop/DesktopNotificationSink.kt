package com.openai.companion.desktop

import java.awt.Color
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/** macOS/JVM delivery port. The user enables proactive reminders in Settings. */
internal class DesktopNotificationSink {
    private val trayIcon: TrayIcon? by lazy {
        if (!SystemTray.isSupported()) return@lazy null
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color(34, 95, 232)
        graphics.fillOval(1, 1, 14, 14)
        graphics.dispose()
        TrayIcon(image, "OpenAICompanion").also { icon ->
            icon.isImageAutoSize = true
            SystemTray.getSystemTray().add(icon)
        }
    }

    fun deliver(title: String, body: String): Boolean {
        val icon = trayIcon ?: return false
        icon.displayMessage(title, body, TrayIcon.MessageType.INFO)
        return true
    }
}
