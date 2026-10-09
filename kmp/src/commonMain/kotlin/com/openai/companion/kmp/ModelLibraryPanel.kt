package com.openai.companion.kmp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ModelLibraryPanel(state: ModelLibraryState, busy: Boolean, install: (String) -> Unit,
    engine: String = state.catalogEngine.ifBlank { if (state.device.platform == "macOS") "Ollama" else "llama.cpp" },
    loading: Boolean = false, error: String? = null, browse: (String, Boolean) -> Unit = { _, _ -> },
    inspect: (String) -> Unit = {}) {
    val uriHandler = LocalUriHandler.current
    val focusManager = LocalFocusManager.current
    var search by remember(engine) { mutableStateOf("") }
    LaunchedEffect(engine) { browse("", false) }
    Text("$engine 模型库", style = MaterialTheme.typography.titleMedium)
    val device = state.device
    Text("${device.platform} ${device.osMajor} · ${device.architecture} · 内存 ${device.memoryBytes?.let(ModelLibrary::gb) ?: "未知"} GB · 可用空间 ${device.freeBytes?.let(ModelLibrary::gb) ?: "未知"} GB",
        style = MaterialTheme.typography.bodySmall)
    Text(if (engine == "MLX") "来源：Hugging Face / mlx-community" else "来源：Hugging Face / GGUF 仓库",
        style = MaterialTheme.typography.bodySmall)
    Text("浏览只读取模型信息。下载完成后点击“使用此模型”切换；兼容性按硬件估算，当前仅支持文本。", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(search, { search = it }, label = { Text("搜索 Hugging Face 模型") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row { Button(enabled = !loading && !busy, onClick = { focusManager.clearFocus(); browse(search, false) }) { Text("搜索") }
        TextButton(enabled = !loading && !busy, onClick = { browse(search, false) }) { Text("刷新列表") } }
    fun sameEngine(model: LibraryModel) = model.engine == engine || (engine == "Ollama" && model.engine == "llama.cpp")
    val installed = state.models.filter { it.id in state.installed && sameEngine(it) }
    if (installed.isNotEmpty()) Text("已下载", style = MaterialTheme.typography.titleSmall)
    val available = if (state.catalogEngine == engine) state.catalog.filter { it.id !in state.installed && sameEngine(it) } else emptyList()
    (installed + available).distinctBy { it.id }.forEach { model ->
        val downloaded = model.id in state.installed
        val compatibility = ModelLibrary.compatibility(model, device, downloaded)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(model.title)
                Text(model.repository, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { uriHandler.openUri("https://huggingface.co/${model.repository}") }) { Text("Hugging Face 模型页") }
                Text(if (model.resolved) "${ModelLibrary.gb(model.bytes)} GB · 建议内存 ${model.memoryGB} GB+" else "查看文件以确认大小和量化版本", style = MaterialTheme.typography.bodySmall)
                if (model.downloads > 0) Text("下载 ${model.downloads} · 喜欢 ${model.likes} · 更新 ${model.updated.take(10)}", style = MaterialTheme.typography.bodySmall)
                if (model.resolved) Text(compatibility.description, style = MaterialTheme.typography.bodySmall)
                model.unavailableReason?.let { if (!model.resolved) Text(it, style = MaterialTheme.typography.bodySmall) }
                Button(modifier = Modifier.testTag("model-library-action-${model.id}"), enabled = !busy && !loading && state.selected != model.id &&
                    (if (!model.resolved) model.unavailableReason == null else compatibility.allowed),
                    onClick = { if (model.resolved) install(model.id) else inspect(model.id) }) {
                    Text(if (state.selected == model.id) "使用中" else if (downloaded) "使用此模型" else if (!model.resolved) "查看文件" else "下载")
                }
                if (downloaded && state.selected != model.id) Text("已下载，无需重复下载", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (state.catalogEngine == engine && state.hasMore) OutlinedButton(enabled = !loading && !busy, onClick = { browse(search, true) }) { Text("加载更多模型") }
    if (loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在读取 Hugging Face 模型信息…") }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (!loading && state.catalogLoaded && available.isEmpty()) Text("没有更多匹配的模型，可调整搜索词。")
    if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在下载或加载模型，请保持 App 前台…") }
}
