package com.openai.companion.kmp

import kotlinx.serialization.json.jsonObject

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.launch

data class MobileSession(val id: Long, val title: String)
data class MobileMessage(val role: String, val content: String)
data class MobileToolApproval(val id: Long, val name: String, val argumentsJson: String)
data class MobileInputPrompt(val id: Long, val server: String, val params: JsonObject)
data class MobileA2aApproval(val id: Long, val request: A2aDelegation)

data class MobileUiState(
    val sessions: List<MobileSession> = emptyList(),
    val activeSessionId: Long? = null,
    val messages: List<MobileMessage> = emptyList(),
    val streamedText: String = "",
    val streamedReasoning: String = "",
    val sending: Boolean = false,
    val modelStatus: String = "未导入端侧模型",
    val modelEngines: List<String> = emptyList(),
    val modelEngine: String = "llama.cpp",
    val modelImportLabel: String = "导入 GGUF",
    val modelChanging: Boolean = false,
    val modelCatalogLoading: Boolean = false,
    val modelCatalogError: String? = null,
    val modelLibrary: ModelLibraryState? = null,
    val mealReminderEnabled: Boolean = false,
    val commuteReminderEnabled: Boolean = false,
    val mealTime: String = "19:00",
    val commuteTime: String = "18:30",
    val proactiveTasks: List<ProactiveTask> = emptyList(),
    val proactiveSettings: ProactiveSettings = ProactiveSettings(),
    val backgroundReminderStatus: String = "后台提醒未启用",
    val reminderSettingsAvailable: Boolean = false,
    val memorySyncEndpoint: String = "",
    val memorySyncStatus: String = "未配置",
    val mcpEndpoint: String = "",
    val mcpStatus: String = "未连接 MCP",
    val mcpBusy: Boolean = false,
    val deviceToolsEnabled: Boolean = false,
    val approval: MobileToolApproval? = null,
    val inputPrompt: MobileInputPrompt? = null,
    val deviceEndpoint: String = "",
    val deviceName: String = "",
    val deviceAcceptsTasks: Boolean = false,
    val deviceStatus: String = "未配置跨设备执行",
    val a2aAgents: List<A2aAgent> = emptyList(),
    val a2aTasks: List<A2aTask> = emptyList(),
    val a2aApproval: MobileA2aApproval? = null,
    val error: String? = null,
)

interface MobileActions {
    fun createSession()
    fun closeSession()
    fun openSession(id: Long)
    fun deleteSession(id: Long)
    fun send(text: String)
    fun cancel()
    fun configureMcp(endpoint: String)
    fun setDeviceToolsEnabled(enabled: Boolean) = Unit
    fun authorizeMcp(clientId: String)
    fun answerApproval(id: Long, allow: Boolean)
    fun answerInput(id: Long, contentJson: String?)
    fun selectModelEngine(engine: String) = Unit
    fun installModel(id: String) = Unit
    fun browseModels(search: String, more: Boolean) = Unit
    fun inspectModel(id: String) = Unit
    fun importModel()
    fun saveProactiveSettings(mealEnabled: Boolean, mealTime: String,
        commuteEnabled: Boolean, commuteTime: String)
    fun saveProactiveTask(task: ProactiveTask) = Unit
    fun saveProactiveConfig(settings: ProactiveSettings) = Unit
    fun openReminderSettings() = Unit
    fun configureMemorySync(endpoint: String, token: String) = Unit
    fun syncMemories() = Unit
    fun deleteProactiveTask(id: String) = Unit
    fun configureDevices(endpoint: String, token: String, name: String, accepts: Boolean) = Unit
    fun pairDevice(endpoint: String, code: String, name: String, accepts: Boolean) = Unit
    fun addA2aAgent(cardUrl: String)
    fun disableA2aAgent(id: String)
    fun setA2aBearerToken(id: String, token: String)
    fun refreshA2aTasks()
    fun replyToA2aTask(id: Long, text: String)
    fun declineA2aTask(id: Long)
    fun cancelA2aTask(id: Long)
    fun answerA2aApproval(id: Long, allow: Boolean)
}

