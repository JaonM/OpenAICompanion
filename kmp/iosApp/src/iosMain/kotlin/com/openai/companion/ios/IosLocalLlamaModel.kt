package com.openai.companion.ios

import com.openai.companion.ios.llama.oc_mlx_generate
import com.openai.companion.ios.llama.oc_mlx_download
import com.openai.companion.ios.llama.oc_mlx_cancel
import com.openai.companion.ios.llama.oc_mlx_unload
import com.openai.companion.ios.llama.oc_llama_destroy
import com.openai.companion.ios.llama.oc_llama_cancel
import com.openai.companion.ios.llama.oc_llama_create
import com.openai.companion.ios.llama.oc_llama_free_string
import com.openai.companion.ios.llama.oc_llama_generate
import com.openai.companion.ios.llama.oc_llama_load
import com.openai.companion.kmp.LocalModelContext
import com.openai.companion.kmp.AppModelServe
import com.openai.companion.kmp.IosGgufImporter
import com.openai.companion.kmp.ModelStreamCallback
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSUserDefaults

/** Kotlin/Native model callback; both native engines share the serialized model gate. */
@OptIn(ExperimentalForeignApi::class)
class IosLocalLlamaModel : AppModelServe {
    private var engine = oc_llama_create() ?: error("无法创建 llama.cpp 引擎")
    private val gate = Mutex()
    private val defaults = NSUserDefaults.standardUserDefaults
    private val importer = IosGgufImporter()

    val selectedEngine: String get() = defaults.stringForKey("localInferenceEngine")?.takeIf { it == "MLX" } ?: "llama.cpp"
    val importLabel: String get() = if (selectedEngine == "MLX") "下载 Qwen3.5 MLX 4bit（约 3.06 GB）" else "导入 GGUF"

    suspend fun selectEngine(name: String) = withContext(Dispatchers.Default) {
        require(name in listOf("llama.cpp", "MLX")) { "未知推理引擎" }
        gate.withLock {
            if (name != selectedEngine) {
                oc_llama_destroy(engine)
                engine = oc_llama_create() ?: error("无法创建 llama.cpp 引擎")
                oc_mlx_unload()
                defaults.setObject(name, forKey = "localInferenceEngine")
            }
        }
    }

    suspend fun importModel() {
        if (selectedEngine == "MLX") {
            withContext(Dispatchers.Default) { gate.withLock {
                oc_mlx_unload()
                checkNative(oc_mlx_download(mlxDirectory()))
            } }
            return
        }
        val imported = importer.import()
        withContext(Dispatchers.Default) {
            gate.withLock {
                try {
                    checkNative(oc_llama_load(engine, imported.path))
                    val previous = defaults.stringForKey(MODEL_FILE_KEY)
                    defaults.setObject(imported.fileName, forKey = MODEL_FILE_KEY)
                    defaults.setObject(imported.displayName, forKey = MODEL_DISPLAY_KEY)
                    if (previous != null && previous != imported.fileName &&
                        previous == previous.substringAfterLast('/') &&
                        previous == previous.substringAfterLast('\\')) {
                        val oldPath = modelDirectory() + previous
                        NSFileManager.defaultManager.removeItemAtPath(oldPath, error = null)
                    }
                } catch (failure: Throwable) {
                    NSFileManager.defaultManager.removeItemAtPath(imported.path, error = null)
                    throw failure
                }
            }
        }
    }

