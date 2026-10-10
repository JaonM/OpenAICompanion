package com.openai.companion.kmp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shared user-facing guidance; opening help never saves or changes connection settings. */
@Composable
fun DeviceConfigurationHelp() {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("配置说明") }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("跨设备执行 · 配置说明") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HelpSection("1. 准备设备服务", "跨设备执行需要独立部署的设备服务；安装 App 不会自动部署服务。服务可以部署在服务器或一台常在线的电脑上，由管理员提供 HTTPS 地址和设备凭据。所有参与设备都必须能访问这个地址，且信任其证书。")
                HelpSection("2. 填写连接信息", "设备服务地址填写服务根地址，例如 https://tasks.example.com，不是模型接口或 Agent Card 地址。本设备名称用于识别和指定执行设备，建议各设备名称不同。首次接入在高级设置填写管理员签发的设备令牌，再保存设备连接；留空令牌会复用已保存的凭据。每台设备使用各自的令牌，不要复制另一台设备的身份。")
                HelpSection("3. 连接更多设备", "新设备可使用管理员单独签发的令牌，或使用同一用户已连接设备提供的一次性配对码。当前 App 的配对码生成入口在桌面端“添加另一台设备”。在新设备填写相同服务地址、设备名称及配对码，点击“使用配对码连接”；配对码 10 分钟有效且仅能使用一次，无需再填令牌。不要公开分享令牌或配对码。")
                HelpSection("4. 决定是否接单", "只发起委托的设备无需开启接单。希望替其他设备执行任务时，开启“允许本设备接收任务”并保存，配置可用的推理引擎和模型。实际可执行能力取决于模型、已配置工具及系统权限；接单开关不会授予所有工具权限。手机仅在 App 前台持续接单，挂起或退出后不能保证执行；Mac 后台接单需另行启动常驻 worker，并保持在线和模型服务可用。需要用户确认或前台权限的工具仍需在目标设备处理。")
                HelpSection("5. 发起与查看任务", "保存后等待设备注册和发现，通常最多约 15 秒。在对话中描述任务，必要时指定“委托给设备名称：任务内容”，核对委托确认后发送。普通问答默认本地推理，需要其他设备能力时才委托。进度、补充信息请求和结果在远端任务卡查看；任务使用独立上下文，请在委托中说明必要信息，完整聊天记录和文件不会自动传过去。")
                HelpSection("6. A2A 何时配置", "同一设备服务下的设备会自动接入 A2A，不必逐台填写 Agent Card。只有连接独立的第三方智能体时，才进入“远端 Agent（A2A）”，填写提供方的 Agent Card 地址；若要求认证，再配置其访问令牌。第三方智能体凭据与设备服务令牌分别管理。")
                HelpSection("连接失败怎么办", "先检查各设备能否访问服务地址及证书是否可信。localhost / 127.0.0.1 只指向当前设备，手机不能用它访问另一台电脑；局域网服务需网络互通并允许 App 的本地网络访问。认证失败请检查设备令牌是否被撤销，配对失败请重新生成有效配对码。目标设备不可用时检查其在线状态、接单开关、前台或 worker 状态及模型配置。清空地址并保存可停用连接；有执行中任务时先处理任务再切换服务。")
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("关闭") } },
    )
}

@Composable
private fun HelpSection(title: String, description: String) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    Text(description, style = MaterialTheme.typography.bodySmall)
}
