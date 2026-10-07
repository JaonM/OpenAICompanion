package com.openai.companion.android

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A cancelled caller never consumes a subsequent permission request's result. */
class AndroidCalendarPermission(private val activity: ComponentActivity) {
    private val gate = Mutex()
    private var pending: CompletableDeferred<Boolean>? = null
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val reply = pending
        pending = null
        reply?.complete(grants.values.all { it })
    }
    suspend fun isForeground(): Boolean = withContext(Dispatchers.Main) {
        activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }
    suspend fun request(): Boolean = requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR))
    suspend fun requestWrite(): Boolean = requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))

    private suspend fun requestPermissions(required: Array<String>): Boolean = gate.withLock { withContext(Dispatchers.Main) {
        if (!isForeground()) return@withContext false
        fun granted() = required.all { activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (granted()) return@withContext true
        // Coalesce an existing system prompt, then request any additional permission separately.
        pending?.let { it.await() }
        if (granted()) return@withContext true
        val reply = CompletableDeferred<Boolean>()
        pending = reply
        try { launcher.launch(required) }
        catch (error: Exception) { pending = null; reply.completeExceptionally(error) }
        reply.await()
        granted()
    } }
}
