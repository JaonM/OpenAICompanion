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

macOS、iOS、Android 均接入共享设备服务。macOS 的设置中进入“跨设备执行与远端任务”；手机在设置中配置“跨设备执行”，原有远端任务卡支持查看、回复和取消。配置只有地址和设备名，秘密令牌使用现有 Keychain / Android 加密存储。开启接单是执行端的显式授权；手机接单要求 App 前台，Mac 支持常驻 worker。后台执行只允许文本推理和宿主明确允许的公共只读工具。端侧工具扩展默认关闭，可单独开启；调用仍检查系统权限、前台状态和逐次确认。

能力来自当前注册的工具名，加上 `model.complete`。没有摄像头、Shell 或项目构建工具时，设备不会宣称支持这些操作。第一版宿主的资源目录为空：包含 `resource_refs` 的请求会被拒绝路由，直到宿主实现可信资源登记和访问校验；不能凭自然语言路径猜测资源位置。

每个任务固定一台设备。设备独立执行，不读取接收设备的本地持续对话、长期记忆或 persona，不向前台聊天发送流式事件，不再委托第三台设备。执行只允许接单时的工具快照；原有工具策略和真实前台状态继续由目标宿主验证。本地聊天、远端执行与自动追问共享执行锁，本地停止生成不会取消远端任务；等待执行锁的本地请求被取消后不会继续启动。

## 部署与配对

生产入口是 `scripts/companion_service.py`，统一提供任务网关、记忆同步和可撤销设备身份，三份 SQLite 数据库独立保存。`device_task_server.py` 和 `memory_sync_server.py` 的独立 HTTP 启动器保留为开发、协议测试入口；环境变量静态令牌不用于生产身份管理。

### HTTPS 服务

仓库提供 Uvicorn + Caddy 部署配置：

```sh
export COMPANION_DOMAIN=tasks.example.com
# 从秘密管理器注入随机 COMPANION_METRICS_TOKEN；不要提交到仓库。
docker compose -f deploy/service/compose.yaml config --quiet
docker compose -f deploy/service/compose.yaml up -d --build
```

域名需解析到部署主机，80/443 可达；Caddy 管理 TLS，应用端口只开放在容器网络。服务使用非 root 用户、持久化数据卷、只读根文件系统、资源限制和健康检查。对外 URL 必须与证书及客户端填写的地址一致。部署前应使用加密磁盘和受控备份存储；数据库尚未加密，也未实现端到端加密。

初次由运维签发本用户的第一台设备凭据：

```sh
docker compose -f deploy/service/compose.yaml exec -T companion \
  python service_credentials.py --db /data/credentials.sqlite --user user-1 \
  issue --device mac-001 --scopes tasks memory
```

明文令牌只返回一次，存入设备 Keychain / Android 加密存储。Mac 配置服务地址和令牌后，可为新设备生成有效期 10 分钟的一次性配对码；手机输入服务地址、配对码和设备名。服务端原子消费配对码，设备 ID 和用户身份由凭据决定。配对继承签发设备的权限，不提升权限。同一用户的 Mac 设置中可列出设备访问权限并撤销，服务后续请求即时拒绝已撤销令牌。没有区分普通设备和管理员设备：持有本用户有效凭据的设备均可管理本用户凭据，适用于个人可信设备部署。

保存连接后最多等待 15 秒完成注册、发现，另一设备自动出现在 A2A Agent 列表。同一身份不能通过配对码重新绑定；令牌轮换由 `POST /v1/credentials/rotate` 完成，旧令牌立即失效。轮换或配对响应丢失需要运维重新签发凭据，不自动重放。撤销凭据不会撤回已经开始的系统副作用。

本机开发可使用虚拟环境：

```sh
python3 -m venv .venv-service
.venv-service/bin/pip install -r scripts/requirements-service.txt
export COMPANION_SERVICE_DATA=/secure/path/companion-service
export COMPANION_SERVICE_URL=http://127.0.0.1:9444
.venv-service/bin/python scripts/companion_service.py
```

服务具备流式请求体限制、请求超时、按凭据限流、配对限流及并发上限。`GET /healthz` 和 `/readyz` 用于探活；`GET /metrics` 需要独立指标令牌，返回进程内请求计数和耗时总量。重启后指标清零。结构化日志只记录请求 ID、路由、状态和耗时，不记录令牌、任务正文、工具参数或个人记忆。当前单实例部署限流不跨进程共享，任务保留期限和磁盘配额仍需上线前配置与压测。

### Mac 自动接单

先通过桌面 UI 配好模型服务、设备连接和接单开关，再退出 UI，运行已打包应用的 worker：

```sh
/Applications/OpenAICompanion.app/Contents/MacOS/OpenAICompanion --worker
```

`deploy/macos/com.openai.companion.worker.plist` 是可供运维安装的 LaunchAgent 模板，未自动安装。默认共享桌面数据目录；文件锁禁止 UI 和 worker 同时使用同一份执行状态，打开 UI 前先停止 worker。worker 不展示窗口，持续注册、领取和回传任务，仍需本机在线、模型服务可用。日历与需确认的远程工具要求前台，后台不会弹出确认或自动写入。手机无需监听入站端口；挂起或退出后不持续接单，后台文本任务应路由到在线 Mac worker。

### 数据维护与升级

`service_admin.py` 提供 `backup`、`export`、`delete-user`，`--data` 指向包含三份数据库的服务目录。备份使用 SQLite online backup 并校验完整性，包含已提交 WAL 内容；不能直接复制运行中的 `.sqlite` 文件。备份应加密保存，恢复前停服务并完整替换数据库集合。在线备份逐库进行，不承诺跨三份数据库的一致快照；需要一致快照时先停止服务。

