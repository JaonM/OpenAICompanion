# 本地会话轨迹

`Session::initialize_with_memory` 会在 App 指定的 SQLite 文件中创建一条会话记录。之后每次调用 `Session::run_turn`，Harness 会先写入用户输入，再逐条写入助手消息、工具调用和工具结果；结束时记录状态、最终输出、推理文本、步数或错误。写入中的 Turn 状态为 `running`，进程异常退出后仍可读到已写入的部分。达到步数上限、失败和取消分别标记为 `max_steps`、`failed`、`cancelled`。

```rust
use harness::{Configuration, MemoryStore, Session};

# async fn example() -> Result<(), Box<dyn std::error::Error>> {
// 宿主 App 先注册模型与 MCP 回调，再打开自己的私有数据库文件。
let store = MemoryStore::open("/app-private-data/memory.sqlite")?;
let mut session = Session::initialize_with_memory(
    Configuration::default(), "", store.clone(),
).await?;
let session_id = session.trace_session_id.expect("trace enabled");
session.run_turn("查看明天的日程").await?;

// 下次启动：列出、读取并恢复同一会话。
let _sessions = store.list_trace_sessions()?;
let _turns = store.list_trace_turns(session_id)?;
let mut resumed = Session::resume_with_memory(
    Configuration::default(), "", store.clone(), session_id,
).await?;
resumed.run_turn("继续").await?;

// 用户要求清除该会话时，级联删除它的所有 Turn 和消息。
store.delete_trace_session(session_id)?;
# Ok(())
# }
```

恢复会话时，只有成功结束或达到步数上限的 Turn 会重新加入短期上下文；失败、取消和异常中断的记录保留供查看，不进入下一轮推理。模型实际接收最近 8 轮，SQLite 中的更早轨迹仍可查询。`Session::initialize` 是不带本地存储的兼容入口。

轨迹会包含用户原文、工具参数与返回值、助手输出，以及成功运行的推理文本。SQLite 文件目前未加密，应由宿主放入 App 私有目录；正式处理敏感个人数据前仍需接入加密存储。当前 UniFFI 接口尚未暴露 `Session` 的创建和运行，因此 KMP App 的会话桥接仍需补齐。

删除一条中期或长期记忆不会自动删除包含相同内容的原会话轨迹；需要清除原对话时，调用 `delete_trace_session`。目前没有按单条 Turn 删除的接口。
