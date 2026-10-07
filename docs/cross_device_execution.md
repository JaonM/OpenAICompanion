# 跨设备任务执行（v1）

用户请求默认由发起设备的 Harness 处理。普通聊天不经过网关；需要设备动作时，模型使用 `list_execution_devices` 获取真实能力，再使用 `route_task` 检查能力、资源、指定设备和可用性。`LOCAL` 继续本地执行，`REMOTE` 使用已有 `delegate_to_agent` 提交；`NEEDS_USER_ACTION` 要求打开目标 App 或开启接单，`WAITING` 可明确委托排队，`UNSUPPORTED` 不能假装执行成功。

## 架构与边界

```text
发起设备 Harness
  -> 路由工具（KMP CrossDeviceService）
  -> A2aClient + 原有委托确认
  -> HTTPS A2A 网关 + SQLite 任务队列
  -> 目标设备主动领取任务
  -> app_execute_device_task（独立 Rust Agent Loop）
  -> 本机工具 / 已配置 MCP（原有权限确认）
  -> 持久化待回传结果 -> 网关 -> 发起设备任务卡
```

macOS、iOS、Android 均接入共享设备服务。macOS 的设置中进入“跨设备执行与远端任务”；手机在设置中配置“跨设备执行”，原有远端任务卡支持查看、回复和取消。配置只有地址和设备名，秘密令牌使用现有 Keychain / Android 加密存储。开启“允许本设备在前台接收任务”是执行端的显式授权；仍不会跳过每次工具调用的权限和确认。

能力来自当前注册的工具名，加上 `model.complete`。没有摄像头、Shell 或项目构建工具时，设备不会宣称支持这些操作。第一版宿主的资源目录为空：包含 `resource_refs` 的请求会被拒绝路由，直到宿主实现可信资源登记和访问校验；不能凭自然语言路径猜测资源位置。

每个任务固定一台设备。设备独立执行，不读取接收设备的本地持续对话、长期记忆或 persona，不向前台聊天发送流式事件，不再委托第三台设备。执行只允许接单时的工具快照；原有工具策略和真实前台状态继续由目标宿主验证。本地聊天、远端执行与自动追问共享执行锁，本地停止生成不会取消远端任务；等待执行锁的本地请求被取消后不会继续启动。

## 部署与配对

启动独立网关（可与记忆服务同机部署，但数据库和接口独立）。为每台设备生成不同的高熵令牌，将以下结构放入受限权限的进程环境文件中，由进程管理器注入 `DEVICE_TASK_TOKENS`：

```json
{
  "<手机令牌>": {"user_id": "user-1", "device_id": "phone-001"},
  "<Mac令牌>": {"user_id": "user-1", "device_id": "mac-001"}
}
```

```sh
python3 scripts/device_task_server.py \
  --db /secure/path/device-tasks.sqlite \
  --base-url https://tasks.example.com:9444 \
  --cert /secure/path/fullchain.pem --key /secure/path/privkey.pem \
  --host 0.0.0.0 --port 9444
```

`--base-url` 必须是客户端实际访问的地址，Agent Card 中的 RPC 地址由它生成。客户端禁止跳转，并拒绝把设备令牌发送到与 Card 不同源的接口。服务直接部署需要 TLS；当前启动器只允许无证书的回环开发 URL。

在两端设置中填同一服务地址，各填自己的设备令牌和可辨识名称。在 Mac 勾选接单并保持 App 前台，手机可只作为发起端。保存后最多等待 15 秒完成注册和发现；另一设备的 Agent 会自动进入已配置 A2A Agent 列表。设备 ID 由服务端凭据决定，模型和客户端请求不能冒充另一设备。不同用户的目录和任务隔离。移除令牌并重启网关可撤销设备访问。

不需要端侧监听端口，也不需要开放手机或 Mac 的入站公网连接。手机被挂起后不会持续接单，恢复运行后继续。

## 协议与多轮交互

网关按设备提供：