离线导出/删除使用如下命令，执行账号必须有数据目录权限：

```sh
python3 scripts/service_admin.py --data /secure/path/companion-service \
  export --user user-1 --output /secure/path/user-1.json --service-stopped
python3 scripts/service_admin.py --data /secure/path/companion-service \
  delete-user --user user-1 --confirm-user user-1 --service-stopped
```

`--service-stopped` 是操作者确认，脚本不会自动停止或检测服务；容器部署时也需先停止应用，再由能访问持久卷的维护容器执行。导出不包含令牌或配对码散列。删除先撤销访问，再删除该用户任务、记忆及目录数据，可在中断后重试；保留已撤销凭据的散列和身份记录以阻止旧身份被配对复用。应用端数据、历史导出与备份需分别清理，尚无 App 内统一数据管理入口。

服务数据库使用 `PRAGMA user_version` 迁移，历史版本 0 可原地升级到 1，拒绝启动比当前服务更新的数据库。Rust 本地数据库同样拒绝未知未来版本；A2A 老表兼容新增持久化消息 ID。升级前备份，回滚使用旧程序及升级前备份，不能直接用旧程序打开新库。

## 协议与多轮交互

网关按设备提供：

- `GET /agents/<device_id>/card`：需要认证的 A2A Agent Card。
- `POST /agents/<device_id>/rpc`：A2A 1.0 JSON-RPC `SendMessage`、`GetTask`、`CancelTask`。
- `GET /v1/devices`：同一用户的能力与在线状态。
- `POST /v1/devices/register`：仅更新令牌所对应设备的描述和心跳。
- `POST /v1/devices/claim`：原子领取一个任务，返回执行 attempt 和任务 checkpoint。
- `POST /v1/devices/heartbeat`：续租当前 attempt。
- `POST /v1/devices/finish`：提交该 attempt 的结果和 checkpoint。
- `POST /v1/devices/abandon`：进程恢复时将未完成 attempt 标记为结果未知。
- `companion/GetMessageReceipt`：网关声明恢复能力后，按持久化 `messageId` 查询已接收消息对应的任务。

A2A 客户端显式设置 `configuration.returnImmediately=true`，提交后轮询任务；网关也支持未设置该字段时等待终止或中断状态的标准阻塞行为。`messageId` 在同一用户/目标设备下去重；相同 ID 和消息重放原任务，不同消息复用 ID 被拒绝。

目标 Harness 的最终输出格式为 `{"state":"completed|input_required|failed","text":"..."}`。需要补充输入时返回 `TASK_STATE_INPUT_REQUIRED`，用户在任务卡回复；客户端携带原 `taskId/contextId` 发送消息，网关重新排队同一任务。checkpoint 保存该任务的模型消息、工具调用和结果，下一段执行沿用，避免把已执行工具当成新任务重跑。不自动附带发起设备的完整对话。发起端可自动回答原委托中已明确的事实：答案必须是原委托的逐字引用，不读取个人记忆或其他聊天；新选择和授权请求由用户回复。每个任务最多自动处理三次不同追问，已处理的问题在推理和发送前持久化，重启后不重复回复。

结果由任务卡展示，并通过已有 A2A 最近结果机制供之后本地对话使用。后台任务结果不会主动生成一轮新的聊天回复。第一版不支持 SSE、推送、文件传输或多设备任务拆分。

## 故障与取消

- 领取在 SQLite 事务内完成；同一设备同时只领取一个运行中任务。
- 执行每 20 秒续租，租期 120 秒；失联后标记 `executionUnknown`，本地任务显示 `EXECUTION_UNKNOWN` 并继续查询。任务不会自动回收、重派或重新执行。原 attempt 的结果可解除不确定状态。
- 执行完成后，先原子保存本机待回传结果，再上传。上传失败或进程重启后只重传结果，不重新运行 Harness。
- 新建日历等副作用继续使用现有设备操作日志。该日志不跨设备同步，不宣称无条件 exactly-once。
- 排队中和等待输入的任务可以取消；正在执行的任务返回 `TaskNotCancelableError`，不会假装已经撤销系统写入。端侧工具已经执行的效果不会被任务取消回滚。
- 发送或回复结果未知时沿用已有 A2A 恢复策略，不自动重发。首条提交和回复的 `messageId` 在发送前持久化；本网关通过消息回执恢复任务 ID 和最新状态，不重新执行提交。普通 A2A Agent 未声明此恢复扩展时，仍需人工核对。
- 执行中、存在待回传结果或待确认的执行 attempt 时禁止切换网关配置，以免丢失结果归属。

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

HTTP 集成测试覆盖两设备提交、追问、回复、回传和用户隔离；存储测试覆盖并发接单、重启、不确定状态和幂等。KMP 测试覆盖本地优先、指定设备、资源匹配、前台条件、不同源令牌保护、回传恢复、有限自动回复和取消归属。Harness 绑定测试验证任务不读取本地对话、不向聊天发送事件，回复后保留工具结果且不重复调用。新增生产服务测试覆盖凭据撤销与轮换、并发配对、消息回执、导出删除隔离、请求边界及真实 Uvicorn 启停。JVM 测试覆盖后台路由、进程中断不重跑、损坏状态拒绝覆盖和原子文件替换。实际两设备 UI、后台挂起和网络切换仍需真机验收。