/** Shared Compose UI for the iOS/Android mobile app; no SwiftUI shell is required. */
@Composable
fun CompanionMobileScreen(state: MobileUiState, actions: MobileActions) {
    var showSettings by remember { mutableStateOf(false) }
    var showTasks by remember { mutableStateOf(false) }
    var settingsSection by remember { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    var followSentMessage by remember { mutableStateOf(false) }
    var followLatest by remember { mutableStateOf(true) }
    var draft by remember { mutableStateOf("") }
    val sendDraft = {
        if (!state.sending && !state.modelChanging && state.activeSessionId != null && draft.isNotBlank()) {
            followSentMessage = true
            followLatest = true
            actions.send(draft.trim())
            draft = ""
            keyboard?.hide()
        }
    }
    var endpointDraft by remember(state.mcpEndpoint) { mutableStateOf(state.mcpEndpoint) }
    var oauthClientId by remember { mutableStateOf("") }
    var deviceEndpointDraft by remember(state.deviceEndpoint) { mutableStateOf(state.deviceEndpoint) }
    var deviceNameDraft by remember(state.deviceName) { mutableStateOf(state.deviceName) }
    var deviceAcceptsDraft by remember(state.deviceAcceptsTasks) { mutableStateOf(state.deviceAcceptsTasks) }
    var deviceTokenDraft by remember { mutableStateOf("") }
    var advancedDevices by remember { mutableStateOf(false) }
    var pairingCode by remember { mutableStateOf("") }
    var a2aCardDraft by remember { mutableStateOf("") }
    var memorySyncEndpointDraft by remember(state.memorySyncEndpoint) { mutableStateOf(state.memorySyncEndpoint) }
    var memorySyncTokenDraft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val uiScope = rememberCoroutineScope()
    val nearLatest by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
            layout.totalItemsCount == 0 || (last != null &&
                (last.index == layout.totalItemsCount - 1 ||
                    (last.index == layout.totalItemsCount - 2 &&
                        last.offset + last.size <= layout.viewportEndOffset)))
        }
    }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) followLatest = false
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && nearLatest) followLatest = true
        }
    }
    LaunchedEffect(state.messages.size, state.streamedText.length, state.streamedReasoning.length, state.sending, state.a2aTasks) {
        if (!showSettings && !showTasks && (followLatest || followSentMessage)) {
            val last = state.messages.size +
                (if (state.sending && state.streamedReasoning.isNotEmpty()) 1 else 0) +
                (if (state.sending && state.streamedText.isNotEmpty()) 1 else 0) +
                (if (state.sending) 1 else 0)
            // Schedule scrolling with layout instead of forcing remeasure while text nodes update.
            if (last >= 0) listState.requestScrollToItem(last)
            followSentMessage = false
        }
    }
    val approval = state.approval
    val inputPrompt = state.inputPrompt

    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) companionDarkColors else companionLightColors) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).background(MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                        Text("✦", color = MaterialTheme.colorScheme.onPrimary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (showSettings) settingsSection ?: "设置" else if (showTasks) "远端任务" else "OpenAICompanion",
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (showSettings) "个人助理偏好" else if (showTasks) "${state.a2aTasks.count { !it.terminal }} 个进行中" else "持续对话 · ${if (state.sending) "正在处理" else "就绪"}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!showSettings) TextButton(onClick = {
                        keyboard?.hide()
                        showTasks = !showTasks
                    }) { Text(if (showTasks) "对话" else "任务") }
                    TextButton(onClick = {
                        keyboard?.hide()
                        showSettings = !showSettings
                        settingsSection = null
                    }) {
                        Text(if (showSettings) "完成" else "设置")
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                when {
                    showSettings -> { key(settingsSection) {
                        val settingsScroll = rememberScrollState()
                        Column(Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceVariant)
                        .verticalScroll(settingsScroll).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (settingsSection == null) {
                            Text("按功能管理你的助理", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            MobileSettingsEntry("端侧模型", state.modelStatus) { settingsSection = "端侧模型" }
                            MobileSettingsEntry("主动任务", state.backgroundReminderStatus) { settingsSection = "主动任务" }
                            MobileSettingsEntry("跨设备执行", state.deviceStatus) { settingsSection = "跨设备执行" }
                            MobileSettingsEntry("记忆点跨端同步", state.memorySyncStatus) { settingsSection = "记忆点跨端同步" }
                            MobileSettingsEntry("远程 MCP", state.mcpStatus) { settingsSection = "远程 MCP" }
                            MobileSettingsEntry("工具扩展", if (state.deviceToolsEnabled) "已开启" else "默认关闭 · 可选扩展") { settingsSection = "工具扩展" }
                        } else {
                            TextButton(onClick = { settingsSection = null }) { Text("‹ 所有设置") }
                        }
                        if (settingsSection == "端侧模型") {
                            MobileSectionTitle("端侧模型")
                            if (state.modelEngines.isNotEmpty()) {
                                Text("推理引擎", style = MaterialTheme.typography.labelLarge)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    state.modelEngines.forEach { engine ->
                                        FilterChip(selected = state.modelEngine == engine,
                                            enabled = !state.sending && !state.modelChanging,
                                            onClick = { actions.selectModelEngine(engine) }, label = { Text(engine) })
                                    }
                                }
                            }
                            Text(if (state.modelChanging) "正在准备模型…" else state.modelStatus)
                            OutlinedButton(enabled = !state.sending && !state.modelChanging,
                                onClick = actions::importModel) { Text(if (state.modelEngine == "MLX") "导入本地 MLX 文件夹" else "导入本地 GGUF 文件") }
                            if (state.modelEngine == "MLX") Text("进入包含配置、tokenizer 和 safetensors 权重的模型文件夹，再点“打开”。导入后点击“使用此模型”启用。", style = MaterialTheme.typography.bodySmall)
                            state.modelLibrary?.let { ModelLibraryPanel(it, state.sending || state.modelChanging, actions::installModel,
                                engine = state.modelEngine, loading = state.modelCatalogLoading, error = state.modelCatalogError,
                                browse = actions::browseModels, inspect = actions::inspectModel, scrollState = settingsScroll) }
                        }
                        if (settingsSection == "工具扩展") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(state.deviceToolsEnabled, actions::setDeviceToolsEnabled,
                                    modifier = Modifier.semantics { contentDescription = "端侧工具扩展开关" })
                                Text("启用端侧工具扩展（设备上下文与日历）")
                            }
                            Text("扩展默认关闭；启用后，日历访问仍需逐次确认和系统权限。")
                            Text(state.mcpStatus, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(20.dp))
                        }
                        if (settingsSection == "主动任务") {
                            MobileSectionTitle("主动任务")
                            Row {
                                Checkbox(state.proactiveSettings.enabled, { enabled ->
                                    actions.saveProactiveConfig(state.proactiveSettings.copy(enabled = enabled))
                                }, modifier = Modifier.semantics { contentDescription = "主动推送开关" })
                                Text("开启主动推送")
                            }
                            Text(state.backgroundReminderStatus)
                            Text("固定计划由系统提醒；实时条件检查与模型任务仍需要可运行的执行端。")
                            if (state.reminderSettingsAvailable) TextButton(onClick = actions::openReminderSettings) { Text("配置精确提醒权限") }
                            Text("后台发现间隔")
                            listOf(15L, 30L, 60L, 180L).chunked(2).forEach { pair ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    pair.forEach { minutes ->
                                        OutlinedButton(onClick = {
                                            actions.saveProactiveConfig(state.proactiveSettings.copy(discoveryIntervalMinutes = minutes))
                                        }, enabled = state.proactiveSettings.discoveryIntervalMinutes != minutes,
                                            modifier = Modifier.weight(1f)) {
                                            Text(if (minutes < 60) "${minutes} 分钟" else "${minutes / 60} 小时")
                                        }
                                    }
                                }
                            }
                            Text("端侧模型根据记忆点和可用查询工具推理未来可帮助的任务；到点后再次查询实时信息。")
                            state.proactiveTasks.forEach { task ->
                                Row {
                                    Checkbox(task.enabled, { enabled ->
                                        actions.saveProactiveTask(task.copy(enabled = enabled))
                                    }, modifier = Modifier.testTag("proactive-toggle-${task.title}"))
                                    Text(task.title)
                                    TextButton(onClick = { actions.deleteProactiveTask(task.scenario) },
                                        modifier = Modifier.testTag("proactive-delete-${task.title}")) { Text("删除") }
                                }
                            }
                            Spacer(Modifier.height(20.dp))
                        }
                        if (settingsSection == "记忆点跨端同步") {
                            MobileSectionTitle("记忆点跨端同步")
                            OutlinedTextField(
                                value = memorySyncEndpointDraft,
                                onValueChange = { memorySyncEndpointDraft = it },
                                label = { Text("HTTPS 同步接口地址") },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                            )
                            OutlinedTextField(
                                value = memorySyncTokenDraft,
                                onValueChange = { memorySyncTokenDraft = it },
                                label = { Text("访问令牌（留空保留已保存令牌）") },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                            )
                            Text(state.memorySyncStatus)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    actions.configureMemorySync(memorySyncEndpointDraft, memorySyncTokenDraft)
                                    memorySyncTokenDraft = ""
                                }) { Text("保存并同步") }
                                OutlinedButton(onClick = actions::syncMemories) { Text("立即同步") }
                            }
                            Spacer(Modifier.height(20.dp))
                        }
                        if (settingsSection == "远程 MCP") {
                            MobileSectionTitle("远程 MCP")
                            OutlinedTextField(
                                value = endpointDraft,
                                onValueChange = { endpointDraft = it },
                                label = { Text("MCP 服务地址") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Text(state.mcpStatus)
                            Button(
                                onClick = { actions.configureMcp(endpointDraft) },
                                enabled = !state.mcpBusy,
                            ) { Text(if (state.mcpBusy) "处理中…" else "连接 / 更新") }
                            if (state.mcpStatus.startsWith("需要 OAuth")) {
                                OutlinedTextField(
                                    value = oauthClientId,
                                    onValueChange = { oauthClientId = it.trim() },
                                    label = { Text("OAuth Client ID（可选）") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                )
                                Button(
                                    onClick = { actions.authorizeMcp(oauthClientId) },
                                    enabled = !state.mcpBusy,
                                ) { Text("在浏览器中授权") }
                                Text("留空将尝试动态注册；也可填写已预注册的 Client ID。")
                            }
                            Text("远程地址需使用 HTTPS；本机允许 HTTP。")
                            Spacer(Modifier.height(20.dp))
                        }
                        if (settingsSection == "跨设备执行") {
                            MobileSectionTitle("我的设备 · 跨设备执行")
                            Text("普通问答默认在本机完成。需要其他设备时，在对话中说“委托给 Mac：整理这份资料”，确认后发送；结果会回到当前对话。")
                            Text("首次连接：从已连接的 Mac 获取服务地址和配对码，填入下方即可。所有设备使用同一个 HTTPS 设备服务；配对后自动发现，无需再配置 A2A。", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(deviceEndpointDraft, { deviceEndpointDraft = it }, label = { Text("设备服务地址") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "设备服务地址" })
                            OutlinedTextField(deviceNameDraft, { deviceNameDraft = it }, label = { Text("本设备名称") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "本设备名称" })
                            TextButton(onClick = { advancedDevices = !advancedDevices }) { Text(if (advancedDevices) "收起高级设置" else "高级设置（令牌与第三方智能体）") }
                            if (advancedDevices) {
                            OutlinedTextField(deviceTokenDraft, { deviceTokenDraft = it }, label = { Text("设备令牌（留空复用）") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                                Text("管理员首次接入可填写令牌；已有设备通常使用配对码。令牌不需要重复填写。", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { settingsSection = "远端 Agent（A2A）" }) { Text("远端 Agent（A2A）") }
                            }
                            OutlinedTextField(pairingCode, { pairingCode = it }, label = { Text("一次性配对码（10 分钟有效）") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "一次性配对码" })
                            OutlinedButton(enabled = pairingCode.isNotBlank(), onClick = {
                                actions.pairDevice(deviceEndpointDraft, pairingCode, deviceNameDraft, deviceAcceptsDraft)
                                pairingCode = ""
                            }) { Text("使用配对码连接") }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(deviceAcceptsDraft, { deviceAcceptsDraft = it }, modifier = Modifier.semantics { contentDescription = "前台接单开关" })
                                Text("允许本设备在前台接收任务")
                            }
                            Text(state.deviceStatus)
                            if (state.a2aAgents.any { it.enabled }) Text("可委托设备 / 智能体：" + state.a2aAgents.filter { it.enabled }.joinToString { it.name })
                            Text("任务使用独立上下文；工具权限仍需本机确认。清空地址可停用。")
                            Button(onClick = {
                                actions.configureDevices(deviceEndpointDraft, deviceTokenDraft, deviceNameDraft, deviceAcceptsDraft)
                                deviceTokenDraft = ""
                            }) { Text("保存设备连接") }
                        }
                        if (settingsSection == "远端 Agent（A2A）") {
                            MobileSectionTitle("第三方智能体（A2A）")
                            Text("仅连接独立的第三方 Agent 时需要。自己的手机和 Mac 配对后会自动发现，不用在这里重复添加。Agent Card 地址由该服务提供方提供。")
                            OutlinedTextField(
                                value = a2aCardDraft,
                                onValueChange = { a2aCardDraft = it },
                                label = { Text("Agent Card 地址") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Button(onClick = { actions.addA2aAgent(a2aCardDraft) }, enabled = a2aCardDraft.isNotBlank()) {
                                Text("添加 Agent")
                            }
                            state.a2aAgents.forEach { agent ->
                                var tokenDraft by remember(agent.id) { mutableStateOf("") }
                                Text("${agent.name} · ${agent.skills}")
                                Text(agent.interfaceUrl)
                                if (agent.requiresAuthentication) {
                                    Text(if (agent.bearerSupported) "此 Agent 要求 Bearer 认证。" else "此 Agent 的认证方式暂不支持。")
                                }
                                if (agent.bearerSupported) {
                                    OutlinedTextField(
                                        value = tokenDraft,
                                        onValueChange = { tokenDraft = it },
                                        label = { Text("Bearer 访问令牌") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        visualTransformation = PasswordVisualTransformation(),
                                    )
                                    TextButton(enabled = tokenDraft.isNotBlank(), onClick = {
                                        actions.setA2aBearerToken(agent.id, tokenDraft)
                                        tokenDraft = ""
                                    }) { Text("保存访问令牌") }
                                }
                                TextButton(onClick = { if (agent.enabled) actions.disableA2aAgent(agent.id) else actions.addA2aAgent(agent.cardUrl) }, modifier = Modifier.semantics { contentDescription = "${if (agent.enabled) "停用" else "启用"} Agent ${agent.name}" }) { Text(if (agent.enabled) "停用" else "启用") }
                            }
                        }
                    } } }
                    showTasks -> {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("执行进度与结果", style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = actions::refreshA2aTasks) { Text("刷新") }
                        }
                        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (state.a2aTasks.isEmpty()) item {
                                Text("暂无远端任务。回到对话，告诉助理需要哪台设备协助。",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            items(state.a2aTasks.sortedByDescending { it.id }, key = { it.id }) { task ->
                                val agentName = state.a2aAgents.firstOrNull { it.id == task.agentId }?.name ?: task.agentId
                                A2aTaskCard(task, agentName, actions)
                            }
                        }
                    }
                    state.activeSessionId == null -> {
                        Text("正在加载对话…")
                    }
                    else -> {
                        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            if (state.messages.isEmpty() && state.streamedText.isEmpty()) {
                                item {
                                    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("✦", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.displaySmall)
                                        Spacer(Modifier.height(20.dp))
                                        Text("今天想一起完成什么？", style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.SemiBold)
                                        Text("从一个问题开始，保留这段会话的上下文。",
                                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 10.dp))
                                    }
                                }
                            }
                            itemsIndexed(state.messages, key = { index, message -> "message-$index-${message.role}" },
                                contentType = { _, message -> message.role }) { index, message ->
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    MobileMessageRow(message, Modifier.testTag("conversation-message-$index-${message.role}"))
                                    val task = delegatedTaskId(message)?.let { id -> state.a2aTasks.firstOrNull { it.id == id } }
                                    if (task != null) {
                                        val name = state.a2aAgents.firstOrNull { it.id == task.agentId }?.name ?: task.agentId
                                        A2aTaskCard(task, name, actions)
                                    }
                                }
                            }
                            if (state.sending && state.streamedReasoning.isNotEmpty()) {
                                item(key = "reasoning-streaming") {
                                    Text("思考过程：${state.streamedReasoning}",
                                        modifier = Modifier.fillMaxWidth().padding(start = 38.dp).testTag("reasoning-streaming"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (state.sending && state.streamedText.isNotEmpty()) {
                                item(key = "assistant-streaming") { MobileMessageRow(MobileMessage("assistant", state.streamedText), Modifier.testTag("assistant-streaming")) }
                            }
                            if (state.sending) {
                                item(key = "assistant-thinking") { MobileThinkingBubble(state.streamedText.isEmpty()) }
                            }
                            // A trailing anchor reaches the end even when a single reply is taller than the viewport.
                            item(key = "conversation-end") { Spacer(Modifier.height(1.dp)) }
                        }
                        if (!nearLatest) TextButton(onClick = {
                            followLatest = true
                            uiScope.launch {
                                listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                            }
                        }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("↓ 最新消息") }
                        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 2.dp,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                BasicTextField(value = draft, onValueChange = { draft = it },
                                    modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                                        .semantics { contentDescription = "输入消息" }
                                        .onPreviewKeyEvent { event ->
                                            if (event.key == Key.Enter && !event.isShiftPressed) {
                                                if (event.type == KeyEventType.KeyDown) sendDraft()
                                                true
                                            } else false
                                        },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                    keyboardActions = KeyboardActions(onSend = { sendDraft() }),
                                    minLines = 1, maxLines = 4,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    decorationBox = { input ->
                                        Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.CenterStart) {
                                            if (draft.isEmpty()) Text("给 Companion 发送消息…",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            input()
                                        }
                                    })
                                if (state.sending) TextButton(onClick = actions::cancel) { Text("停止") }
                                else FilledIconButton(enabled = draft.isNotBlank() && !state.modelChanging,
                                    modifier = Modifier.testTag("send-message").semantics { contentDescription = "发送" },
                                    onClick = sendDraft) {
                                    Icon(sendPaperPlane, contentDescription = null, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                        Text(state.modelStatus, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 6.dp))
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
            }
        }
        if (approval != null) {
            AlertDialog(
                onDismissRequest = { actions.answerApproval(approval.id, false) },
                title = { Text("允许 MCP 工具调用？") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(approval.name)
                        Text(approval.argumentsJson)
                        Text("允许后会向 MCP 服务发送这些参数。")
                    }
                },
                confirmButton = {
                    TextButton(onClick = { actions.answerApproval(approval.id, true) }) {
                        Text("允许一次")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { actions.answerApproval(approval.id, false) }) {
                        Text("拒绝")
                    }
                },
            )
        }
        state.a2aApproval?.let { request ->
            AlertDialog(
                onDismissRequest = { actions.answerA2aApproval(request.id, false) },
                title = { Text("委托给远端 Agent？") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(request.request.agent.name)
                        Text("目标：${request.request.agent.interfaceUrl}")
                        Text("将发送以下内容：")
                        Text(request.request.taskText)
                    }
                },
                confirmButton = { TextButton(onClick = { actions.answerA2aApproval(request.id, true) }) { Text("发送一次") } },
                dismissButton = { TextButton(onClick = { actions.answerA2aApproval(request.id, false) }) { Text("拒绝") } },
            )
        }
        if (inputPrompt != null) {
            val form = remember(inputPrompt.id) { runCatching { McpElicitationForm.parse(inputPrompt.params) }.getOrNull() }
            val values = remember(inputPrompt.id) {
                mutableStateMapOf<String, String>().also { map ->
                    form?.fields?.forEach { field ->
                        map[field.name] = field.defaultValue.ifEmpty {
                            if (field.type == "boolean" && field.required) "false" else ""
                        }
                    }
                }
            }
            var contentDraft by remember(inputPrompt.id) { mutableStateOf("{}") }
            val encodedContent = runCatching {
                form?.encode(values)?.toString()
                    ?: (Json.parseToJsonElement(contentDraft) as JsonObject).toString()
            }.getOrNull()
            AlertDialog(
                onDismissRequest = { actions.answerInput(inputPrompt.id, null) },
                title = { Text("MCP 服务请求输入") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("请求方：${inputPrompt.server}")
                        Text(inputPrompt.params["message"]?.jsonPrimitive?.content.orEmpty())
                        Text("不要在表单中输入密码、令牌或支付凭据。")
                        if (form == null) {
                            Text("此表单结构暂不支持自动填写，请检查 Schema 后填写 JSON 对象。")
                            Text(inputPrompt.params["requestedSchema"]?.toString().orEmpty())
                            OutlinedTextField(
                                value = contentDraft,
                                onValueChange = { contentDraft = it },
                                label = { Text("回复内容（JSON 对象）") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            form.fields.forEach { field ->
                                Text(field.title + if (field.required) " *" else "")
                                if (field.description.isNotBlank()) Text(field.description)
                                if (field.choices.isNotEmpty()) Text("可选：${field.choices.joinToString("、")}")
                                if (field.type == "boolean") {
                                    Row {
                                        Checkbox(
                                            checked = values[field.name] == "true",
                                            onCheckedChange = { values[field.name] = it.toString() },
                                        )
                                        Text("是")
                                    }
                                } else {
                                    OutlinedTextField(
                                        value = values[field.name].orEmpty(),
                                        onValueChange = { values[field.name] = it },
                                        label = { Text(field.type) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = encodedContent != null,
                        onClick = { actions.answerInput(inputPrompt.id, encodedContent) },
                    ) { Text("提交一次") }
                },
                dismissButton = {
                    TextButton(onClick = { actions.answerInput(inputPrompt.id, null) }) { Text("拒绝") }
                },
            )
        }
    }
}

@Composable
private fun A2aTaskCard(task: A2aTask, agentName: String, actions: MobileActions) {
    var reply by remember(task.id) { mutableStateOf("") }
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("远端任务 · $agentName", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { contentDescription = "远端任务编号 ${task.id} · $agentName" })
            Text(task.requestText, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(when (task.state) {
                "SUBMITTING" -> "正在提交"
                "SEND_UNCERTAIN" -> "发送状态未知，请勿直接重发"
                "REPLY_SUBMITTING" -> "正在提交回复"
                "REPLY_UNCERTAIN" -> "回复状态未知，正在核查远端任务"
                "EXECUTION_UNKNOWN" -> "设备失联，执行结果未知；正在核查，不会自动重试"
                "CANCEL_UNCERTAIN" -> "取消状态未知，正在核查远端任务"
                "TASK_STATE_SUBMITTED" -> "已提交"
                "TASK_STATE_WORKING" -> "处理中"
                "TASK_STATE_INPUT_REQUIRED" -> "等待你的回复"
                "TASK_STATE_AUTH_REQUIRED" -> "需要认证"
                "TASK_STATE_COMPLETED", "DIRECT_MESSAGE" -> "已完成"
                "TASK_STATE_FAILED" -> "失败"
                "TASK_STATE_CANCELED" -> "已取消"
                "TASK_STATE_REJECTED" -> "远端已拒绝"
                else -> task.state
            }, style = MaterialTheme.typography.labelMedium,
                color = if (task.state == "TASK_STATE_FAILED") MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary)
            task.question?.let { Text("远端请求：$it") }
            if (task.state == "TASK_STATE_AUTH_REQUIRED") {
                Text("请按远端说明完成授权；访问令牌可在设置中为该 Agent 单独保存。")
            }
            task.result?.let { result ->
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer) {
                    Text("远端结果：$result", modifier = Modifier.padding(12.dp).semantics {
                        contentDescription = "任务 ${task.id} 远端结果：$result"
                    })
                }
            }
            if (task.state == "TASK_STATE_INPUT_REQUIRED") {
                OutlinedTextField(reply, { reply = it }, label = { Text("回复该远端任务") }, modifier = Modifier.fillMaxWidth())
                Row {
                    TextButton(enabled = reply.isNotBlank(), onClick = {
                        actions.replyToA2aTask(task.id, reply)
                        reply = ""
                    }) { Text("提交回复") }
                    TextButton(onClick = { actions.declineA2aTask(task.id) }) { Text("拒绝提供") }
                }
            }
            if (!task.terminal && task.remoteTaskId != null) {
                TextButton(onClick = { actions.cancelA2aTask(task.id) }) { Text("取消任务") }
            }
        }
    }
}

@Composable
private fun MobileThinkingBubble(thinking: Boolean) {
    val transition = rememberInfiniteTransition(label = "thinking")
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.testTag("assistant-thinking")) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (thinking) "正在思考" else "正在生成", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            repeat(3) { index ->
                val alpha by transition.animateFloat(initialValue = 0.25f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(500, delayMillis = index * 150), RepeatMode.Reverse),
                    label = "thinking-dot-$index")
                Box(Modifier.size(5.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    RoundedCornerShape(50)))
            }
        }
    }
}

private val sendPaperPlane = ImageVector.Builder("SendPaperPlane", 20.dp, 20.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
        moveTo(21f, 3f)
        lineTo(3f, 10f)
        lineTo(10f, 13f)
        lineTo(13f, 21f)
        close()
        moveTo(10f, 13f)
        lineTo(21f, 3f)
    }
}.build()

