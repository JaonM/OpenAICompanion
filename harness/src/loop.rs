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
        None,
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
        None,
    )
    .await
}

/// Isolated device task; preserves only its own checkpoint, never the local chat.
pub(crate) async fn run_device_task<E: ToolExecutor + Sync>(
    model: &ModelServeWrapper, executor: &mut E, config: &Configuration,
    system_prompt: &str, history: &[Message], input: String,
) -> Result<AgentRun, AgentError> {
    run_with_history_observed_mode(model, executor, config, system_prompt, history,
        input, &mut |_| Ok(()), false, Some(crate::types::device_task_response_format())).await
}

/// Repeated delegation is a fresh action. Keep unrelated reference context and
/// the durable trace, but omit prior submissions and copied submission replies.
fn fresh_delegation_history(history: &[Message], input: &str) -> Vec<Message> {
    let starts: Vec<usize> = history.iter().enumerate().filter_map(|(i, m)|
        matches!(m, Message::User { .. }).then_some(i)).collect();
    let turns: Vec<_> = starts.iter().enumerate().map(|(n, &start)|
        &history[start..starts.get(n + 1).copied().unwrap_or(history.len())]).collect();
    let delegates = |turn: &[Message]| turn.iter().any(|m|
        matches!(m, Message::Assistant { tool_calls, .. }
            if tool_calls.iter().any(|c| c.name == "delegate_to_agent")));
    if !turns.iter().any(|turn| delegates(turn)
        && matches!(&turn[0], Message::User { content } if content == input)) {
        return history.to_vec();
    }
    let submission_replies: Vec<_> = turns.iter().filter(|turn| delegates(turn))
        .flat_map(|turn| turn.iter()).filter_map(|m| match m {
            Message::Assistant { content, tool_calls } if tool_calls.is_empty() && !content.is_empty() => Some(content),
            _ => None,
        }).collect();
    let mut result = history[..starts.first().copied().unwrap_or(history.len())].to_vec();
    for turn in turns {
        let repeated = matches!(&turn[0], Message::User { content } if content == input);
        let copied = turn.iter().any(|m| matches!(m, Message::Assistant { content, .. }
            if submission_replies.contains(&content)));
        if !repeated && !delegates(turn) && !copied { result.extend_from_slice(turn); }
    }
    result
}

// A previous delegation does not grant permission to send the next question away.
fn explicitly_remote(input: &str) -> bool {
    let text = input.to_lowercase();
    if ["不要委托", "不委托", "do not delegate", "don't delegate"].iter().any(|word| text.contains(word)) { return false; }
    ["委托给", "委托到", "交给", "发送到", "远端执行", "远程执行", "在电脑上", "在 mac 上",
        "delegate to", "delegate this", "run on", "execute on", "send to", "ask the agent"]
        .iter().any(|word| text.contains(word))

}

