package com.openai.companion.kmp

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Url
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.math.ceil

/** Reads repository metadata only. Artifact downloads belong to the explicit host action. */
class HuggingFaceModelCatalog(private val http: HttpClient = HttpClient { install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 20_000; socketTimeoutMillis = 30_000 } }) {
    private val gate = Mutex()
    private var known = ModelLibrary.models.associateBy { it.id }
    var items: List<LibraryModel> = emptyList(); private set
    var engine: String = ""; private set
    var loaded = false; private set
    private var next: String? = null
    val hasMore get() = next != null
    val models get() = known.values.toList()
    fun get(id: String) = known[id] ?: error("请刷新模型列表")
    fun remember(models: List<LibraryModel>) { known = known + models.associateBy { it.id } }

    suspend fun browse(selectedEngine: String, search: String, more: Boolean) = gate.withLock {
        require(selectedEngine in setOf("MLX", "llama.cpp", "Ollama"))
        val response = if (more) {
            val page = next ?: return@withLock
            val url = Url(page)
            require(url.protocol.name == "https" && url.host == "huggingface.co" && url.encodedPath == "/api/models")
            http.get(page)
        } else http.get("https://huggingface.co/api/models") {
            if (selectedEngine == "MLX") parameter("author", "mlx-community")
            if (selectedEngine != "MLX") parameter("filter", "gguf")
            parameter("search", search.trim()); parameter("sort", "downloads")
            parameter("direction", "-1"); parameter("limit", 24); parameter("full", true)
        }
        check(response.status.value == 200) { "Hugging Face 列表 HTTP ${response.status.value}" }
        val raw = response.bodyAsText()
        require(raw.length < 4_000_000) { "模型列表响应过大" }
        val page = Json.parseToJsonElement(raw).jsonArray.mapNotNull { rawModel ->
            val entry = rawModel.jsonObject
            val repo = entry.text("id")
            if (!validRepository(repo)) return@mapNotNull null
            if (selectedEngine == "MLX" && !repo.startsWith("mlx-community/")) return@mapNotNull null
            val seed = ModelLibrary.models.firstOrNull { it.repository == repo && it.engine == "MLX" }
            val id = seed?.id ?: repo
            val cached = known[id]?.takeIf { it.resolved }
            (cached ?: LibraryModel(id, repo.substringAfter('/'), if (selectedEngine == "MLX") "MLX" else "llama.cpp",
                0, 0, repo, entry.text("sha"), resolved = false)).copy(
                downloads = entry.number("downloads"), likes = entry.number("likes"), updated = entry.text("lastModified"),
                unavailableReason = when {
                    entry["gated"]?.jsonPrimitive?.content !in setOf(null, "false") -> "此仓库需要 Hugging Face 授权，当前未接入登录"
                    entry.text("pipeline_tag") !in setOf("", "text-generation", "image-text-to-text") -> "当前只接入文本对话，不适配此任务类型"
                    else -> cached?.unavailableReason
                })
        }
        known = known + page.associateBy { it.id }
        items = if (more) (items + page).distinctBy { it.id } else page
        engine = selectedEngine; loaded = true
        next = Regex("<([^>]+)>; rel=\"next\"").find(response.headers["Link"].orEmpty())?.groupValues?.get(1)
    }

