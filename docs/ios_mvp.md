# iOS MVP（KMP + Compose Multiplatform）

iOS 的目标架构是 KMP + Compose Multiplatform 界面、Kotlin/Native 的 Rust Harness 绑定及 llama.cpp 端侧推理，不使用 Swift 源码。共享 Compose 界面位于 [`kmp/src/commonMain`](../kmp/src/commonMain)，MCP 客户端位于同一源码集；iOS 的 Compose `UIViewController` 工厂和 MCP 连接管理位于 [`kmp/src/iosMain`](../kmp/src/iosMain)。

**基础对话与指定 Mac 跨设备委托已通过真机验收，完整生产验收尚未完成。** 最新结果见本文末尾及 [2026-10-08 验收记录](../scripts/acceptance/results-2026-10-08.json)。以下 2026-09 的构建与验证描述保留为历史记录。 [`ios/OpenAICompanion.xcodeproj`](../ios/OpenAICompanion.xcodeproj) 的 App target 只编译 Objective-C 启动壳和 Objective-C++ llama.cpp 适配，Gradle 构建 `kmp/iosApp` 静态框架；旧 Swift 原型和 Swift UniFFI 生成文件已移除。`kmp/iosApp` 已增加 Kotlin/Native Harness 适配、MCP 服务、Compose 状态控制器、GGUF 文件导入和 llama.cpp C 桥接的组装代码。`kmp/iosRustBridge` 已能生成 Kotlin/Native UniFFI 源码。2026-09-26 已在 Xcode 27.0 环境完成 llama.cpp XCFramework、Apple Silicon 模拟器与 iPhone 的无签名 App 构建；iPhone 18 Pro 模拟器已安装并启动 App，首屏、本地 SQLite 初始化、单一持续对话与重启恢复，以及小型 GGUF 的端侧生成回复已在模拟器验证。本机 MCP 连接和 3 个工具的发现已通过模拟器 UI 测试；工具调用、OAuth 与真机端到端验收仍需完成。

## 构建

需要完整 Xcode（含 iOS SDK）、CMake 3.28+、JDK 21 和 Rust 工具链；仅有 Command Line Tools 不够。先在 Xcode 中接受许可并设置开发团队。从仓库根目录执行：

```bash
rustup target add aarch64-apple-ios aarch64-apple-ios-sim
./scripts/ios-llama.sh
open ios/OpenAICompanion.xcodeproj
```

安装完整 Xcode 后，运行 `./scripts/ios-app-check.sh` 进行无签名的模拟器 App 构建验收；它会准备 llama.cpp XCFramework，并通过 Xcode 的脚本阶段编译 KMP/Rust 桥接。工具调用历史的 Foundation 层转换测试可随时运行 `./scripts/test-ios-prompt.sh`，但不能替代 iOS App 链接与运行验证。

