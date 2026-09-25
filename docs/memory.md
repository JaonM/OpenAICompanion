# Memory（首版）

Harness 目前有三层记忆：

| 层级 | 存储位置 | 生命周期 | 写入方式 |
| --- | --- | --- | --- |
| 短期 | `Session.turns`；原始轨迹也写入本地 SQLite | 当前 Session；每轮只向模型发送最近 8 轮 | `Session::run_turn` 自动维护 |
| 中期 | App 指定路径的 SQLite | 跨 Session；默认 30 天，可指定到期时间 | App 明确调用 `remember`，例如保存任务摘要 |
| 长期 | 同一 SQLite | 持久保存，直到更新或删除 | App 明确调用 `remember` 或 `promote` |

## Rust 接入

```rust
use harness::{Configuration, MemoryStore, MemoryTier, NewMemory, Session};

# async fn example() -> Result<(), Box<dyn std::error::Error>> {
// App 负责选择自己的私有数据目录，并在此之前注册模型回调。
let memory = MemoryStore::open("/app-private-data/memory.sqlite")?;
let mut session = Session::initialize_with_memory(
    Configuration::default(),
    "",
    memory.clone(),
).await?;

session.remember(NewMemory {
    tier: MemoryTier::Medium,
    topic_id: Some("travel-plan".into()),
    content: "北京旅行的酒店尚未确认".into(),
    source: "user:turn-123".into(),
    expires_at: None,
})?;
let result = session.run_turn("继续讨论北京旅行").await?;

// 下一个 Session 用同一路径重新打开 MemoryStore，仍能检索这条中期记忆。
memory.archive_topic("travel-plan")?; // 事项完成时退出主动检索
let _ = result;
# Ok(())
# }
```

`MemoryStore` 还提供 `update(id, content, source)`、`forget(id)`、`promote(id, source)`、`list_active()` 和 `search(query, limit)`。`forget` 物理删除；归档只让已完成的中期事项退出检索。每条记忆保留来源、创建时间和更新时间。检索是本地词面匹配，不调用模型或网络；每轮最多注入 8 条、合计约 4000 字符。

本版不会自动从普通对话或原始工具结果中写入长期记忆。摘要提取、用户确认、去重、冲突处理，以及 SQLite 文件加密均需在宿主 App 接入时补齐。SQLite 文件目前未由 Harness 加密，应放在 App 私有目录；包含敏感个人信息的正式产品需要加密存储。当前 UniFFI 接口还没有暴露 `Session` 的创建和运行，所以这套入口目前供 Rust 宿主使用，KMP App 接入仍需补充会话桥接。

会话轨迹的查询、恢复与删除接口见[本地会话轨迹](session_trace.md)。
