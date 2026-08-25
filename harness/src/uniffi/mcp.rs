use std::sync::{Arc, Mutex, OnceLock};

use crate::AgentError;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ToolExecutionError {
    Timeout,
    PermissionDenied,
    NetworkUnreachable,
    InvalidArguments,
    ResourceNotFound,
    ServerInternalError,
    Cancelled,
    Unknown,
}

impl std::fmt::Display for ToolExecutionError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{self:?}")
    }
}

impl std::error::Error for ToolExecutionError {}

#[derive(Debug, Clone, PartialEq, Eq, ::uniffi::Record)]
pub struct McpTool {
    pub name: String,
    pub description: String,
    pub input_schema_json: String,
    pub retryable: bool,
}

#[::uniffi::export(with_foreign)]
#[::async_trait::async_trait]
pub trait ToolProvider: Send + Sync {
    async fn get_tools(&self) -> Result<Vec<McpTool>, ToolExecutionError>;
    async fn call_tool(
        &self,
        name: String,
        arguments_json: String,
    ) -> Result<String, ToolExecutionError>;
}

static TOOL_PROVIDER: OnceLock<Mutex<Option<Arc<dyn ToolProvider>>>> = OnceLock::new();

fn provider_slot() -> &'static Mutex<Option<Arc<dyn ToolProvider>>> {
    TOOL_PROVIDER.get_or_init(|| Mutex::new(None))
}

pub fn store_tool_provider(provider: Arc<dyn ToolProvider>) {
    let mut slot = provider_slot().lock().expect("tool provider lock poisoned");
    *slot = Some(provider);
}

fn current_tool_provider() -> Result<Option<Arc<dyn ToolProvider>>, AgentError> {
    Ok(provider_slot()
        .lock()
        .map_err(|_| AgentError::Model("tool provider lock poisoned".into()))?
        .clone())
}

pub async fn register_all_mcp_tools(
    registry: &mut crate::ToolRegistry,
) -> Result<usize, AgentError> {
    let Some(provider) = current_tool_provider()? else {
        registry.clear_mcp_tools()?;
        return Ok(0);
    };
    let tools: Vec<McpTool> = provider
        .get_tools()
        .await
        .map_err(AgentError::ToolProvider)?;
    let count = tools.len();
    registry.replace_mcp_tools(provider, tools)?;
    Ok(count)
}

pub fn clear_tool_provider() {
    *provider_slot().lock().expect("tool provider lock poisoned") = None;
}

pub fn unregister_tool_provider_from_registry(
    registry: &mut crate::ToolRegistry,
) -> Result<(), AgentError> {
    clear_tool_provider();
    registry.clear_mcp_tools()
}