    suspend fun inspect(id: String): List<LibraryModel> = gate.withLock {
        val model = get(id)
        require(validRepository(model.repository))
        val pinned = model.revision.takeIf { it.matches(Regex("[a-f0-9]{40}")) }
        val response = http.get("https://huggingface.co/api/models/${model.repository}" + (pinned?.let { "/revision/$it" } ?: "")) { parameter("blobs", true) }
        check(response.status.value == 200) { "Hugging Face 文件信息 HTTP ${response.status.value}" }
        val raw = response.bodyAsText(); require(raw.length < 8_000_000)
        val entry = Json.parseToJsonElement(raw).jsonObject
        val revision = entry.text("sha"); require(revision.matches(Regex("[a-f0-9]{40}")))
        val files = entry["siblings"]!!.jsonArray.mapNotNull { rawFile ->
            val file = rawFile.jsonObject
            val path = file.text("rfilename")
            if (!validArtifact(path)) return@mapNotNull null
            val lfs = file["lfs"] as? JsonObject
            ModelArtifact(path, file.number("size"), lfs?.text("sha256").orEmpty(), file.text("blobId"))
        }
        val resolved = if (model.engine == "MLX") {
            val selected = files.filter { it.file.endsWith(".safetensors") || it.file.endsWith(".json") ||
                it.file in setOf("tokenizer.model", "merges.txt", "vocab.txt", "chat_template.jinja") }
            val weights = selected.filter { it.file.endsWith(".safetensors") }
            require(weights.isNotEmpty() && selected.any { it.file == "config.json" }) { "仓库没有可加载的 MLX 权重" }
            require(selected.size <= 512 && selected.any { it.file in setOf("tokenizer.json", "tokenizer.model") }) { "缺少本地 tokenizer 或文件清单过大" }
            require(selected.all { it.bytes > 0 && (it.sha256.matches(Regex("[a-f0-9]{64}")) || it.blobId.matches(Regex("[a-f0-9]{40}"))) }) { "缺少可校验的模型文件信息" }
            val weightBytes = weights.sumOf { it.bytes }
            val family = (entry["config"] as? JsonObject)?.text("model_type").orEmpty()
            listOf(model.copy(bytes = selected.sumOf { it.bytes }, memoryGB = maxOf(model.memoryGB, memoryGB(weightBytes)), revision = revision,
                files = selected, resolved = true,
                unavailableReason = if (family in setOf("qwen3", "qwen3_5", "qwen3_5_text", "qwen2", "llama", "mistral", "gemma", "gemma2", "gemma3_text", "phi3")) null else "当前 MLX 文本适配尚未验证模型架构：${family.ifBlank { "未知" }}"))
        } else {
            files.filter { it.file.endsWith(".gguf", true) }.map { file ->
                val seed = ModelLibrary.models.firstOrNull { it.repository == model.repository && it.file == file.file }
                model.copy(id = seed?.id ?: "${model.repository}/${file.file}", title = file.file, file = file.file,
                    bytes = file.bytes, memoryGB = seed?.memoryGB ?: memoryGB(file.bytes), revision = revision,
                    sha256 = file.sha256, files = listOf(file), resolved = true,
                    unavailableReason = when {
                        file.file.contains("mmproj", ignoreCase = true) -> "视觉投影文件不能独立作为文本模型"
                        Regex("-\\d{5}-of-\\d{5}\\.gguf$").containsMatchIn(file.file) -> "分片 GGUF 暂未接入"
                        file.bytes <= 0 || !file.sha256.matches(Regex("[a-f0-9]{64}")) -> "缺少文件大小或 SHA256"
                        else -> null
                    })
            }.also { require(it.isNotEmpty()) { "仓库没有 GGUF 文件" } }
        }
        known = known + resolved.associateBy { it.id }
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) items = items.take(index) + resolved + items.drop(index + 1)
        resolved
    }
    companion object {
        fun validRepository(id: String) = id.split('/').let { it.size == 2 && it.all { part -> part.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")) && part !in setOf(".", "..") } }
        fun validArtifact(path: String) = path.length in 1..200 && path.split('/').all { it.isNotBlank() && it !in setOf(".", "..") && it.matches(Regex("[A-Za-z0-9_.-]+")) }
        private fun memoryGB(weights: Long) = ceil((weights * 1.5 + 2_000_000_000L) / 1_000_000_000.0).toInt().coerceAtLeast(4)
    }
}
private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.content.orEmpty()
private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.longOrNull ?: 0L
