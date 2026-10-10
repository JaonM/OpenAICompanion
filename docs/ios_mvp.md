# iOS MVP（KMP + Compose Multiplatform）

iOS 的目标架构是 KMP + Compose Multiplatform 界面、Kotlin/Native 的 Rust Harness 绑定及可选 llama.cpp / MLX 端侧推理。界面仍由 KMP 实现；MLX 使用薄 Swift 原生桥接调用官方库。共享 Compose 界面位于 [`kmp/src/commonMain`](../kmp/src/commonMain)，MCP 客户端位于同一源码集；iOS 的 Compose `UIViewController` 工厂和 MCP 连接管理位于 [`kmp/src/iosMain`](../kmp/src/iosMain)。

**基础对话与指定 Mac 跨设备委托已通过真机验收，完整生产验收尚未完成。** 最新结果见本文末尾及 [2026-10-08 验收记录](../scripts/acceptance/results-2026-10-08.json)。以下 2026-09 的构建与验证描述保留为历史记录。 [`ios/OpenAICompanion.xcodeproj`](../ios/OpenAICompanion.xcodeproj) 的 App target 编译 Objective-C 启动壳、Objective-C++ llama.cpp 适配和 Swift MLX 适配，Gradle 构建 `kmp/iosApp` 静态框架；旧 Swift 原型和 Swift UniFFI 生成文件已移除。`kmp/iosApp` 已增加 Kotlin/Native Harness 适配、MCP 服务、Compose 状态控制器、GGUF 文件导入和 llama.cpp C 桥接的组装代码。`kmp/iosRustBridge` 已能生成 Kotlin/Native UniFFI 源码。2026-09-26 已在 Xcode 27.0 环境完成 llama.cpp XCFramework、Apple Silicon 模拟器与 iPhone 的无签名 App 构建；iPhone 18 Pro 模拟器已安装并启动 App，首屏、本地 SQLite 初始化、单一持续对话与重启恢复，以及小型 GGUF 的端侧生成回复已在模拟器验证。本机 MCP 连接和 3 个工具的发现已通过模拟器 UI 测试；工具调用、OAuth 与真机端到端验收仍需完成。

## 构建

需要完整 Xcode（含 iOS SDK 和 Metal Toolchain；缺失时运行 `xcodebuild -downloadComponent MetalToolchain`）、CMake 3.28+、JDK 21 和 Rust 工具链；仅有 Command Line Tools 不够。先在 Xcode 中接受许可并设置开发团队。从仓库根目录执行：

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

当前 Qwen3 / Qwen3.5 思考模式使用 8192 token 上下文，其他模型使用 4096 token 上下文，正文最多生成 512 token，Qwen3 额外预留最多 1024 token、Qwen3.5 预留最多 256 token 的思考预算，并使用 GGUF 内的聊天模板。超长会话、不支持的模板或设备内存不足会明确报错。Rust Harness 继续负责会话、轨迹和流事件；llama.cpp 负责本地推理。KMP 宿主提供模型回调与文件选择代码，调用现有 Objective-C++ 引擎的 C 接口；已通过 iOS SDK 编译和模拟器启动验证，小型 GGUF 推理已通过模拟器 UI 测试。模型侧现可在已连接远程 MCP 时输出单个 JSON 工具调用，由 Harness 执行并继续对话；此路径尚未在真机上验收。MVP 仍只支持纯文本，不支持多模态输入。

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
| 查看远端任务 | 顶栏“任务”进入独立任务页；对话页不再常驻远端任务状态/刷新栏，最新任务在前；统一圆角卡片，区分状态与结果，保留刷新、回复、拒绝和取消。 |
| 管理设置 | 总览展示七个功能分组与当前状态，进入具体表单后可返回“所有设置”或点击“完成”。模型、主动任务、跨设备、Agent、同步、MCP、工具扩展分别管理。 |
| 阅读执行记录 | 工具与思考记录继续使用灰色小字单行省略，默认隐藏全文，点击展开；正文与审批确认仍正常显示。 |
| 启用端侧工具 | 工具扩展放入独立分组，显示默认关闭的状态；启用与权限确认仍使用原业务动作。 |

表单只调整显示与入口，既有保存、配对、OAuth、审批、委托与任务操作继续调用原有 `MobileActions`。令牌草稿仍掩码显示并在保存后清空；任务页切换本身不触发任务取消或重新发送。

新增真机用例验证任务页/设置分组导航、草稿保留和实际提交的原文；普通对话、委托 Mac 并显示 `42`、工具记录折叠，以及设置和重启用例继续回归。XCTest 的 Compose TextView 在输入前后都可能没有 AX `value`，因此草稿保留通过真实截图与发送后的消息内容核验，不能把空 AX 属性判为实际丢稿。初次证书信任阻断、模拟器诊断中止和测试断言误判均保留历史证据；最终计数以结果记录为准。

