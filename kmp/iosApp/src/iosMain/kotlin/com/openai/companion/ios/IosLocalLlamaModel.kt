package com.openai.companion.ios

import com.openai.companion.kmp.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import com.openai.companion.ios.llama.oc_mlx_download_manifest
import com.openai.companion.ios.llama.oc_model_download
import com.openai.companion.kmp.ModelLibrary
import com.openai.companion.kmp.ModelLibraryProvider
import com.openai.companion.kmp.ModelLibraryState
import com.openai.companion.kmp.ModelDevice
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSNumber
import platform.Foundation.NSFileSystemFreeSize
import platform.UIKit.UIDevice
import com.openai.companion.ios.llama.oc_mlx_generate
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
class IosLocalLlamaModel : AppModelServe, ModelLibraryProvider {
    private var engine = oc_llama_create() ?: error("无法创建 llama.cpp 引擎")
    private val gate = Mutex()
    private val defaults = NSUserDefaults.standardUserDefaults
    private val importer = IosGgufImporter()
    private val catalog = HuggingFaceModelCatalog().apply {
        remember(runCatching { Json.decodeFromString<List<LibraryModel>>(defaults.stringForKey("downloadedCatalogModels") ?: "[]") }.getOrDefault(emptyList()))
    }
    private fun rememberDownload(model: LibraryModel) {
        catalog.remember(listOf(model))
        val installed = catalog.models.filter { downloaded(it) }
        defaults.setObject(Json.encodeToString(installed), forKey = "downloadedCatalogModels")
    }
    override suspend fun browseModels(engine: String, search: String, more: Boolean) = catalog.browse(engine, search, more)
    override suspend fun inspectModel(id: String) { catalog.inspect(id) }
    private fun ggufPath(model: LibraryModel): String {
        val legacy = defaults.stringForKey(MODEL_FILE_KEY)
        if (model.id in ModelLibrary.models.map { it.id } && model.file.isNotEmpty() && legacy?.endsWith(model.file) == true && NSFileManager.defaultManager.fileExistsAtPath(modelDirectory() + legacy)) return modelDirectory() + legacy
        return if (model.id in ModelLibrary.models.map { it.id }) modelDirectory() + model.file
            else modelDirectory() + model.repository + "/" + model.file
    }
    private fun downloaded(model: LibraryModel): Boolean {
        if (!model.resolved) return false
        val files = NSFileManager.defaultManager
        return if (model.engine == "MLX") mlxReady(model)
            else model.file.isNotEmpty() && files.fileExistsAtPath(ggufPath(model))
    }

    val selectedEngine: String get() = defaults.stringForKey("localInferenceEngine")?.takeIf { it == "MLX" } ?: "llama.cpp"
    val importLabel: String get() = "导入 GGUF"

    override fun modelLibrary(): ModelLibraryState {
        val files = NSFileManager.defaultManager
        val free = (files.attributesOfFileSystemForPath(NSHomeDirectory(), error = null)?.get(NSFileSystemFreeSize) as? NSNumber)?.longLongValue
        val device = ModelDevice("iOS", UIDevice.currentDevice.systemVersion.substringBefore('.').toIntOrNull() ?: 0,
            if (NSBundle.mainBundle.bundlePath.contains("CoreSimulator")) "simulator" else "arm64",
            NSProcessInfo.processInfo.physicalMemory.toLong(), free, setOf("llama.cpp", "MLX"))
        val installed = catalog.models.filter(::downloaded).map { it.id }.toSet()
        val active = defaults.stringForKey(if (selectedEngine == "MLX") "selectedCatalogMLX" else "selectedCatalogGGUF")
        val selected = if (selectedEngine == "MLX") active?.takeIf { it in installed } ?: "qwen35-mlx".takeIf { it in installed }
            else active?.takeIf { it in installed && catalog.get(it).engine == "llama.cpp" }
                ?: catalog.models.firstOrNull { it.id in installed && it.file.isNotEmpty() && modelPath()?.endsWith(it.file) == true }?.id
        return ModelLibraryState(device, installed, selected, catalog.models, catalog.items, catalog.engine, catalog.hasMore, catalog.loaded)
    }