@Composable
private fun MobileSettingsEntry(title: String, summary: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("settings-$title"),
        shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(summary, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("›", modifier = Modifier.padding(start = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MobileSectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
private fun MobileMessageRow(message: MobileMessage, modifier: Modifier = Modifier) {
    if (message.role == "tool" || message.role == "reasoning") {
        var expanded by remember(message) { mutableStateOf(false) }
        val title = if (message.role == "tool") "工具" else "思考过程"
        val preview = message.content.lineSequence().firstOrNull().orEmpty().take(64)
        Column(Modifier.fillMaxWidth().padding(start = 38.dp)) {
            TextButton(onClick = { expanded = !expanded },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(if (expanded) "⌄ $title" else "› $title：$preview…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (expanded) Text(message.content, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier.semantics { contentDescription = "${message.role}：${message.content}" })
        }
        return
    }
    val user = message.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        if (!user) {
            Box(Modifier.size(28.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center) { Text("✦", color = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.width(10.dp))
        }
        Surface(shape = RoundedCornerShape(14.dp),
            color = if (user) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            modifier = Modifier.fillMaxWidth(if (user) 0.86f else 0.90f)) {
            Column(Modifier.padding(if (user) 14.dp else 0.dp)) {
                if (!user) Text(if (message.role == "tool") "工具" else "Companion",
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 6.dp))
                Text(message.content, style = MaterialTheme.typography.bodyMedium,
                    modifier = modifier.semantics { contentDescription = "${message.role}：${message.content}" })
            }
        }
    }
}

/** Associate a durable remote receipt with its originating conversation turn. */
internal fun delegatedTaskId(message: MobileMessage): Long? {
    if (message.role != "tool" || !message.content.startsWith("delegate_to_agent: ")) return null
    return runCatching {
        Json.parseToJsonElement(message.content.removePrefix("delegate_to_agent: "))
            .jsonObject["local_task_id"]?.jsonPrimitive?.content?.toLongOrNull()
    }.getOrNull()
}