模拟器 UI 测试可在 Xcode 的 `OpenAICompanion` scheme 中运行。`OpenAICompanionUITests` 会验证单一持续对话、设置页和重启恢复、本地模型回复，以及本机 MCP 连接；MCP 用例需要先运行 `python3 scripts/mcp_smoke_server.py`，模型用例需要先将本地 GGUF 复制到模拟器 App 容器，可运行 `./scripts/test-ios-model.sh /path/to/model.gguf <已启动模拟器的 UDID>`。未满足模型或 MCP 前置条件时，对应用例会明确跳过。2026-09-27 更新单一持续对话界面后，在 iPhone 18 Pro / iOS 27.0 模拟器上重跑 3 项 UI 测试，全部通过且没有跳过。2026-09-26 的模型用例使用 [QuantFactory 的 SmolLM2-135M-Instruct Q4_K_M](https://huggingface.co/QuantFactory/SmolLM2-135M-Instruct-GGUF/blob/main/SmolLM2-135M-Instruct.Q4_K_M.gguf)，SHA-256 为 `8030f04528538d47bda434f6f0bdf3952c40a58123e4d5e755332f23731a8684`。

模拟器 MCP 手动验收可在另一终端运行 `python3 scripts/mcp_smoke_server.py`，并在 App 设置中连接 `http://127.0.0.1:8765/mcp`。该服务只绑定本机，提供 `echo`、返回 `isError` 的 `report_error`、以及触发表单交互的 `ask_name`；在 `http://127.0.0.1:8765/stats` 查看调用次数。iOS App 的 `NSAllowsLocalNetworking` 仅为本地测试地址开放 ATS 例外，共享客户端仍拒绝非本机 HTTP。导入适合手机的 GGUF 后，分别让模型调用这三个工具并逐次审批：确认回显、错误仅调用一次、表单接受与拒绝均可继续对话。`python3 scripts/test_mcp_smoke_server.py` 可先验证服务本身。在 `kmp/` 目录运行 `RUN_MCP_SMOKE_FIXTURE=1 ./gradlew -PkmpJvmToolchain=21 jvmTest --tests 'com.openai.companion.kmp.McpSmokeFixtureIntegrationTest'`，还可让共享 KMP 客户端直连同一验收服务，自动检查四条协议路径和工具调用次数。模型是否稳定选择工具取决于所导入的 GGUF；这套手动流程仍需在实际模拟器上执行。

Intel Mac 模拟器还需 `x86_64-apple-ios`；UniFFI 绑定生成会按当前 Mac 架构选择 `x86_64-apple-darwin` 或 `aarch64-apple-darwin`，`ios-app-check.sh` 也会检查对应 Rust 目标。`ios-llama.sh` 拉取固定版本 llama.cpp `v0.4.1`，调用上游脚本生成包含 iOS 模拟器和真机切片的 `llama.xcframework`。产物位于 `ios/Vendor/llama.cpp/build-apple/`，不提交到 Git。Rust 的 Kotlin/Native 绑定由 `kmp/iosRustBridge` 的 Gradle/UniFFI 流程生成。

## 端侧模型

App 通过 llama.cpp 在手机本机加载 GGUF，不再连接 Mac 上的 Ollama 或其他 HTTP 模型服务。在“设置”中从“文件”导入指令微调 GGUF；模型复制到 App 的 Application Support，UserDefaults 只记录文件名。App 不内置模型。桌面端 `unsloth/Qwen3.8-27B-GGUF` 通常过大，不适合作为手机默认模型；请按设备可用内存选择较小的量化聊天模型。

当前每次请求使用 4096 token 上下文，最多生成 512 token，并使用 GGUF 内的聊天模板。超长会话、不支持的模板或设备内存不足会明确报错。Rust Harness 继续负责会话、轨迹和流事件；llama.cpp 负责本地推理。KMP 宿主已新增无 Swift 的模型回调与文件选择代码，调用现有 Objective-C++ 引擎的 C 接口；已通过 iOS SDK 编译和模拟器启动验证，小型 GGUF 推理已通过模拟器 UI 测试。模型侧现可在已连接远程 MCP 时输出单个 JSON 工具调用，由 Harness 执行并继续对话；此路径尚未在真机上验收。MVP 仍只支持纯文本，不支持多模态输入。

端侧模型在下一轮提示词中保留 Harness 的工具调用名称与调用 ID，并将工具返回正文标记为不可信数据；该消息转换已在 macOS Foundation 环境做独立测试，但仍需 iOS 真机检验模型行为。

## 远程 MCP（开发中）

KMP 共享客户端支持新版 `server/discover` 与逐请求元数据、旧版 `initialize`/`Mcp-Session-Id` 回退、工具列表分页、调用和 `Mcp-Param-*` 请求头；SSE 请求响应逐行读取，不会等待服务器关闭连接。新版服务声明 `tools.listChanged` 时会使用 `subscriptions/listen`；旧版会话则尝试独立 GET/SSE 流，支持 `Last-Event-ID` 续接、服务器指定的 `retry`、GET 405 降级、GET/POST 404 后重建会话，以及关闭时尝试 DELETE。旧版 POST SSE 响应在最终结果前断开且服务器给出事件 ID 时，会通过 GET 续接同一请求，暂时性 408/429/5xx 可重试，**不会重发工具调用 POST**。以上协议路径已有本地 JVM 服务测试；JVM + Rust Harness 集成测试还覆盖模型发起工具调用、用户批准或拒绝、MCP 服务执行及模型继续回复。工具回调错误通过显式结果记录跨 UniFFI 返回，拒绝审批不会再触发 Rust 线程 panic。iOS Kotlin 连接管理负责本地保存地址、HTTPS/localhost 限制、请求超时、工具快照及逐次审批；`kmp/iosApp` 已提供从 Compose 状态控制器到 Kotlin/Native UniFFI 的代码路径。Xcode target 已改为这条链路，桥接模块已通过模拟器与真机 App 构建，并完成模拟器启动；OAuth、MRTR 交互及真机端到端验收尚未完成。

新版 `input_required` 多轮工具协议驱动已在 KMP 客户端实现：仅在显式配置输入处理器及相应客户端能力时逐轮回答，重发时原样携带 `requestState`、替换而不累积 `inputResponses`，最多 10 轮；没有处理器时拒绝，不会自动代用户同意。共享 Compose 界面已有 `elicitation/create` 表单模式的明确提交/拒绝弹窗，显示请求方域名；工具调用审批也显示服务域名。平面字符串、数字、整数、布尔及单选字段按 Schema 渲染和做基础校验，其他结构可退回手填 JSON。界面提示不要通过表单输入凭据；不声明 sampling、roots 或 URL elicitation 能力。Harness 工具快照更新时，iOS 设置页的工具数量也会从同一快照更新；连接、变更和断开的共享桥接行为有 JVM 回归测试。协议、表单及状态控制器已有 JVM 测试，iOS App 完整链接及弹窗真机交互仍待验证。

远程服务返回 401/403 时，共享传输层保留 `WWW-Authenticate: Bearer` 的资源元数据地址、scope 和错误类型。KMP 的 Protected Resource Metadata / Authorization Server Metadata 发现模块限制响应大小，按规范尝试 endpoint 路径和根 well-known，检查 issuer、资源 URL 与 HTTPS（localhost 可用 HTTP）。共享 OAuth 客户端已实现授权码 + PKCE S256、state/issuer 校验、`resource` 参数、预注册 client ID 与 DCR 回退；iOS Kotlin 层使用系统网页授权会话、安全随机数和 SHA-256，受保护端点在 Compose 设置页显示授权入口。Compose 在连接或授权期间禁用重复提交，iOS 授权服务也串行化浏览器会话，避免重复点击打开并发授权。令牌按 MCP endpoint 存在设备本地 Keychain，启动时恢复，显式断开时删除；过期前通过刷新令牌续期，轮换后的凭据更新到 Keychain，并校验 issuer/resource 不变。共享 MCP 传输层在 POST/GET/DELETE 请求上添加 Bearer。JSON-RPC POST、旧版会话的工具调用 SSE 续接 GET 和工具列表订阅 GET 收到 401 时，都会刷新令牌并至多重试一次；续接 GET 不会重发原工具调用 POST。再次失败、刷新不可用或返回 403 时，会向 iOS Compose 状态报告需要 OAuth 授权。iOS 重新授权优先复用同一端点已保存的 client ID；`insufficient_scope` 挑战指定的新 scope 会进入授权请求。协议测试覆盖元数据发现、DCR、PKCE、授权码交换、令牌刷新、scope 升级、Bearer 请求和这些 401 恢复路径；最新 iOS 服务层已通过模拟器框架编译及链接。**scope 升级的 iOS 端到端验证及 Keychain 真机行为仍未完成**；匿名 MCP 连接已通过模拟器 UI 测试。无签名模拟器的 Keychain 读取可能返回 `-34018`：此时匿名服务仍可连接，需要 OAuth 的服务会明确报告 Keychain 错误。

共享 MCP 客户端对单个 JSON-RPC 响应设 4 MiB 上限，`tools/list` 最多读取 32 页、512 个工具；异常服务端超限时会明确报错。服务端返回的 `isError` 工具结果会保留原始内容及错误标记，交给模型和会话轨迹；它不会因正文含有“network”或“timeout”等词而被当作传输故障自动重试。Rust Agent Loop 对远程 MCP 工具的网络错误或超时也不自动重试，因为服务端可能已执行，只是响应丢失；本地工具仍保留原有重试策略。工具列表变更通知会合并排队；刷新暂时失败时会退避重试，不依赖服务端再发一次通知。Rust/JVM 回归测试及 JVM + Rust + 本地 MCP 服务的集成测试已覆盖相应路径，iOS App 仍需实机验证。

## 范围与验证

KMP + Compose 版本已有共享界面、状态控制器、MCP 客户端、Kotlin/Native Harness 适配和端侧推理。每台设备保存自己的 SQLite；跨设备任务服务和记忆同步接口已实现，完整生产联调仍待验收，参见 [跨设备执行](cross_device_execution.md) 与 [记忆同步](memory_sync.md)。

当前开发机已有 Xcode 27.0 与 iOS 27.0 模拟器运行时。`./scripts/ios-llama.sh` 已生成 llama.cpp XCFramework，`./scripts/ios-app-check.sh` 已通过模拟器 App 构建；`xcodebuild -sdk iphoneos CODE_SIGNING_ALLOWED=NO build` 也已通过。iPhone 18 Pro 模拟器上的 App 已安装和启动，首屏安全区域、持续运行和 SQLite 初始化已确认。CMake 3.31.10 此次从 Python wheel 临时安装在 `/private/tmp`，后续构建仍需系统提供 CMake 3.28+。已使用 SHA-256 校验的 SmolLM2-135M-Instruct Q4_K_M GGUF，在模拟器 UI 测试中验证模型识别、消息发送及助手回复；另一项 UI 测试验证单一持续对话、设置页和重启恢复。本机 MCP 连接已通过第三项 UI 测试；文件选择器导入、取消生成、MCP 工具调用与 OAuth 交互及真机行为仍需端到端验收。


## 2026-10-08 交互优化方案与施工

沿用 Mac 的蓝色主色、浅色/深色主题和单一持续对话，优先减少设置查找、历史阅读与任务查看之间的操作成本。施工位于共享 `CompanionMobileUi.kt`；Android 复用此界面，但本轮仅验收 iOS。

| 操作场景 | 已实现的交互 |
| --- | --- |
| 阅读对话 | 靠近列表末尾时跟随新消息与正文增量；翻看历史时保留位置，提供“↓ 最新消息”按钮。 |
| 输入与导航 | 输入框与纸飞机发送按钮同排，默认一行、按内容最多展开四行；模型状态放到框外，发送/停止点击区域保留；适配键盘，发送和切换页面时收起键盘；任务页、设置页来回切换保留未发送草稿。生成期间显示停止按钮。 |
| 查看远端任务 | 顶栏“任务”与对话区“查看”进入独立任务页，最新任务在前；统一圆角卡片，区分状态与结果，保留刷新、回复、拒绝和取消。 |
| 管理设置 | 总览展示七个功能分组与当前状态，进入具体表单后可返回“所有设置”或点击“完成”。模型、主动任务、跨设备、Agent、同步、MCP、工具扩展分别管理。 |
| 阅读执行记录 | 工具与思考记录继续使用灰色小字单行省略，默认隐藏全文，点击展开；正文与审批确认仍正常显示。 |
| 启用端侧工具 | 工具扩展放入独立分组，显示默认关闭的状态；启用与权限确认仍使用原业务动作。 |

表单只调整显示与入口，既有保存、配对、OAuth、审批、委托与任务操作继续调用原有 `MobileActions`。令牌草稿仍掩码显示并在保存后清空；任务页切换本身不触发任务取消或重新发送。

新增真机用例验证任务页/设置分组导航、草稿保留和实际提交的原文；普通对话、委托 Mac 并显示 `42`、工具记录折叠，以及设置和重启用例继续回归。XCTest 的 Compose TextView 在输入前后都可能没有 AX `value`，因此草稿保留通过真实截图与发送后的消息内容核验，不能把空 AX 属性判为实际丢稿。初次证书信任阻断、模拟器诊断中止和测试断言误判均保留历史证据；最终计数以结果记录为准。

本轮未覆盖 VoiceOver、最大动态字体、横屏、iPad 多窗口、深色真机与长时间交互压力；这些项目仍待专项验收。实时思考流仍受原生后端当前能力限制，不因本次折叠样式变更而视为已验收。

最终单排输入框与纸飞机发送按钮的签名包已安装在 iPhone 17 Pro Max / iOS 27.0.1，五项真机用例 **5 通过、0 失败、0 跳过**。普通对话用例要求新答案实际进入可视区，并验证翻看历史后点击“最新消息”可返回；委托 Mac 的新任务显示结果 `42`。设置总览、任务卡片与输入框截图已导出检查。Mac 核心业务代码本轮没有改动，端侧工具继续作为默认关闭的可选扩展。

纸飞机为代码内的矢量图标，保留“发送”的无障碍名称；最小输入点击高度 44 dp，图标按钮使用标准交互尺寸。最终版本再次完成相同五项真机回归 **5/0/0**，共享 JVM 编译通过；尺寸和图标已从真实截图核对。
