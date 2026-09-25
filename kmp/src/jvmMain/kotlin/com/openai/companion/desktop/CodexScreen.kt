package com.openai.companion.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.DialogState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.openai.companion.kmp.CompanionStreamAccumulator

private val secondaryText = Color(0xFF7B838D)

@Composable
internal fun CodexScreen(backend: DesktopBackend) {
    val scope = rememberCoroutineScope()
    val scroll = rememberLazyListState()
    var sessions by remember { mutableStateOf(emptyList<DesktopSession>()) }
    var activeId by remember { mutableStateOf<Long?>(null) }
    var messages by remember { mutableStateOf(emptyList<DesktopMessage>()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(false) }
    var streamedText by remember { mutableStateOf("") }
    var reasoning by remember { mutableStateOf("") }
    var showReasoning by remember { mutableStateOf(true) }
    var modelStatus by remember { mutableStateOf("检查本地模型…") }
    var sessionToDelete by remember { mutableStateOf<DesktopSession?>(null) }
    val streamAccumulator = remember { CompanionStreamAccumulator() }

    suspend fun refresh() { sessions = backend.sessions() }
    suspend fun select(id: Long) {
        messages = backend.openSession(id)
        activeId = id
        error = null
    }
    suspend fun create() {
        activeId = backend.newSession()
        messages = emptyList()
        refresh()
        error = null
    }
    fun submit() {
        val text = input.trim()
        val id = activeId ?: return
        if (text.isEmpty() || busy) return
        input = ""
        messages = messages + DesktopMessage("user", text)
        streamedText = ""
        reasoning = ""
        streamAccumulator.reset()
        busy = true
        scope.launch {
            val streamEvents = Channel<DesktopStreamEvent>(Channel.UNLIMITED)
            val streamCollector = launch {
                for (event in streamEvents) {
                    when (event) {
                        is DesktopStreamEvent.Reasoning -> reasoning = streamAccumulator.addReasoning(event.text)
                        is DesktopStreamEvent.Text -> streamedText = streamAccumulator.addText(event.text)
                        is DesktopStreamEvent.Completed -> Unit
                        is DesktopStreamEvent.Error -> Unit
                    }
                }
            }
            error = null
            try {
                backend.send(text) { event -> streamEvents.trySend(event) }
                streamEvents.close()
                streamCollector.join()
                messages = backend.loadSession(id)
                refresh()
            } catch (cause: Exception) {
                error = if (cause.message == "生成已取消") null else cause.message ?: cause.toString()
                if (input.isBlank()) input = text
                runCatching { backend.loadSession(id) }.getOrNull()?.let { messages = it }
            } finally {
                streamEvents.close()
                streamCollector.join()
                streamedText = ""
                reasoning = ""
                busy = false
            }
        }
    }
    LaunchedEffect(Unit) {
        busy = true
        try {
            backend.initialize()
            modelStatus = backend.modelServe.localStatus()
            refresh()
            if (sessions.isEmpty()) create() else select(sessions.first().id)
        } catch (cause: Exception) { error = cause.message ?: cause.toString() }
        finally { busy = false }
    }
    LaunchedEffect(backend) {
        while (true) {
            delay(15_000)
            modelStatus = backend.modelServe.localStatus()
        }
    }
    LaunchedEffect(messages.size, streamedText.length, reasoning.length) {
        val count = messages.size + if (busy && (streamedText.isNotEmpty() || reasoning.isNotEmpty())) 1 else 0
        if (count > 0) scroll.animateScrollToItem(count - 1)
    }
    if (settings) CodexSettings(backend, { settings = false }, { error = it }) {
        scope.launch { modelStatus = backend.modelServe.localStatus() }
    }
    sessionToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = { Text("删除会话？") },
            text = { Text("“${target.preview.ifBlank { "新会话" }}”及其本地轨迹将被永久删除。") },
            confirmButton = {
                TextButton(onClick = {
                    sessionToDelete = null
                    scope.launch {
                        busy = true
                        try {
                            backend.deleteSession(target.id)
                            refresh()
                            if (activeId == target.id) {
                                if (sessions.isEmpty()) create() else select(sessions.first().id)
                            }
                        } catch (cause: Exception) { error = cause.message }
                        finally { busy = false }
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { sessionToDelete = null }) { Text("取消") } },
        )
    }

    Row(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || !event.isMetaPressed) return@onPreviewKeyEvent false
        when (event.key) {
            Key.N -> {
                if (!busy) scope.launch {
                    busy = true
                    try { create() } catch (cause: Exception) { error = cause.message }
                    finally { busy = false }
                }
                true
            }
            Key.Enter -> { submit(); true }
            Key.Comma -> { settings = true; true }
            else -> false
        }
    }) {
        Column(Modifier.width(250.dp).fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp)) {
            Text("✦  OpenAICompanion", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(9.dp))
            Spacer(Modifier.height(18.dp))
            OutlinedButton(onClick = { scope.launch {
                busy = true
                try { create() } catch (cause: Exception) { error = cause.message }
                finally { busy = false }
            } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("＋  新建会话") }
            Spacer(Modifier.height(26.dp))
            Text("最近会话", color = secondaryText, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 9.dp))
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                items(sessions, key = { it.id }) { session ->
                    Row(Modifier.fillMaxWidth().background(
                        if (session.id == activeId) MaterialTheme.colorScheme.surface else Color.Transparent,
                        RoundedCornerShape(8.dp)).clickable(enabled = !busy) { scope.launch {
                        busy = true
                        try { select(session.id) } catch (cause: Exception) { error = cause.message }
                        finally { busy = false }
                    } }.padding(start = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("◦", color = secondaryText)
                        Text(session.preview.ifBlank { "新会话" }, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f).padding(start = 6.dp))
                        TextButton(onClick = { sessionToDelete = session }, enabled = !busy) {
                            Text("×", color = secondaryText)
                        }
                    }
                }
            }
            HorizontalDivider()
            TextButton(onClick = { settings = true }, modifier = Modifier.fillMaxWidth()) {
                Text("⚙  设置与模型", color = MaterialTheme.colorScheme.onSurface)
            }
        }

        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().height(70.dp).padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(sessions.firstOrNull { it.id == activeId }?.preview?.ifBlank { "新会话" } ?: "新会话",
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("$modelStatus · ${backend.modelServe.model}", color = secondaryText,
                        style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { settings = true }) { Text("设置") }
            }
            HorizontalDivider()
            if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 38.dp, vertical = 10.dp))
            LazyColumn(state = scroll, modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(22.dp)) {
                items(messages) { CodexMessage(it) }
                if (busy && (streamedText.isNotEmpty() || reasoning.isNotEmpty())) item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 42.dp, vertical = 8.dp)) {
                        Text("✦  Companion · 生成中", style = MaterialTheme.typography.labelMedium)
                        if (reasoning.isNotEmpty()) {
                            TextButton(onClick = { showReasoning = !showReasoning }) {
                                Text(if (showReasoning) "⌄  推理过程" else "›  推理过程")
                            }
                            if (showReasoning) Text(reasoning, color = secondaryText,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (streamedText.isNotEmpty()) Text(streamedText,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 42.dp, vertical = 17.dp)) {
                Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        OutlinedTextField(input, { input = it },
                            placeholder = { Text("给 Companion 发送消息…") },
                            minLines = 2, maxLines = 5, enabled = !busy,
                            modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("${backend.modelServe.model} · $modelStatus", color = secondaryText,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.weight(1f))
                            if (busy) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                TextButton(onClick = backend::cancel) { Text("停止") }
                            } else Button(onClick = ::submit,
                                enabled = input.isNotBlank() && activeId != null) { Text("发送 ↗") }
                        }
                    }
                }
                Text("⌘↵ 发送 · ⌘N 新会话 · ⌘, 设置", color = secondaryText,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun CodexMessage(message: DesktopMessage) {
    if (message.role == "status") {
        Text(message.content, color = secondaryText,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 78.dp))
        return
    }
    if (message.role == "reasoning") {
        var expanded by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 78.dp)) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "⌄  推理过程" else "›  查看推理过程")
            }
            if (expanded) Text(message.content, color = secondaryText,
                style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    val user = message.role == "user"
    Row(Modifier.fillMaxWidth().padding(horizontal = 42.dp),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        if (!user) {
            Box(Modifier.size(27.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center) {
                Text("✦", color = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.width(10.dp))
        }
        Surface(shape = RoundedCornerShape(12.dp),
            color = if (user) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
            modifier = Modifier.fillMaxWidth(if (user) 0.78f else 0.94f)) {
            Column(Modifier.padding(if (user) 13.dp else 0.dp)) {
                if (!user) Text(if (message.role == "tool") "工具" else "Companion",
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 5.dp))
                Text(message.content, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun CodexSettings(
    backend: DesktopBackend,
    onClose: () -> Unit,
    onError: (String?) -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var endpoint by remember { mutableStateOf(backend.modelServe.endpoint) }
    var model by remember { mutableStateOf(backend.modelServe.model) }
    var apiKey by remember { mutableStateOf(backend.modelServe.apiKey) }
    var mcpEndpoint by remember { mutableStateOf(backend.mcpEndpoint) }
    var connecting by remember { mutableStateOf(false) }
    DialogWindow(onCloseRequest = onClose, title = "设置", state = DialogState(width = 560.dp, height = 565.dp)) {
        MaterialTheme {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("模型与连接", style = MaterialTheme.typography.titleLarge)
                Text("默认连接本机 Ollama，使用 Unsloth Qwen3.8-27B UD-Q4_K_M。", color = secondaryText,
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Chat Completions 地址") },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(model, { model = it }, label = { Text("模型名称") },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API Key（仅本次运行）") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                HorizontalDivider()
                OutlinedTextField(mcpEndpoint, { mcpEndpoint = it }, label = { Text("远程 MCP（可选）") },
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = !connecting && endpoint.isNotBlank() && model.isNotBlank(), onClick = {
                        scope.launch {
                            connecting = true
                            try {
                                backend.connectMcp(mcpEndpoint)
                                backend.modelServe.save(endpoint, model, apiKey)
                                onError(null)
                                onSaved()
                                onClose()
                            } catch (cause: Exception) { onError(cause.message ?: cause.toString()) }
                            finally { connecting = false }
                        }
                    }) { Text("保存设置") }
                }
            }
        }
    }
}
