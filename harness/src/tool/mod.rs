use std::collections::HashMap;

use crate::{AgentError, ToolCall, ToolDefinition, ToolOutput};

/// A concrete executable capability. The core does not assume JSON, MCP, or a
/// particular process/HTTP implementation.
pub trait Tool: Send + Sync {
    fn definition(&self) -> ToolDefinition;
    fn execute(&self, call: &ToolCall) -> Result<ToolOutput, AgentError>;
}

#[derive(Default)]
pub struct ToolRegistry {
    tools: HashMap<String, Box<dyn Tool>>,
}

impl ToolRegistry {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn register(&mut self, tool: impl Tool + 'static) -> Result<(), AgentError> {
        let definition = tool.definition();
        let name = definition.name.trim().to_owned();
        if name.is_empty() {
            return Err(AgentError::EmptyToolName);
        }
        if self.tools.contains_key(&name) {
            return Err(AgentError::DuplicateTool(name));
        }
        self.tools.insert(name, Box::new(tool));
        Ok(())
    }

    pub fn list_tools(&self) -> Vec<ToolDefinition> {
        let mut definitions: Vec<_> = self.tools.values().map(|tool| tool.definition()).collect();
        definitions.sort_by(|left, right| left.name.cmp(&right.name));
        definitions
    }

    pub fn execute(&self, call: &ToolCall) -> Result<ToolOutput, AgentError> {
        let tool = self
            .tools
            .get(&call.name)
            .ok_or_else(|| AgentError::UnknownTool(call.name.clone()))?;
        tool.execute(call).map_err(|error| AgentError::Tool {
            name: call.name.clone(),
            message: error.to_string(),
        })
    }
}
