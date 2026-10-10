package com.openai.companion.kmp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

enum class MemoryClearTier(val key: String, val label: String, val description: String) {
    Short("short", "短期记忆", "清空本设备的聊天记录、原始思考与工具轨迹，以及最近对话上下文。保留已提取的中长期记忆和中期摘要。"),
    Medium("medium", "中期记忆", "清空本设备的对话摘要，以及阶段性计划、目标和进行中事项的记忆点。保留原始聊天记录与长期记忆。"),
    Long("long", "长期记忆", "清空本设备已提取的持续事实、偏好和约束等长期记忆点。保留原始聊天记录与中期记忆；近期聊天中仍有的事实仍可作为上下文。"),
}

@Composable
fun MemoryManagementPanel(enabled: Boolean, onClear: suspend (MemoryClearTier) -> Unit) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<MemoryClearTier?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("清空历史与记忆", style = MaterialTheme.typography.titleMedium)
        Text("三档独立清理，只删除所选档位。操作不可撤销，请先结束生成。", style = MaterialTheme.typography.bodySmall)
        MemoryClearTier.entries.forEach { tier ->
            Text(tier.description, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = enabled && !clearing, onClick = { selected = tier }) { Text("清空${tier.label}") }
        }
        Text("已开启记忆同步时，中长期记忆点的删除会在成功同步后传播至服务及同一用户的其他设备；对话轨迹与摘要只在本机清理。模型、连接凭据、主动提醒和远端任务不在清理范围内。", style = MaterialTheme.typography.bodySmall)
        Text("保留的聊天或摘要可能仍包含相同事实；之后的新对话可产生新记忆。若要清空三层，请分别执行三档清理。", style = MaterialTheme.typography.bodySmall)
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    selected?.let { tier ->
        AlertDialog(
            onDismissRequest = { if (!clearing) selected = null },
            title = { Text("确认清空${tier.label}？") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tier.description)
                Text("此操作不可撤销。中长期记忆点的删除会在已配置的同步成功后传播，备份及历史导出不会自动删除。")
            } },
            confirmButton = { TextButton(enabled = enabled && !clearing, onClick = {
                clearing = true
                scope.launch {
                    try { onClear(tier); status = "${tier.label}已清空"; selected = null }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { status = "清空失败：${error.message}"; selected = null }
                    finally { clearing = false }
                }
            }) { Text(if (clearing) "清空中…" else "确认清空") } },
            dismissButton = { TextButton(enabled = !clearing, onClick = { selected = null }) { Text("取消") } },
        )
    }
}
