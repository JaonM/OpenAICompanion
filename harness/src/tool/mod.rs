mod policy;
pub use policy::ToolPolicy;

use std::collections::{HashMap, HashSet};
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
    fn execute(&self, call: ToolCall) -> ExecutorFuture<'static, Result<ToolOutput, AgentError>>;

    fn should_retry(&self, _call: &ToolCall, error: &AgentError) -> bool {
        error.is_retryable()
    }
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
        let mut names = HashSet::new();
        for tool in &tools {
            if tool.policy.as_ref().is_some_and(|policy| !policy.valid()) {
                return Err(AgentError::InvalidAction("invalid host tool policy".into()));
            }
            let name = tool.name.trim();
            if name.is_empty() || name == "load_more_tools" || name != tool.name {
                return Err(AgentError::InvalidAction("invalid MCP tool name".into()));
            }
            if !names.insert(name) {
                return Err(AgentError::DuplicateTool(name.to_owned()));
            }
            if self.tools.contains_key(name)
                && !matches!(self.tools.get(name), Some(RegisteredTool::KmpMcp { .. }))
            {
                return Err(AgentError::DuplicateTool(name.to_owned()));
            }
        }
        let mut state = self
            .state
            .lock()
            .map_err(|_| AgentError::Model("tool registry lock poisoned".into()))?;
        self.tools
            .retain(|_, tool| !matches!(tool, RegisteredTool::KmpMcp { .. }));
        state.order.retain(|name| self.tools.contains_key(name));
        state.page_start = state.page_start.min(state.order.len());
        for tool in tools {
            let mut definition =
                ToolDefinition::new(&tool.name, &tool.description, &tool.input_schema_json);
            definition.policy = tool.policy;
            let name = definition.name.clone();
            self.tools.insert(
                name.clone(),
                RegisteredTool::KmpMcp {
                    definition,
                    provider: Arc::clone(&provider),
                },
            );
            state.order.push(name);
        }
        Ok(())
    }

    pub fn clear_mcp_tools(&mut self) -> Result<(), AgentError> {
        let mut state = self
            .state
            .lock()
            .map_err(|_| AgentError::Model("tool registry lock poisoned".into()))?;
        self.tools
            .retain(|_, tool| !matches!(tool, RegisteredTool::KmpMcp { .. }));
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

    pub(crate) fn execute_background(&self, call: ToolCall) -> ExecutorFuture<'static, Result<ToolOutput, AgentError>> {
        self.execute_in_context(call, "background")
    }

    fn execute_in_context(&self, call: ToolCall, context: &'static str) -> ExecutorFuture<'static, Result<ToolOutput, AgentError>> {
        if context == "background" && !self.background_allowed(&call.name) {
            return Box::pin(async move { Err(AgentError::ToolExecution {
                name: call.name, message: "host policy forbids background execution".into(),
                error: crate::ToolExecutionError::PermissionDenied,
            }) });
        }
        let execution = match self.tools.get(&call.name) {
            Some(RegisteredTool::Builtin(tool) | RegisteredTool::Local(tool)) => {
                Arc::clone(tool).execute(call.clone())
            }
            Some(RegisteredTool::KmpMcp { provider, .. }) => {
                let provider = Arc::clone(provider);
                let call = call.clone();
                Box::pin(async move {
                    provider
                        .call_tool(call.name.clone(), call.arguments.clone(), context.into())
                        .await
                        .into_result()
                        .map(|(output, is_error)| {
                            if is_error {
                                ToolOutput::failure(output)
                            } else {
                                ToolOutput::success(output)
                            }
                        })
                        .map_err(|(error, message)| AgentError::ToolExecution {
                            name: call.name.clone(),
                            message,
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

    pub(crate) fn background_allowed(&self, name: &str) -> bool {
        self.definition(name).and_then(|definition| definition.policy)
            .is_some_and(|policy| policy.allows_background_read())
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

    fn execute(&self, call: ToolCall) -> ExecutorFuture<'static, Result<ToolOutput, AgentError>> {
        self.execute_in_context(call, "foreground")
    }

    fn should_retry(&self, call: &ToolCall, error: &AgentError) -> bool {
        if call.name == "delegate_to_agent" || call.name == "list_remote_agents" {
            return false;
        }
        let permitted = match self.tools.get(&call.name) {
            Some(RegisteredTool::KmpMcp { definition, .. }) => definition.policy.as_ref().is_some_and(|policy| policy.allows_retry()),
            Some(_) => true,
            None => false,
        };
        permitted && error.is_retryable()
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
    use crate::{ToolCallReply, ToolListReply};

    struct UnusedProvider;

    #[async_trait::async_trait]
    impl ToolProvider for UnusedProvider {
        async fn get_tools(&self) -> ToolListReply {
            ToolListReply {
                tools: Vec::new(),
                error_code: None,
                error_message: None,
            }
        }

        async fn call_tool(&self, _: String, _: String, _: String) -> ToolCallReply {
            ToolCallReply {
                output_json: "{}".into(),
                is_error: false,
                error_code: None,
                error_message: None,
            }
        }
    }

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

    #[test]
    fn invalid_mcp_snapshot_does_not_partially_replace_existing_tools() {
        let mut registry = ToolRegistry::new(8).unwrap();
        registry.register(NamedTool("local")).unwrap();
        let provider: Arc<dyn ToolProvider> = Arc::new(UnusedProvider);
        let tool = |name: &str| McpTool {
            name: name.into(),
            description: String::new(),
            input_schema_json: "{}".into(),
            policy: None,
        };
        registry
            .replace_mcp_tools(Arc::clone(&provider), vec![tool("old")])
            .unwrap();
        let names = |registry: &ToolRegistry| {
            registry
                .list_tools()
                .unwrap()
                .into_iter()
                .map(|tool| tool.name)
                .collect::<Vec<_>>()
        };
        assert_eq!(names(&registry), ["local", "old"]);

        assert!(matches!(
            registry.replace_mcp_tools(Arc::clone(&provider), vec![tool("new"), tool("new")]),
            Err(AgentError::DuplicateTool(_))
        ));
        assert_eq!(names(&registry), ["local", "old"]);
        assert!(matches!(
            registry.replace_mcp_tools(Arc::clone(&provider), vec![tool("local")]),
            Err(AgentError::DuplicateTool(_))
        ));
        assert_eq!(names(&registry), ["local", "old"]);
        assert!(matches!(
            registry.replace_mcp_tools(Arc::clone(&provider), vec![tool(" padded ")]),
            Err(AgentError::InvalidAction(_))
        ));
        assert_eq!(names(&registry), ["local", "old"]);

        registry
            .replace_mcp_tools(provider, vec![tool("new")])
            .unwrap();
        assert_eq!(names(&registry), ["local", "new"]);
    }

    fn public_read_policy() -> ToolPolicy {
        ToolPolicy { version: 1, origin: "device".into(), effect: "read".into(), data_class: "public".into(),
            requires_foreground: false, background_eligible: true, requires_approval: false, retry_mode: "safe_read".into() }
    }

    #[test]
    fn explicit_policy_controls_background_and_retries_independently_of_names() {
        let mut registry = ToolRegistry::new(8).unwrap();
        let provider: Arc<dyn ToolProvider> = Arc::new(UnusedProvider);
        let read = public_read_policy();
        let mut write = read.clone();
        write.effect = "write".into(); write.requires_foreground = true; write.background_eligible = false;
        write.requires_approval = true; write.retry_mode = "never".into();
        let mut private = read.clone();
        private.data_class = "personal".into(); private.requires_approval = true;
        private.requires_foreground = true; private.background_eligible = false; private.retry_mode = "never".into();
        registry.replace_mcp_tools(provider, vec![
            McpTool { name:"device_get_context".into(), description:String::new(), input_schema_json:"{}".into(), policy:Some(read) },
            McpTool { name:"get_destructive_action".into(), description:String::new(), input_schema_json:"{}".into(), policy:Some(write) },
            McpTool { name:"get_calendar".into(), description:String::new(), input_schema_json:"{}".into(), policy:Some(private) },
            McpTool { name:"get_legacy".into(), description:String::new(), input_schema_json:"{}".into(), policy:None },
        ]).unwrap();
        for (name, permitted) in [("device_get_context", true), ("get_destructive_action", false), ("get_calendar", false), ("get_legacy", false)] {
            assert_eq!(registry.background_allowed(name), permitted);
            let error = AgentError::ToolExecution { name: name.into(), message:"lost connection".into(), error:crate::ToolExecutionError::NetworkUnreachable };
            assert_eq!(registry.should_retry(&ToolCall::new("1", name, "{}"), &error), permitted);
            assert_eq!(block_on(registry.execute_background(ToolCall::new("1", name, "{}"))).is_ok(), permitted);
        }
    }

    #[test]
    fn policy_change_revokes_background_access_and_invalid_policy_does_not_replace_snapshot() {
        let mut registry = ToolRegistry::new(8).unwrap();
        let provider: Arc<dyn ToolProvider> = Arc::new(UnusedProvider);
        let tool = |policy| McpTool { name:"context".into(), description:String::new(), input_schema_json:"{}".into(), policy:Some(policy) };
        registry.replace_mcp_tools(provider.clone(), vec![tool(public_read_policy())]).unwrap();
        assert!(registry.background_allowed("context"));
        let mut invalid = public_read_policy(); invalid.effect = "write".into();
        assert!(registry.replace_mcp_tools(provider.clone(), vec![tool(invalid)]).is_err());
        assert!(registry.background_allowed("context"));
        let mut revoked = public_read_policy(); revoked.requires_approval = true;
        registry.replace_mcp_tools(provider, vec![tool(revoked)]).unwrap();
        assert!(!registry.background_allowed("context"));
        assert!(block_on(registry.execute_background(ToolCall::new("1", "context", "{}"))).is_err());
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