    override suspend fun installModel(id: String) = withContext(Dispatchers.Default) {
        gate.withLock {
            var model = catalog.get(id)
            val installed = downloaded(model)
            if (!installed) {
                if (model.engine == "MLX" || !model.resolved) model = catalog.inspect(id).first { it.id == id }
                val compatible = ModelLibrary.compatibility(model, modelLibrary().device)
                check(compatible.allowed) { compatible.description }
                require(HuggingFaceModelCatalog.validRepository(model.repository))
                if (model.engine == "MLX") checkNative(oc_mlx_download_manifest(mlxDirectory(model), model.repository, model.revision, Json.encodeToString(model.files)))
                else checkNative(oc_model_download(model.url, ggufPath(model), model.sha256, model.bytes))
                rememberDownload(model)
                return@withLock // Download never changes the active model.
            }
            val compatible = ModelLibrary.compatibility(model, modelLibrary().device, installed = true)
            check(compatible.allowed) { compatible.description }
            if (model.engine == "llama.cpp") {
                oc_mlx_unload()
                checkNative(oc_llama_load(engine, ggufPath(model)))
            } else {
                oc_llama_destroy(engine)
                engine = oc_llama_create() ?: error("无法创建 llama.cpp 引擎")
                oc_mlx_unload()
            }
            defaults.setObject(model.id, forKey = if (model.engine == "MLX") "selectedCatalogMLX" else "selectedCatalogGGUF")
            defaults.setObject(model.engine, forKey = "localInferenceEngine")
        }
    }

    suspend fun selectEngine(name: String) = withContext(Dispatchers.Default) {
        require(name in listOf("llama.cpp", "MLX")) { "未知推理引擎" }
        if (name == selectedEngine) return@withContext
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
        check(selectedEngine == "llama.cpp") { "请在 MLX 模型库中下载模型" }
        val imported = importer.import()
        withContext(Dispatchers.Default) {
            gate.withLock {
                try {
                    checkNative(oc_llama_load(engine, imported.path))
                    val previous = defaults.stringForKey(MODEL_FILE_KEY)
                    defaults.removeObjectForKey("selectedCatalogGGUF")
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
                val path = if (useMLX) mlxDirectory(activeMLX()).also { check(mlxReady(activeMLX())) { "请先下载 MLX 模型" } }
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
        if (selectedEngine == "MLX") return if (mlxReady(activeMLX())) "MLX 模型可用 · ${activeMLX().repository.substringAfter('/')}" else "请在模型库下载并选择 MLX 模型"
        val path = modelPath() ?: return "请导入 GGUF 模型"
        return if (NSFileManager.defaultManager.fileExistsAtPath(path)) "端侧模型已导入 · ${path.substringAfterLast('/').removeSuffix(".gguf")}"
            else "模型文件丢失，请重新导入"
    }

    private fun modelPath(): String? {
        defaults.stringForKey("selectedCatalogGGUF")?.let { id ->
            val model = catalog.models.firstOrNull { it.id == id && it.engine == "llama.cpp" }
            if (model != null) return ggufPath(model)
        }
        val name = defaults.stringForKey(MODEL_FILE_KEY)?.takeIf(String::isNotBlank) ?: return null
        require(name.endsWith(".gguf", ignoreCase = true) && name == name.substringAfterLast('/') &&
            name == name.substringAfterLast('\\') && name != "..") { "本地模型文件名无效" }
        return modelDirectory() + name
    }

    private fun activeMLX() = catalog.models.firstOrNull { it.id == defaults.stringForKey("selectedCatalogMLX") && it.engine == "MLX" }
        ?: ModelLibrary.get("qwen35-mlx")
    private fun mlxDirectory(model: LibraryModel) = NSHomeDirectory() + "/Library/Application Support/OpenAICompanion/MLXModels/" +
        if (model.id == "qwen35-mlx") "Qwen3.5-4B-MLX-4bit" else model.repository
    private fun mlxReady(model: LibraryModel): Boolean {
        val files = model.files.map { it.file }.ifEmpty { listOf("config.json", "model.safetensors", "tokenizer.json", "tokenizer_config.json", "chat_template.jinja") }
        return files.all { NSFileManager.defaultManager.fileExistsAtPath(mlxDirectory(model) + "/" + it) }
    }

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
