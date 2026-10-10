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

### App 内配置说明

三端的跨设备执行设置均提供“配置说明”按钮，点击打开可滚动的详细说明，关闭后返回原设置；查看说明不会保存或修改连接。说明覆盖服务准备、地址与设备身份、令牌或一次性配对、接单条件、任务结果、第三方 A2A 和连接排查。流程不要求特定的发起设备或执行设备；当前配对码生成入口在桌面端，新设备也可使用管理员单独签发的凭据接入。

2026-10-10 已更新验收 iPhone 的签名安装包，真机 `testCrossDeviceConfigurationHelp` 通过：设置入口可见且可点击，说明可打开、滚动至故障排查并关闭返回。桌面和 iOS 编译通过；Android 构建和该弹窗的 Android 真机显示仍待验收。

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

## 2026-10-07 本机验收记录

本次验收使用独立数据库、一次性回环服务、专用 Keychain 测试条目和新建的 `Companion Acceptance` 模拟器，不读写私人日历。结果只对已运行的平台及场景有效。

测试数量、运行结果与未覆盖范围保存在[结构化验收结果](../scripts/acceptance/results-2026-10-07.json)。

| 能力 | 实际证据 | 结论与边界 |
| --- | --- | --- |
| Harness 对话、记忆、任务与策略 | Rust 59 项回归通过 | 逻辑与持久化通过；不代表真实模型质量评测 |
| MCP、A2A、记忆同步、路由与恢复 | JVM 全部 114 项通过，零跳过；真实 MCP 服务链路开启 | 协议及原生桥接通过；真实手机与 Mac 联动仍待验收 |
| Keychain | 真正执行读、写、更新、删除及非交互读取 | 本机通过；真机安全存储和发布签名身份变化仍待验收 |
| Mac worker | 最新 DMG 打包；真实应用进程 + Uvicorn + Rust + Keychain 自动接单、回传、重启防重跑及进程互斥 | 通过；模型使用确定性 HTTP 测试响应，未安装 LaunchAgent，也未验证睡眠/登录重启 |
| 服务凭据与数据维护 | Python 22 项通过，含 Uvicorn 启停、配对撤销、消息回执、导出删除及三库备份恢复 | 本机通过；未进行公网、容量及长期运行验收 |
| iOS App | 最新模拟器 App 完整构建和真机目标无签名构建 | 编译/链接通过；真实设备未连接 |
| iOS 基础 UI | 会话与设置重启恢复、MCP 连接、真实 GGUF 回复 3 项通过，无跳过 | iOS 27 模拟器通过；小模型只验证能回复，不代表质量达标 |
| iOS 后台提醒 | 允许通知后终止 App，SpringBoard 实际显示一次性计划提醒；1 项通过，无跳过 | 模拟器链路通过；重复时间、夏令时、专注模式及真实设备省电行为仍待验收 |
| 端侧工具扩展 | iOS 模拟器实际切换关闭→开启→关闭，工具数 5→9→5；1 项通过，无跳过 | 开关及注册通过；日历授权、查询和写入仅有共享契约/桥接测试，真实账户待验收 |
| Android | 当前无 Android SDK 或验收设备，用户确认暂不提供 | 构建、后台排程与端到端待验收 |
| 容器与 TLS | Compose 配置校验通过，尝试实际构建镜像 | 拉取 `python:3.13-slim` 在 Docker Hub 认证请求处超时；容器、TLS 部署未通过验收 |

验收中修复了 Apple/Desktop 构建误依赖 Android SDK、worker 无窗口模式等待 UI 主线程、后台读取 Keychain 可能等待授权 UI 的问题。MCP 用例未开启时现在明确标记跳过，避免无操作却计为通过。iOS MCP UI 断言按远程 3 个工具 + 路由 2 个工具更新；扩展额外注册设备上下文、日历查询、日历目录和新建四个工具。

