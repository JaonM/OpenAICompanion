use crate::{AgentError, ModelRequest, ModelResponse, ToolCall};
use serde_json::Value;
use std::sync::{Arc, Mutex, OnceLock};
use std::sync::atomic::{AtomicUsize, Ordering};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ModelServeError {
    RequestFailed,
    Cancelled,
    Unknown,
}

impl std::fmt::Display for ModelServeError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{self:?}")
    }
}

impl std::error::Error for ModelServeError {}

static MODEL_SERVE: OnceLock<Mutex<Option<Arc<dyn ModelServeCallback>>>> = OnceLock::new();
static AGENT_EVENT_SINK: OnceLock<Mutex<Option<Arc<dyn AgentEventSink>>>> = OnceLock::new();

fn model_serve_slot() -> &'static Mutex<Option<Arc<dyn ModelServeCallback>>> {
    MODEL_SERVE.get_or_init(|| Mutex::new(None))
}

fn agent_event_sink_slot() -> &'static Mutex<Option<Arc<dyn AgentEventSink>>> {
    AGENT_EVENT_SINK.get_or_init(|| Mutex::new(None))
}

/// Foreign model adapter. The actual model request is executed by the APP/KMP layer.
#[::uniffi::export(with_foreign)]
#[::async_trait::async_trait]
pub trait ModelServeCallback: Send + Sync {
    async fn complete(
        &self,
        request_json: String,
        callback: Arc<dyn ModelStreamCallback>,
    ) -> Result<(), ModelServeError>;
}

/// Receives provider-neutral Chat Completions stream chunks from APP/KMP.
#[::uniffi::export(with_foreign)]
pub trait ModelStreamCallback: Send + Sync {
    fn on_chunk(&self, chunk_json: String);
}

/// Receives user-visible Agent Loop events in the APP layer.
#[::uniffi::export(with_foreign)]
pub trait AgentEventSink: Send + Sync {
    fn on_reasoning_delta(&self, text: String);
    fn on_text_delta(&self, text: String);
    fn on_completed(&self, final_text: String);
    fn on_error(&self, error_json: String);
}

/// Harness-side adapter that translates internal state to Chat Completions JSON.
#[derive(Clone)]
pub struct ModelServeWrapper {
    provider: Arc<dyn ModelServeCallback>,
    foreground_turns: Arc<AtomicUsize>,
}

pub(crate) struct ForegroundTurn(Arc<AtomicUsize>);
impl Drop for ForegroundTurn {
    fn drop(&mut self) { self.0.fetch_sub(1, Ordering::AcqRel); }
}

impl ModelServeWrapper {
    pub fn new(provider: Arc<dyn ModelServeCallback>) -> Self {
        Self { provider, foreground_turns: Arc::new(AtomicUsize::new(0)) }
    }

    pub(crate) fn foreground_turn(&self) -> ForegroundTurn {
        self.foreground_turns.fetch_add(1, Ordering::AcqRel);
        ForegroundTurn(Arc::clone(&self.foreground_turns))
    }

    pub fn registered() -> Result<Self, AgentError> {
        let provider = model_serve_slot()
            .lock()
            .map_err(|_| AgentError::Model("model serve lock poisoned".into()))?
            .clone()
            .ok_or_else(|| AgentError::Model("model serve is not registered".into()))?;
        Ok(Self::new(provider))
    }

    pub async fn complete(&self, request: ModelRequest) -> Result<ModelResponse, AgentError> {
        self.complete_internal(request, true).await
    }

    /// Background memory work must not emit user-visible stream events.
    pub async fn complete_silent(
        &self,
        request: ModelRequest,
    ) -> Result<ModelResponse, AgentError> {
        if self.foreground_turns.load(Ordering::Acquire) != 0 {
            return Err(AgentError::Model("background memory work deferred during foreground turn".into()));
        }
        self.complete_internal(request, false).await
    }

