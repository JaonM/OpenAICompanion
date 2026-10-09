package com.openai.companion.kmp

/** Hardware thresholds are conservative app policy, not vendor performance guarantees. */
data class ModelDevice(val platform: String, val osMajor: Int, val architecture: String,
    val memoryBytes: Long?, val freeBytes: Long?, val engines: Set<String>)
data class LibraryModel(val id: String, val title: String, val engine: String, val bytes: Long,
    val memoryGB: Int, val repository: String, val revision: String, val file: String = "", val sha256: String = "") {
    val url: String get() = "https://huggingface.co/$repository/resolve/$revision/$file"
    val ollamaName: String get() = "hf.co/$repository:Q8_0"
}
data class ModelCompatibility(val allowed: Boolean, val description: String)
data class ModelLibraryState(val device: ModelDevice, val installed: Set<String> = emptySet(), val selected: String? = null)
interface ModelLibraryProvider {
    fun modelLibrary(): ModelLibraryState
    suspend fun installModel(id: String)
}
object ModelLibrary {
    val models = listOf(
        LibraryModel("qwen35-mlx", "Qwen3.5 4B · MLX 4bit", "MLX", 3061129077, 8,
            "mlx-community/Qwen3.5-4B-MLX-4bit", "32f3e8ecf65426fc3306969496342d504bfa13f3"),
        LibraryModel("qwen35-gguf", "Qwen3.5 4B · GGUF Q8", "llama.cpp", 4482403488, 12,
            "unsloth/Qwen3.5-4B-GGUF", "e87f176479d0855a907a41277aca2f8ee7a09523", "Qwen3.5-4B-Q8_0.gguf",
            "10cc391b403021dd11c614679d2fd92f611c3681d29e29651b717316965d61e1"),
        LibraryModel("qwen3-small", "Qwen3 0.6B · GGUF Q8（轻量）", "llama.cpp", 639446688, 4,
            "Qwen/Qwen3-0.6B-GGUF", "23749fefcc72300e3a2ad315e1317431b06b590a", "Qwen3-0.6B-Q8_0.gguf",
            "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031")
    )
    fun get(id: String) = models.first { it.id == id }
    fun compatibility(model: LibraryModel, device: ModelDevice, installed: Boolean = false): ModelCompatibility {
        val engine = if (device.platform == "macOS" && model.engine == "llama.cpp") "Ollama" else model.engine
        val reasons = mutableListOf<String>()
        if (engine !in device.engines) reasons += "当前端未接入 $engine 引擎"
        val minimum = when (device.platform) { "iOS" -> 17; "Android" -> 26; "macOS" -> 14; else -> Int.MAX_VALUE }
        if (device.osMajor < minimum) reasons += "系统版本不满足要求（$minimum+）"
        if (device.architecture !in setOf("arm64", "aarch64", "arm64-v8a", "x86_64", "amd64") ||
            (model.engine == "MLX" && device.architecture !in setOf("arm64", "aarch64"))) reasons += "不支持当前处理器架构"
        if (device.memoryBytes == null) reasons += "无法确认设备内存"
        else if (device.memoryBytes < model.memoryGB * 1_000_000_000L) reasons += "建议至少 ${model.memoryGB} GB 内存"
        if (!installed) {
            if (device.freeBytes == null) reasons += "无法确认剩余空间"
            else if (device.freeBytes < model.bytes * 2 + 1_000_000_000L) reasons += "下载暂存需要 ${gb(model.bytes * 2 + 1_000_000_000L)} GB 空间"
        }
        return ModelCompatibility(reasons.isEmpty(), if (reasons.isEmpty()) "支持当前设备（估算）" else reasons.joinToString("；"))
    }
    fun gb(bytes: Long): String = (((bytes + 50_000_000L) / 100_000_000L).toDouble() / 10).toString()
}
