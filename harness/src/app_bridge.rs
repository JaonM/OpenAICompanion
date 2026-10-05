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
        store
            .ensure_single_trace_session()
            .map_err(|error| error.to_string())?;
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
        let id = state
            .store
            .ensure_single_trace_session()
            .map_err(|error| error.to_string())?
            .id;
        if state
            .active_session
            .lock()
            .map_err(|_| "session lock poisoned".to_owned())?
            .as_ref()
            .and_then(|session| session.trace_session_id)
            == Some(id)
        {
            return Ok::<_, String>(id);
        }
        let session = state
            .runtime
            .block_on(Session::resume_with_memory(
                Configuration::default(),
                "",
                state.store.clone(),
                id,
            ))
            .map_err(|error| error.to_string())?;
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
        let canonical = state
            .store
            .ensure_single_trace_session()
            .map_err(|error| error.to_string())?
            .id;
        if session_id != canonical {
            return Err("only the continuous conversation can be opened".to_owned());
        }
        if state
            .active_session
            .lock()
            .map_err(|_| "session lock poisoned".to_owned())?
            .as_ref()
            .and_then(|session| session.trace_session_id)
            == Some(session_id)
        {
            return Ok::<_, String>(());
        }
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
                "preview": turns.first().map(|turn| turn.user_input.as_str()).unwrap_or("持续对话"),
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
    let _ = session_id;
    AppResult::failure("the continuous conversation cannot be deleted as a session")
}

pub fn app_put_proactive_rule(
    scenario: String,
    enabled: bool,
    local_minute: i64,
    weekday_mask: i64,
    lead_minutes: i64,
    timezone_offset_minutes: i64,
) -> AppResult {
    match app_state().and_then(|state| {
        state
            .store
            .put_proactive_rule(
                &scenario,
                enabled,
                local_minute,
                weekday_mask,
                lead_minutes,
                timezone_offset_minutes,
            )
            .map_err(|error| error.to_string())
    }) {
        Ok(rule) => AppResult::success(json!(rule)),
        Err(error) => AppResult::failure(error),
    }
}

pub fn app_put_proactive_task(task_json: String) -> AppResult {
    let result = (|| {
        let task = serde_json::from_str::<crate::proactive::ProactiveRule>(&task_json)
            .map_err(|error| error.to_string())?;
        let state = app_state()?;
        state
            .store
            .put_proactive_task(task)
            .map_err(|error| error.to_string())
    })();
    match result {
        Ok(task) => AppResult::success(json!(task)),
        Err(error) => AppResult::failure(error),
    }
}

pub fn app_delete_proactive_task(id: String) -> AppResult {
    match app_state().and_then(|state| {
        state
            .store
            .delete_proactive_task(&id)
            .map_err(|error| error.to_string())
    }) {
        Ok(()) => AppResult::success(json!({})),
        Err(error) => AppResult::failure(error),
    }
}

pub fn app_get_proactive_settings() -> AppResult {
    match app_state().and_then(|state| state.store.proactive_settings().map_err(|e| e.to_string()))
    {
        Ok(settings) => AppResult::success(json!(settings)),
        Err(error) => AppResult::failure(error),
    }
}

pub fn app_set_proactive_settings(enabled: bool, discovery_interval_minutes: i64) -> AppResult {
    let result = app_state().and_then(|state| {
        state
            .store
            .set_proactive_settings(crate::proactive::ProactiveSettings {
                enabled,
                discovery_interval_minutes,
            })
            .map_err(|e| e.to_string())?;
        state.store.proactive_settings().map_err(|e| e.to_string())
    });
    match result {
        Ok(settings) => AppResult::success(json!(settings)),
        Err(error) => AppResult::failure(error),
    }
}

/// Read generation BEFORE exporting: concurrent writes can cause an extra upload,
/// but can never be acknowledged without having been included in the snapshot.
pub fn app_prepare_memory_sync() -> AppResult {
    let result = (|| {
        let state = app_state()?;
        let generation = state
            .store
            .memory_sync_generation()
            .map_err(|e| e.to_string())?;
        let records = state
            .store
            .export_memory_sync()
            .map_err(|e| e.to_string())?;
        Ok::<_, String>(json!({"generation": generation, "records": records}))
    })();
    match result {
        Ok(batch) => AppResult::success(batch),
        Err(e) => AppResult::failure(e),
    }
}

pub fn app_acknowledge_memory_sync(generation: i64) -> AppResult {
    match app_state().and_then(|s| {
        s.store
            .acknowledge_memory_sync(generation)
            .map_err(|e| e.to_string())
    }) {
        Ok(()) => AppResult::success(json!({})),
        Err(e) => AppResult::failure(e),
    }
}

pub fn app_memory_sync_pending() -> AppResult {
    match app_state().and_then(|s| s.store.memory_sync_pending().map_err(|e| e.to_string())) {
        Ok(pending) => AppResult::success(json!(pending)),
        Err(e) => AppResult::failure(e),
    }
}

pub fn app_export_memory_sync() -> AppResult {
    match app_state().and_then(|state| state.store.export_memory_sync().map_err(|e| e.to_string()))
    {
        Ok(records) => AppResult::success(json!(records)),
        Err(error) => AppResult::failure(error),
    }
}

pub fn app_merge_memory_sync(records_json: String) -> AppResult {
    let result = (|| {
        let records: Vec<crate::memory_sync::SyncRecord> =
            serde_json::from_str(&records_json).map_err(|e| e.to_string())?;
        let state = app_state()?;
        state
            .store
            .merge_memory_sync(&records)
            .map_err(|e| e.to_string())
    })();
    match result {
        Ok(changed) => AppResult::success(json!({"changed":changed})),
        Err(error) => AppResult::failure(error),
    }
}

