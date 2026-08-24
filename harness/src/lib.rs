//! Reusable, dependency-free Agent Loop kernel.

mod error;
pub mod r#loop;
pub mod serving;
pub mod tool;

pub use error::AgentError;
pub use r#loop::{
    AgentEvent, AgentRun, LoopConfig, Message, ModelRequest, ModelResponse, TerminationReason,
    ToolCall, ToolDefinition, ToolOutput,
};
pub use r#loop::{AgentLoop, AgentLoopBuilder, NoopObserver, Observer};
pub use serving::ModelServe;
pub use tool::{Tool, ToolRegistry};
