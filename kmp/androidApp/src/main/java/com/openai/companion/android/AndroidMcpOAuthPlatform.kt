package com.openai.companion.android

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import com.openai.companion.kmp.McpOAuthBrowser
import com.openai.companion.kmp.McpOAuthCrypto
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AndroidMcpOAuthCrypto : McpOAuthCrypto {
    private val random = SecureRandom()
    override fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)
    override fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

/** Browser redirect is delivered to MainActivity through its custom-scheme intent filter. */
class AndroidMcpOAuthBrowser(private val activity: ComponentActivity) : McpOAuthBrowser {
    private val gate = Mutex()
    private var pending: CompletableDeferred<String>? = null

    override suspend fun authorize(url: String, redirectUri: String): String = gate.withLock {
        check(redirectUri == REDIRECT_URI)
        val reply = CompletableDeferred<String>()
        pending = reply
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
            reply.await()
        } finally {
            pending = null
        }
    }

    fun onRedirect(uri: Uri): Boolean {
        if (uri.scheme != "openai-companion" || uri.host != "oauth" || uri.path != "/callback") return false
        pending?.complete(uri.toString())
        return true
    }

    companion object { const val REDIRECT_URI = "openai-companion://oauth/callback" }
}
