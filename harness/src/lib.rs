//! Reusable, dependency-free Agent Loop kernel.

use std::sync::Arc;

mod configuration;
mod error;
pub mod r#loop;
pub mod serving;
pub mod tool;
mod types;
pub mod uniffi;

pub use uniffi::{McpTool, ToolExecutionError, ToolProvider};
::uniffi::include_scaffolding!("harness");

pub use configuration::Configuration;
pub use error::AgentError;
pub use r#loop::run;
pub use serving::{ModelFuture, ModelServing};
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
