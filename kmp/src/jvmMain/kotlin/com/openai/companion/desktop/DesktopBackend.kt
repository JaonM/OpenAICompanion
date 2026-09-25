package com.openai.companion.desktop

import com.openai.companion.kmp.GeneratedHarnessBindingsAdapter
import com.openai.companion.kmp.AppAgentEventSink
import com.openai.companion.kmp.CompanionConversationCodec
import com.openai.companion.kmp.KotlinSdkMcpClient
import com.openai.companion.kmp.McpServerManager
import com.openai.companion.kmp.registerMcpProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.harness.AppResult
import uniffi.harness.appDeleteSession
import uniffi.harness.appListSessions
import uniffi.harness.appLoadSession
import uniffi.harness.appOpenStore
import uniffi.harness.appResumeSession
import uniffi.harness.appSendMessage
import uniffi.harness.appStartSession
import uniffi.harness.cancelAgentLoop

data class DesktopSession(val id: Long, val preview: String)
data class DesktopMessage(val role: String, val content: String)
sealed interface DesktopStreamEvent {
    data class Reasoning(val text: String) : DesktopStreamEvent
    data class Text(val text: String) : DesktopStreamEvent
    data class Completed(val text: String) : DesktopStreamEvent
    data class Error(val json: String) : DesktopStreamEvent
}

class DesktopBackend(val modelServe: DesktopModelServe = DesktopModelServe()) {
    private val codec = CompanionConversationCodec()
    private val bindings = GeneratedHarnessBindingsAdapter()
    private val manager = McpServerManager()
    private var initialized = false
    @Volatile var mcpEndpoint: String = ""
        private set

    suspend fun initialize() {
        if (initialized) return
        withContext(Dispatchers.IO) {
            val library = System.getenv("HARNESS_LIBRARY_PATH")
                ?: System.getProperty("compose.application.resources.dir")?.let {
                    File(it, "libharness.dylib").absolutePath
                }
                ?: File("../harness/target/release/libharness.dylib").absolutePath
            require(File(library).isFile) { "找不到 Rust Harness 动态库：$library" }
            System.setProperty("uniffi.component.harness.libraryOverride", library)
            bindings.registerModelServeCallback(modelServe)
            val support = System.getenv("COMPANION_DATA_DIR")?.takeIf(String::isNotBlank)
                ?.let(::File)
                ?: File(System.getProperty("user.home"),
                    "Library/Application Support/OpenAICompanion")
            require(support.isDirectory || support.mkdirs()) { "无法创建应用数据目录" }
            appOpenStore(File(support, "companion.sqlite").absolutePath).value()
        }
        registerMcpProvider(bindings, manager)
        initialized = true
    }

    suspend fun sessions(): List<DesktopSession> = withContext(Dispatchers.IO) {
        codec.sessions(appListSessions().value()).map { session ->
            DesktopSession(session.id, session.preview)
        }
    }

    suspend fun newSession(): Long = withContext(Dispatchers.IO) {
        codec.sessionId(appStartSession().value())
    }

    suspend fun openSession(id: Long): List<DesktopMessage> = withContext(Dispatchers.IO) {
        appResumeSession(id).value()
        loadSession(id)
    }

    suspend fun loadSession(id: Long): List<DesktopMessage> = withContext(Dispatchers.IO) {
        codec.messages(appLoadSession(id).value()).map { message ->
            DesktopMessage(message.role, message.content)
        }
    }

    suspend fun send(message: String, onEvent: (DesktopStreamEvent) -> Unit = {}): String = withContext(Dispatchers.IO) {
        bindings.registerAgentEventSink(object : AppAgentEventSink {
            override fun onReasoningDelta(text: String) = onEvent(DesktopStreamEvent.Reasoning(text))
            override fun onTextDelta(text: String) = onEvent(DesktopStreamEvent.Text(text))
            override fun onCompleted(finalText: String) = onEvent(DesktopStreamEvent.Completed(finalText))
            override fun onError(errorJson: String) = onEvent(DesktopStreamEvent.Error(errorJson))
        })
        try {
            codec.output(appSendMessage(message).value())
        } catch (error: Exception) {
            if (modelServe.lastError == "生成已取消" ||
                error.message?.contains("cancelled by user", ignoreCase = true) == true) {
                throw IllegalStateException("生成已取消", error)
            }
            throw IllegalStateException(modelServe.lastError ?: error.message.orEmpty(), error)
        } finally {
            bindings.unregisterAgentEventSink()
        }
    }

    suspend fun deleteSession(id: Long) = withContext(Dispatchers.IO) {
        appDeleteSession(id).value()
    }

    suspend fun connectMcp(endpoint: String) {
        if (endpoint.isBlank()) {
            manager.detach("remote")
            mcpEndpoint = ""
        } else {
            val normalized = endpoint.trim()
            manager.attach("remote", KotlinSdkMcpClient.create(normalized))
            mcpEndpoint = normalized
        }
    }

    fun cancel() {
        cancelAgentLoop()
        modelServe.cancelCurrentRequest()
    }
}

private fun AppResult.value(): String {
    if (!ok) error(error)
    return valueJson
}
