# OpenAICompanion

OpenAICompanion 是一个面向个人设备、以本地数据为中心的 AI 助手实验项目。它使用 Rust Harness 运行 Agent Loop、保存会话轨迹与记忆，并通过 Kotlin Multiplatform（KMP）和 UniFFI 连接应用端。项目仍处于 MVP 开发阶段；架构图中的部分能力尚未实现。

## 当前状态

| 平台 | 实现 | 状态 |
| --- | --- | --- |
| macOS | Compose Desktop（KMP/JVM）+ Rust Harness | MVP 已实现，可在 macOS 本机构建运行 |
| iOS | 原生 SwiftUI + KMP 共享逻辑 + Rust Harness | 源码已接入；尚未完成 Xcode 链接、模拟器和真机验收 |
| Android | KMP 平台代码 | 尚无可用的 App MVP |

已验证的 macOS MVP 支持本地会话的新建、恢复与删除、流式模型输出、推理内容展示、停止生成，以及 SQLite 会话轨迹。Rust Harness 提供当前会话的短期上下文、跨会话中期记忆和持久化长期记忆；中长期记忆目前需要宿主显式写入，不会自动从普通对话提炼。macOS 端还支持配置远程 MCP 服务；iOS 端尚未接入这一能力。

## 快速开始

### macOS

需要 macOS、JDK 21、Rust 工具链，以及一个 OpenAI 兼容的 Chat Completions 模型服务。首次构建还需要下载 Gradle、Kotlin 和 Rust 依赖。从仓库根目录运行：

```bash
./scripts/macos-app.sh run
```

应用默认使用本机 Ollama 地址 `http://localhost:11434/v1/chat/completions`，模型名为 `hf.co/unsloth/Qwen3.8-27B-GGUF:UD-Q4_K_M`。模型需自行安装，也可在应用设置中改用其他兼容服务。打包 DMG：

```bash
./scripts/macos-app.sh package
```

安装模型、运行参数和数据目录详见 [macOS MVP 指南](docs/macos_mvp.md)。

### iOS

需要完整 Xcode（含 iOS SDK）、JDK 21 和 Rust iOS 目标。生成 Swift UniFFI 绑定后，在 Xcode 中打开工程：

```bash
rustup target add aarch64-apple-ios aarch64-apple-ios-sim
./scripts/ios-bindings.sh
open ios/OpenAICompanion.xcodeproj
```

iPhone 不在设备上运行上述 27B 模型；真机需在设置中填写同一局域网内的 Mac 模型服务地址。当前 iOS 工程尚未经过完整 Xcode 构建与运行验收，具体前提和限制见 [iOS MVP 指南](docs/ios_mvp.md)。

## 架构

- `harness/`：Rust Agent Loop、模型与工具回调接口、SQLite 记忆及会话轨迹。
- `kmp/`：跨平台数据模型与会话解析；macOS 桌面界面、模型客户端和 MCP 连接实现。
- `ios/`：原生 SwiftUI 界面，通过 KMP framework 与 UniFFI Swift 绑定复用共享逻辑和 Rust Harness。
- `scripts/`：绑定生成及平台构建脚本。

![OpenAICompanion 产品愿景架构图](assets/architecture.png)

上图描述产品愿景，不代表所有模块已在当前 MVP 中实现。已落地能力以代码和各平台指南为准。

## 测试

Rust Harness：

```bash
cargo test --manifest-path harness/Cargo.toml
```

KMP/JVM 桥接测试需要先构建 Rust 动态库：

```bash
cargo build --manifest-path harness/Cargo.toml --release
cd kmp
HARNESS_LIBRARY_PATH=../harness/target/release/libharness.dylib \
  ./gradlew -PkmpJvmToolchain=21 jvmTest
```

iOS 的 KMP 代码可用 `./gradlew -PkmpJvmToolchain=21 compileKotlinIosSimulatorArm64` 在 `kmp/` 下单独编译；这不等同于 iOS App 的链接或运行验收。

## 数据与安全

会话轨迹和记忆保存在应用本地 SQLite 中，目前**没有数据库加密**。模型请求会发送到设置的服务地址；macOS 与 iOS 各自保存本地数据，尚无跨设备会话同步。不要将未认证的 Ollama HTTP 服务直接暴露到公网。处理敏感个人数据前，请先评估存储加密、模型服务权限与网络配置。

## 文档

- [Agent Loop 设计](docs/agent_loop_core.md)
- [三层记忆实现](docs/memory.md)
- [本地会话轨迹](docs/session_trace.md)
- [macOS MVP](docs/macos_mvp.md)
- [iOS MVP](docs/ios_mvp.md)
- [设备日历工具](docs/device_calendar_tool.md)
- [待办与已知限制](docs/todo.md)

## 反馈与贡献

构建或使用时遇到问题，可在仓库 Issue 中提供操作系统、Xcode/JDK/Rust 版本、复现步骤和相关日志。欢迎围绕 [待办与已知限制](docs/todo.md) 提交讨论或 Pull Request；提交代码前请运行受影响模块的测试。

## 许可证

本项目采用 [Apache License 2.0](LICENSE)。
