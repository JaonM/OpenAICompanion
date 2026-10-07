package com.openai.companion.kmp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val sending: Boolean = false,
    val modelStatus: String = "未导入端侧模型",
    val mealReminderEnabled: Boolean = false,
    val commuteReminderEnabled: Boolean = false,
    val mealTime: String = "19:00",
    val commuteTime: String = "18:30",
    val proactiveTasks: List<ProactiveTask> = emptyList(),
    val proactiveSettings: ProactiveSettings = ProactiveSettings(),
    val memorySyncEndpoint: String = "",
    val memorySyncStatus: String = "未配置",
    val mcpEndpoint: String = "",
    val mcpStatus: String = "未连接 MCP",
    val mcpBusy: Boolean = false,
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
    fun authorizeMcp(clientId: String)
    fun answerApproval(id: Long, allow: Boolean)
    fun answerInput(id: Long, contentJson: String?)
    fun importModel()
    fun saveProactiveSettings(mealEnabled: Boolean, mealTime: String,
        commuteEnabled: Boolean, commuteTime: String)
    fun saveProactiveTask(task: ProactiveTask) = Unit
    fun saveProactiveConfig(settings: ProactiveSettings) = Unit
    fun configureMemorySync(endpoint: String, token: String) = Unit
    fun syncMemories() = Unit
    fun deleteProactiveTask(id: String) = Unit
    fun configureDevices(endpoint: String, token: String, name: String, accepts: Boolean) = Unit
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
    var draft by remember { mutableStateOf("") }
    var endpointDraft by remember(state.mcpEndpoint) { mutableStateOf(state.mcpEndpoint) }
    var oauthClientId by remember { mutableStateOf("") }
    var deviceEndpointDraft by remember(state.deviceEndpoint) { mutableStateOf(state.deviceEndpoint) }
    var deviceNameDraft by remember(state.deviceName) { mutableStateOf(state.deviceName) }
    var deviceAcceptsDraft by remember(state.deviceAcceptsTasks) { mutableStateOf(state.deviceAcceptsTasks) }
    var deviceTokenDraft by remember { mutableStateOf("") }
    var a2aCardDraft by remember { mutableStateOf("") }
    var memorySyncEndpointDraft by remember(state.memorySyncEndpoint) { mutableStateOf(state.memorySyncEndpoint) }
    var memorySyncTokenDraft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val uiScope = rememberCoroutineScope()
    val approval = state.approval
    val inputPrompt = state.inputPrompt

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("OpenAICompanion", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { showSettings = !showSettings }) {
                        Text(if (showSettings) "完成" else "设置")
                    }
                }
                Spacer(Modifier.height(12.dp))
                when {
                    showSettings -> { Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Text("端侧模型", style = MaterialTheme.typography.titleMedium)
                        Text(state.modelStatus)
                        OutlinedButton(onClick = actions::importModel) { Text("导入 GGUF") }
                        Spacer(Modifier.height(20.dp))
                        Text("主动任务", style = MaterialTheme.typography.titleMedium)
                        Row {
                            Checkbox(state.proactiveSettings.enabled, { enabled ->
                                actions.saveProactiveConfig(state.proactiveSettings.copy(enabled = enabled))
                            })
                            Text("开启主动推送")
                        }
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
                                })
                                Text(task.title)
                                TextButton(onClick = { actions.deleteProactiveTask(task.scenario) }) { Text("删除") }
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        Text("记忆点跨端同步", style = MaterialTheme.typography.titleMedium)
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
                        Text("远程 MCP", style = MaterialTheme.typography.titleMedium)
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
                        Text("远端 Agent（A2A）", style = MaterialTheme.typography.titleMedium)
                        Text("跨设备执行", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(deviceEndpointDraft, { deviceEndpointDraft = it }, label = { Text("设备服务地址") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(deviceNameDraft, { deviceNameDraft = it }, label = { Text("本设备名称") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(deviceTokenDraft, { deviceTokenDraft = it }, label = { Text("设备令牌（留空复用）") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(deviceAcceptsDraft, { deviceAcceptsDraft = it })
                            Text("允许本设备在前台接收任务")
                        }
                        Text(state.deviceStatus)
                        Text("任务使用独立上下文；工具权限仍需本机确认。清空地址可停用。")
                        Button(onClick = {
                            actions.configureDevices(deviceEndpointDraft, deviceTokenDraft, deviceNameDraft, deviceAcceptsDraft)
                            deviceTokenDraft = ""
                        }) { Text("保存设备连接") }

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
                            TextButton(onClick = { if (agent.enabled) actions.disableA2aAgent(agent.id) else actions.addA2aAgent(agent.cardUrl) }) { Text(if (agent.enabled) "停用" else "启用") }
                        }
                    } }
                    state.activeSessionId == null -> {
                        Text("正在加载对话…")
                    }
                    else -> {
                        if (state.a2aTasks.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("远端任务 ${state.a2aTasks.count { !it.terminal }} 个进行中")
                                Row {
                                    TextButton(onClick = {
                                        uiScope.launch {
                                            listState.animateScrollToItem(
                                                state.messages.size + if (state.streamedText.isNotEmpty()) 1 else 0,
                                            )
                                        }
                                    }) { Text("查看") }
                                    TextButton(onClick = actions::refreshA2aTasks) { Text("刷新") }
                                }
                            }
                        }
                        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                            items(state.messages) { message ->
                                Text(
                                    text = "${message.role}：${message.content}",
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                )
                            }
                            if (state.streamedText.isNotEmpty()) {
                                item { Text(state.streamedText) }
                            }
                            items(state.a2aTasks) { task ->
                                val agentName = state.a2aAgents.firstOrNull { it.id == task.agentId }?.name ?: task.agentId
                                A2aTaskCard(task, agentName, actions)
                            }
                        }
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            label = { Text("输入消息") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row {
                            Button(
                                enabled = !state.sending && draft.isNotBlank(),
                                onClick = { actions.send(draft.trim()); draft = "" },
                            ) { Text("发送") }
                            if (state.sending) TextButton(onClick = actions::cancel) { Text("停止") }
                        }
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
    Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), tonalElevation = 3.dp) {
        Column(Modifier.padding(12.dp)) {
            Text("远端任务 · $agentName", style = MaterialTheme.typography.titleSmall)
            Text(task.requestText)
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
            })
            task.question?.let { Text("远端请求：$it") }
            if (task.state == "TASK_STATE_AUTH_REQUIRED") {
                Text("请按远端说明完成授权；访问令牌可在设置中为该 Agent 单独保存。")
            }
            task.result?.let { Text("远端结果：$it") }
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
