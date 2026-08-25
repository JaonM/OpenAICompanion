use std::collections::HashMap;
use std::future::Future;
use std::pin::Pin;
use std::sync::{Arc, Mutex};

use crate::{AgentError, McpTool, ToolCall, ToolDefinition, ToolOutput, ToolProvider};

pub type ToolFuture<'a> =
    Pin<Box<dyn Future<Output = Result<ToolOutput, AgentError>> + Send + 'a>>;
pub type ExecutorFuture<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;

pub trait Tool: Send + Sync {
    fn definition(&self) -> ToolDefinition;
    fn execute<'a>(&'a self, call: &'a ToolCall) -> ToolFuture<'a>;
}

/// Execution seam for policy, sandbox, retries, and remote dispatch.
pub trait ToolExecutor {
    fn refresh<'a>(&'a mut self) -> ExecutorFuture<'a, Result<(), AgentError>> {
        Box::pin(async { Ok(()) })
    }

    fn list_tools(&self) -> Result<Vec<ToolDefinition>, AgentError>;
    fn execute<'a>(&'a mut self, call: &'a ToolCall) -> ExecutorFuture<'a, Result<ToolOutput, AgentError>>;
}

enum RegisteredTool {
    Builtin(Box<dyn Tool>),
    Local(Box<dyn Tool>),
    KmpMcp {
        definition: ToolDefinition,
        provider: Arc<dyn ToolProvider>,
    },
}

struct DisclosureState {
    order: Vec<String>,
    loaded_tools: usize,
    num_tool_per_load: usize,
}

struct LoadMoreTools {
    state: Arc<Mutex<DisclosureState>>,
}
impl Tool for LoadMoreTools {
    fn definition(&self) -> ToolDefinition {
        ToolDefinition::new(
            "load_more_tools",
            "Load the next page of registered tools",
            "{num_tools?: integer}",
        )
    }

    fn execute<'a>(&'a self, _: &'a ToolCall) -> ToolFuture<'a> {
        Box::pin(async move {
        let mut state = self.state.lock().map_err(|_| AgentError::Tool {
            name: "load_more_tools".into(),
            message: "tool registry lock poisoned".into(),
        })?;
        let before = state.loaded_tools;
        state.loaded_tools = (before + state.num_tool_per_load).min(state.order.len());
        let names = state.order[before..state.loaded_tools].join(", ");
        Ok(ToolOutput::success(if names.is_empty() {
            "No more tools are available".into()
        } else {
            format!("Loaded tools: {names}")
        }))
        })
    }
}

pub struct ToolRegistry {
    tools: HashMap<String, RegisteredTool>,
    state: Arc<Mutex<DisclosureState>>,
}

impl ToolRegistry {
    pub fn new(num_tool_per_load: usize) -> Result<Self, AgentError> {
        if num_tool_per_load == 0 {
            return Err(AgentError::InvalidConfig(
                "num_tool_per_load must be greater than zero",
            ));
        }
        let state = Arc::new(Mutex::new(DisclosureState {
            order: Vec::new(),
            loaded_tools: 0,
            num_tool_per_load,
        }));
        let mut registry = Self {
            tools: HashMap::new(),
            state: Arc::clone(&state),
        };
        registry.tools.insert(
            "load_more_tools".into(),
            RegisteredTool::Builtin(Box::new(LoadMoreTools { state })),
        );
        Ok(registry)
    }

    pub fn register(&mut self, tool: impl Tool + 'static) -> Result<(), AgentError> {
        let definition = tool.definition();
        self.insert(definition, RegisteredTool::Local(Box::new(tool)))
    }

