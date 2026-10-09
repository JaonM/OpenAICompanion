package com.openai.companion.desktop

import com.openai.companion.kmp.MessageMarkdown

import com.openai.companion.kmp.ModelLibraryPanel
import com.openai.companion.kmp.DeviceExecutionPanel

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.DialogState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.openai.companion.kmp.CompanionStreamAccumulator

private val secondaryText: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

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
    val pendingApproval by backend.pendingMcpApproval.collectAsState()
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
        if (count > 0) scroll.animateScrollToItem(count)
    }
    if (settings) CodexSettings(backend, { settings = false }, { error = it }) {
        scope.launch { modelStatus = backend.modelServe.localStatus() }
    }
    pendingApproval?.let { request ->
        AlertDialog(
            onDismissRequest = { backend.answerMcpApproval(request.id, false) },
            title = { Text("允许 MCP 工具调用？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(request.toolName, fontWeight = FontWeight.SemiBold)
                    Text("参数：${request.argumentsJson}", maxLines = 10,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall)
                    Text("确认后才会把这些参数发送到已连接的 MCP 服务。",
                        color = secondaryText, style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                Button(onClick = { backend.answerMcpApproval(request.id, true) }) { Text("允许一次") }
            },
            dismissButton = {
                TextButton(onClick = { backend.answerMcpApproval(request.id, false) }) { Text("拒绝") }
            },
        )
    }

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || !event.isMetaPressed) return@onPreviewKeyEvent false
        when (event.key) {
            Key.Enter -> { submit(); true }
            Key.Comma -> { settings = true; true }
            else -> false
        }
    }) {
        Column(Modifier.width(270.dp).fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 14.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Box(Modifier.size(30.dp).background(MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                    Text("✦", color = MaterialTheme.colorScheme.onPrimary)
                }
                Spacer(Modifier.width(10.dp))
                Text("OpenAICompanion", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().clickable { settings = true }
                .padding(horizontal = 10.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("⚙", color = secondaryText)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("设置与模型", style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium)
                    Text(modelStatus, color = secondaryText, style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        Column(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surface)) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 30.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(31.dp).background(MaterialTheme.colorScheme.primaryContainer,
                    RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                    Text("▢", color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(sessions.firstOrNull { it.id == activeId }?.preview?.ifBlank { "持续对话" } ?: "持续对话",
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("持续对话 · ${if (busy) "正在处理" else "就绪"}", color = secondaryText,
                        style = MaterialTheme.typography.labelSmall)
                }
                Surface(color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(9.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.clickable { settings = true }) {
                    Text(backend.modelServe.model, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.widthIn(max = 190.dp).padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 38.dp, vertical = 10.dp))
            if (messages.isEmpty() && streamedText.isEmpty() && reasoning.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(Modifier.widthIn(max = 570.dp).padding(30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                            Text("✦", style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.height(22.dp))
                        Text("今天想一起完成什么？", style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(9.dp))
                        Text("从一个问题开始，Companion 会保留这段会话的上下文。",
                            style = MaterialTheme.typography.bodyMedium, color = secondaryText,
                            textAlign = TextAlign.Center)
                    }
                }
            } else LazyColumn(state = scroll, modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                item { Spacer(Modifier.height(13.dp)) }
                items(messages) { CodexMessage(it) }
                if (busy && (streamedText.isNotEmpty() || reasoning.isNotEmpty())) item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 56.dp, vertical = 8.dp)) {
                        Text("✦  Companion · 生成中", style = MaterialTheme.typography.labelMedium)
                        if (reasoning.isNotEmpty()) {
                            TextButton(onClick = { showReasoning = !showReasoning }) {
                                Text(if (showReasoning) "⌄  推理过程" else "›  推理过程")
                            }
                            if (showReasoning) Text(reasoning, color = secondaryText,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (streamedText.isNotEmpty()) MessageMarkdown(streamedText,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                }
                item { Spacer(Modifier.height(14.dp)) }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 44.dp, vertical = 17.dp)) {
                Surface(shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shadowElevation = 5.dp,
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                        OutlinedTextField(input, { input = it },
                            placeholder = { Text("给 Companion 发送消息…", color = secondaryText) },
                            minLines = 2, maxLines = 5, enabled = !busy,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("●  $modelStatus", color = secondaryText,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.weight(1f))
                            if (busy) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                TextButton(onClick = backend::cancel) { Text("停止") }
                            } else Button(onClick = ::submit, shape = RoundedCornerShape(10.dp),
                                enabled = input.isNotBlank() && activeId != null) { Text("发送  ↑") }
                        }
                    }
                }
                Text("⌘↵ 发送 · ⌘, 设置", color = secondaryText,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun CodexMessage(message: DesktopMessage) {
    if (message.role == "status") {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp)) {
                Text(message.content, color = secondaryText,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp))
            }
        }
        return
    }
    if (message.role == "reasoning") {
        var expanded by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 92.dp)) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "⌄  推理过程" else "›  查看推理过程")
            }
            if (expanded) Text(message.content, color = secondaryText,
                style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    val user = message.role == "user"
    Row(Modifier.fillMaxWidth().padding(horizontal = 56.dp),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        if (!user) {
            Box(Modifier.size(29.dp).background(MaterialTheme.colorScheme.primaryContainer,
                RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center) {
                Text("✦", color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(10.dp))
        }
        Surface(shape = RoundedCornerShape(14.dp),
            color = if (user) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            modifier = Modifier.fillMaxWidth(if (user) 0.76f else 0.92f)) {
            Column(Modifier.padding(if (user) 14.dp else 0.dp)) {
                if (!user) Text(if (message.role == "tool") "工具" else "Companion",
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 5.dp))
                if (message.role == "tool") Text(message.content, style = MaterialTheme.typography.bodyMedium)
                else MessageMarkdown(message.content)
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
    var showTasks by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var deviceToolsEnabled by remember { mutableStateOf(backend.deviceToolsEnabled) }
    var connecting by remember { mutableStateOf(false) }
    var library by remember { mutableStateOf(backend.modelServe.modelLibrary()) }
    var downloading by remember { mutableStateOf(false) }
    var catalogLoading by remember { mutableStateOf(false) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    fun catalogAction(action: suspend () -> Unit) {
        if (catalogLoading || downloading) return
        catalogLoading = true
        scope.launch {
            try { action(); catalogError = null }
            catch (cause: Exception) { catalogError = cause.message }
            finally { library = backend.modelServe.modelLibrary(); catalogLoading = false }
        }
    }
    LaunchedEffect(Unit) { backend.modelServe.localStatus(); library = backend.modelServe.modelLibrary() }
    DialogWindow(onCloseRequest = onClose, title = "设置", state = DialogState(width = 560.dp, height = 730.dp)) {
        MaterialTheme {
            val modelLibraryScroll = rememberScrollState()
            Column(Modifier.fillMaxSize().verticalScroll(modelLibraryScroll).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ModelLibraryPanel(library, downloading, engine = "Ollama", scrollState = modelLibraryScroll, loading = catalogLoading, error = catalogError,
                    browse = { search, more -> catalogAction { backend.modelServe.browseModels("Ollama", search, more) } },
                    inspect = { id -> catalogAction { backend.modelServe.inspectModel(id) } }, install = { id -> scope.launch {
                    downloading = true
                    try {
                        backend.modelServe.installModel(id)
                        model = backend.modelServe.model
                        onSaved()
                    } catch (cause: Exception) { onError(cause.message) }
                    finally { library = backend.modelServe.modelLibrary(); downloading = false }
                } })
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(deviceToolsEnabled, { enabled -> scope.launch {
                        try { backend.setDeviceToolsEnabled(enabled); deviceToolsEnabled = enabled }
                        catch (error: Exception) { onError(error.message) }
                    } })
                    Text("启用端侧工具扩展（设备上下文与日历）")
                }
                OutlinedTextField(mcpEndpoint, { mcpEndpoint = it }, label = { Text("远程 MCP（可选）") },
                    modifier = Modifier.fillMaxWidth())
                Text(backend.mcpStatus, color = secondaryText,
                    style = MaterialTheme.typography.bodySmall)
                Text("远程 MCP 使用 HTTPS；本机 localhost 可使用 HTTP。连接成功后保存，下次启动会自动恢复。",
                    color = secondaryText, style = MaterialTheme.typography.labelSmall)
                HorizontalDivider()
                Text("主动提醒", style = MaterialTheme.typography.titleMedium)
                Text("使用本地模型时，每轮对话后及后台每 30 分钟主动发现机会；App 运行期间按时调用 Harness 判断是否推送。",
                    color = secondaryText, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { showDevices = true }) { Text("跨设备执行与远端任务") }
                Button(onClick = { showTasks = true }) { Text("管理主动任务") }
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = !connecting && !downloading && endpoint.isNotBlank() && model.isNotBlank(), onClick = {
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
    if (showDevices) DialogWindow(onCloseRequest = { showDevices = false }, title = "跨设备执行", state = DialogState(width = 620.dp, height = 760.dp)) {
        MaterialTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                DeviceExecutionPanel(backend.devices, backend.a2a)
            }
        }
    }
    if (showTasks) ProactiveTasksDialog(backend, { showTasks = false }, onError)
}

@Composable
private fun ProactiveTasksDialog(backend: DesktopBackend, onClose: () -> Unit, onError: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf(backend.proactiveTasks) }
    var proactiveSettings by remember { mutableStateOf(backend.proactiveSettings) }
    var memorySyncEndpoint by remember { mutableStateOf(backend.memorySyncEndpoint) }
    var memorySyncToken by remember { mutableStateOf("") }
    var memorySyncStatus by remember { mutableStateOf(backend.memorySyncStatus) }
    DialogWindow(onCloseRequest = onClose, title = "主动任务", state = DialogState(width = 560.dp, height = 500.dp)) {
        MaterialTheme {
            Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("端侧模型会从对话和已有记忆主动发现任务。", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(proactiveSettings.enabled, { enabled -> scope.launch {
                        try {
                            backend.saveProactiveConfig(proactiveSettings.copy(enabled = enabled))
                            proactiveSettings = backend.proactiveSettings
                        } catch (cause: Exception) { onError(cause.message) }
                    } })
                    Text("开启主动推送")
                }
                Text("后台发现间隔")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15L, 30L, 60L, 180L).forEach { minutes ->
                        OutlinedButton(onClick = { scope.launch {
                            try {
                                backend.saveProactiveConfig(proactiveSettings.copy(discoveryIntervalMinutes = minutes))
                                proactiveSettings = backend.proactiveSettings
                            } catch (cause: Exception) { onError(cause.message) }
                        } }, enabled = proactiveSettings.discoveryIntervalMinutes != minutes) {
                            Text(if (minutes < 60) "${minutes} 分钟" else "${minutes / 60} 小时")
                        }
                    }
                }
                if (tasks.isEmpty()) Text("尚无主动任务")
                tasks.forEach { task ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(task.enabled, { enabled -> scope.launch {
                            try { backend.saveProactiveTask(task.copy(enabled = enabled)); tasks = backend.proactiveTasks }
                            catch (cause: Exception) { onError(cause.message) }
                        } })
                        Text(task.title)
                        TextButton(onClick = { scope.launch {
                            try { backend.deleteProactiveTask(task.scenario); tasks = backend.proactiveTasks }
                            catch (cause: Exception) { onError(cause.message) }
                        } }) { Text("删除") }
                    }
                }
                Text("记忆点跨端同步", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(memorySyncEndpoint, { memorySyncEndpoint = it },
                    label = { Text("HTTPS 同步接口地址") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(memorySyncToken, { memorySyncToken = it },
                    label = { Text("访问令牌（留空保留）") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Text(memorySyncStatus)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch {
                        try {
                            backend.configureMemorySync(memorySyncEndpoint, memorySyncToken)
                            memorySyncToken = ""
                            memorySyncStatus = backend.memorySyncStatus
                        } catch (cause: Exception) {
                            memorySyncStatus = backend.memorySyncStatus
                            onError(cause.message)
                        }
                    } }) { Text("保存并同步") }
                    OutlinedButton(onClick = { scope.launch {
                        try { backend.syncMemories(); memorySyncStatus = backend.memorySyncStatus }
                        catch (cause: Exception) { memorySyncStatus = backend.memorySyncStatus; onError(cause.message) }
                    } }) { Text("立即同步") }
                }
            }
        }
    }
}
