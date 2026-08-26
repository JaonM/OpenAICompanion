use crate::{
    AgentError, AgentRun, Configuration, Message, ModelRequest, ModelResponse, ModelServing,
    TerminationReason, ToolCall, ToolExecutor, ToolOutput,
};
use tokio::task::JoinSet;
use tokio::time::timeout;

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
    E: ToolExecutor + Sync,
{
    if config.max_step == 0 {
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
    if !executor.is_initialized() {
        return Err(AgentError::InvalidConfig(
            "tool executor must be initialized before running the agent loop",
        ));
    }

    let user_input = user_input.into();
    let mut history = vec![Message::User {
        content: user_input.clone(),
    }];

    for step in 0..config.max_step {
        executor.sync_if_changed().await?;
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
        let results = execute_tools(executor, &calls, config).await?;

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
        steps: config.max_step,
        termination: TerminationReason::MaxStepsReached,
    })
}

async fn execute_tools<E: ToolExecutor + Sync>(
    executor: &E,
    calls: &[ToolCall],
    config: &Configuration,
) -> Result<Vec<Result<ToolOutput, AgentError>>, AgentError> {
    let mut tasks = JoinSet::new();
    let mut attempts = vec![0usize; calls.len()];
    let mut results: Vec<Option<Result<ToolOutput, AgentError>>> =
        (0..calls.len()).map(|_| None).collect();
    let mut next_to_start = 0;
    let mut completed = 0;

    while completed < calls.len() {
        while tasks.len() < config.max_concurrent_tools && next_to_start < calls.len() {
            spawn_tool_attempt(
                &mut tasks,
                executor,
                calls[next_to_start].clone(),
                next_to_start,
                config.tool_execute_timeout,
            );
            next_to_start += 1;
        }

        let Some(joined) = tasks.join_next().await else {
            return Err(AgentError::Model(
                "tool task scheduler stopped unexpectedly".into(),
            ));
        };
        let (index, result) =
            joined.map_err(|error| AgentError::Model(format!("tool task failed: {error}")))?;
        if let Err(error) = &result {
            if attempts[index] < config.max_tool_retries
                && executor.is_retryable(&calls[index])
                && error.is_retryable()
            {
                let multiplier = 1u32.checked_shl(attempts[index] as u32).unwrap_or(u32::MAX);
                attempts[index] += 1;
                tokio::time::sleep(config.retry_backoff.saturating_mul(multiplier)).await;
                spawn_tool_attempt(
                    &mut tasks,
                    executor,
                    calls[index].clone(),
                    index,
                    config.tool_execute_timeout,
                );
                continue;
            }
        }
        results[index] = Some(result);
        completed += 1;
    }

    Ok(results
        .into_iter()
        .map(|result| result.expect("all tool calls completed"))
        .collect())
}

fn spawn_tool_attempt<E: ToolExecutor>(
    tasks: &mut JoinSet<(usize, Result<ToolOutput, AgentError>)>,
    executor: &E,
    call: ToolCall,
    index: usize,
    timeout_duration: std::time::Duration,
) {
    let execution = executor.execute(call.clone());
    let tool_name = call.name;
    tasks.spawn(async move {
        let result = timeout(timeout_duration, execution)
            .await
            .map_err(|_| AgentError::ToolExecution {
                name: tool_name.clone(),
                error: crate::ToolExecutionError::Timeout,
                message: format!("timed out after {} ms", timeout_duration.as_millis()),
            })
            .and_then(|result| result);
        (index, result)
    });
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
        fn execute(&self, call: ToolCall) -> crate::tool::ToolFuture {
            Box::pin(async move { Ok(ToolOutput::success(&call.arguments)) })
        }
    }

    struct HangingTool;
    impl Tool for HangingTool {
        fn definition(&self) -> ToolDefinition {
            ToolDefinition::new("hang", "Never completes", "{}")
        }

        fn execute(&self, _: ToolCall) -> crate::tool::ToolFuture {
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

        fn execute(&self, _: ToolCall) -> crate::tool::ToolFuture {
            let state = std::sync::Arc::clone(&self.state);
            Box::pin(async move {
                {
                    let mut state = state.lock().unwrap();
                    state.active += 1;
                    state.max_active = state.max_active.max(state.active);
                }
                tokio::time::sleep(std::time::Duration::from_millis(10)).await;
                state.lock().unwrap().active -= 1;
                Ok(ToolOutput::success("done"))
            })
        }
    }

    struct FlakyTool {
        attempts: std::sync::Arc<std::sync::Mutex<usize>>,
    }

    impl Tool for FlakyTool {
        fn definition(&self) -> ToolDefinition {
            ToolDefinition::new("flaky", "Fails once", "{}").with_retryable(true)
        }

        fn execute(&self, _: ToolCall) -> crate::tool::ToolFuture {
            let attempts = std::sync::Arc::clone(&self.attempts);
            Box::pin(async move {
                let mut attempts = attempts.lock().unwrap();
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
        runtime().block_on(executor.initialize()).unwrap();
        let result = runtime().block_on(run(
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
        runtime().block_on(executor.initialize()).unwrap();
        let config = Configuration {
            tool_execute_timeout: std::time::Duration::from_millis(5),
            ..Configuration::default()
        };

        let result = runtime().block_on(run(&mut model, &mut executor, &config, "question"));

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
                attempts: std::sync::Arc::new(std::sync::Mutex::new(0)),
            })
            .unwrap();
        runtime().block_on(executor.initialize()).unwrap();
        let config = Configuration {
            max_tool_retries: 1,
            retry_backoff: std::time::Duration::from_millis(1),
            ..Configuration::default()
        };

        let result = runtime()
            .block_on(run(&mut model, &mut executor, &config, "question"))
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
        runtime().block_on(executor.initialize()).unwrap();

        let result = runtime()
            .block_on(run(
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

    fn runtime() -> tokio::runtime::Runtime {
        tokio::runtime::Builder::new_current_thread()
            .enable_time()
            .build()
            .expect("test Tokio runtime should be created")
    }
}