    /// Replaces all KMP-provided tools with the latest aggregate snapshot.
    /// KMP owns the set of MCP servers; Rust owns only this snapshot.
    pub fn replace_mcp_tools(
        &mut self,
        provider: Arc<dyn ToolProvider>,
        tools: Vec<McpTool>,
    ) -> Result<(), AgentError> {
        let names = tools
            .iter()
            .map(|tool| tool.name.trim().to_owned())
            .collect::<Vec<_>>();
        for name in &names {
            if name.is_empty() || name == "load_more_tools" {
                return Err(AgentError::InvalidAction("invalid MCP tool name".into()));
            }
            if self.tools.contains_key(name)
                && !matches!(self.tools.get(name), Some(RegisteredTool::KmpMcp { .. }))
            {
                return Err(AgentError::DuplicateTool(name.clone()));
            }
        }
        self.tools
            .retain(|_, tool| !matches!(tool, RegisteredTool::KmpMcp { .. }));
        {
            let mut state = self
                .state
                .lock()
                .map_err(|_| AgentError::Model("tool registry lock poisoned".into()))?;
            state.order.retain(|name| self.tools.contains_key(name));
            state.loaded_tools = state.loaded_tools.min(state.order.len());
        }
        for tool in tools {
            let definition =
                ToolDefinition::new(&tool.name, &tool.description, &tool.input_schema_json);
            self.insert(
                definition.clone(),
                RegisteredTool::KmpMcp {
                    definition,
                    provider: Arc::clone(&provider),
                },
            )?;
        }
        Ok(())
    }

    pub fn clear_mcp_tools(&mut self) -> Result<(), AgentError> {
        self.tools
            .retain(|_, tool| !matches!(tool, RegisteredTool::KmpMcp { .. }));
        let mut state = self
            .state
            .lock()
            .map_err(|_| AgentError::Model("tool registry lock poisoned".into()))?;
        state.order.retain(|name| self.tools.contains_key(name));
        state.loaded_tools = state.loaded_tools.min(state.order.len());
        Ok(())
    }

    pub fn set_num_tool_per_load(&mut self, value: usize) -> Result<(), AgentError> {
        if value == 0 {
            return Err(AgentError::InvalidConfig(
                "num_tool_per_load must be greater than zero",
            ));
        }
        self.state
            .lock()
            .map_err(|_| AgentError::Model("tool registry lock poisoned".into()))?
            .num_tool_per_load = value;
        Ok(())
    }

    fn insert(
        &mut self,
        definition: ToolDefinition,
        tool: RegisteredTool,
    ) -> Result<(), AgentError> {
        let name = definition.name.trim().to_owned();
        if name.is_empty() {
            return Err(AgentError::EmptyToolName);
        }
        if self.tools.contains_key(&name) {
            return Err(AgentError::DuplicateTool(name));
        }
        self.tools.insert(name.clone(), tool);
        self.state
            .lock()
            .map_err(|_| AgentError::Model("tool registry lock poisoned".into()))?
            .order
            .push(name);
        Ok(())
    }

    fn definition(&self, name: &str) -> Option<ToolDefinition> {
        self.tools.get(name).map(|tool| match tool {
            RegisteredTool::Builtin(tool) | RegisteredTool::Local(tool) => tool.definition(),
            RegisteredTool::KmpMcp { definition, .. } => definition.clone(),
        })
    }
}

impl ToolExecutor for ToolRegistry {
    fn refresh<'a>(&'a mut self) -> ExecutorFuture<'a, Result<(), AgentError>> {
        Box::pin(async move { crate::uniffi::register_all_mcp_tools(self).await.map(|_| ()) })
    }

    fn list_tools(&self) -> Result<Vec<ToolDefinition>, AgentError> {
        let state = self
            .state
            .lock()
            .map_err(|_| AgentError::Model("tool registry lock poisoned".into()))?;
        let end = state.loaded_tools.min(state.order.len());
        let mut result = state.order[..end]
            .iter()
            .filter_map(|name| self.definition(name))
            .collect::<Vec<_>>();
        if end < state.order.len() {
            result.push(
                self.definition("load_more_tools")
                    .expect("builtin tool registered"),
            );
        }
        Ok(result)
    }

    fn execute<'a>(&'a mut self, call: &'a ToolCall) -> ExecutorFuture<'a, Result<ToolOutput, AgentError>> {
        Box::pin(async move {
        let tool = self
            .tools
            .get(&call.name)
            .ok_or_else(|| AgentError::UnknownTool(call.name.clone()))?;
        let result = match tool {
            RegisteredTool::Builtin(tool) | RegisteredTool::Local(tool) => tool.execute(call).await,
            RegisteredTool::KmpMcp { provider, .. } => Ok(ToolOutput::success(
                provider.call_tool(call.name.clone(), call.arguments.clone()).await,
            )),
        };
        result.map_err(|error| AgentError::Tool {
            name: call.name.clone(),
            message: error.to_string(),
        })
        })
    }
}

impl Default for ToolRegistry {
    fn default() -> Self {
        Self::new(8).expect("default tool page size is valid")
    }
}
