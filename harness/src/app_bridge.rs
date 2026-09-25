//! Minimal host-facing session API for the macOS MVP.

use std::sync::{Arc, Mutex, OnceLock};

use serde_json::{Value, json};

use crate::{Configuration, MemoryStore, Message, Session, TerminationReason, TraceStatus};

#[derive(Debug, Clone, ::uniffi::Record)]
pub struct AppResult {
    pub ok: bool,
    pub value_json: String,
    pub error: String,
}

impl AppResult {
    fn success(value: Value) -> Self {
        Self {
            ok: true,
            value_json: value.to_string(),
            error: String::new(),
        }
    }

    fn failure(error: impl ToString) -> Self {
        Self {
            ok: false,
            value_json: String::new(),
            error: error.to_string(),
        }
    }
}

struct AppState {
    store: MemoryStore,
    runtime: tokio::runtime::Runtime,
    active_session: Mutex<Option<Session>>,
}

static APP_STATE: OnceLock<Mutex<Option<Arc<AppState>>>> = OnceLock::new();

fn app_state() -> Result<Arc<AppState>, String> {
    APP_STATE
        .get_or_init(|| Mutex::new(None))
        .lock()
        .map_err(|_| "app state lock poisoned".to_owned())?
        .clone()
        .ok_or_else(|| "local store is not open".to_owned())
}

pub fn app_open_store(database_path: String) -> AppResult {
    if database_path.trim().is_empty() {
        return AppResult::failure("database path is empty");
    }
    let result = (|| {
        let store = MemoryStore::open(database_path).map_err(|error| error.to_string())?;
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .map_err(|error| error.to_string())?;
        let state = Arc::new(AppState {
            store,
            runtime,
            active_session: Mutex::new(None),
        });
        *APP_STATE
            .get_or_init(|| Mutex::new(None))
            .lock()
            .map_err(|_| "app state lock poisoned".to_owned())? = Some(state);
        Ok::<_, String>(())
    })();
    match result {
        Ok(()) => AppResult::success(json!({})),
        Err(error) => AppResult::failure(error),
    }
}

pub fn app_start_session() -> AppResult {
    let result = (|| {
        let state = app_state()?;
        let session = state
            .runtime
            .block_on(Session::initialize_with_memory(
                Configuration::default(),
                "",
                state.store.clone(),
            ))
            .map_err(|error| error.to_string())?;
        let id = session.trace_session_id.ok_or("session has no trace id")?;
        *state
            .active_session
            .lock()
            .map_err(|_| "session lock poisoned".to_owned())? = Some(session);
        Ok::<_, String>(id)
    })();
    match result {
        Ok(id) => AppResult::success(json!({ "id": id })),
        Err(error) => AppResult::failure(error),
    }
}

pub fn app_resume_session(session_id: i64) -> AppResult {
    let result = (|| {
        let state = app_state()?;
        let session = state
            .runtime
            .block_on(Session::resume_with_memory(
                Configuration::default(),
                "",
                state.store.clone(),
                session_id,
            ))
            .map_err(|error| error.to_string())?;
        *state
            .active_session
            .lock()
            .map_err(|_| "session lock poisoned".to_owned())? = Some(session);
        Ok::<_, String>(())
    })();
    match result {
        Ok(()) => AppResult::success(json!({ "id": session_id })),
        Err(error) => AppResult::failure(error),
    }
}

pub fn app_send_message(user_input: String) -> AppResult {
    if user_input.trim().is_empty() {
        return AppResult::failure("message is empty");
    }
    let result = (|| {
        let state = app_state()?;
        let mut active = state
            .active_session
            .lock()
            .map_err(|_| "session lock poisoned".to_owned())?;
        let session = active.as_mut().ok_or("no active session")?;
        let run = state
            .runtime
            .block_on(session.run_turn(user_input))
            .map_err(|error| error.to_string())?;
        let termination = match run.termination {
            TerminationReason::Completed => "completed",
            TerminationReason::MaxStepsReached => "max_steps",
        };
        Ok::<_, String>(json!({
            "session_id": session.trace_session_id,
            "output": run.output,
            "steps": run.steps,
            "termination": termination,
        }))
    })();
    match result {
        Ok(value) => AppResult::success(value),
        Err(error) => AppResult::failure(error),
    }
}

