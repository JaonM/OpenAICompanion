use crate::{
    AgentError, AgentRun, Configuration, Message, ModelRequest, ModelResponse, ModelServing,
    TerminationReason, ToolExecutor,
};

/// Runs one tool-use loop. State and lifecycle are owned by the caller; this
/// function only coordinates model requests and tool execution.
pub async fn run<M, E>(
    model: &mut M,
    executor: &mut E,
    config: &Configuration,
    user_input: impl Into<String>,
) -> Result<AgentRun, AgentError>
where
    M: ModelServing,
    E: ToolExecutor,
{
    if config.max_steps == 0 {
        return Err(AgentError::InvalidConfig(
            "max_steps must be greater than zero",
        ));
    }
    if config.num_tool_per_load == 0 {
        return Err(AgentError::InvalidConfig(
            "num_tool_per_load must be greater than zero",
        ));
    }

    let user_input = user_input.into();
    let mut history = vec![Message::User {
        content: user_input.clone(),
    }];

    for step in 0..config.max_steps {
        executor.refresh().await?;
        let response = model.complete(ModelRequest {
            system_prompt: config.system_prompt.clone(),
            user_input: user_input.clone(),
            history: history.clone(),
            tools: executor.list_tools()?,
        }).await?;

        if response.tool_calls.is_empty() {
            return Ok(AgentRun {
                output: response.content,
                history,
                steps: step + 1,
                termination: TerminationReason::Completed,
            });
        }
        validate_response(&response)?;
        history.push(Message::Assistant {
            content: response.content,
            tool_calls: response.tool_calls.clone(),
        });
        for call in response.tool_calls {
            let output = executor.execute(&call).await?;
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
        steps: config.max_steps,
        termination: TerminationReason::MaxStepsReached,
    })
}

fn validate_response(response: &ModelResponse) -> Result<(), AgentError> {
    if response
        .tool_calls
        .iter()
        .any(|call| call.id.trim().is_empty() || call.name.trim().is_empty())
    {
        return Err(AgentError::InvalidAction(
            "tool call id and name must not be empty".into(),
        ));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Tool, ToolCall, ToolDefinition, ToolOutput};

    struct ScriptedModel {
        responses: Vec<ModelResponse>,
    }
    impl ModelServing for ScriptedModel {
        fn complete<'a>(&'a mut self, _: ModelRequest) -> crate::ModelFuture<'a> {
            Box::pin(async move { Ok(self.responses.remove(0)) })
        }
    }
    struct Echo;
    impl Tool for Echo {
        fn definition(&self) -> ToolDefinition {
            ToolDefinition::new("echo", "Echo", "text")
        }
        fn execute<'a>(&'a self, call: &'a ToolCall) -> crate::tool::ToolFuture<'a> {
            Box::pin(async move { Ok(ToolOutput::success(&call.arguments)) })
        }
    }

    #[test]
    fn runs_tool_then_returns_model_answer() {
        let mut model = ScriptedModel {
            responses: vec![
                ModelResponse::with_tool_calls("", vec![ToolCall::new("1", "echo", "hello")]),
                ModelResponse::final_text("done"),
            ],
        };
        let mut executor = crate::ToolRegistry::new(1).unwrap();
        executor.register(Echo).unwrap();
        let result = block_on(run(
            &mut model,
            &mut executor,
            &Configuration::default(),
            "question",
        ));
        let result = result.unwrap();
        assert_eq!(result.output, "done");
    }

    fn block_on<F: std::future::Future>(mut future: F) -> F::Output {
        use std::task::{Context, Poll, RawWaker, RawWakerVTable, Waker};
        fn clone(_: *const ()) -> RawWaker { RawWaker::new(std::ptr::null(), &VTABLE) }
        fn noop(_: *const ()) {}
        static VTABLE: RawWakerVTable = RawWakerVTable::new(clone, noop, noop, noop);
        let waker = unsafe { Waker::from_raw(RawWaker::new(std::ptr::null(), &VTABLE)) };
        let mut context = Context::from_waker(&waker);
        let mut future = unsafe { std::pin::Pin::new_unchecked(&mut future) };
        loop {
            if let Poll::Ready(value) = future.as_mut().poll(&mut context) { return value; }
        }
    }
}