    pub(crate) async fn complete_with_events(
        &self,
        request: ModelRequest,
        emit_events: bool,
    ) -> Result<ModelResponse, AgentError> {
        self.complete_internal(request, emit_events).await
    }

    async fn complete_internal(
        &self,
        request: ModelRequest,
        emit_events: bool,
    ) -> Result<ModelResponse, AgentError> {
        let request_json = request.to_chat_completions_json().map_err(|error| {
            AgentError::Model(format!("failed to encode model request: {error}"))
        })?;
        let stream = Arc::new(StreamAccumulator {
            state: Mutex::new(StreamState::default()),
            sink: if emit_events {
                current_agent_event_sink()?
            } else {
                None
            },
        });
        let result = self
            .provider
            .complete(
                request_json,
                Arc::clone(&stream) as Arc<dyn ModelStreamCallback>,
            )
            .await
            .map_err(|error| AgentError::Model(format!("model request failed: {error}")));
        if let Err(error) = result {
            if let Some(sink) = &stream.sink {
                sink.on_error(serde_json::json!({"error": error.to_string()}).to_string());
            }
            return Err(error);
        }
        match Arc::clone(&stream).into_response() {
            Ok(response) => Ok(response),
            Err(error) => {
                if let Some(sink) = &stream.sink {
                    sink.on_error(serde_json::json!({"error": error.to_string()}).to_string());
                }
                Err(error)
            }
        }
    }
}

#[derive(Default)]
struct StreamAccumulator {
    state: Mutex<StreamState>,
    sink: Option<Arc<dyn AgentEventSink>>,
}

#[derive(Default)]
struct StreamState {
    reasoning: String,
    content: String,
    tool_calls: Vec<PartialToolCall>,
    error: Option<String>,
    received: bool,
}

#[derive(Default)]
struct PartialToolCall {
    id: String,
    name: String,
    arguments: String,
}

impl ModelStreamCallback for StreamAccumulator {
    fn on_chunk(&self, chunk_json: String) {
        let Ok(chunk) = serde_json::from_str::<Value>(&chunk_json) else {
            self.state.lock().expect("stream lock poisoned").error =
                Some("invalid model stream chunk JSON".into());
            return;
        };
        let mut state = self.state.lock().expect("stream lock poisoned");
        if let Some(error) = chunk["error"].as_str() {
            state.error = Some(error.to_owned());
            return;
        }
        state.received = true;
        let choice = &chunk["choices"][0];
        let is_delta = choice.get("delta").is_some();
        let message = if !is_delta {
            &choice["message"]
        } else {
            &choice["delta"]
        };
        let content_delta = message["content"].as_str().unwrap_or_default().to_owned();
        if !content_delta.is_empty() {
            state.content.push_str(&content_delta);
        }
        let reasoning_delta = message["reasoning_content"]
            .as_str()
            .or_else(|| message["reasoning"].as_str())
            .unwrap_or_default()
            .to_owned();
        if !reasoning_delta.is_empty() {
            state.reasoning.push_str(&reasoning_delta);
        }
        if let Some(tool_calls) = message["tool_calls"].as_array() {
            for tool_call in tool_calls {
                let index = tool_call["index"]
                    .as_u64()
                    .map(|index| index as usize)
                    .unwrap_or_else(|| state.tool_calls.len());
                while state.tool_calls.len() <= index {
                    state.tool_calls.push(PartialToolCall::default());
                }
                let partial = &mut state.tool_calls[index];
                if let Some(id) = tool_call["id"].as_str() {
                    partial.id.push_str(id);
                }
                let function = &tool_call["function"];
                if let Some(name) = function["name"].as_str() {
                    partial.name.push_str(name);
                }
                if let Some(arguments) = function["arguments"].as_str() {
                    partial.arguments.push_str(arguments);
                }
            }
        }
        drop(state);
        if let Some(sink) = &self.sink {
            if !reasoning_delta.is_empty() {
                sink.on_reasoning_delta(reasoning_delta);
            }
            if !content_delta.is_empty() {
                sink.on_text_delta(content_delta);
            }
        }
    }
}