- `GET /agents/<device_id>/card`：需要认证的 A2A Agent Card。
- `POST /agents/<device_id>/rpc`：A2A 1.0 JSON-RPC `SendMessage`、`GetTask`、`CancelTask`。
- `GET /v1/devices`：同一用户的能力与在线状态。
- `POST /v1/devices/register`：仅更新令牌所对应设备的描述和心跳。
- `POST /v1/devices/claim`：原子领取一个任务，返回执行 attempt 和任务 checkpoint。
- `POST /v1/devices/heartbeat`：续租当前 attempt。
- `POST /v1/devices/finish`：提交该 attempt 的结果和 checkpoint。

A2A 客户端显式设置 `configuration.returnImmediately=true`，提交后轮询任务；网关也支持未设置该字段时等待终止或中断状态的标准阻塞行为。`messageId` 在同一用户/目标设备下去重；相同 ID 和消息重放原任务，不同消息复用 ID 被拒绝。

目标 Harness 的最终输出格式为 `{"state":"completed|input_required|failed","text":"..."}`。需要补充输入时返回 `TASK_STATE_INPUT_REQUIRED`，用户在任务卡回复；客户端携带原 `taskId/contextId` 发送消息，网关重新排队同一任务。checkpoint 保存该任务的模型消息、工具调用和结果，下一段执行沿用，避免把已执行工具当成新任务重跑。不自动附带发起设备的完整对话。发起端可自动回答原委托中已明确的事实：答案必须是原委托的逐字引用，不读取个人记忆或其他聊天；新选择和授权请求由用户回复。每个任务最多自动处理三次不同追问，已处理的问题在推理和发送前持久化，重启后不重复回复。

结果由任务卡展示，并通过已有 A2A 最近结果机制供之后本地对话使用。后台任务结果不会主动生成一轮新的聊天回复。第一版不支持 SSE、推送、文件传输或多设备任务拆分。

## 故障与取消

- 领取在 SQLite 事务内完成；同一设备同时只领取一个运行中任务。
- 执行每 20 秒续租，租期 120 秒；失联后标记 `executionUnknown`，本地任务显示 `EXECUTION_UNKNOWN` 并继续查询。任务不会自动回收、重派或重新执行。原 attempt 的结果可解除不确定状态。
- 执行完成后，先原子保存本机待回传结果，再上传。上传失败或进程重启后只重传结果，不重新运行 Harness。
- 新建日历等副作用继续使用现有设备操作日志。该日志不跨设备同步，不宣称无条件 exactly-once。
- 排队中和等待输入的任务可以取消；正在执行的任务返回 `TaskNotCancelableError`，不会假装已经撤销系统写入。端侧工具已经执行的效果不会被任务取消回滚。
- 发送或回复结果未知时沿用已有 A2A 恢复策略，不自动重发。首条提交若未收到任务 ID，仍需人工核对远端任务。
- 执行中或存在待回传结果时禁止切换网关配置，以免丢失结果归属。

网关会保存委托正文、结果和工具 checkpoint；当前未实现数据库加密和端到端加密。工具的访问令牌、模型凭据和完整本地会话不会随任务自动上传。

## 验证

```sh
python3 -m unittest discover -s scripts -p test_device_task_server.py
cargo test --manifest-path harness/Cargo.toml
```

KMP 测试及 iOS 编译：

```sh
cargo build --release --manifest-path harness/Cargo.toml
cd kmp
HARNESS_LIBRARY_PATH="$PWD/../harness/target/release/libharness.dylib" \
  ./gradlew -PkmpJvmToolchain=21 -PskipAndroidApp=true \
  jvmTest :iosApp:compileKotlinIosSimulatorArm64
```

HTTP 集成测试覆盖两设备提交、追问、回复、回传和用户隔离；存储测试覆盖并发接单、重启、不确定状态和幂等。KMP 测试覆盖本地优先、指定设备、资源匹配、前台条件、不同源令牌保护、回传恢复、有限自动回复和取消归属。Harness 绑定测试验证任务不读取本地对话、不向聊天发送事件，回复后保留工具结果且不重复调用。实际两设备 UI、后台挂起和网络切换仍需真机验收。
