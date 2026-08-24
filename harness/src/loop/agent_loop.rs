use crate::{
    AgentError, AgentRun, LoopConfig, Message, ModelRequest, ModelServe, TerminationReason,
    ToolRegistry,
};

/// Minimal tool-use loop: ask the model, execute returned tools, then feed
/// their results back into the next model request.
pub struct AgentLoop<M> {
    model: M,
    registry: ToolRegistry,
    config: LoopConfig,
}

pub struct AgentLoopBuilder<M> {
    model: M,
    registry: ToolRegistry,
    config: LoopConfig,
}

impl<M> AgentLoopBuilder<M> {
    pub fn new(model: M) -> Self {
        Self {
            model,
            registry: ToolRegistry::new(),
            config: LoopConfig::default(),
        }
    }

    pub fn with_tool(mut self, tool: impl crate::Tool + 'static) -> Result<Self, AgentError> {
        self.registry.register(tool)?;
        Ok(self)
    }

    pub fn with_config(mut self, config: LoopConfig) -> Self {
        self.config = config;
        self
    }

    pub fn build(self) -> Result<AgentLoop<M>, AgentError> {
        if self.config.max_steps == 0 {
            return Err(AgentError::InvalidConfig(
                "max_steps must be greater than zero",
            ));
        }
        Ok(AgentLoop {
            model: self.model,
            registry: self.registry,
            config: self.config,
        })
    }
}

impl<M: ModelServe> AgentLoop<M> {
    pub fn new(model: M) -> Result<Self, AgentError> {
        AgentLoopBuilder::new(model).build()
    }

    pub fn run(&mut self, user_input: impl Into<String>) -> Result<AgentRun, AgentError> {
        let user_input = user_input.into();
        let mut history = vec![Message::User {
            content: user_input.clone(),
        }];

        for step in 0..self.config.max_steps {
            let response = self.model.complete(ModelRequest {
                system_prompt: self.config.system_prompt.clone(),
                user_input: user_input.clone(),
                history: history.clone(),
                tools: self.registry.list_tools(),
            })?;

            if response.tool_calls.is_empty() {
                return Ok(AgentRun {
                    output: response.content,
                    history,
                    steps: step + 1,
                    termination: TerminationReason::Completed,
                });
            }

            if response
                .tool_calls
                .iter()
                .any(|call| call.id.trim().is_empty() || call.name.trim().is_empty())
            {
                return Err(AgentError::InvalidAction(
                    "tool call id and name must not be empty".into(),
                ));
            }

            history.push(Message::Assistant {
                content: response.content,
                tool_calls: response.tool_calls.clone(),
            });
            for call in response.tool_calls {
                let output = self.registry.execute(&call)?;
                history.push(Message::Tool {
                    call_id: call.id,
                    name: call.name,
                    content: output.content,
                    is_error: output.is_error,
                });
            }
        }

        Ok(AgentRun {
            output: String::new(),
            history,
            steps: self.config.max_steps,
            termination: TerminationReason::MaxStepsReached,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{ModelResponse, ToolCall, ToolDefinition, ToolOutput};

    struct ScriptedModel {
        responses: Vec<ModelResponse>,
    }
    impl ModelServe for ScriptedModel {
        fn complete(&mut self, _request: ModelRequest) -> Result<ModelResponse, AgentError> {
            Ok(if self.responses.is_empty() {
                ModelResponse::final_text("fallback")
            } else {
                self.responses.remove(0)
            })
        }
    }

    struct EchoTool;
    impl crate::Tool for EchoTool {
        fn definition(&self) -> ToolDefinition {
            ToolDefinition::new("echo", "Echo input", "text")
        }
        fn execute(&self, call: &ToolCall) -> Result<ToolOutput, AgentError> {
            Ok(ToolOutput::success(call.arguments.clone()))
        }
    }

    #[test]
    fn direct_answer_finishes() {
        let model = ScriptedModel {
            responses: vec![ModelResponse::final_text("answer")],
        };
        let mut agent = AgentLoop::new(model).unwrap();
        let result = agent.run("question").unwrap();
        assert_eq!(result.output, "answer");
        assert_eq!(result.termination, TerminationReason::Completed);
    }

    #[test]
    fn tool_result_returns_to_model_context() {
        let model = ScriptedModel {
            responses: vec![
                ModelResponse::with_tool_calls("", vec![ToolCall::new("call-1", "echo", "hello")]),
                ModelResponse::final_text("done"),
            ],
        };
        let mut agent = AgentLoopBuilder::new(model)
            .with_tool(EchoTool)
            .unwrap()
            .build()
            .unwrap();
        let result = agent.run("question").unwrap();
        assert_eq!(result.output, "done");
        assert!(
            matches!(result.history[2], Message::Tool { ref content, .. } if content == "hello")
        );
    }

    #[test]
    fn stops_at_max_steps() {
        struct EndlessModel;
        impl ModelServe for EndlessModel {
            fn complete(&mut self, _: ModelRequest) -> Result<ModelResponse, AgentError> {
                Ok(ModelResponse::with_tool_calls(
                    "",
                    vec![ToolCall::new("x", "echo", "x")],
                ))
            }
        }
        let config = LoopConfig {
            max_steps: 2,
            ..LoopConfig::default()
        };
        let mut agent = AgentLoopBuilder::new(EndlessModel)
            .with_tool(EchoTool)
            .unwrap()
            .with_config(config)
            .build()
            .unwrap();
        assert_eq!(
            agent.run("question").unwrap().termination,
            TerminationReason::MaxStepsReached
        );
    }

    #[test]
    fn rejects_invalid_tool_call() {
        struct InvalidModel;
        impl ModelServe for InvalidModel {
            fn complete(&mut self, _: ModelRequest) -> Result<ModelResponse, AgentError> {
                Ok(ModelResponse::with_tool_calls(
                    "",
                    vec![ToolCall::new("", "echo", "x")],
                ))
            }
        }
        let mut agent = AgentLoop::new(InvalidModel).unwrap();
        assert!(matches!(
            agent.run("question"),
            Err(AgentError::InvalidAction(_))
        ));
    }
}
