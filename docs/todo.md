# 遗留 TODO

## Agent Loop

- [ ] 明确定义 `Action`、工具调用和模型响应的稳定协议（候选：JSON/MCP）。
- [ ] 增加取消信号和每工具调用的资源限制。
- [x] 增加单工具执行超时控制。
- [x] 增加可重试工具的指数退避重试。
- [ ] 由 app 层接入正式异步 runtime；Harness 核心不绑定 Tokio/Coroutine runtime。
- [ ] 支持流式模型输出及增量事件。
- [x] 支持同一模型响应中的工具调用并发执行，并保持结果顺序。
- [x] 增加 `max_concurrent_tools` 并发上限配置。
- [ ] 完善工具状态一致性和依赖约束。
- [ ] 增加持久化 session、memory 和 trace store。
- [ ] 增加权限检查、sandbox 和 process spawn 边界。
- [ ] 为模型适配器、工具适配器和 observer 增加集成测试。
- [ ] 评估 `async` runtime、错误库、序列化库等依赖；在接口稳定前保持依赖留白。

## 工程化

- [ ] 补充 workspace、CI 与文档测试配置。
- [x] 在 CI 中执行 `cargo run --manifest-path harness/Cargo.toml --features cli --bin uniffi-bindgen` 并验证 Kotlin 绑定生成。
- [ ] 接入目标平台的 UniFFI Kotlin 生成物与 Rust 动态库构建产物。
- [ ] 为 KMP 端到端连接远程/端侧 MCP Server 增加平台测试。
- [ ] 建立成本、延迟、工具成功率和循环终止原因指标。
- [ ] 定义版本化 API 和兼容性策略。
