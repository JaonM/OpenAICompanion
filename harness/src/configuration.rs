use std::time::Duration;

/// Harness-wide runtime configuration.
///
/// Provider-specific settings and concrete MCP transport settings belong to
/// the embedding layer; this type only contains loop-level policy.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Configuration {
    pub max_step: usize,
    pub num_tool_per_load: usize,
    /// Maximum number of tool calls that may be in flight in one model step.
    pub max_concurrent_tools: usize,
    pub tool_execute_timeout: Duration,
    pub max_tool_retries: usize,
    pub retry_backoff: Duration,
    pub continue_after_tool_error: bool,
}

impl Default for Configuration {
    fn default() -> Self {
        Self {
            max_step: 16,
            num_tool_per_load: 8,
            max_concurrent_tools: 4,
            tool_execute_timeout: Duration::from_secs(30),
            max_tool_retries: 2,
            retry_backoff: Duration::from_secs(1),
            continue_after_tool_error: true,
        }
    }
}
