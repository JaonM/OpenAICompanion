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
  - [x] Rust 侧短期会话消息、中期/长期 SQLite 记忆、检索及显式删除。
  - [x] Rust 侧本地 Session/Turn/消息轨迹写入、读取、恢复及删除。
  - [x] 单一持续对话、最多 8 轮原始轨迹及后台端侧模型每 4 轮生成一条摘要。
  - [x] 成功 Turn 后的后台记忆点提取、来源校验、按主题去重和冲突跳过。
  - [ ] 记忆点的用户确认、查看与编辑界面，以及敏感数据的加密存储。
- [x] 成功 Turn 后由端侧模型异步提取一次性或每周主动任务；本地计划、幂等领取、独立 Harness 推理与 iOS/macOS 前台投递；旧饭点/通勤规则自动迁移。
- [x] 无新 Turn 时每 30 分钟从已有记忆与查询工具主动发现条件检查任务，保留去重和用户删除意图。
- [ ] 完善任务提取的自然语言时间校验、端侧模型结构化输出重试和工具只读能力核验。
- [ ] 主动任务的闭 App 准时服务、天气/餐饮/出行实时数据源及 Android 通知投递。
- [ ] 接入天气等外部事件订阅，支持事件变化后重新评估任务，而不只在既定时间检查。
  - [ ] SQLite 加密与持续对话的分页浏览和清除全部数据入口。
- [ ] 增加权限检查、sandbox 和 process spawn 边界。
- [ ] 为模型适配器、工具适配器和 observer 增加集成测试。
- [ ] 评估 `async` runtime、错误库、序列化库等依赖；在接口稳定前保持依赖留白。

## 工程化

- [ ] 补充 workspace、CI 与文档测试配置。
- [x] 在 CI 中执行 `cargo run --manifest-path harness/Cargo.toml --features cli --bin uniffi-bindgen` 并验证 Kotlin 绑定生成。
- [ ] 接入目标平台的 UniFFI Kotlin 生成物与 Rust 动态库构建产物。
- [ ] 为 KMP 端到端连接远程/端侧 MCP Server 增加平台测试。
- [ ] 在真实 iOS/Android/macOS App 中补充日历权限申请和端到端测试。
- [ ] 建立成本、延迟、工具成功率和循环终止原因指标。
- [ ] 定义版本化 API 和兼容性策略。
