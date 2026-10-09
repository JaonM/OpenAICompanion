package com.openai.companion.kmp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ModelLibraryPanel(state: ModelLibraryState, busy: Boolean, install: (String) -> Unit) {
    Text("模型库", style = MaterialTheme.typography.titleMedium)
    val device = state.device
    Text("${device.platform} ${device.osMajor} · ${device.architecture} · 内存 ${device.memoryBytes?.let(ModelLibrary::gb) ?: "未知"} GB · 可用空间 ${device.freeBytes?.let(ModelLibrary::gb) ?: "未知"} GB",
        style = MaterialTheme.typography.bodySmall)
    Text("兼容性按系统、引擎、架构、总内存和存储估算；运行速度及长上下文仍受可用内存影响。当前仅支持文本。下载期间请保持 App 前台。",
        style = MaterialTheme.typography.bodySmall)
    ModelLibrary.models.forEach { model ->
        val installed = model.id in state.installed
        val compatibility = ModelLibrary.compatibility(model, device, installed)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(model.title)
                Text("${ModelLibrary.gb(model.bytes)} GB · 建议内存 ${model.memoryGB} GB+", style = MaterialTheme.typography.bodySmall)
                Text(compatibility.description, style = MaterialTheme.typography.bodySmall)
                if (model.id == "qwen3-small") Text("轻量模型适合基础问答，复杂理解能力有限。", style = MaterialTheme.typography.bodySmall)
                Button(enabled = !busy && compatibility.allowed && state.selected != model.id,
                    onClick = { install(model.id) }) {
                    Text(if (state.selected == model.id) "使用中" else if (installed) "使用此模型" else "下载并使用")
                }
            }
        }
    }
    if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在下载或加载模型，请稍候…") }
}
