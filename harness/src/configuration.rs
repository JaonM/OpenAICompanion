/// Harness-wide runtime configuration.
///
/// Provider-specific settings and concrete MCP transport settings belong to
/// the embedding layer; this type only contains loop-level policy.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Configuration {
    pub system_prompt: String,
    pub max_steps: usize,
    pub num_tool_per_load: usize,
}

impl Default for Configuration {
    fn default() -> Self {
        Self {
            system_prompt: String::new(),
            max_steps: 16,
            num_tool_per_load: 8,
        }
    }
}
