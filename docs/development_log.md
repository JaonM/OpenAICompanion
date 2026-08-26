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
- 增加端侧日历 `get_events` 工具：通过 EventKit/CalendarContract 查询系统日历，并使用 Kotlin MCP SDK `ChannelTransport` 在进程内连接 Device MCP Server 与 Client，再接入 KMP 聚合器和 UniFFI Provider。
- 将端侧工具生命周期统一到共享的 `device-tools` MCP Server；`get_events` 改为 Server 注册扩展，后续联系人、定位等端侧工具可在同一个 Server 中复用连接和生命周期。
- 将 `get_events` 查询参数改为 ISO 8601 日期范围，增加标题关键词、数量限制和升降序排序；三端统一过滤已取消日程，并默认查询今天到明天的日程。
- 将 Kotlin MCP SDK 从 `0.15.0` 降级到 `0.10.0`，匹配项目 Kotlin `2.2.0`，避免 SDK 使用 Kotlin `2.4.0` 编译产物导致 metadata 版本不兼容。
- 将 MCP `ToolSchema` 改为通过 Kotlin Serialization 编码为标准 JSON，再经 UniFFI 传递给 Rust Harness；增加测试防止退回 Kotlin 对象字符串格式。
- 增加 MCP `tools/list_changed` 监听：KMP 聚合器刷新工具快照后通过 UniFFI 主动推送给 Rust，Rust 仅在 Agent Loop 后续轮次检查本地版本，不再重复拉取 KMP 工具列表。
- 将首次工具加载从 Agent Loop 移至显式的 `ToolExecutor::initialize()` Session 初始化阶段；未初始化的 `ToolRegistry` 不允许启动 Agent Loop。

## 验证结果

- `cargo test --all-targets`：通过。
- `cargo clippy --all-targets -- -D warnings`：通过。
- KMP Gradle 验证：当前仓库没有 Gradle wrapper 可执行脚本，且依赖下载环境尚未配置，待 CI/开发机验证。
