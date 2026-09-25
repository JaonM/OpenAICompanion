package com.openai.companion.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
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
        MaterialTheme { CodexScreen(backend) }
    }
}
