use std::collections::HashMap;
use std::future::Future;
use std::pin::Pin;
use std::sync::{Arc, Mutex};

use crate::{AgentError, McpTool, ToolCall, ToolDefinition, ToolOutput, ToolProvider};

pub type ToolFuture =
    Pin<Box<dyn Future<Output = Result<ToolOutput, AgentError>> + Send + 'static>>;
pub type ExecutorFuture<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;

pub trait Tool: Send + Sync {
    fn definition(&self) -> ToolDefinition;
    fn execute(&self, call: ToolCall) -> ToolFuture;
}

/// Execution seam for policy, sandbox, retries, and remote dispatch.
pub trait ToolExecutor {
    /// Loads the initial tool snapshot for a session.
    fn initialize(&mut self) -> ExecutorFuture<'_, Result<(), AgentError>> {
        self.refresh()
    }

    fn is_initialized(&self) -> bool {
        true
    }

    fn refresh(&mut self) -> ExecutorFuture<'_, Result<(), AgentError>> {
        Box::pin(async { Ok(()) })
    }

    /// Applies locally pushed tool snapshots without pulling from KMP again.
    fn sync_if_changed(&mut self) -> ExecutorFuture<'_, Result<(), AgentError>> {
        Box::pin(async { Ok(()) })
    }

    fn list_tools(&self) -> Result<Vec<ToolDefinition>, AgentError>;
    fn is_retryable(&self, call: &ToolCall) -> bool {
        self.list_tools()
            .ok()
            .and_then(|tools| tools.into_iter().find(|tool| tool.name == call.name))
            .is_some_and(|tool| tool.retryable)
    }
    fn execute(&self, call: ToolCall) -> ExecutorFuture<'static, Result<ToolOutput, AgentError>>;
}

enum RegisteredTool {
    Builtin(Arc<dyn Tool>),
    Local(Arc<dyn Tool>),
    KmpMcp {
        definition: ToolDefinition,
        provider: Arc<dyn ToolProvider>,
    },
}

struct DisclosureState {
    order: Vec<String>,
    page_start: usize,
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

    fn execute(&self, _: ToolCall) -> ToolFuture {
        let state = Arc::clone(&self.state);
        Box::pin(async move {
            let mut state = state.lock().map_err(|_| AgentError::Tool {
                name: "load_more_tools".into(),
                message: "tool registry lock poisoned".into(),
            })?;
            let before = state.page_start;
            let page_end = (before + state.num_tool_per_load).min(state.order.len());
            state.page_start = page_end;
            let names = state.order[before..page_end].join(", ");
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
    mcp_snapshot_version: u64,
    initialized: bool,
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
            page_start: 0,
            num_tool_per_load,
        }));
        let mut registry = Self {
            tools: HashMap::new(),
            state: Arc::clone(&state),
            mcp_snapshot_version: 0,
            initialized: false,
        };
        registry.tools.insert(
            "load_more_tools".into(),
            RegisteredTool::Builtin(Arc::new(LoadMoreTools { state })),
        );
        Ok(registry)
    }

    pub fn register(&mut self, tool: impl Tool + 'static) -> Result<(), AgentError> {
        let definition = tool.definition();
        self.insert(definition, RegisteredTool::Local(Arc::new(tool)))
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
            state.page_start = state.page_start.min(state.order.len());
        }
        for tool in tools {
            let definition =
                ToolDefinition::new(&tool.name, &tool.description, &tool.input_schema_json)
                    .with_retryable(tool.retryable);
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
        state.page_start = state.page_start.min(state.order.len());
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
    fn initialize(&mut self) -> ExecutorFuture<'_, Result<(), AgentError>> {
        Box::pin(async move {
            self.refresh().await?;
            self.initialized = true;
            Ok(())
        })
    }

    fn is_initialized(&self) -> bool {
        self.initialized
    }

    fn refresh(&mut self) -> ExecutorFuture<'_, Result<(), AgentError>> {
        Box::pin(async move {
            crate::uniffi::register_all_mcp_tools(self).await.map(|_| {
                self.mcp_snapshot_version = crate::uniffi::current_mcp_tool_snapshot().0;
            })
        })
    }

    fn sync_if_changed(&mut self) -> ExecutorFuture<'_, Result<(), AgentError>> {
        Box::pin(async move {
            let (version, tools) = crate::uniffi::current_mcp_tool_snapshot();
            if version == self.mcp_snapshot_version {
                return Ok(());
            }
            let Some(provider) = crate::uniffi::current_tool_provider()? else {
                self.clear_mcp_tools()?;
                self.mcp_snapshot_version = version;
                return Ok(());
            };
            self.replace_mcp_tools(provider, tools)?;
            self.mcp_snapshot_version = version;
            Ok(())
        })
    }

    fn list_tools(&self) -> Result<Vec<ToolDefinition>, AgentError> {
        let state = self
            .state
            .lock()
            .map_err(|_| AgentError::Model("tool registry lock poisoned".into()))?;
        let start = state.page_start.min(state.order.len());
        let end = (start + state.num_tool_per_load).min(state.order.len());
        let mut result = state.order[start..end]
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

    fn is_retryable(&self, call: &ToolCall) -> bool {
        self.definition(&call.name)
            .is_some_and(|definition| definition.retryable)
    }

    fn execute(&self, call: ToolCall) -> ExecutorFuture<'static, Result<ToolOutput, AgentError>> {
        let execution = match self.tools.get(&call.name) {
            Some(RegisteredTool::Builtin(tool) | RegisteredTool::Local(tool)) => {
                Arc::clone(tool).execute(call.clone())
            }
            Some(RegisteredTool::KmpMcp { provider, .. }) => {
                let provider = Arc::clone(provider);
                let call = call.clone();
                Box::pin(async move {
                    provider
                        .call_tool(call.name.clone(), call.arguments.clone())
                        .await
                        .map(ToolOutput::success)
                        .map_err(|error| AgentError::ToolExecution {
                            name: call.name.clone(),
                            message: error.to_string(),
                            error,
                        })
                })
            }
            None => return Box::pin(async move { Err(AgentError::UnknownTool(call.name)) }),
        };
        let name = call.name.clone();
        Box::pin(async move {
            let result = execution.await;
            result.map_err(|error| match error {
                AgentError::ToolExecution { .. } => error,
                error => AgentError::Tool {
                    name,
                    message: error.to_string(),
                },
            })
        })
    }
}