本轮未覆盖 VoiceOver、最大动态字体、横屏、iPad 多窗口、深色真机与长时间交互压力；这些项目仍待专项验收。实时思考流仍受原生后端当前能力限制，不因本次折叠样式变更而视为已验收。

最终单排输入框与纸飞机发送按钮的签名包已安装在 iPhone 17 Pro Max / iOS 27.0.1，五项真机用例 **5 通过、0 失败、0 跳过**。普通对话用例要求新答案实际进入可视区，并验证翻看历史后点击“最新消息”可返回；委托 Mac 的新任务显示结果 `42`。设置总览、任务卡片与输入框截图已导出检查。Mac 核心业务代码本轮没有改动，端侧工具继续作为默认关闭的可选扩展。

纸飞机为代码内的矢量图标，保留“发送”的无障碍名称；最小输入点击高度 44 dp，图标按钮使用标准交互尺寸。最终版本再次完成相同五项真机回归 **5/0/0**，共享 JVM 编译通过；尺寸和图标已从真实截图核对。

2026-10-08 补充：移除对话顶部常驻远端任务状态与刷新栏，保留顶栏“任务”入口及任务页刷新。真机导航/草稿用例通过，跨设备用例在排除前台 App 干扰和 Runner 连接中断后复测通过，手机发起任务并显示 Mac 返回的 42；初次失败及未执行的重试均保留在验收记录中。证据：`ios-task-toolbar-1008.xcresult`、`ios-task-toolbar-reconnect-1008.xcresult`。