iOS 链接仍报告 SQLite 对象最低版本为 27.0、ICU 为 17.2，而 App 声明 17.0；本次只运行 iOS 27 模拟器，不能声称兼容 17.0。上线前应修正部署版本配置并在最低支持系统验收。

### 真机验收进展（2026-10-07 / 2026-10-08）

后续已连接 iPhone 17 Pro Max / iOS 27.0，用户启用开发者模式并完成 Personal Team 签名与开发者 App 信任。已签名安装 App；基础会话/设置重启恢复 1 项、真实 SmolLM2 GGUF 新回复 1 项通过，均无跳过。模型用例增加发送前输入检查，并要求出现新的助手回复；首次第三方键盘未接收自动化输入，数据库没有请求记录，切换系统键盘后通过。

Mac 已部署仅使用测试数据库的局域网 HTTPS 服务。Mac 客户端通过 CA、IP 地址校验，未认证设备查询返回 401；真实打包 worker 使用独立 Preferences、Keychain 条目和进程专用信任库，调用本机 Qwen 模型完成文本预检并回传 `42`，重启后任务状态、执行 attempt 与结果保持不变。这些结果不等于手机与 Mac 的任务往返通过。

**手机 HTTPS 配对和注册已成功，但手机任务执行未通过。** 用户开启测试根证书完全信任后，实际 App 配对请求和设备注册成功，未绕过证书校验；UI 用例随后因设备名称断言失败，已修正待重验。服务端提交的真实手机任务被领取并执行，但 SmolLM2 返回普通文本而非规定的任务状态 JSON，任务最终为 `TASK_STATE_FAILED`。未放宽任务状态校验。iPhone→Mac 委托、结果任务卡、追问、网络切换和睡眠恢复继续待验收。

2026-10-08，按用户指定切换为 [Qwen/Qwen3-0.6B-GGUF](https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/blob/main/Qwen3-0.6B-Q8_0.gguf) 官方 Q8_0 文件（639,446,688 字节），镜像下载后核对官方 SHA256 通过。已传入手机私有模型目录，默认模型选择持久保存并读回确认；签名构建、安装、App 启动均通过。iOS 适配使用 Qwen3 官方非思考模式的生成前缀，提示词测试通过。手机当前为 iOS 27.0.1，前两次 XCTest 在启用自动化模式时超时；用户完成开发者自动化设置后已恢复进入 App。第三方键盘造成的发送前输入失败已通过切换系统键盘解决，失败时数据库确认没有模型请求。Qwen3 真机生成冒烟测试通过（1 通过、0 失败、0 跳过），但对“Reply with hi.”返回中文问候，未严格遵循指定措辞，此结果不代表模型质量验收通过。接着配对重验收到 iOS `Local network prohibited`，服务端没有收到新配对请求；补齐 `NSLocalNetworkUsageDescription` 后签名构建、安装通过，用户开启权限后实际配对与注册成功。真实手机任务被领取执行并回传，Qwen3 算出 `42`，却返回 `{"completed":true,"input_required":false,"text":"42"}`，缺少规定的字符串 `state` 字段，最终任务为 `TASK_STATE_FAILED`。任务状态严格校验保持不变。手机执行验收未通过，手机发起路由及结果卡继续待验收。

真机配对用例是显式 fixture 验收：将独立服务签发的 `{"endpoint":"https://...","code":"一次性配对码"}` 写入测试 Runner 容器的 `Documents/device-acceptance.json`，只运行 `testCrossDevicePairingAndForegroundExecution`；模型使用已校验的 `test-smollm2.gguf`。用例通过 UI 配对、重启恢复，再保持前台 120 秒供验收控制器发送任务。**该 UI 用例本身不验证远端结果，必须同时核对网关任务结果，二者都通过才能接受前台执行。** 缺少 fixture 时明确跳过。fixture、证书私钥和令牌只保存在受保护临时目录，不提交仓库；验收后应关闭临时服务并由用户移除手机测试证书。

### 复现打包 worker 验收

