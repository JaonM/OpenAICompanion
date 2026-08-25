# 开发日志

## 2026-08-25

- 将 Rust Agent Loop 收敛为 `harness/src/loop.rs` 中的 `run` 函数；循环只依赖 `ModelServing` 与 `ToolExecutor`。
- 将 `ToolRegistry` 作为工具发现和执行的核心实现，`load_more_tools` 改为注册到 Registry 的内置工具。
- 增加 MCP 工具快照替换和清理逻辑；Rust 不持有 MCP Server 生命周期，只持有 KMP 聚合后的当前快照，并在每个 loop step 前刷新。
- 接通 UniFFI scaffolding：UDL 使用 `McpTool` 与 foreign `ToolProvider`，Rust API 与 UDL 的 provider 方法签名保持一致。
- 增加 KMP Gradle/Kotlin 初版：使用 `modelcontextprotocol/kotlin-sdk`，提供远程 Streamable HTTP Client、端侧 Server 抽象、Server 聚合管理和 Rust Provider 适配边界。
- 增加项目内 `uniffi-bindgen` CLI target 和 `scripts/generate-uniffi-kotlin.sh`，不再依赖开发机全局安装命令；已验证可生成 Kotlin binding。
- UniFFI 生成脚本固定从 `harness` crate 工作目录、使用相对 UDL 路径运行，避免 UniFFI 报告 UDL 不在 crate 内。
- UniFFI 生成改用 library mode：先构建 `libharness.dylib`，再从动态库生成 Kotlin 绑定，规避单 UDL 模式的 crate 定位限制；JVM/JNA 生成物放在 `jvmMain`，commonMain 只保留抽象。
- 将 Rust `ModelServing`、`Tool`、`ToolExecutor` 和 Loop 改为 Future 驱动；UniFFI `ToolProvider` 也改为异步 foreign trait，Kotlin Provider 不再使用 `runBlocking`。

## 验证结果

- `cargo test --all-targets`：通过。
- `cargo clippy --all-targets -- -D warnings`：通过。
- KMP Gradle 验证：当前仓库没有 Gradle wrapper 可执行脚本，且依赖下载环境尚未配置，待 CI/开发机验证。