    override suspend fun complete(requestJson: String, callback: ModelStreamCallback): Unit =
        withContext(Dispatchers.Default) {
            gate.withLock {
                val useMLX = selectedEngine == "MLX"
                val path = if (useMLX) mlxDirectory().also { check(mlxReady()) { "请先下载 MLX 模型" } }
                    else modelPath() ?: error("请先导入 GGUF 模型")
                memScoped {
                    if (!useMLX) checkNative(oc_llama_load(engine, path))
                    val sink = StableRef.create(ChunkSink(callback))
                    try {
                        LocalModelContext.complete(requestJson) { fittedRequest ->
                            val onChunk = staticCFunction { chunk: CPointer<ByteVar>?, context: kotlinx.cinterop.COpaquePointer? ->
                                    if (context != null) {
                                        val target = context.asStableRef<ChunkSink>().get()
                                        target.emit(chunk?.toKString())
                                    }
                                }
                            checkNative(if (useMLX) oc_mlx_generate(path, fittedRequest, MAX_OUTPUT_TOKENS, onChunk, sink.asCPointer())
                                else oc_llama_generate(engine, fittedRequest, MAX_OUTPUT_TOKENS, onChunk, sink.asCPointer()))
                        }
                        sink.get().failure?.let { throw it }
                        if (!useMLX && defaults.boolForKey("acceptanceActivateImportedModel")) {
                            val domain = NSBundle.mainBundle.bundleIdentifier?.let(defaults::persistentDomainForName)
                            val previous = domain?.get(MODEL_FILE_KEY) as? String
                            val name = path.substringAfterLast('/')
                            defaults.setObject(name, forKey = MODEL_FILE_KEY)
                            defaults.setObject(name.removeSuffix(".gguf"), forKey = MODEL_DISPLAY_KEY)
                            if (previous != null && previous != name && previous.endsWith(".gguf", ignoreCase = true) &&
                                previous == previous.substringAfterLast('/') && previous == previous.substringAfterLast('\\')) {
                                check(NSFileManager.defaultManager.removeItemAtPath(modelDirectory() + previous, error = null)) {
                                    "新模型已启用，但旧模型删除失败，请重试清理。"
                                }
                            }
                        }
                    } finally {
                        sink.dispose()
                    }
                }
                Unit
            }
        }

    fun cancel() {
        oc_llama_cancel(engine)
        oc_mlx_cancel()
    }

    fun status(): String {
        if (selectedEngine == "MLX") return if (mlxReady()) "MLX 模型可用 · Qwen3.5-4B-MLX-4bit" else "请下载 Qwen3.5 MLX 4bit 模型"
        val path = modelPath() ?: return "请导入 GGUF 模型"
        return if (NSFileManager.defaultManager.fileExistsAtPath(path)) "端侧模型已导入 · ${path.substringAfterLast('/').removeSuffix(".gguf")}"
            else "模型文件丢失，请重新导入"
    }

    private fun modelPath(): String? {
        val name = defaults.stringForKey(MODEL_FILE_KEY)?.takeIf(String::isNotBlank) ?: return null
        require(name.endsWith(".gguf", ignoreCase = true) && name == name.substringAfterLast('/') &&
            name == name.substringAfterLast('\\') && name != "..") { "本地模型文件名无效" }
        return modelDirectory() + name
    }

    private fun mlxDirectory() = NSHomeDirectory() + "/Library/Application Support/OpenAICompanion/MLXModels/Qwen3.5-4B-MLX-4bit"
    private fun mlxReady() = listOf("config.json", "model.safetensors", "tokenizer.json", "tokenizer_config.json", "chat_template.jinja")
        .all { NSFileManager.defaultManager.fileExistsAtPath(mlxDirectory() + "/" + it) }

    private fun modelDirectory() = NSHomeDirectory() + "/Library/Application Support/OpenAICompanion/Models/"

    private fun checkNative(failure: CPointer<ByteVar>?) {
        if (failure == null) return
        val message = try { failure.toKString() } finally { oc_llama_free_string(failure) }
        error(message)
    }

    private class ChunkSink(val callback: ModelStreamCallback) {
        var failure: Throwable? = null

        fun emit(chunk: String?) {
            if (failure != null) return
            try {
                require(chunk != null) { "llama.cpp 返回了空片段" }
                callback.onChunk(chunk)
            } catch (error: Throwable) {
                failure = error
            }
        }
    }

    private companion object {
        const val MODEL_FILE_KEY = "localGGUFFileName"
        const val MODEL_DISPLAY_KEY = "localGGUFDisplayName"
        const val MAX_OUTPUT_TOKENS = 512
    }
}
