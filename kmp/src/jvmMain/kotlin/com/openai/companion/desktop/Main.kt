package com.openai.companion.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application

fun main() = application {
    val backend = remember { DesktopBackend() }
    Window(
        onCloseRequest = ::exitApplication,
        title = "OpenAICompanion",
        state = WindowState(width = 1120.dp, height = 760.dp),
    ) {
        val dark = isSystemInDarkTheme()
        MaterialTheme(colorScheme = if (dark) companionDarkColors else companionLightColors) {
            CodexScreen(backend)
        }
    }
}

private val companionLightColors = lightColorScheme(
    primary = Color(0xFF225FE8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8F0FF),
    onPrimaryContainer = Color(0xFF173B84),
    surface = Color(0xFFFCFDFE),
    surfaceVariant = Color(0xFFF3F6FA),
    onSurface = Color(0xFF172235),
    onSurfaceVariant = Color(0xFF68788D),
    outlineVariant = Color(0xFFE2E8F0),
)

private val companionDarkColors = darkColorScheme(
    primary = Color(0xFF83A9FF),
    onPrimary = Color(0xFF102D65),
    primaryContainer = Color(0xFF203B6F),
    onPrimaryContainer = Color(0xFFD8E5FF),
    surface = Color(0xFF151A23),
    surfaceVariant = Color(0xFF202734),
    onSurface = Color(0xFFE5EAF3),
    onSurfaceVariant = Color(0xFFA9B6C8),
    outlineVariant = Color(0xFF354052),
)
