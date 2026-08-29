use std::{error::Error, fmt};

use crate::ToolExecutionError;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AgentError {
    InvalidConfig(&'static str),
    InvalidAction(String),
    DuplicateTool(String),
    EmptyToolName,
    UnknownTool(String),
    Model(String),
    Cancelled,
    Tool {
        name: String,
        message: String,
    },
    ToolExecution {
        name: String,
        error: ToolExecutionError,
        message: String,
    },
    ToolProvider(ToolExecutionError),
}

impl fmt::Display for AgentError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidConfig(message) => write!(f, "invalid loop configuration: {message}"),
            Self::InvalidAction(message) => write!(f, "invalid model action: {message}"),
            Self::DuplicateTool(name) => write!(f, "tool already registered: {name}"),
            Self::EmptyToolName => write!(f, "tool name must not be empty"),
            Self::UnknownTool(name) => write!(f, "unknown tool: {name}"),
            Self::Model(message) => write!(f, "model error: {message}"),
            Self::Cancelled => f.write_str("agent loop cancelled by user"),
            Self::Tool { name, message } => write!(f, "tool '{name}' failed: {message}"),
            Self::ToolExecution {
                name,
                error,
                message,
            } => {
                write!(f, "tool '{name}' failed with {error}: {message}")
            }
            Self::ToolProvider(error) => write!(f, "MCP provider failed: {error}"),
        }
    }
}

impl Error for AgentError {}

impl AgentError {
    pub fn is_retryable(&self) -> bool {
        matches!(
            self,
            Self::ToolExecution {
                error: ToolExecutionError::Timeout
                    | ToolExecutionError::NetworkUnreachable
                    | ToolExecutionError::ServerInternalError,
                ..
            } | Self::ToolProvider(
                ToolExecutionError::Timeout
                    | ToolExecutionError::NetworkUnreachable
                    | ToolExecutionError::ServerInternalError
            )
        )
    }
}
