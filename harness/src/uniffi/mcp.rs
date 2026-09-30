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
}

#[derive(Debug, Clone, ::uniffi::Record)]
pub struct ToolListReply {
    pub tools: Vec<McpTool>,
    pub error_code: Option<String>,
    pub error_message: Option<String>,
}

#[derive(Debug, Clone, ::uniffi::Record)]
pub struct ToolCallReply {
    pub output_json: String,
    pub is_error: bool,
    pub error_code: Option<String>,
    pub error_message: Option<String>,
}

impl ToolExecutionError {
    fn from_callback_code(code: &str) -> Self {
        match code {
            "TIMEOUT" => Self::Timeout,
            "PERMISSION_DENIED" => Self::PermissionDenied,
            "NETWORK_UNREACHABLE" => Self::NetworkUnreachable,
            "INVALID_ARGUMENTS" => Self::InvalidArguments,
            "RESOURCE_NOT_FOUND" => Self::ResourceNotFound,
            "SERVER_INTERNAL_ERROR" => Self::ServerInternalError,
            "CANCELLED" => Self::Cancelled,
            _ => Self::Unknown,
        }
    }
}

impl ToolListReply {
    pub fn into_result(self) -> Result<Vec<McpTool>, ToolExecutionError> {
        match self.error_code {
            Some(code) => Err(ToolExecutionError::from_callback_code(&code)),
            None => Ok(self.tools),
        }
    }
}

impl ToolCallReply {
    pub fn into_result(self) -> Result<(String, bool), (ToolExecutionError, String)> {
        match self.error_code {
            Some(code) => Err((
                ToolExecutionError::from_callback_code(&code),
                self.error_message
                    .unwrap_or_else(|| "MCP callback failed".into()),
            )),
            None => Ok((self.output_json, self.is_error)),
        }
    }
}

#[::uniffi::export(with_foreign)]
#[::async_trait::async_trait]
pub trait ToolProvider: Send + Sync {
    async fn get_tools(&self) -> ToolListReply;
    async fn call_tool(&self, name: String, arguments_json: String) -> ToolCallReply;
}

static TOOL_PROVIDER: OnceLock<Mutex<Option<Arc<dyn ToolProvider>>>> = OnceLock::new();
static MCP_TOOL_SNAPSHOT: OnceLock<Mutex<(u64, Vec<McpTool>)>> = OnceLock::new();

fn provider_slot() -> &'static Mutex<Option<Arc<dyn ToolProvider>>> {
    TOOL_PROVIDER.get_or_init(|| Mutex::new(None))
}

fn tool_snapshot_slot() -> &'static Mutex<(u64, Vec<McpTool>)> {
    MCP_TOOL_SNAPSHOT.get_or_init(|| Mutex::new((0, Vec::new())))
}

pub fn store_tool_provider(provider: Arc<dyn ToolProvider>) {
    let mut slot = provider_slot().lock().expect("tool provider lock poisoned");
    *slot = Some(provider);
}

pub fn update_mcp_tools(tools: Vec<McpTool>) {
    let mut snapshot = tool_snapshot_slot()
        .lock()
        .expect("MCP tool snapshot lock poisoned");
    snapshot.0 = snapshot.0.wrapping_add(1);
    snapshot.1 = tools;
}

pub(crate) fn current_mcp_tool_snapshot() -> (u64, Vec<McpTool>) {
    tool_snapshot_slot()
        .lock()
        .expect("MCP tool snapshot lock poisoned")
        .clone()
}

pub(crate) fn current_tool_provider() -> Result<Option<Arc<dyn ToolProvider>>, AgentError> {
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
        .into_result()
        .map_err(AgentError::ToolProvider)?;
    let count = tools.len();
    registry.replace_mcp_tools(provider, tools)?;
    Ok(count)
}

pub fn clear_tool_provider() {
    *provider_slot().lock().expect("tool provider lock poisoned") = None;
    update_mcp_tools(Vec::new());
}

pub fn unregister_tool_provider_from_registry(
    registry: &mut crate::ToolRegistry,
) -> Result<(), AgentError> {
    clear_tool_provider();
    registry.clear_mcp_tools()
}

#[cfg(test)]
mod tests {
    use super::{ToolCallReply, ToolExecutionError, ToolListReply};

    #[test]
    fn foreign_callback_replies_preserve_tool_error_classification() {
        let denied = ToolCallReply {
            output_json: String::new(),
            is_error: false,
            error_code: Some("PERMISSION_DENIED".into()),
            error_message: Some("user declined".into()),
        };
        assert_eq!(
            denied.into_result(),
            Err((ToolExecutionError::PermissionDenied, "user declined".into()))
        );
        let reported = ToolCallReply {
            output_json: r#"{"content":[{"type":"text","text":"network timeout"}],"isError":true}"#
                .into(),
            is_error: true,
            error_code: None,
            error_message: None,
        };
        assert_eq!(reported.into_result().unwrap().1, true);
        let unavailable = ToolListReply {
            tools: Vec::new(),
            error_code: Some("NETWORK_UNREACHABLE".into()),
            error_message: None,
        };
        assert_eq!(
            unavailable.into_result(),
            Err(ToolExecutionError::NetworkUnreachable)
        );
    }
}
