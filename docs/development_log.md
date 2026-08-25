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
- 将 `load_more_tools` 改为覆盖式分页：每次调用切换到下一页工具，不累积上一页可见工具，并补充分页测试。
- 增加 `Configuration.tool_execute_timeout`，使用 Future 定时器限制单个工具执行时间，超时转换为统一的 `ToolExecutionError::Timeout`。
- 增加统一 `ToolExecutionError`、可重试工具元数据、指数退避重试，以及工具最终失败后的 AI Tool Message 反馈开关。
- 将同一轮模型响应中的多个工具调用改为并发执行；所有调用完成后仍按模型返回顺序写入 Tool Message，并补充并发回归测试。
- 增加 `Configuration.max_concurrent_tools`，使用有序并发缓冲限制单轮工具调用的最大在途数量；默认上限为 4，配置为 1 时退化为串行执行。

## 验证结果

- `cargo test --all-targets`：通过。
- `cargo clippy --all-targets -- -D warnings`：通过。
- KMP Gradle 验证：当前仓库没有 Gradle wrapper 可执行脚本，且依赖下载环境尚未配置，待 CI/开发机验证。