在仓库根目录准备服务环境、打包应用和隔离 Preferences 工厂，再执行测试：

```sh
python3 -m venv .venv-service
.venv-service/bin/pip install -r scripts/requirements-service.txt
./scripts/macos-app.sh package
mkdir -p /tmp/companion-acceptance-java
javac -d /tmp/companion-acceptance-java scripts/acceptance/AcceptancePreferencesFactory.java
jar cf /tmp/companion-acceptance-prefs.jar -C /tmp/companion-acceptance-java .
cd kmp
RUN_MCP_SMOKE_FIXTURE=1 COMPANION_TEST_KEYCHAIN=1 \
COMPANION_TEST_WORKER_APP="$PWD/build/compose/binaries/main/app/OpenAICompanion.app/Contents/MacOS/OpenAICompanion" \
COMPANION_TEST_PREFS_JAR=/tmp/companion-acceptance-prefs.jar \
COMPANION_TEST_SERVICE_PYTHON="$PWD/../.venv-service/bin/python" \
HARNESS_LIBRARY_PATH="$PWD/../harness/target/release/libharness.dylib" \
./gradlew -PkmpJvmToolchain=21 -PskipAndroidApp=true jvmTest
```

worker 用例只在显式配置应用路径时执行。工厂 JAR 是测试注入，隔离模型/设置并在应用本身身份下创建、清理测试凭据，不打进产品包，也不修改个人 Preferences。未配置验收开关的真实服务、worker 和 Keychain 用例应按跳过处理。

### 复现 iOS 退出后提醒验收

新建并启动名称以 `Companion Acceptance` 开头的专用模拟器，安装并启动最新版 App 一次。不要复用带有个人任务的实例。在仓库根目录执行：

```sh
python3 scripts/acceptance/prepare_ios_reminder.py --device <专用模拟器-UDID>
xcodebuild -project ios/OpenAICompanion.xcodeproj -scheme OpenAICompanion \
  -configuration Debug -destination 'platform=iOS Simulator,id=<专用模拟器-UDID>' \
  -parallel-testing-enabled NO -collect-test-diagnostics never CODE_SIGNING_ALLOWED=NO \
  -only-testing:OpenAICompanionUITests/OpenAICompanionUITests/testScheduledReminderSurvivesAppTermination test
```

准备脚本仅允许已启动的专用模拟器，拒绝存在其他任务的数据库。测试从设置开启主动推送、允许通知，再终止 App 并观察系统通知；缺少专用任务时明确跳过。通知权限必须允许，拒绝授权应视为该场景未验收。

2026-10-08 再次通过真实 HTTPS A2A 提交 `17 + 25`，Mac 打包 App 的后台 worker 使用实际本地模型完成执行；`GetTask` 返回 `TASK_STATE_COMPLETED`，任务结果与 artifact 均为 `42`。此结果不覆盖手机发起委托及结果任务卡 UI。

同日 Mac 真实 A2A 多轮交互通过：初始问候任务在缺少姓名时进入 `TASK_STATE_INPUT_REQUIRED`，以同一 taskId/contextId 提交姓名 Ada 后进入 `TASK_STATE_COMPLETED`，返回 `Hello, Ada!`。此验收覆盖 HTTPS API、实际 worker 和模型，不覆盖手机追问 UI。

手机 Qwen3 的自然语言指定 Mac 委托未通过：模型调用 `delegate_to_agent` 时把显示名称 `Acceptance Mac` 用作 `agent_id`，跳过设备发现和路由；真实 ID 为已注册的 Agent Card URL。工具正确拒绝未知 ID，手机数据库确认没有新远端任务。后续提供准确 ID 的受控链路测试不能代替此自动选址验收。

Mac 排队任务的 HTTPS A2A 取消 API 通过：`submitted → canceled`，后续 `GetTask` 保持 canceled；不覆盖执行中的中断和手机取消 UI。

