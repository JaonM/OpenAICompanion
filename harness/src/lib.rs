//! Reusable, dependency-free Agent Loop kernel.

use std::sync::Arc;

mod cancellation;
mod configuration;
pub mod context;
mod error;
pub mod r#loop;
pub mod serving;
pub mod tool;
mod types;
pub mod uniffi;

pub use uniffi::{McpTool, ToolExecutionError, ToolProvider};
::uniffi::include_scaffolding!("harness");

pub use configuration::Configuration;
pub use context::{ContextDirectories, SessionContext};
pub use error::AgentError;
pub use r#loop::run;
pub use serving::{
    AgentEventSink, ModelServeCallback, ModelServeError, ModelServeWrapper, ModelStreamCallback,
};
pub use tool::{Tool, ToolExecutor, ToolRegistry};
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