2026-10-08 思考与输入交互更新：iOS 的 Qwen3 提示词改为打开 `<think>`，先自由生成思考，再对回答应用现有任务/工具 JSON 约束。思考通过 `reasoning_content` 与正文分离，完成后按既有灰色小字折叠展示；达到思考预算后补齐结束标记并继续生成答案，思考内容不会作为正文返回。采样采用 [Qwen 官方量化模型建议](https://huggingface.co/Qwen/Qwen3-0.6B-GGUF#best-practices)的温度 0.6、TopP 0.95、TopK 20 与存在惩罚 1.5；预算分阶段处理参考 [Qwen 思考预算说明](https://github.com/QwenLM/Qwen3/blob/main/docs/source/getting_started/thinking_budget.md)。生成期间显示三点呼吸气泡，完成、取消或失败后移除。输入框支持软键盘发送键及硬件回车发送，硬件 Shift+Enter 保留换行；空草稿和生成期间不会重复提交。共享 Compose 输入和气泡修改也适用于 Android，Android 推理引擎配置未随此项改变。开启思考增加等待时间，不代表模型交流质量已验收。


## 2026-10-08 Qwen3.5-4B Q8_0 真机替换

当前验收 iPhone 已切换为 [unsloth/Qwen3.5-4B-GGUF](https://huggingface.co/unsloth/Qwen3.5-4B-GGUF) 的 `Qwen3.5-4B-Q8_0.gguf`（8 bit）。下载使用上游仓库，固定 revision `e87f176479d0855a907a41277aca2f8ee7a09523`；文件大小 4,482,403,488 字节，SHA-256 `10cc391b403021dd11c614679d2fd92f611c3681d29e29651b717316965d61e1` 已校验。现有 llama.cpp `v0.4.1` 支持 `qwen35` 架构，无需升级原生依赖；本次只接入文本推理，没有接入视觉投影模型。

模型保存在 App 私有 Models 目录，不提交到 Git，也不打包进 App。先暂存新文件并通过两轮真实推理，再通过仅 Debug 验收包支持的 `COMPANION_ACCEPTANCE_ACTIVATE_MODEL=1` 启动，在成功推理后持久化模型选择、删除此前选择的旧 GGUF；普通启动及 Release 不执行此验收动作。手机目录最终只剩新模型，旧 `Qwen3-0.6B-Q8_0.gguf` 已删除，普通重启后的默认选择仍为 Qwen3.5。

在 iPhone 17 Pro Max / iOS 27.0.1 上，`testQwen35ReplacementAndDialogue` **1 通过、0 失败、0 跳过**：新独立问题 `17+25` 精确返回 `42`，后续“上一条答案加 8”精确返回 `50`；激活后再次返回 `42`，普通重启验证模型选择。三轮均通过键盘回车发送，检查思考气泡出现、回复完成后消失，以及新答案进入可视区；截图核对思考内容保持灰色小字折叠。证据为 `ios-qwen35-mobile-budget-1008.xcresult`。

当前 Qwen3.5 思考预算 256 token、正文预算 512 token；验收算术轮次等待约 70–90 秒，8 bit 的响应速度仍有限。这些结果验证模型加载、基础回答和多轮上下文，不能替代开放对话质量、长会话、设备内存压力及其他机型专项验收。此前语法 URL 转义错误和更长思考预算导致的测试超时保留在验收记录中。

新模型的指定 Mac 委托也已通过真机回归：`testCrossDeviceDelegatesToMacAndShowsResult` **1 通过、0 失败、0 跳过**（94.987 秒）。手机生成委托、用户批准“发送一次”、创建新任务，再显示 Acceptance Mac 返回的精确 `42`；断言限定新任务编号，不把历史卡片计作成功。验收任务明确要求只输出数字；此前空格差异及 Mac 附带额外解释的两次断言失败仍保留。证据为 `ios-qwen35-cross-device-digits-1008.xcresult`。这不覆盖后台自动接单、App 退出后的推送或完整生产部署验收。


## 2026-10-08 流式正文与 MLX 引擎

“设置 → 端侧模型 → 推理引擎”可选择 llama.cpp 或 MLX，选择存入设备本地，普通重启保留。切换时卸载原引擎，两个模型不会同时常驻；下载和切换期间禁止重复操作与发送。llama.cpp 继续使用已有 Qwen3.5 Q8_0 GGUF；增加 MLX 选择不删除它。

MLX 使用固定 `mlx-swift-lm 3.32.3`，依赖版本由 Xcode `Package.resolved` 锁定。模型为 [mlx-community/Qwen3.5-4B-MLX-4bit](https://huggingface.co/mlx-community/Qwen3.5-4B-MLX-4bit)，固定 revision `32f3e8ecf65426fc3306969496342d504bfa13f3`，全部文件约 3.06 GB。`model.safetensors` 大小 3,034,300,695 字节，SHA-256 `5fb9acd0246866381cf8c5c354c6db1019f6498eec4ccb4f5edcc71ffeacb2db`。下载入口逐文件校验大小和 SHA-256，全部完成后才替换模型目录；下载失败保留原目录。模型保存在 App 私有 MLXModels 目录，不加入 App 包或 Git。本次已经从上游下载、校验并复制到验收手机；App 内再次下载的网络流程另列待验收。

两个引擎均接收 Harness 的 `messages` 请求，使用 `role` 和文本 `content`，输出 Chat Completions 风格的增量片段。llama.cpp 兼容层将 developer 转为 system，将工具返回包为不可信 user 数据，并保留历史工具名和调用 ID。MLX 的最新原生工具协议见下文；两者均不是完整 OpenAI HTTP API。llama.cpp 使用 GGUF 内模板的原生适配；MLX Tokenizer 使用模型的 `chat_template.jinja`。当前只接入文本推理：仓库含视觉权重和多模态模板不代表 App 已支持图片、视频或音频输入。

Qwen3.5 保留 256 token 思考预算和最多 512 token 正文。思考内容通过独立 reasoning 增量实时显示为灰色小字，最多保留 1200 个 UTF-16 单元并用省略号截断，界面最多显示三行；历史思考仍折叠显示。随后正文在模型生成期间逐步增长，支持从工具 JSON 包装中解码不完整字符串，并处理转义、中文和 Unicode 代理对。只输出新增片段，结束时补齐剩余片段；工具调用与委托任务的结构化状态仍在完整校验后交付，不能流成正文。停止、完成或失败后移除实时正文与思考气泡。

Foundation 流片段/模板回归通过；共享控制器 13 项、设备路由 8 项回归通过，覆盖思考截断、委托结果关联、引擎操作互斥、失败恢复及目标别名；Rust 67 项单元测试通过。真机结果及失败历史记录在 [验收记录](../scripts/acceptance/results-2026-10-08.json)，不能用构建成功代替模型推理和流式交互验收。

远端任务结果现在关联到本轮 `delegate_to_agent` 工具回执，在对话内随任务状态更新显示。普通问答不提供委托工具；需要远端能力或用户明确要求跨设备时，必须先取得本轮有效路由，再向同一 Agent 委托，历史路由不能作为新请求的授权。前台对话期间延后新的后台记忆推理，避免工具往返之间插入记忆生成。上述变更的最终真机回归仍以验收记录为准。

MLX 的长提示词预填充分为每批 128 token，并同步回收 GPU 中间结果，避免手机上的瞬时内存峰值；结构化输出使用 MLX 官方库的 JSON Schema 约束接口。已通过的 MLX 真机用例覆盖 `42 → 50` 连续对话、重启保留引擎、切回原 GGUF 并回答 `42`。此前长提示词退出、流式语法掩码计算变慢及路由重复发现的失败均保留在验收记录中。

明确委托尚未提交时，Harness 使用标准 `tool_choice: required`，llama.cpp 通过输出语法约束要求调用，MLX 的 Qwen/JSON 格式通过原生工具参数约束及最终调用检查要求调用；路由不支持、需要用户处理或执行失败时仍可解释原因。Harness 也拒绝模型绕过此要求返回的口头应答，只有本轮真实任务回执才确认提交。


## 2026-10-09 收尾验收

本轮功能验收通过：MLX 普通算术问答返回精确 `42`，没有远端审批或新增任务（50.662 秒）；MLX 与 llama.cpp 均验证了思考增长、正文增长、完整 `1..30` 和停止后恢复输入。布局修复后的完整用例分别耗时 189.428 秒、337.265 秒。MLX 指定 Mac 委托、审批、执行及对话内显示新任务结果 `42` 通过（157.384 秒，`ios-advertised-capabilities-final-1009.xcresult`）；反向委托手机的 MLX 结构化任务也返回 `completed/42`。最终签名 App 与 UI 测试构建通过。失败历史保留于验收 JSON，混合结果的测试组不计为整组通过。

设备发现现在明确包含 `model.complete`，路由参数只采用本轮发布的能力名；未配置设备网关时不发布设备路由工具，保留独立 A2A 的直接委托路径。消息列表使用稳定标识，自动滚动随下一次布局执行，修复测试中出现的文本布局缓存崩溃。

这些是指定手机和测试服务下的功能验收，不能视为完整生产或性能验收：已开始的后台记忆推理仍可能延长下一轮等待；App 内重新下载 3.06 GB 模型、其他机型与长时内存压力、Android 真机、后台推送和正式服务部署仍按原记录待验收。MLX 当前只支持文本输入。

## 2026-10-10 MLX 原生工具协议

MLX 改用模型自身的 chat template：标准 `tools` Schema 传入 `UserInput.tools`；历史 assistant 保留 `tool_calls`，工具结果保留 `role: tool`、`tool_call_id` 和 `name`。API 中的 JSON 参数字符串转为模板所需的对象，布尔、数值、数组和空值保留相应类型。非文本消息或损坏参数显式报错，不静默丢弃。

输出由 SDK `ToolCallProcessor` 解析，Qwen3.5 使用 `.qwen35`，其他模型使用模型配置的格式。只接受本轮声明的工具并启用严格参数校验；结束时拒绝损坏或不完整的调用。调用转回标准 Chat Completions `message.tool_calls`，由现有 Harness 完成审批、路由、执行和工具回填。工具结果仍是不可信数据，设备路由授权继续由 Harness 校验。标准工具 Schema 只引用本轮发现的资源；没有资源时限制 `resource_refs` 为空，并将委托目标限制为本轮 REMOTE 路由返回的 Agent。过去请求的路由不会沿用。

普通答案直接流式输出文本与 Markdown，不再强制 `{"text":...}` 包装；思考保持独立流。适配层提醒模型遵循最新用户的输出格式，避免旧回答格式或旧任务失败影响当前请求。显式 JSON Schema 且无工具时继续使用 xgrammar；同时提供工具时保留原生调用语法，以提示词要求最终 JSON，并交给现有结构化结果校验，不将整个输出限制为 JSON。`tool_choice: required` 使用 xgrammar 原生 structural tag 约束工具名和参数 Schema；Qwen3.5/JSON 采用 SDK 支持的 `<tool_call>` 内 JSON，XML 格式采用 `qwen_xml_parameter` 编译器。其他格式以提示词和最终校验要求调用。未产生有效调用会报错。llama.cpp 的既有兼容协议不受本次修改影响。

运行 `scripts/test-ios-prompt.sh` 验证两种消息适配，运行 `scripts/test-ios-mlx-values.sh` 验证生产转换函数的 JSON 类型与 UTF-16 流式边界；`scripts/test-ios-mlx-parser.sh <mlx-swift-lm checkout>` 直接编译所用 SDK 的 Qwen3.5 解析器，验证原生 XML、兼容 JSON 和损坏调用。真机验证及失败记录见 `scripts/acceptance/results-2026-10-10.json`。

本轮真机验收通过：MLX 普通问答精确返回 `42` 且未增加远端任务（55.416 秒）；思考与正文在生成期间增长，最终 `1..30` 完整无重复，停止后恢复输入（120.755 秒）；原生工具完成设备发现、路由、审批、Mac 执行与对话内结果 `42`（168.710 秒，本地任务 26，远端任务 `518873fe1f4346b8bc746828501ff446`）。服务端独立核验状态 `TASK_STATE_COMPLETED`、结果 `42`；截图确认手机前台任务卡已完成。签名构建、输入适配、SDK 解析和 JVM 回归通过。中间失败保留于验收记录，混合测试组不计为整组通过。真机覆盖当前 Qwen3.5 模型，未覆盖所有 MLX 模型及长时压力。
