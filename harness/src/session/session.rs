use crate::{
    AgentError, Configuration, ModelServeWrapper, SessionContext, ToolExecutor, ToolRegistry,
};

use super::Turn;

/// Owns the state shared by all runs in one conversation session.
pub struct Session {
    pub configuration: Configuration,
    pub system_prompt: String,
    pub model_serve: ModelServeWrapper,
    pub tool_registry: ToolRegistry,
    pub turns: Vec<Turn>,
}

impl Session {
    /// Builds the session prompt and initializes the first MCP tool snapshot.
    pub async fn initialize(
        configuration: Configuration,
        history_summary: impl AsRef<str>,
    ) -> Result<Self, AgentError> {
        let context = SessionContext::initialize(history_summary.as_ref())
            .map_err(|error| AgentError::Model(error.to_string()))?;
        let model_serve = ModelServeWrapper::registered()?;
        let mut tool_registry = ToolRegistry::new(configuration.num_tool_per_load)?;
        tool_registry.initialize().await?;
        Ok(Self {
            configuration,
            system_prompt: context.system_prompt,
            model_serve,
            tool_registry,
            turns: Vec::new(),
        })
    }

    pub fn add_turn(&mut self, turn: Turn) {
        self.turns.push(turn);
    }
}