提供准确 Agent ID 的受控手机委托已走通：实际手机显示委托确认并提交任务，Mac 完成后返回 `17 + 25 = 42`，手机任务数据库同步为 `TASK_STATE_COMPLETED` 且结果一致。首次 UI 用例仅接受 `42`，断言过窄；修正后再次发送同一请求，Qwen3 重复了旧任务回复而没有发起新委托，历史结果保护正确判为失败。结果卡展示将单独验证先前已由真实服务端核验的任务，不算作新任务调度或自动选址通过。

最终独立任务卡 UI 验收通过（1 通过、0 失败、0 跳过）：App 重启后点击“查看”，显示 Acceptance Mac、真实任务请求、已完成状态及 `17 + 25 = 42`。配对及重启连接 UI 用例也通过（1/0/0）。此结果覆盖先前已实际完成任务的展示，不代表新请求自动选址、手机执行状态格式或模型重复调度通过。**本轮跨设备整体验收仍未通过**，Qwen3-0.6B 当前适配下的上述模型行为仍需修复并重验；生产部署、真机提醒、网络/睡眠恢复、最低 OS 及 Android 等原有待验收项继续保留。

### 跨设备问题修复与复验（2026-10-08）

上述失败记录保留为历史证据。本轮修复了任务状态 JSON、显示名称寻址、历史结果复用，以及单轮重复委托和提前声称取得远端结果的问题：

- 设备执行请求携带严格状态 schema，iOS llama.cpp 使用语法约束生成 `state/text`；路由工具调用也约束为完整 JSON，并限制为已提供的工具名称。普通聊天的文本包装由适配器解包。状态解析继续拒绝布尔状态、未知状态、空文本和额外字段。
- Agent ID 优先按真实 ID 解析；仅对唯一已启用的显示名称做兼容。重名、未知名称和已停用 ID 均拒绝，确认与鉴权使用真实 ID。
- 同文委托的模型上下文排除旧提交及其复制回复，原始日志仍完整保存。收到有效委托回执后，执行框架直接确认“远端任务已提交，请在任务卡查看进度和结果。”并结束本轮本地生成；委托失败仍可修正。结果由远端任务卡异步展示。
- 真机用例核对新任务编号和对应结果；普通聊天按本次请求的消息标识验收，避免长对话虚拟列表的屏幕计数误判。执行期间会临时显示任务状态，配对用例等待其恢复为可接单状态。

最终签名版本在 iPhone 17 Pro Max / iOS 27.0.1 使用 Qwen3-0.6B Q8_0 复验通过。Mac 使用最新版独立打包 worker、隔离测试身份、局域网 HTTPS 和实际 Ollama 模型。自然语言指定 Acceptance Mac 的两次同文请求分别创建本地任务 6、7，对应远端任务 `cd9c88135a18454a8db67b8f0b14037d`、`bc8abdb074c440c994c451abfaf7b4db`；均完成并返回 `42`，手机展示各自的新结果卡。两轮各只有一次委托工具调用，聊天只确认提交。手机前台执行的任务 `a6b529cba17243aab87ec107eb9d1347` 返回严格的 `{"state":"completed","text":"42"}`，网关状态为 completed，结果与 artifact 均为 `42`。

最终委托与普通聊天 UI 用例 2 通过、0 失败、0 跳过；重复委托与配对恢复 UI 用例 2/0/0。旧任务卡独立用例也通过。Rust 63 项、完整 JVM 115 项全部通过，JVM 的独立 worker、Keychain、MCP fixture 均实际运行，无跳过；原生提示词/语法检查与签名构建通过。可复核的任务与测试结果见 `scripts/acceptance/results-2026-10-07.json` 的 `cross_device_fixes_2026_10_08`。

这仅关闭本轮列出的 iPhone/Mac 问题。基于能力/资源的真机自动选址、手机追问及取消 UI、后台提醒、网络切换与睡眠/登录恢复、最低支持 OS、Android 真机和生产 HTTPS 部署仍待验收；端侧工具继续作为默认关闭的扩展项。
