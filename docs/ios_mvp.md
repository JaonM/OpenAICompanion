# iOS MVP（SwiftUI + KMP + Rust Harness）

iOS 工程在 [`ios/OpenAICompanion.xcodeproj`](../ios/OpenAICompanion.xcodeproj)。界面是原生 SwiftUI，使用 `NavigationStack`、系统列表、搜索、滑动删除、设置 sheet 与贴合安全区域的输入栏。会话列表、消息、发送与取消通过 UniFFI 调用同一套 Rust Harness；本地 SQLite 存于 iOS Application Support。`kmp/src/commonMain` 中的 `CompanionConversationCodec`、`CompanionStreamAccumulator` 和 `CompanionModelDefaults` 同时供 macOS 与 iOS 使用。iOS 端不是单独重写业务层。

## 构建前提

- 完整 Xcode（含 iOS SDK）和 JDK 21，先在 Xcode 中接受许可并选好开发团队。仅安装 Command Line Tools 不够。
- Rust 工具链，以及真机和 Apple Silicon 模拟器使用的 `aarch64-apple-ios`、`aarch64-apple-ios-sim` 目标（Intel Mac 模拟器另需 `x86_64-apple-ios`）：

```bash
rustup target add aarch64-apple-ios aarch64-apple-ios-sim
```

- 在仓库根目录先生成或更新 Swift UniFFI 绑定（Rust UDL 变更后必做）：

```bash
./scripts/ios-bindings.sh
open ios/OpenAICompanion.xcodeproj
```

选择 `OpenAICompanion` scheme、iPhone 模拟器或真机并运行。Xcode 的构建阶段会先执行 KMP 的 `embedAndSignAppleFrameworkForXcode`，再把对应平台的 Rust `staticlib` 链入 App。`ios/Generated` 中的 Swift、C 头和 modulemap 是根据 Rust UDL 生成的；不应手工修改生成文件。

## 模型连接

iPhone 不内置 27B 模型；它作为模型 HTTP 客户端连接 Mac 上的 Ollama 或其他 OpenAI 兼容接口。默认模型名为 `hf.co/unsloth/Qwen3.8-27B-GGUF:UD-Q4_K_M`。模拟器默认访问 `http://localhost:11434/v1/chat/completions`；真机首次需在“设置”填写 Mac 的局域网地址，如 `http://192.168.1.10:11434/v1/chat/completions`。Mac 上只监听 `127.0.0.1` 的 Ollama 无法被真机访问，需要让服务监听局域网接口并允许防火墙访问。裸 HTTP 与未认证的 Ollama 只能用于可信局域网，不能直接暴露到公网。API Key 只留在进程内存；地址和模型名保存在设备 UserDefaults。

App 包含 `NSLocalNetworkUsageDescription` 与 `NSAllowsLocalNetworking`，因此首次访问局域网时 iOS 会请求本地网络权限。用户拒绝后可在系统“设置”中重新开启。

## MVP 范围与验证

已接入会话新建、列表/搜索、恢复、删除确认、消息轨迹、流式正文与推理、停止生成、模型设置和状态探测。每台设备有自己的本地 SQLite；Mac 与 iPhone 共用 Rust 实现和 KMP 逻辑，但当前没有跨设备会话同步。iOS MVP 还未接入 macOS 的 MCP 管理器，因此不应把桌面端的远程 MCP 能力视为 iOS 已实现。

当前开发机只有 Command Line Tools，缺少完整 Xcode/iOS SDK，无法链接、安装或实机验收 iOS App。已执行 plist 与 Xcode 工程语法检查、Swift 语法解析、KMP/JVM 测试，并成功编译 `compileKotlinIosSimulatorArm64`；`linkDebugFrameworkIosSimulatorArm64` 停在 `xcrun xcodebuild -version`，需要完整 Xcode。安装后仍需执行模拟器和真机的编译、发送、取消、切换会话、重启恢复和删除验收。