pub fn app_list_sessions() -> AppResult {
    let result = (|| {
        let state = app_state()?;
        let sessions = state
            .store
            .list_trace_sessions()
            .map_err(|error| error.to_string())?;
        let summaries = sessions.into_iter().map(|session| {
            let turns = state.store.list_trace_turns(session.id)
                .map_err(|error| error.to_string())?;
            Ok::<_, String>(json!({
                "id": session.id,
                "created_at": session.created_at,
                "preview": turns.first().map(|turn| turn.user_input.as_str()).unwrap_or("新会话"),
            }))
        }).collect::<Result<Vec<_>, _>>()?;
        Ok::<_, String>(json!(summaries))
    })();
    match result {
        Ok(value) => AppResult::success(value),
        Err(error) => AppResult::failure(error),
    }
}

pub fn app_load_session(session_id: i64) -> AppResult {
    let result = (|| {
        let state = app_state()?;
        let turns = state
            .store
            .list_trace_turns(session_id)
            .map_err(|error| error.to_string())?;
        let mut messages = Vec::new();
        for turn in &turns {
            let mut reasoning_added = false;
            for message in &turn.messages {
                if !reasoning_added && matches!(message, Message::Assistant { .. }) {
                    if let Some(reasoning) = turn.reasoning.as_ref().filter(|text| !text.is_empty())
                    {
                        messages.push(json!({ "role": "reasoning", "content": reasoning }));
                    }
                    reasoning_added = true;
                }
                let visible = match message {
                    Message::User { content } => {
                        Some(json!({ "role": "user", "content": content }))
                    }
                    Message::Assistant { content, .. } if !content.is_empty() => {
                        Some(json!({ "role": "assistant", "content": content }))
                    }
                    Message::Assistant { tool_calls, .. } if !tool_calls.is_empty() => {
                        Some(json!({ "role": "tool", "content": format!("调用工具：{}",
                            tool_calls.iter().map(|call| call.name.as_str()).collect::<Vec<_>>().join(", ")) }))
                    }
                    Message::Tool {
                        name,
                        content,
                        is_error,
                        ..
                    } => Some(
                        json!({ "role": "tool", "content": format!("{name}: {content}"), "is_error": is_error }),
                    ),
                    _ => None,
                };
                if let Some(visible) = visible {
                    messages.push(visible);
                }
            }
            let status = match turn.status {
                TraceStatus::Running => {
                    Some("上次生成可能已中断，可以重新发送这条消息。".to_owned())
                }
                TraceStatus::Failed => Some(format!(
                    "生成失败：{}",
                    turn.error.as_deref().unwrap_or("未知错误")
                )),
                TraceStatus::Cancelled => Some("生成已取消。".to_owned()),
                TraceStatus::MaxStepsReached => Some("已达到最大执行步数。".to_owned()),
                TraceStatus::Completed => None,
            };
            if let Some(content) = status {
                messages.push(json!({ "role": "status", "content": content }));
            }
        }
        Ok::<_, String>(json!({ "id": session_id, "messages": messages }))
    })();
    match result {
        Ok(value) => AppResult::success(value),
        Err(error) => AppResult::failure(error),
    }
}

pub fn app_delete_session(session_id: i64) -> AppResult {
    let result = (|| {
        let state = app_state()?;
        let mut active = state
            .active_session
            .lock()
            .map_err(|_| "session lock poisoned".to_owned())?;
        state
            .store
            .delete_trace_session(session_id)
            .map_err(|error| error.to_string())?;
        if active.as_ref().and_then(|session| session.trace_session_id) == Some(session_id) {
            *active = None;
        }
        Ok::<_, String>(())
    })();
    match result {
        Ok(()) => AppResult::success(json!({})),
        Err(error) => AppResult::failure(error),
    }
}
