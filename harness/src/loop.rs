use crate::{
    AgentError, AgentRun, Configuration, Message, ModelRequest, ModelResponse, ModelServeWrapper,
    TerminationReason, ToolCall, ToolExecutor, ToolOutput,
};
use tokio::task::JoinSet;
use tokio::time::timeout;
use tokio_util::sync::CancellationToken;

/// Runs one tool-use loop. State and lifecycle are owned by the caller; this
/// function only coordinates model requests and tool execution.
pub async fn run<E>(
    model: &ModelServeWrapper,
    executor: &mut E,
    config: &Configuration,
    system_prompt: &str,
    user_input: impl Into<String>,
) -> Result<AgentRun, AgentError>
where
    E: ToolExecutor + Sync,
{
    run_with_history(model, executor, config, system_prompt, &[], user_input).await
}

/// Runs a turn with earlier messages from the same session. The returned
/// history includes the complete final assistant message for the next turn.
pub async fn run_with_history<E>(
    model: &ModelServeWrapper,
    executor: &mut E,
    config: &Configuration,
    system_prompt: &str,
    previous_history: &[Message],
    user_input: impl Into<String>,
) -> Result<AgentRun, AgentError>
where
    E: ToolExecutor + Sync,
{
    run_with_history_observed(
        model,
        executor,
        config,
        system_prompt,
        previous_history,
        user_input,
        |_| Ok(()),
    )
    .await
}

pub(crate) async fn run_with_history_observed<E, O>(
    model: &ModelServeWrapper,
    executor: &mut E,
    config: &Configuration,
    system_prompt: &str,
    previous_history: &[Message],
    user_input: impl Into<String>,
    mut observe: O,
) -> Result<AgentRun, AgentError>
where
    E: ToolExecutor + Sync,
    O: FnMut(&Message) -> Result<(), AgentError>,
{
    run_with_history_observed_mode(
        model,
        executor,
        config,
        system_prompt,
        previous_history,
        user_input,
        &mut observe,
        true,
    )
    .await
}

/// Runs an independent background task without user-visible stream events or
/// replacing the foreground loop's cancellation token.
pub(crate) async fn run_silent<E>(
    model: &ModelServeWrapper,
    executor: &mut E,
    config: &Configuration,
    system_prompt: &str,
    user_input: impl Into<String>,
) -> Result<AgentRun, AgentError>
where
    E: ToolExecutor + Sync,
{
    run_with_history_observed_mode(
        model,
        executor,
        config,
        system_prompt,
        &[],
        user_input,
        &mut |_| Ok(()),
        false,
    )
    .await
}