impl Default for ToolRegistry {
    fn default() -> Self {
        Self::new(8).expect("default tool page size is valid")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct NamedTool(&'static str);

    impl Tool for NamedTool {
        fn definition(&self) -> ToolDefinition {
            ToolDefinition::new(self.0, self.0, "{}")
        }

        fn execute(&self, _: ToolCall) -> ToolFuture {
            Box::pin(async { Ok(ToolOutput::success("ok")) })
        }
    }

    #[test]
    fn load_more_tools_replaces_the_visible_page() {
        let mut registry = ToolRegistry::new(2).unwrap();
        for name in ["one", "two", "three", "four", "five"] {
            registry.register(NamedTool(name)).unwrap();
        }

        let names = |tools: Vec<ToolDefinition>| {
            tools.into_iter().map(|tool| tool.name).collect::<Vec<_>>()
        };
        assert_eq!(
            names(registry.list_tools().unwrap()),
            vec!["one", "two", "load_more_tools"]
        );

        block_on(registry.execute(ToolCall::new("1", "load_more_tools", "{}"))).unwrap();
        assert_eq!(
            names(registry.list_tools().unwrap()),
            vec!["three", "four", "load_more_tools"]
        );

        block_on(registry.execute(ToolCall::new("2", "load_more_tools", "{}"))).unwrap();
        assert_eq!(names(registry.list_tools().unwrap()), vec!["five"]);
    }

    fn block_on<F: Future>(mut future: F) -> F::Output {
        use std::task::{Context, Poll, RawWaker, RawWakerVTable, Waker};
        fn clone(_: *const ()) -> RawWaker {
            RawWaker::new(std::ptr::null(), &VTABLE)
        }
        fn noop(_: *const ()) {}
        static VTABLE: RawWakerVTable = RawWakerVTable::new(clone, noop, noop, noop);
        let waker = unsafe { Waker::from_raw(RawWaker::new(std::ptr::null(), &VTABLE)) };
        let mut context = Context::from_waker(&waker);
        let mut future = unsafe { Pin::new_unchecked(&mut future) };
        loop {
            if let Poll::Ready(value) = future.as_mut().poll(&mut context) {
                return value;
            }
        }
    }
}