fn delegation_target(history: &[Message], explicit: bool) -> Option<String> {
    let index = history.iter().rposition(|message| matches!(message, Message::Tool { name, .. } if name == "route_task"))?;
    let Message::Tool { content, is_error: false, call_id, .. } = &history[index] else { return None; };
    let call = history[..index].iter().rev().find_map(|message| match message {
        Message::Assistant { tool_calls, .. } => tool_calls.iter().find(|call| call.id == *call_id),
        _ => None,
    })?;
    (|| {
        let args: serde_json::Value = serde_json::from_str(&call.arguments).ok()?;
        // Model-only work stays local unless this user request names remote execution.
        let requires_device = args["required_capabilities"].as_array().is_some_and(|items|
            items.iter().any(|item| item.as_str().is_some_and(|name| name != "model.complete")))
            || args["resource_refs"].as_array().is_some_and(|items| !items.is_empty());
        if !explicit && !requires_device { return None; }
        let result: serde_json::Value = serde_json::from_str(content).ok()?;
        if result["decision"] != "REMOTE" && !(explicit && result["decision"] == "WAITING") { return None; }
        result["agent_id"].as_str().map(str::to_owned)
    })()
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
    response_format: Option<serde_json::Value>,
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
    let model_history = if emit_events {
        fresh_delegation_history(previous_history, &user_input)
    } else {
        previous_history.to_vec()
    };
    let system_prompt = if emit_events {
        format!("{system_prompt}\nEach new user request to execute or delegate is a fresh action, even when its text repeats. Historical task handles are not proof that this new request was submitted. Only a tool result from this turn can confirm submission.")
    } else {
        system_prompt.to_owned()
    };
    let mut reasoning = String::new();

    for step in 0..config.max_step {
        cancelable(&cancellation, executor.sync_if_changed()).await??;
        let mut offered = executor.list_tools()?;
        let explicit = explicitly_remote(&user_input);
        let current_turn = &history[previous_history.len()..];
        let routed = delegation_target(current_turn, explicit);
        let has_router = offered.iter().any(|tool| tool.name == "route_task");
        let can_delegate = routed.is_some() || (explicit && !has_router);
        offered.retain(|tool| match tool.name.as_str() {
            "delegate_to_agent" => can_delegate,
            "list_remote_agents" => explicit && !has_router,
            "list_execution_devices" => !current_turn.iter().any(|message| matches!(message, Message::Tool { name, is_error: false, .. } if name == "list_execution_devices")),
            "route_task" => routed.is_none(),
            _ => true,
        });
        let blocked_execution = current_turn.iter().rev().find_map(|message| match message {
            Message::Tool { name, is_error, .. } if name == "delegate_to_agent" => Some(*is_error),
            Message::Tool { name, content, is_error, .. } if name == "route_task" => Some(*is_error ||
                serde_json::from_str::<serde_json::Value>(content).ok().is_some_and(|route|
                    matches!(route["decision"].as_str(), Some("UNSUPPORTED" | "NEEDS_USER_ACTION")))),
            _ => None,
        }).unwrap_or(false);
        let require_tool = explicit && !blocked_execution && offered.iter().any(|tool|
            matches!(tool.name.as_str(), "route_task" | "delegate_to_agent" | "list_remote_agents" | "list_execution_devices"));
        let response = cancelable(
            &cancellation,
            model.complete_with_events(
                ModelRequest {
                    response_format: response_format.clone(),
                    system_prompt: system_prompt.clone(),
                    user_input: user_input.clone(),
                    history: model_history.iter().cloned().chain(history[previous_history.len()..].iter().cloned()).collect(),
                    tools: offered.clone(),
                },
                emit_events,
                require_tool,
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
            if require_tool {
                return Err(AgentError::Model("Remote task was not submitted: the model returned text without calling a routing or delegation tool.".into()));
            }
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
        // Enforce delegation even if a model invents a call omitted from its request.
        let permitted = calls.iter().all(|call| {
            call.name != "delegate_to_agent" || (can_delegate && routed.as_ref().is_none_or(|agent| {
                    serde_json::from_str::<serde_json::Value>(&call.arguments).ok()
                        .is_some_and(|args| args["agent_id"].as_str() == Some(agent.as_str()))
                }))
        });
        let results = if permitted {
            execute_tools(executor, &calls, config, &cancellation).await?
        } else {
            calls.iter().map(|_| Ok(ToolOutput::failure(
                "Tool not permitted for this turn. Ordinary questions must be answered locally; remote execution requires a current route_task REMOTE decision for the exact agent."))).collect()
        };

        let mut delegated = false;
        let mut tool_error = false;
        for (call, result) in calls.into_iter().zip(results) {
            let output = match result {
                Ok(output) => output,
                Err(error) if config.continue_after_tool_error => {
                    ToolOutput::failure(error.to_string())
                }
                Err(error) => return Err(error),
            };
            tool_error |= output.is_error;
            delegated |= emit_events && call.name == "delegate_to_agent" && !output.is_error &&
                serde_json::from_str::<serde_json::Value>(&output.content).ok()
                    .and_then(|v| v["remote_task_id"].as_str().map(|id| !id.is_empty())).unwrap_or(false);
            history.push(Message::Tool {
                call_id: call.id,
                name: call.name,
                content: output.content,
                is_error: output.is_error,
            });
            observe(history.last().expect("tool message just appended"))?;
        }
        if delegated {
            let output = if tool_error {
                "远端任务已提交，但部分工具调用失败；请查看任务卡和记录。"
            } else {
                "远端任务已提交，请在任务卡查看进度和结果。"
            }.to_owned();
            history.push(Message::Assistant { content: output.clone(), tool_calls: vec![] });
            observe(history.last().expect("submission acknowledgement just appended"))?;
            crate::serving::notify_agent_completed(output.clone());
            return Ok(AgentRun { reasoning, output, history, steps: step + 1,
                termination: TerminationReason::Completed });
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

        async fn call_tool(&self, name: String, _: String, _: String) -> ToolCallReply {
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
        requests: std::sync::Mutex<Vec<serde_json::Value>>,
        responses: std::sync::Mutex<Vec<ModelResponse>>,
    }
    #[async_trait::async_trait]
    impl crate::ModelServeCallback for ScriptedModel {
        async fn complete(
            &self,
            request: String,
            callback: std::sync::Arc<dyn crate::ModelStreamCallback>,
        ) -> Result<(), crate::ModelServeError> {
            self.requests.lock().unwrap().push(serde_json::from_str(&request).unwrap());
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
            requests: std::sync::Mutex::new(Vec::new()),
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
    fn remote_tool_choices_progress_from_discovery_to_route_to_delegation() {
        struct RoutingTool(&'static str, &'static str);
        impl Tool for RoutingTool {
            fn definition(&self) -> ToolDefinition { ToolDefinition::new(self.0, "Routing", "{}") }
            fn execute(&self, _: ToolCall) -> crate::tool::ToolFuture {
                let output = self.1;
                Box::pin(async move { Ok(ToolOutput::success(output)) })
            }
        }
        let provider = std::sync::Arc::new(ScriptedModel {
            requests: std::sync::Mutex::new(Vec::new()),
            responses: std::sync::Mutex::new(vec![
                ModelResponse::with_tool_calls("", vec![ToolCall::new("d", "list_execution_devices", "{}")]),
                ModelResponse::with_tool_calls("", vec![ToolCall::new("r", "route_task", r#"{"required_capabilities":["model.complete"]}"#)]),
                ModelResponse::final_text("ready"),
            ]),
        });
        let model = ModelServeWrapper::new(provider.clone());
        let mut executor = crate::ToolRegistry::new(16).unwrap();
        for (name, output) in [("list_execution_devices", "{}"), ("list_remote_agents", "[]"),
            ("route_task", r#"{"decision":"REMOTE","agent_id":"mac"}"#), ("delegate_to_agent", "{}")] {
            executor.register(RoutingTool(name, output)).unwrap();
        }
        runtime().block_on(executor.initialize()).unwrap();
        let error = runtime().block_on(run(&model, &mut executor, &Configuration::default(), "", "委托给 Mac")).unwrap_err();
        assert!(error.to_string().contains("not submitted"));
        let requests = provider.requests.lock().unwrap();
        assert!(requests.iter().all(|request| request["tool_choice"] == "required"));
        let names = |index: usize| requests[index]["tools"].as_array().unwrap().iter()
            .map(|tool| tool["function"]["name"].as_str().unwrap()).collect::<Vec<_>>();
        assert!(names(0).contains(&"list_execution_devices"));
        assert!(!names(0).contains(&"list_remote_agents"));
        assert!(!names(0).contains(&"delegate_to_agent"));
        assert_eq!(names(1), vec!["route_task"]);
        assert_eq!(names(2), vec!["delegate_to_agent"]);
    }

    #[test]
    fn ordinary_question_cannot_delegate_even_if_model_ignores_offered_tools() {
        struct NeverDelegate;
        impl Tool for NeverDelegate {
            fn definition(&self) -> ToolDefinition { ToolDefinition::new("delegate_to_agent", "Delegate", "{}") }
            fn execute(&self, _: ToolCall) -> crate::tool::ToolFuture { panic!("ordinary chat must not execute delegation") }
        }
        let model = model(vec![
            ModelResponse::with_tool_calls("", vec![ToolCall::new("bad", "delegate_to_agent", r#"{"agent_id":"mac"}"#)]),
            ModelResponse::final_text("42"),
        ]);
        let mut executor = crate::ToolRegistry::new(1).unwrap();
        executor.register(NeverDelegate).unwrap();
        runtime().block_on(executor.initialize()).unwrap();
        let result = runtime().block_on(run(&model, &mut executor, &Configuration::default(), "", "17+25是多少")).unwrap();
        assert_eq!(result.output, "42");
        assert!(result.history.iter().any(|message| matches!(message, Message::Tool { is_error: true, .. })));
    }

    #[test]
    fn route_permission_requires_current_remote_execution_and_exact_target() {
        fn route(capability: &str, decision: &str) -> Vec<Message> {
            vec![
                Message::Assistant { content: "".into(), tool_calls: vec![ToolCall::new("r", "route_task", format!(r#"{{"required_capabilities":["{capability}"]}}"#))] },
                Message::Tool { call_id: "r".into(), name: "route_task".into(), content: format!(r#"{{"decision":"{decision}","agent_id":"mac"}}"#), is_error: false },
            ]
        }
        assert_eq!(delegation_target(&route("model.complete", "REMOTE"), false), None);
        assert_eq!(delegation_target(&route("model.complete", "REMOTE"), true), Some("mac".into()));
        assert_eq!(delegation_target(&route("read_file", "REMOTE"), false), Some("mac".into()));
        let mut history = route("read_file", "REMOTE"); history.extend(route("model.complete", "LOCAL"));
        assert_eq!(delegation_target(&history, true), None);
        assert!(explicitly_remote("请把任务「计算17+25，不要调用工具」委托给 Acceptance Mac"));
        assert!(!explicitly_remote("Mac 是什么？"));
        assert!(!explicitly_remote("17+25，不要委托给 Mac"));
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
    fn confirmed_delegation_finishes_without_model_replay_but_errors_can_be_corrected() {
        struct Receipt(bool);
        impl Tool for Receipt {
            fn definition(&self) -> ToolDefinition { ToolDefinition::new("delegate_to_agent", "Delegate", "{}") }
            fn execute(&self, _: ToolCall) -> crate::tool::ToolFuture {
                let output = if self.0 { ToolOutput::failure("Unknown agent") }
                    else { ToolOutput::success(r#"{"local_task_id":1,"remote_task_id":"remote1","state":"TASK_STATE_SUBMITTED"}"#) };
                Box::pin(async move { Ok(output) })
            }
        }
        for failed in [false, true] {
            let mut responses = vec![ModelResponse::with_tool_calls("", vec![ToolCall::new("d1", "delegate_to_agent", "{}")])];
            if failed { responses.push(ModelResponse::final_text("Please choose an enabled agent")); }
            let model = model(responses);
            let mut executor = crate::ToolRegistry::new(1).unwrap();
            executor.register(Receipt(failed)).unwrap();
            runtime().block_on(executor.initialize()).unwrap();
            let result = runtime().block_on(run(&model, &mut executor, &Configuration::default(), "", "Delegate to Mac")).unwrap();
            assert_eq!(result.steps, if failed { 2 } else { 1 });
            assert_eq!(result.termination, TerminationReason::Completed);
            assert_eq!(result.output, if failed { "Please choose an enabled agent" } else { "远端任务已提交，请在任务卡查看进度和结果。" });
            assert!(matches!(result.history.last(), Some(Message::Assistant { tool_calls, .. }) if tool_calls.is_empty()));
        }
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
                        policy: None,
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

#[cfg(test)]
mod delegation_context_tests {
    use super::*;
    #[test]
    fn repeated_delegation_keeps_other_context_and_durable_history() {
        let input = "Delegate this to Mac";
        let history = vec![
            Message::User { content: "The input is 17 + 25".into() },
            Message::Assistant { content: "Understood".into(), tool_calls: vec![] },
            Message::User { content: input.into() },
            Message::Assistant { content: "".into(), tool_calls: vec![ToolCall::new("c1", "delegate_to_agent", "{}")] },
            Message::Tool { call_id: "c1".into(), name: "delegate_to_agent".into(), content: "old handle".into(), is_error: false },
            Message::Assistant { content: "Submitted old task".into(), tool_calls: vec![] },
        ];
        assert_eq!(fresh_delegation_history(&history, input), history[..2]);
        assert_eq!(fresh_delegation_history(&history, "What happened?"), history);
        let mut replay = history.clone();
        replay.extend([Message::User { content: input.into() }, Message::Assistant { content: "Submitted old task".into(), tool_calls: vec![] }]);
        assert_eq!(fresh_delegation_history(&replay, input), history[..2]);
        let mut copied = history.clone();
        copied.extend([Message::User { content: "A different delegation request".into() }, Message::Assistant { content: "Submitted old task".into(), tool_calls: vec![] }]);
        assert_eq!(fresh_delegation_history(&copied, input), history[..2]);
        assert_eq!(history.len(), 6);
    }
}
