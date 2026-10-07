package com.openai.companion.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.openai.companion.kmp.CompanionMobileScreen
import com.openai.companion.kmp.MobileController
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/** Android shell; the screen and its conversation/approval controller come from commonMain. */
class MainActivity : ComponentActivity() {
    private val scope = MainScope()
    private lateinit var oauthBrowser: AndroidMcpOAuthBrowser
    private lateinit var backend: AndroidMobileBackend

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        oauthBrowser = AndroidMcpOAuthBrowser(this)
        val importer = AndroidGgufImporter(this)
        val model = AndroidLocalLlamaModel(applicationContext, importer)
        val notifications = AndroidNotificationSink(this)
        val calendarPermission = AndroidCalendarPermission(this)
        val controller = MobileController(scope) { approve, requestInput, approveA2a ->
            AndroidMobileBackend(applicationContext, model, notifications, oauthBrowser,
                approve, approveA2a, requestInput, calendarPermission).also { backend = it }
        }
        setContent {
            val state by controller.state.collectAsState()
            CompanionMobileScreen(state, controller)
        }
        controller.start()
        intent?.data?.let(oauthBrowser::onRedirect)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let(oauthBrowser::onRedirect)
    }

    override fun onResume() {
        super.onResume()
        if (::backend.isInitialized) backend.onActivityResumed()
    }

    override fun onDestroy() {
        if (::backend.isInitialized) backend.shutdown()
        scope.cancel()
        super.onDestroy()
    }
}
