package com.openai.companion.kmp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Desktop settings and remote task interactions; mobile uses its existing task cards. */
@Composable
fun DeviceExecutionPanel(service: CrossDeviceService, client: A2aClient) {
    val scope = rememberCoroutineScope()
    val status by service.status.collectAsState()
    val tasks by client.tasks.collectAsState()
    val agents by client.agents.collectAsState()
    var endpoint by remember { mutableStateOf(service.endpoint) }
    var name by remember { mutableStateOf(service.deviceName) }
    var token by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var inviteDeviceId by remember { mutableStateOf("device-" + kotlin.random.Random.nextLong().toULong().toString(16)) }
    var advanced by remember { mutableStateOf(false) }
    var issuedCode by remember { mutableStateOf("") }
    var credentials by remember { mutableStateOf<kotlinx.serialization.json.JsonArray?>(null) }
    var accepts by remember { mutableStateOf(service.acceptsTasks) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun action(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            try { block(); error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message }
            finally { busy = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("我的设备 · 跨设备执行", style = MaterialTheme.typography.titleLarge)
        Text("普通问答在本机完成。需要远端时，在对话中说“委托给设备名称：任务内容”，确认后发送，结果回到对话。")
        Text("参与协作的设备连接同一个 HTTPS 设备服务，并使用同一用户下各自的凭据。连接后自动发现，无需重复配置 A2A。", style = MaterialTheme.typography.bodySmall)
        DeviceConfigurationHelp()
        OutlinedTextField(endpoint, { endpoint = it }, label = { Text("设备服务地址") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(name, { name = it }, label = { Text("本设备名称") }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级设置" else "高级设置（首台设备接入与权限）") }
        if (advanced) {
            OutlinedTextField(token, { token = it }, label = { Text("设备令牌（留空复用）") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Text("设备令牌由服务管理员提供；其他设备使用配对码即可。", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(pairingCode, { pairingCode = it }, label = { Text("一次性配对码") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedButton(enabled = !busy && pairingCode.isNotBlank(), onClick = { action {
            service.pair(endpoint, pairingCode, name, accepts); pairingCode = ""
        } }) { Text("使用配对码连接") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(accepts, { accepts = it }); Text("允许本设备接收任务（后台仅限无需确认的能力）")
        }
        Text(status)
        Text("远端任务使用独立上下文；工具权限仍需本机确认。清空地址可停用。")
        Button(enabled = !busy, onClick = { action { service.configure(endpoint, token, name, accepts); token = "" } }) { Text("保存设备连接") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        HorizontalDivider()
        Text("添加另一台设备", style = MaterialTheme.typography.titleMedium)
        if (advanced) OutlinedTextField(inviteDeviceId, { inviteDeviceId = it }, label = { Text("新设备 ID（字母、数字、下划线或短横线）") })
        OutlinedButton(enabled = !busy && inviteDeviceId.isNotBlank(), onClick = { action {
            issuedCode = service.createPairingCode(inviteDeviceId)
            inviteDeviceId = "device-" + kotlin.random.Random.nextLong().toULong().toString(16)
        } }) { Text("生成 10 分钟配对码") }
        if (issuedCode.isNotBlank()) androidx.compose.foundation.text.selection.SelectionContainer { Text("服务地址：${service.endpoint}\n配对码：$issuedCode\n10 分钟内在另一台设备输入；请勿公开分享。") }
        if (advanced) TextButton(enabled = !busy, onClick = { action { credentials = service.credentials() } }) { Text("查看设备访问权限") }
        credentials?.forEach { entry ->
            val record = entry as kotlinx.serialization.json.JsonObject
            val id = record["id"] as kotlinx.serialization.json.JsonPrimitive
            val device = record["device_id"] as kotlinx.serialization.json.JsonPrimitive
            if (record["revoked"] == kotlinx.serialization.json.JsonNull) Row {
                Text(device.content, modifier = Modifier.weight(1f))
                TextButton(enabled = !busy, onClick = { action {
                    service.revokeCredential(id.content)
                    credentials = kotlinx.serialization.json.JsonArray(credentials.orEmpty().filter { it != entry })
                } }) { Text("撤销访问") }
            }
        }
        Text("已配对设备")
        agents.forEach { agent ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(agent.name, modifier = Modifier.weight(1f))
                TextButton(enabled = !busy, onClick = { action { if (agent.enabled) client.disableAgent(agent.id) else client.addAgent(agent.cardUrl) } }) { Text(if (agent.enabled) "停用委托" else "启用委托") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("远端任务", modifier = Modifier.weight(1f))
            TextButton(enabled = !busy, onClick = { action { client.refresh() } }) { Text("刷新") }
        }
        tasks.forEach { task ->
            var reply by remember(task.id) { mutableStateOf("") }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(agents.firstOrNull { it.id == task.agentId }?.name ?: task.agentId)
                    Text(task.requestText)
                    Text(task.state)
                    task.result?.let { Text(it) }
                    task.question?.let { Text(it) }
                    if (task.state == "TASK_STATE_INPUT_REQUIRED") {
                        OutlinedTextField(reply, { reply = it }, label = { Text("补充信息") })
                        Button(enabled = !busy && reply.isNotBlank(), onClick = { action { client.reply(task.id, reply); reply = "" } }) { Text("回复并继续") }
                    }
                    if (!task.terminal && task.remoteTaskId != null) {
                        TextButton(enabled = !busy, onClick = { action { client.cancel(task.id) } }) { Text("请求取消") }
                    }
                }
            }
        }
    }
}