pub fn app_process_pending_proactive_plans(timezone_offset_minutes: i64) -> AppResult {
    let result = (|| {
        let state = app_state()?;
        if !state
            .store
            .proactive_settings()
            .map_err(|e| e.to_string())?
            .enabled
        {
            return Ok::<_, String>(crate::proactive_planner::PlanOutcome::default());
        }
        let model = crate::ModelServeWrapper::registered().map_err(|error| error.to_string())?;
        crate::proactive_planner::process_pending(&state.store, &model, timezone_offset_minutes)
            .map_err(|error| error.to_string())
    })();
    match result {
        Ok(outcome) => AppResult::success(json!(outcome)),
        Err(error) => AppResult::failure(error),
    }
}

pub fn app_discover_proactive_tasks(timezone_offset_minutes: i64) -> AppResult {
    let result = (|| {
        let state = app_state()?;
        if !state
            .store
            .proactive_settings()
            .map_err(|e| e.to_string())?
            .enabled
        {
            return Ok::<_, String>(crate::proactive_planner::PlanOutcome::default());
        }
        let model = crate::ModelServeWrapper::registered().map_err(|error| error.to_string())?;
        crate::proactive_planner::discover_from_memories(
            &state.store,
            &model,
            timezone_offset_minutes,
        )
        .map_err(|error| error.to_string())
    })();
    match result {
        Ok(outcome) => AppResult::success(json!(outcome)),
        Err(error) => AppResult::failure(error),
    }
}

pub fn app_list_proactive_rules() -> AppResult {
    match app_state().and_then(|state| {
        state
            .store
            .proactive_rules()
            .map_err(|error| error.to_string())
    }) {
        Ok(rules) => AppResult::success(json!(rules)),
        Err(error) => AppResult::failure(error),
    }
}

pub fn app_next_proactive_wake_at() -> AppResult {
    match app_state().and_then(|state| {
        if !state
            .store
            .proactive_settings()
            .map_err(|e| e.to_string())?
            .enabled
        {
            return Ok(None);
        }
        state
            .store
            .next_proactive_wake_at()
            .map_err(|error| error.to_string())
    }) {
        Ok(at) => AppResult::success(json!({ "at": at })),
        Err(error) => AppResult::failure(error),
    }
}

pub fn app_rebase_proactive_rules(timezone_offset_minutes: i64) -> AppResult {
    match app_state().and_then(|state| {
        state
            .store
            .rebase_proactive_rules(timezone_offset_minutes)
            .map_err(|error| error.to_string())
    }) {
        Ok(()) => AppResult::success(json!({})),
        Err(error) => AppResult::failure(error),
    }
}

pub fn app_run_due_proactive() -> AppResult {
    let result = (|| {
        let state = app_state()?;
        if !state
            .store
            .proactive_settings()
            .map_err(|e| e.to_string())?
            .enabled
        {
            return Ok::<_, String>(0);
        }
        let model = crate::ModelServeWrapper::registered().map_err(|error| error.to_string())?;
        state
            .runtime
            .block_on(crate::proactive::run_due(&state.store, &model))
            .map_err(|error| error.to_string())
    })();
    match result {
        Ok(count) => AppResult::success(json!({"processed": count})),
        Err(error) => AppResult::failure(error),
    }
}

pub fn app_ready_proactive_notifications() -> AppResult {
    match app_state().and_then(|state| {
        if !state
            .store
            .proactive_settings()
            .map_err(|e| e.to_string())?
            .enabled
        {
            return Ok(Vec::new());
        }
        state
            .store
            .ready_proactive_notifications()
            .map_err(|error| error.to_string())
    }) {
        Ok(items) => AppResult::success(json!(items)),
        Err(error) => AppResult::failure(error),
    }
}

pub fn app_mark_proactive_delivered(id: i64) -> AppResult {
    match app_state().and_then(|state| {
        state
            .store
            .mark_proactive_delivered(id)
            .map_err(|error| error.to_string())
    }) {
        Ok(()) => AppResult::success(json!({})),
        Err(error) => AppResult::failure(error),
    }
}

fn a2a_store_action(
    action: impl FnOnce(&MemoryStore) -> Result<Value, crate::MemoryError>,
) -> AppResult {
    match app_state().and_then(|state| action(&state.store).map_err(|error| error.to_string())) {
        Ok(value) => AppResult::success(value),
        Err(error) => AppResult::failure(error),
    }
}

pub fn app_a2a_put_agent(agent_json: String) -> AppResult {
    match serde_json::from_str::<Value>(&agent_json) {
        Ok(value) => a2a_store_action(|store| store.a2a_put_agent(&value)),
        Err(error) => AppResult::failure(error),
    }
}

pub fn app_a2a_delete_agent(agent_id: String) -> AppResult {
    a2a_store_action(|store| store.a2a_delete_agent(&agent_id))
}

pub fn app_a2a_list_agents() -> AppResult {
    a2a_store_action(MemoryStore::a2a_agents)
}

pub fn app_a2a_put_task(task_json: String) -> AppResult {
    match serde_json::from_str::<Value>(&task_json) {
        Ok(value) => a2a_store_action(|store| store.a2a_put_task(&value)),
        Err(error) => AppResult::failure(error),
    }
}

pub fn app_a2a_list_tasks() -> AppResult {
    a2a_store_action(MemoryStore::a2a_tasks)
}
