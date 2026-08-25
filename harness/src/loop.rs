use crate::{
    AgentError, AgentRun, Configuration, Message, ModelRequest, ModelResponse, ModelServing,
    TerminationReason, ToolCall, ToolExecutor, ToolOutput,
};
use futures::{StreamExt, future::Either, future::select, stream};
use futures_timer::Delay;

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
    if config.max_concurrent_tools == 0 {
        return Err(AgentError::InvalidConfig(
            "max_concurrent_tools must be greater than zero",
        ));
    }
    if config.tool_execute_timeout.is_zero() {
        return Err(AgentError::InvalidConfig(
            "tool_execute_timeout must be greater than zero",
        ));
    }
    if config.retry_backoff.is_zero() && config.max_tool_retries > 0 {
        return Err(AgentError::InvalidConfig(
            "retry_backoff must be greater than zero when retries are enabled",
        ));
    }

    let user_input = user_input.into();
    let mut history = vec![Message::User {
        content: user_input.clone(),
    }];

    for step in 0..config.max_steps {
        executor.refresh().await?;
        let response = model
            .complete(ModelRequest {
                system_prompt: config.system_prompt.clone(),
                user_input: user_input.clone(),
                history: history.clone(),
                tools: executor.list_tools()?,
            })
            .await?;

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
        let calls = response.tool_calls;
        let results = stream::iter(
            calls
                .iter()
                .map(|call| execute_tool_with_policy(executor, call, config)),
        )
        .buffered(config.max_concurrent_tools)
        .collect::<Vec<_>>()
        .await;

        for (call, result) in calls.into_iter().zip(results) {
            let output = match result {
                Ok(output) => output,
                Err(error) if config.continue_after_tool_error => {
                    ToolOutput::failure(error.to_string())
                }
                Err(error) => return Err(error),
            };
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

async fn execute_tool_with_policy<E: ToolExecutor>(
    executor: &E,
    call: &ToolCall,
    config: &Configuration,
) -> Result<crate::ToolOutput, AgentError> {
    let retryable = executor.is_retryable(call);
    for attempt in 0..=config.max_tool_retries {
        let tool_name = call.name.clone();
        let execution = executor.execute(call);
        let result = match select(execution, Delay::new(config.tool_execute_timeout)).await {
            Either::Left((result, _)) => result,
            Either::Right((_, _)) => Err(AgentError::ToolExecution {
                name: tool_name,
                error: crate::ToolExecutionError::Timeout,
                message: format!(
                    "timed out after {} ms",
                    config.tool_execute_timeout.as_millis()
                ),
            }),
        };

        match result {
            Ok(output) => return Ok(output),
            Err(error)
                if attempt < config.max_tool_retries && retryable && error.is_retryable() =>
            {
                let multiplier = 1u32.checked_shl(attempt as u32).unwrap_or(u32::MAX);
                Delay::new(config.retry_backoff.saturating_mul(multiplier)).await;
            }
            Err(error) => return Err(error),
        }
    }
    unreachable!("tool retry loop always returns")
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

    struct HangingTool;
    impl Tool for HangingTool {
        fn definition(&self) -> ToolDefinition {
            ToolDefinition::new("hang", "Never completes", "{}")
        }

        fn execute<'a>(&'a self, _: &'a ToolCall) -> crate::tool::ToolFuture<'a> {
            Box::pin(async { std::future::pending().await })
        }
    }

    struct ConcurrencyState {
        active: usize,
        max_active: usize,
    }

    struct WaitingTool {
        name: &'static str,
        state: std::sync::Arc<std::sync::Mutex<ConcurrencyState>>,
    }

    impl Tool for WaitingTool {
        fn definition(&self) -> ToolDefinition {
            ToolDefinition::new(self.name, "Waits while recording concurrency", "{}")
        }

        fn execute<'a>(&'a self, _: &'a ToolCall) -> crate::tool::ToolFuture<'a> {
            Box::pin(async move {
                {
                    let mut state = self.state.lock().unwrap();
                    state.active += 1;
                    state.max_active = state.max_active.max(state.active);
                }
                futures_timer::Delay::new(std::time::Duration::from_millis(10)).await;
                self.state.lock().unwrap().active -= 1;
                Ok(ToolOutput::success("done"))
            })
        }
    }

    struct FlakyTool {
        attempts: std::sync::Mutex<usize>,
    }

    impl Tool for FlakyTool {
        fn definition(&self) -> ToolDefinition {
            ToolDefinition::new("flaky", "Fails once", "{}").with_retryable(true)
        }

        fn execute<'a>(&'a self, _: &'a ToolCall) -> crate::tool::ToolFuture<'a> {
            Box::pin(async move {
                let mut attempts = self.attempts.lock().unwrap();
                *attempts += 1;
                if *attempts == 1 {
                    Err(AgentError::ToolExecution {
                        name: "flaky".into(),
                        error: crate::ToolExecutionError::NetworkUnreachable,
                        message: "temporary failure".into(),
                    })
                } else {
                    Ok(ToolOutput::success("recovered"))
                }
            })
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

    #[test]
    fn returns_timeout_when_tool_does_not_complete() {
        let mut model = ScriptedModel {
            responses: vec![ModelResponse::with_tool_calls(
                "",
                vec![ToolCall::new("1", "hang", "{}")],
            )],
        };
        model
            .responses
            .push(ModelResponse::final_text("timeout explained"));
        let mut executor = crate::ToolRegistry::new(1).unwrap();
        executor.register(HangingTool).unwrap();
        let config = Configuration {
            tool_execute_timeout: std::time::Duration::from_millis(5),
            ..Configuration::default()
        };

        let result =
            futures::executor::block_on(run(&mut model, &mut executor, &config, "question"));

        let run = result.unwrap();
        assert_eq!(run.output, "timeout explained");
        assert!(run.history.iter().any(|message| matches!(
            message,
            Message::Tool { name, is_error: true, .. } if name == "hang"
        )));
    }

    #[test]
    fn retries_retryable_tool_errors() {
        let mut model = ScriptedModel {
            responses: vec![
                ModelResponse::with_tool_calls("", vec![ToolCall::new("1", "flaky", "{}")]),
                ModelResponse::final_text("done"),
            ],
        };
        let mut executor = crate::ToolRegistry::new(1).unwrap();
        executor
            .register(FlakyTool {
                attempts: std::sync::Mutex::new(0),
            })
            .unwrap();
        let config = Configuration {
            max_tool_retries: 1,
            retry_backoff: std::time::Duration::from_millis(1),
            ..Configuration::default()
        };

        let result =
            futures::executor::block_on(run(&mut model, &mut executor, &config, "question"))
                .unwrap();

        assert_eq!(result.output, "done");
    }

    #[test]
    fn executes_tool_calls_concurrently() {
        assert!(run_waiting_tools(2) >= 2);
    }

    #[test]
    fn respects_configured_tool_concurrency_limit() {
        assert_eq!(run_waiting_tools(1), 1);
    }

    fn run_waiting_tools(max_concurrent_tools: usize) -> usize {
        let state = std::sync::Arc::new(std::sync::Mutex::new(ConcurrencyState {
            active: 0,
            max_active: 0,
        }));
        let mut model = ScriptedModel {
            responses: vec![
                ModelResponse::with_tool_calls(
                    "",
                    vec![
                        ToolCall::new("1", "first", "{}"),
                        ToolCall::new("2", "second", "{}"),
                    ],
                ),
                ModelResponse::final_text("done"),
            ],
        };
        let mut executor = crate::ToolRegistry::new(2).unwrap();
        executor
            .register(WaitingTool {
                name: "first",
                state: std::sync::Arc::clone(&state),
            })
            .unwrap();
        executor
            .register(WaitingTool {
                name: "second",
                state: std::sync::Arc::clone(&state),
            })
            .unwrap();

        let result = futures::executor::block_on(run(
            &mut model,
            &mut executor,
            &Configuration {
                max_concurrent_tools,
                ..Configuration::default()
            },
            "question",
        ))
        .unwrap();

        assert_eq!(result.output, "done");
        state.lock().unwrap().max_active
    }

    fn block_on<F: std::future::Future>(mut future: F) -> F::Output {
        use std::task::{Context, Poll, RawWaker, RawWakerVTable, Waker};
        fn clone(_: *const ()) -> RawWaker {
            RawWaker::new(std::ptr::null(), &VTABLE)
        }
        fn noop(_: *const ()) {}
        static VTABLE: RawWakerVTable = RawWakerVTable::new(clone, noop, noop, noop);
        let waker = unsafe { Waker::from_raw(RawWaker::new(std::ptr::null(), &VTABLE)) };
        let mut context = Context::from_waker(&waker);
        let mut future = unsafe { std::pin::Pin::new_unchecked(&mut future) };
        loop {
            if let Poll::Ready(value) = future.as_mut().poll(&mut context) {
                return value;
            }
        }
    }
}