async fn run_with_history_observed_mode<E, O>(
    model: &ModelServeWrapper,
    executor: &mut E,
    config: &Configuration,
    system_prompt: &str,
    previous_history: &[Message],
    user_input: impl Into<String>,
    observe: &mut O,
    emit_events: bool,
) -> Result<AgentRun, AgentError>
where
    E: ToolExecutor + Sync,
    O: FnMut(&Message) -> Result<(), AgentError>,
{
    let cancellation = if emit_events {
        crate::cancellation::begin()
    } else {
        CancellationToken::new()
    };
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
    let mut history = previous_history.to_vec();
    history.push(Message::User {
        content: user_input.clone(),
    });
    let mut reasoning = String::new();

    for step in 0..config.max_step {
        cancelable(&cancellation, executor.sync_if_changed()).await??;
        let response = cancelable(
            &cancellation,
            model.complete_with_events(
                ModelRequest {
                    system_prompt: system_prompt.to_owned(),
                    user_input: user_input.clone(),
                    history: history.clone(),
                    tools: executor.list_tools()?,
                },
                emit_events,
            ),
        )
        .await??;
        if !response.reasoning.is_empty() {
            if !reasoning.is_empty() {
                reasoning.push('\n');
            }
            reasoning.push_str(&response.reasoning);
        }

        if response.tool_calls.is_empty() {
            history.push(Message::Assistant {
                content: response.content.clone(),
                tool_calls: Vec::new(),
            });
            observe(history.last().expect("assistant message just appended"))?;
            if emit_events {
                crate::serving::notify_agent_completed(response.content.clone());
            }
            return Ok(AgentRun {
                reasoning,
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
        observe(history.last().expect("assistant message just appended"))?;
        let calls = response.tool_calls;
        let results = execute_tools(executor, &calls, config, &cancellation).await?;

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
            observe(history.last().expect("tool message just appended"))?;
        }
    }

    Ok(AgentRun {
        reasoning,
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
    cancellation: &CancellationToken,
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
                if calls[next_to_start].name == "delegate_to_agent" {
                    std::time::Duration::from_secs(180)
                } else {
                    config.tool_execute_timeout
                },
                cancellation.clone(),
            );
            next_to_start += 1;
        }

        let Some(joined) = cancelable(cancellation, tasks.join_next()).await? else {
            return Err(AgentError::Model(
                "tool task scheduler stopped unexpectedly".into(),
            ));
        };
        let (index, result) =
            joined.map_err(|error| AgentError::Model(format!("tool task failed: {error}")))?;
        if let Err(error) = &result {
            if attempts[index] < config.max_tool_retries
                && executor.should_retry(&calls[index], error)
            {
                let multiplier = 1u32.checked_shl(attempts[index] as u32).unwrap_or(u32::MAX);
                attempts[index] += 1;
                cancelable(
                    cancellation,
                    tokio::time::sleep(config.retry_backoff.saturating_mul(multiplier)),
                )
                .await?;
                spawn_tool_attempt(
                    &mut tasks,
                    executor,
                    calls[index].clone(),
                    index,
                    if calls[index].name == "delegate_to_agent" {
                        std::time::Duration::from_secs(180)
                    } else {
                        config.tool_execute_timeout
                    },
                    cancellation.clone(),
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
    cancellation: CancellationToken,
) {
    let execution = executor.execute(call.clone());
    let tool_name = call.name;
    tasks.spawn(async move {
        let result = cancelable(&cancellation, timeout(timeout_duration, execution))
            .await
            .and_then(|result| {
                result
                    .map_err(|_| AgentError::ToolExecution {
                        name: tool_name.clone(),
                        error: crate::ToolExecutionError::Timeout,
                        message: format!("timed out after {} ms", timeout_duration.as_millis()),
                    })
                    .and_then(|result| result)
            });
        (index, result)
    });
}

async fn cancelable<F, T>(token: &CancellationToken, future: F) -> Result<T, AgentError>
where
    F: std::future::Future<Output = T>,
{
    token
        .run_until_cancelled(future)
        .await
        .ok_or(AgentError::Cancelled)
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
    use crate::{
        McpTool, SessionContext, Tool, ToolCall, ToolCallReply, ToolDefinition, ToolListReply,
        ToolOutput, ToolProvider,
    };
    use std::sync::atomic::{AtomicUsize, Ordering};

    struct FailingMcpProvider {
        network_calls: std::sync::Arc<AtomicUsize>,
        timeout_calls: std::sync::Arc<AtomicUsize>,
    }

    #[async_trait::async_trait]
    impl ToolProvider for FailingMcpProvider {
        async fn get_tools(&self) -> ToolListReply {
            ToolListReply {
                tools: Vec::new(),
                error_code: None,
                error_message: None,
            }
        }

        async fn call_tool(&self, name: String, _: String) -> ToolCallReply {
            if name == "timeout" {
                self.timeout_calls.fetch_add(1, Ordering::SeqCst);
                std::future::pending().await
            }
            self.network_calls.fetch_add(1, Ordering::SeqCst);
            ToolCallReply {
                output_json: String::new(),
                is_error: false,
                error_code: Some("NETWORK_UNREACHABLE".into()),
                error_message: Some("response was lost".into()),
            }
        }
    }

    struct ScriptedModel {
        responses: std::sync::Mutex<Vec<ModelResponse>>,
    }
    #[async_trait::async_trait]
    impl crate::ModelServeCallback for ScriptedModel {
        async fn complete(
            &self,
            _: String,
            callback: std::sync::Arc<dyn crate::ModelStreamCallback>,
        ) -> Result<(), crate::ModelServeError> {
            let response = self.responses.lock().unwrap().remove(0);
            let tool_calls = response
                .tool_calls
                .iter()
                .map(|call| {
                    serde_json::json!({
                        "id": call.id,
                        "type": "function",
                        "function": { "name": call.name, "arguments": call.arguments },
                    })
                })
                .collect::<Vec<_>>();
            callback.on_chunk(
                serde_json::json!({
                    "choices": [{ "message": {
                        "content": response.content,
                        "tool_calls": tool_calls,
                    }}]
                })
                .to_string(),
            );
            Ok(())
        }
    }

    fn model(responses: Vec<ModelResponse>) -> ModelServeWrapper {
        ModelServeWrapper::new(std::sync::Arc::new(ScriptedModel {
            responses: std::sync::Mutex::new(responses),
        }))
    }
    struct Echo;
    impl Tool for Echo {
        fn definition(&self) -> ToolDefinition {
            ToolDefinition::new("echo", "Echo", "{}")
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
            ToolDefinition::new("flaky", "Fails once", "{}")
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
        let model = model(vec![
            ModelResponse::with_tool_calls("", vec![ToolCall::new("1", "echo", "hello")]),
            ModelResponse::final_text("done"),
        ]);
        let mut executor = crate::ToolRegistry::new(1).unwrap();
        executor.register(Echo).unwrap();
        runtime().block_on(executor.initialize()).unwrap();
        let result = runtime().block_on(run(
            &model,
            &mut executor,
            &Configuration::default(),
            &SessionContext::initialize("").unwrap().system_prompt,
            "question",
        ));
        let result = result.unwrap();
        assert_eq!(result.output, "done");
    }

    #[test]
    fn returns_timeout_when_tool_does_not_complete() {
        let model = model(vec![
            ModelResponse::with_tool_calls("", vec![ToolCall::new("1", "hang", "{}")]),
            ModelResponse::final_text("timeout explained"),
        ]);
        let mut executor = crate::ToolRegistry::new(1).unwrap();
        executor.register(HangingTool).unwrap();
        runtime().block_on(executor.initialize()).unwrap();
        let config = Configuration {
            tool_execute_timeout: std::time::Duration::from_millis(5),
            ..Configuration::default()
        };

        let result = runtime().block_on(run(
            &model,
            &mut executor,
            &config,
            &SessionContext::initialize("").unwrap().system_prompt,
            "question",
        ));

        let run = result.unwrap();
        assert_eq!(run.output, "timeout explained");
        assert!(run.history.iter().any(|message| matches!(
            message,
            Message::Tool { name, is_error: true, .. } if name == "hang"
        )));
    }

    #[test]
    fn retries_retryable_tool_errors() {
        let model = model(vec![
            ModelResponse::with_tool_calls("", vec![ToolCall::new("1", "flaky", "{}")]),
            ModelResponse::final_text("done"),
        ]);
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
            .block_on(run(
                &model,
                &mut executor,
                &config,
                &SessionContext::initialize("").unwrap().system_prompt,
                "question",
            ))
            .unwrap();

        assert_eq!(result.output, "done");
    }

    #[test]
    fn remote_mcp_failures_do_not_repeat_possibly_completed_calls() {
        let network_calls = std::sync::Arc::new(AtomicUsize::new(0));
        let timeout_calls = std::sync::Arc::new(AtomicUsize::new(0));
        let provider: std::sync::Arc<dyn ToolProvider> = std::sync::Arc::new(FailingMcpProvider {
            network_calls: std::sync::Arc::clone(&network_calls),
            timeout_calls: std::sync::Arc::clone(&timeout_calls),
        });
        let mut executor = crate::ToolRegistry::new(8).unwrap();
        executor
            .replace_mcp_tools(
                provider,
                ["network", "timeout"]
                    .into_iter()
                    .map(|name| McpTool {
                        name: name.into(),
                        description: String::new(),
                        input_schema_json: "{}".into(),
                    })
                    .collect(),
            )
            .unwrap();
        let config = Configuration {
            max_tool_retries: 3,
            tool_execute_timeout: std::time::Duration::from_millis(5),
            ..Configuration::default()
        };
        let calls = [
            ToolCall::new("1", "network", "{}"),
            ToolCall::new("2", "timeout", "{}"),
        ];
        let results = runtime()
            .block_on(execute_tools(
                &executor,
                &calls,
                &config,
                &CancellationToken::new(),
            ))
            .unwrap();
        assert!(results.iter().all(Result::is_err));
        assert_eq!(network_calls.load(Ordering::SeqCst), 1);
        assert_eq!(timeout_calls.load(Ordering::SeqCst), 1);
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
        let model = model(vec![
            ModelResponse::with_tool_calls(
                "",
                vec![
                    ToolCall::new("1", "first", "{}"),
                    ToolCall::new("2", "second", "{}"),
                ],
            ),
            ModelResponse::final_text("done"),
        ]);
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
                &model,
                &mut executor,
                &Configuration {
                    max_concurrent_tools,
                    ..Configuration::default()
                },
                &SessionContext::initialize("").unwrap().system_prompt,
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
