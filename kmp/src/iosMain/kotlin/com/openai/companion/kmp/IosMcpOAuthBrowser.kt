package com.openai.companion.kmp

import io.ktor.http.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.AuthenticationServices.ASPresentationAnchor
import platform.Foundation.NSURL
import platform.darwin.NSObject
import platform.UIKit.UIApplication
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** System browser-based OAuth on iOS; no Swift view or callback handler is involved. */
class IosMcpOAuthBrowser : McpOAuthBrowser {
    private val scope = MainScope()
    private var activeSession: ASWebAuthenticationSession? = null
    // ASWebAuthenticationSession holds this provider weakly, so retain it until completion.
    private var activePresenter: NSObject? = null

    override suspend fun authorize(url: String, redirectUri: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            check(activeSession == null) { "An OAuth browser session is already active" }
            val authorizationUrl = NSURL.URLWithString(url)
                ?: throw IllegalArgumentException("Invalid OAuth authorization URL")
            val scheme = Url(redirectUri).protocol.name
            val presenter = object : NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {
                override fun presentationAnchorForWebAuthenticationSession(
                    session: ASWebAuthenticationSession,
                ): ASPresentationAnchor = UIApplication.sharedApplication.keyWindow
                    ?: throw IllegalStateException("No iOS window is available for OAuth")
            }
            lateinit var session: ASWebAuthenticationSession
            session = ASWebAuthenticationSession(
                uRL = authorizationUrl,
                callbackURLScheme = scheme,
                completionHandler = { callback, error ->
                    scope.launch {
                        clearSession(session)
                        if (continuation.isActive) {
                            when {
                                error != null -> continuation.resumeWithException(
                                    McpProtocolException("OAuth browser authorization was cancelled or failed"),
                                )
                                callback == null -> continuation.resumeWithException(
                                    McpProtocolException("OAuth browser returned no callback"),
                                )
                                else -> continuation.resume(callback.absoluteString.orEmpty())
                            }
                        }
                    }
                },
            )
            session.presentationContextProvider = presenter
            activeSession = session
            activePresenter = presenter
            continuation.invokeOnCancellation {
                scope.launch {
                    session.cancel()
                    clearSession(session)
                }
            }
            if (!session.start()) {
                clearSession(session)
                continuation.resumeWithException(McpProtocolException("OAuth browser could not start"))
            }
        }
    }

    private fun clearSession(session: ASWebAuthenticationSession) {
        if (activeSession === session) {
            activeSession = null
            activePresenter = null
        }
    }
}
