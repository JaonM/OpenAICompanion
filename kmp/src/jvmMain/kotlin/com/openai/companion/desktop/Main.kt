package com.openai.companion.desktop

import androidx.compose.material3.MaterialTheme
import com.openai.companion.kmp.companionLightColors
import com.openai.companion.kmp.companionDarkColors
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    DesktopProcessLease.acquire().use {
        if (args.contentEquals(arrayOf("--worker"))) {
            runBlocking {
                DesktopBackend(backgroundWorker = true).initialize()
                awaitCancellation()
            }
        } else {
            require(args.isEmpty()) { "仅支持 --worker 参数" }
            runDesktopApp()
        }
    }
}

private fun runDesktopApp() = application {
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
