use std::{error::Error, fmt};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AgentError {
    InvalidConfig(&'static str),
    InvalidAction(String),
    DuplicateTool(String),
    EmptyToolName,
    UnknownTool(String),
    Model(String),
    Tool { name: String, message: String },
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
            Self::Tool { name, message } => write!(f, "tool '{name}' failed: {message}"),
        }
    }
}

impl Error for AgentError {}
