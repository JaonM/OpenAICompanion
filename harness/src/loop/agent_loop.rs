use crate::{
    AgentError, AgentEvent, AgentRun, LoopConfig, Message, ModelRequest, ModelServe,
    TerminationReason, ToolRegistry,
};

pub trait Observer {
    fn on_event(&mut self, event: AgentEvent) -> Result<(), AgentError>;
}

pub struct NoopObserver;
impl Observer for NoopObserver {
    fn on_event(&mut self, _event: AgentEvent) -> Result<(), AgentError> {
        Ok(())
    }
}

pub struct AgentLoop<M, O = NoopObserver> {
    model: M,
    registry: ToolRegistry,
    observer: O,
    config: LoopConfig,
}

pub struct AgentLoopBuilder<M, O = NoopObserver> {
    model: M,
    registry: ToolRegistry,
    observer: O,
    config: LoopConfig,
}

impl<M> AgentLoopBuilder<M> {
    pub fn new(model: M) -> Self {
        Self {
            model,
            registry: ToolRegistry::new(),
            observer: NoopObserver,
            config: LoopConfig::default(),
        }
    }
}

impl<M, O> AgentLoopBuilder<M, O> {
    pub fn with_tool(mut self, tool: impl crate::Tool + 'static) -> Result<Self, AgentError> {
        self.registry.register(tool)?;
        Ok(self)
    }

    pub fn with_observer<NO>(self, observer: NO) -> AgentLoopBuilder<M, NO> {
        AgentLoopBuilder {
            model: self.model,
            registry: self.registry,
            observer,
            config: self.config,
        }
    }

    pub fn with_config(mut self, config: LoopConfig) -> Self {
        self.config = config;
        self
    }

    pub fn build(self) -> Result<AgentLoop<M, O>, AgentError> {
        if self.config.max_steps == 0 {
            return Err(AgentError::InvalidConfig(
                "max_steps must be greater than zero",
            ));
        }
        Ok(AgentLoop {
            model: self.model,
            registry: self.registry,
            observer: self.observer,
            config: self.config,
        })
    }
}

impl<M: ModelServe> AgentLoop<M> {
    pub fn new(model: M) -> Result<Self, AgentError> {
        AgentLoopBuilder::new(model).build()
    }
}

impl<M: ModelServe, O: Observer> AgentLoop<M, O> {
    pub fn run(&mut self, user_input: impl Into<String>) -> Result<AgentRun, AgentError> {
        let user_input = user_input.into();
        let mut history = vec![Message::User {
            content: user_input.clone(),
        }];
        self.observer.on_event(AgentEvent::Started {
            user_input: user_input.clone(),
        })?;

        for step in 0..self.config.max_steps {
            self.observer
                .on_event(AgentEvent::ModelRequested { step })?;
            let response = self.model.complete(ModelRequest {
                system_prompt: self.config.system_prompt.clone(),
                user_input: user_input.clone(),
                history: history.clone(),
                tools: self.registry.list_tools(),
            })?;
            if response.tool_calls.is_empty() {
                self.observer.on_event(AgentEvent::ModelResponded {
                    step,
                    tool_calls: 0,
                })?;
                self.observer.on_event(AgentEvent::Completed {
                    step,
                    output: response.content.clone(),
                })?;
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
                .any(|call| call.name.trim().is_empty() || call.id.trim().is_empty())
            {
                return Err(AgentError::InvalidAction(
                    "tool call id and name must not be empty".into(),
                ));
            }
            self.observer.on_event(AgentEvent::ModelResponded {
                step,
                tool_calls: response.tool_calls.len(),
            })?;
            history.push(Message::Assistant {
                content: response.content,
                tool_calls: response.tool_calls.clone(),
            });
            for call in response.tool_calls {
                self.observer.on_event(AgentEvent::ToolStarted {
                    step,
                    call: call.clone(),
                })?;
                let output = self.registry.execute(&call)?;
                self.observer.on_event(AgentEvent::ToolCompleted {
                    step,
                    call: call.clone(),
                    output: output.clone(),
                })?;
                history.push(Message::Tool {
                    call_id: call.id,
                    name: call.name,
                    content: output.content,
                    is_error: output.is_error,
                });
            }
        }
        self.observer.on_event(AgentEvent::MaxStepsReached {
            max_steps: self.config.max_steps,
        })?;
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
        requests: Vec<ModelRequest>,
    }
    impl ModelServe for ScriptedModel {
        fn complete(&mut self, request: ModelRequest) -> Result<ModelResponse, AgentError> {
            self.requests.push(request);
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
    fn direct_answer_finishes_without_tool_execution() {
        let model = ScriptedModel {
            responses: vec![ModelResponse::final_text("answer")],
            requests: vec![],
        };
        let mut agent = AgentLoop::new(model).unwrap();
        let result = agent.run("question").unwrap();
        assert_eq!(result.output, "answer");
        assert_eq!(result.steps, 1);
        assert_eq!(result.termination, TerminationReason::Completed);
    }

    #[test]
    fn tool_result_is_injected_into_next_model_request() {
        let model = ScriptedModel {
            responses: vec![
                ModelResponse::with_tool_calls("", vec![ToolCall::new("call-1", "echo", "hello")]),
                ModelResponse::final_text("done"),
            ],
            requests: vec![],
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
    fn exposes_sorted_tool_definitions_and_stops_at_limit() {
        struct EmptyModel;
        impl ModelServe for EmptyModel {
            fn complete(&mut self, _: ModelRequest) -> Result<ModelResponse, AgentError> {
                Ok(ModelResponse::with_tool_calls(
                    "",
                    vec![ToolCall::new("x", "echo", "x")],
                ))
            }
        }
        let mut agent = AgentLoopBuilder::new(EmptyModel)
            .with_tool(EchoTool)
            .unwrap()
            .with_config(LoopConfig {
                max_steps: 2,
                ..LoopConfig::default()
            })
            .build()
            .unwrap();
        let result = agent.run("question").unwrap();
        assert_eq!(result.termination, TerminationReason::MaxStepsReached);
        assert_eq!(result.steps, 2);
    }

    #[test]
    fn validates_tool_calls_before_execution() {
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
