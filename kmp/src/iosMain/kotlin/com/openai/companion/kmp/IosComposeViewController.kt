package com.openai.companion.kmp

import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.StateFlow
import platform.UIKit.UIViewController

/** Native iOS host entry point; the screen itself is written in shared Compose code. */
fun createIosComposeViewController(state: StateFlow<MobileUiState>, actions: MobileActions): UIViewController =
    ComposeUIViewController {
        val current by state.collectAsState()
        CompanionMobileScreen(current, actions)
    }
