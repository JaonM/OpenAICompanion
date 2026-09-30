//! Reusable, dependency-free Agent Loop kernel.

use std::sync::Arc;

mod a2a;
mod app_bridge;
mod cancellation;
mod configuration;
pub mod context;
mod error;
pub mod r#loop;
pub mod memory;
mod memory_worker;
mod proactive;
mod proactive_planner;
mod profile_worker;
pub mod serving;
pub mod session;
pub mod tool;
pub mod trace;
mod types;
pub mod uniffi;

pub use a2a::A2aProvider;
pub use app_bridge::{
    AppResult, app_a2a_delete_agent, app_a2a_list_agents, app_a2a_list_tasks, app_a2a_put_agent,
    app_a2a_put_task, app_delete_proactive_task, app_delete_session, app_discover_proactive_tasks,
    app_list_proactive_rules, app_list_sessions, app_load_session, app_mark_proactive_delivered,
    app_next_proactive_wake_at, app_open_store, app_process_pending_proactive_plans,
    app_put_proactive_rule, app_put_proactive_task, app_ready_proactive_notifications,
    app_rebase_proactive_rules, app_resume_session, app_run_due_proactive, app_send_message,
    app_start_session,
};
pub use uniffi::{McpTool, ToolCallReply, ToolExecutionError, ToolListReply, ToolProvider};
::uniffi::include_scaffolding!("harness");

pub use configuration::Configuration;
pub use context::{ContextDirectories, SessionContext};
pub use error::AgentError;
pub use r#loop::run;
pub use memory::{MemoryEntry, MemoryError, MemoryStore, MemoryTier, NewMemory};
pub use proactive::{ProactiveNotification, ProactiveRule};
pub use serving::{
    AgentEventSink, ModelServeCallback, ModelServeError, ModelServeWrapper, ModelStreamCallback,
};
pub use session::{Session, Turn};
pub use tool::{Tool, ToolExecutor, ToolRegistry};
pub use trace::{TraceSession, TraceStatus, TraceTurn};
pub use types::{
    AgentRun, Message, ModelRequest, ModelResponse, TerminationReason, ToolCall, ToolDefinition,
    ToolOutput, tool_definition_to_function_schema,
};
pub use uniffi::{register_all_mcp_tools, unregister_tool_provider_from_registry};

pub fn register_tool_provider(provider: Arc<dyn ToolProvider>) {
    uniffi::store_tool_provider(provider);
}

pub fn update_mcp_tools(tools: Vec<McpTool>) {
    uniffi::update_mcp_tools(tools);
}

pub fn unregister_tool_provider() {
    uniffi::clear_tool_provider();
}

pub fn register_a2a_provider(provider: Arc<dyn A2aProvider>) {
    a2a::register_provider(provider);
}

pub fn unregister_a2a_provider() {
    a2a::unregister_provider();
}

pub fn register_model_serve_callback(provider: Arc<dyn ModelServeCallback>) {
    serving::register_model_serve_callback(provider);
}

pub fn unregister_model_serve_callback() {
    serving::unregister_model_serve_callback();
}

pub fn register_agent_event_sink(sink: Arc<dyn AgentEventSink>) {
    serving::register_agent_event_sink(sink);
}

pub fn unregister_agent_event_sink() {
    serving::unregister_agent_event_sink();
}

pub fn configure_context_directories(agents_directory: String, persona_directory: String) {
    context::configure_context_directories(agents_directory, persona_directory);
}

pub fn clear_context_directories() {
    context::clear_context_directories();
}

/// Cancels the currently running Agent Loop, if one exists.
pub fn cancel_agent_loop() {
    cancellation::cancel();
}
