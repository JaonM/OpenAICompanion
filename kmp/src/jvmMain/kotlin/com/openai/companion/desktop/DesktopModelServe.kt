package com.openai.companion.desktop

import com.openai.companion.kmp.AppModelServe
import com.openai.companion.kmp.ModelStreamCallback
import com.openai.companion.kmp.CompanionModelDefaults
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.io.BufferedReader
import java.io.InputStream
import java.io.Reader
import java.time.Duration
import java.util.prefs.Preferences
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The model adapter stays in the host app; Harness owns the agent loop. */
class DesktopModelServe : AppModelServe {
    private val defaults = CompanionModelDefaults()
    private val preferences = Preferences.userNodeForPackage(DesktopModelServe::class.java)
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()
    private val activeRequest = AtomicReference<CompletableFuture<HttpResponse<InputStream>>?>()
    private val activeStream = AtomicReference<InputStream?>()

    @Volatile var endpoint: String = preferences.get("endpoint", defaults.desktopEndpoint)
    @Volatile var model: String = preferences.get("model", defaults.modelName)
    @Volatile var apiKey: String = ""
    @Volatile var lastError: String? = null
    @Volatile private var cancelled = false

    fun save(endpoint: String, model: String, apiKey: String) {
        this.endpoint = endpoint.trim()
        this.model = model.trim()
        this.apiKey = apiKey.trim()
        preferences.put("endpoint", this.endpoint)
        preferences.put("model", this.model)
    }

    fun cancelCurrentRequest() {
        cancelled = true
        lastError = "生成已取消"
        activeRequest.getAndSet(null)?.cancel(true)
        activeStream.getAndSet(null)?.let { stream -> runCatching { stream.close() } }
    }

    suspend fun localStatus(): String = withContext(Dispatchers.IO) {
        val uri = runCatching { URI.create(endpoint) }.getOrNull() ?: return@withContext "接口地址无效"
        if (uri.host !in setOf("localhost", "127.0.0.1", "::1")) return@withContext "自定义模型服务"
        val base = URI(uri.scheme, null, uri.host, uri.port, "/api/tags", null, null)
        try {
            val request = HttpRequest.newBuilder(base).timeout(Duration.ofSeconds(3)).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return@withContext "本地服务不可用"
            val installed = Json.parseToJsonElement(response.body()).jsonObject["models"]
                ?.jsonArray?.any { item ->
                    val entry = item.jsonObject
                    entry["name"]?.jsonPrimitive?.content == model ||
                        entry["model"]?.jsonPrimitive?.content == model
                } ?: false
            if (installed) "本地模型已就绪" else "模型尚未下载"
        } catch (_: Exception) {
            "本地服务未连接"
        }
    }

    override suspend fun complete(requestJson: String, callback: ModelStreamCallback) {
        lastError = null
        cancelled = false
        try {
            withContext(Dispatchers.IO) {
                defaults.validationError(endpoint, model)?.let(::error)
                val original = Json.parseToJsonElement(requestJson).jsonObject
                val body = buildJsonObject {
                    original.forEach { (key, value) ->
                        if (key != "tools" || value.jsonArray.isNotEmpty()) put(key, value)
                    }
                    put("model", JsonPrimitive(model))
                    put("stream", JsonPrimitive(true))
                }.toString()
                val builder = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofMinutes(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                if (apiKey.isNotEmpty()) builder.header("Authorization", "Bearer $apiKey")
                val pending = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
                activeRequest.set(pending)
                val response = try {
                    if (cancelled) pending.cancel(true)
                    pending.get()
                } finally {
                    activeRequest.compareAndSet(pending, null)
                }
                val stream = response.body()
                activeStream.set(stream)
                try {
                    if (cancelled) error("生成已取消")
                    if (response.statusCode() !in 200..299) {
                        error("模型接口返回 HTTP ${response.statusCode()}: ${stream.bufferedReader().readText().take(300)}")
                    }
                    stream.bufferedReader().use { reader ->
                        if (response.headers().firstValue("Content-Type").orElse("")
                                .contains("text/event-stream", ignoreCase = true)) {
                            consumeServerSentEvents(reader, callback::onChunk)
                        } else {
                            val body = reader.readText()
                            Json.parseToJsonElement(body).jsonObject["choices"]
                                ?: error("模型接口没有返回 choices")
                            callback.onChunk(body)
                        }
                    }
                    if (cancelled) error("生成已取消")
                } finally {
                    activeStream.compareAndSet(stream, null)
                    runCatching { stream.close() }
                }
            }
        } catch (error: Exception) {
            lastError = if (cancelled) "生成已取消" else error.message ?: error.toString()
            throw error
        }
    }
}

internal fun consumeServerSentEvents(reader: Reader, onData: (String) -> Unit) {
    val lines = if (reader is BufferedReader) reader else reader.buffered()
    val data = StringBuilder()
    fun emit(): Boolean {
        if (data.isEmpty()) return false
        val value = data.toString().trimEnd('\n')
        data.clear()
        if (value == "[DONE]") return true
        if (value.isNotBlank()) onData(value)
        return false
    }
    for (line in lines.lineSequence()) {
        when {
            line.isEmpty() -> if (emit()) return
            line.startsWith("data:") -> data.append(line.removePrefix("data:").trimStart()).append('\n')
        }
    }
    emit()
}