impl StreamAccumulator {
    fn into_response(self: Arc<Self>) -> Result<ModelResponse, AgentError> {
        let state = self
            .state
            .lock()
            .map_err(|_| AgentError::Model("model stream lock poisoned".into()))?;
        if let Some(error) = &state.error {
            return Err(AgentError::Model(error.clone()));
        }
        if !state.received {
            return Err(AgentError::Model("model returned no stream chunks".into()));
        }
        Ok(ModelResponse {
            reasoning: state.reasoning.clone(),
            content: state.content.clone(),
            tool_calls: state
                .tool_calls
                .iter()
                .map(|call| ToolCall::new(&call.id, &call.name, &call.arguments))
                .collect(),
        })
    }
}

pub fn register_model_serve_callback(provider: Arc<dyn ModelServeCallback>) {
    *model_serve_slot()
        .lock()
        .expect("model serve lock poisoned") = Some(provider);
}

pub fn unregister_model_serve_callback() {
    *model_serve_slot()
        .lock()
        .expect("model serve lock poisoned") = None;
}

pub fn register_agent_event_sink(sink: Arc<dyn AgentEventSink>) {
    *agent_event_sink_slot()
        .lock()
        .expect("agent event sink lock poisoned") = Some(sink);
}

pub fn unregister_agent_event_sink() {
    *agent_event_sink_slot()
        .lock()
        .expect("agent event sink lock poisoned") = None;
}

pub fn notify_agent_completed(final_text: String) {
    if let Ok(sink) = agent_event_sink_slot().lock() {
        if let Some(sink) = sink.as_ref() {
            sink.on_completed(final_text);
        }
    }
}

fn current_agent_event_sink() -> Result<Option<Arc<dyn AgentEventSink>>, AgentError> {
    agent_event_sink_slot()
        .lock()
        .map_err(|_| AgentError::Model("agent event sink lock poisoned".into()))
        .map(|sink| sink.clone())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn foreground_turn_defers_new_memory_inference_then_releases_it() {
        struct Provider;
        #[async_trait::async_trait]
        impl ModelServeCallback for Provider {
            async fn complete(&self, _: String, callback: Arc<dyn ModelStreamCallback>) -> Result<(), ModelServeError> {
                callback.on_chunk(r#"{"choices":[{"delta":{"content":"ok"}}]}"#.into());
                Ok(())
            }
        }
        let model = ModelServeWrapper::new(Arc::new(Provider));
        let request = || ModelRequest { system_prompt: "".into(), user_input: "question".into(), history: vec![], tools: vec![], response_format: None };
        let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        let guard = model.foreground_turn();
        assert!(runtime.block_on(model.clone().complete_silent(request())).is_err());
        assert_eq!(runtime.block_on(model.complete(request())).unwrap().content, "ok");
        drop(guard);
        assert_eq!(runtime.block_on(model.complete_silent(request())).unwrap().content, "ok");
    }


    #[test]
    fn accumulates_reasoning_content_and_text_deltas() {
        let stream = Arc::new(StreamAccumulator {
            state: Mutex::new(StreamState::default()),
            sink: None,
        });
        stream.on_chunk(
            r#"{"choices":[{"delta":{"reasoning_content":"先分析","content":"你好"}}]}"#.into(),
        );
        stream.on_chunk(
            r#"{"choices":[{"delta":{"reasoning":"，再回答","content":"，世界"}}]}"#.into(),
        );

        let response = stream.into_response().unwrap();
        assert_eq!(response.reasoning, "先分析，再回答");
        assert_eq!(response.content, "你好，世界");
    }

    #[test]
    fn stream_error_is_reported_without_foreign_callback_error() {
        let stream = Arc::new(StreamAccumulator {
            state: Mutex::new(StreamState::default()),
            sink: None,
        });
        stream.on_chunk(r#"{"error":"model offline"}"#.into());
        let error = stream.into_response().unwrap_err();
        assert!(error.to_string().contains("model offline"));
    }
}
