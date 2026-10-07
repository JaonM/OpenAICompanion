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
        Text("跨设备执行", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(endpoint, { endpoint = it }, label = { Text("设备服务地址") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(name, { name = it }, label = { Text("本设备名称") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(token, { token = it }, label = { Text("设备令牌（留空复用）") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(accepts, { accepts = it }); Text("允许本设备在前台接收任务")
        }
        Text(status)
        Text("远端任务使用独立上下文；工具权限仍需本机确认。清空地址可停用。")
        Button(enabled = !busy, onClick = { action { service.configure(endpoint, token, name, accepts); token = "" } }) { Text("保存设备连接") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        HorizontalDivider()
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
