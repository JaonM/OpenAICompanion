use harness::{
    AgentError, Configuration, ModelResponse, ModelServeCallback, ModelServeError,
    ModelServeWrapper, Tool, ToolCall, ToolDefinition, ToolExecutor, ToolOutput, ToolRegistry,
};

// This binary is intentionally small: real model and tool adapters belong in
// applications embedding the harness library.
struct DemoModel {
    first_turn: std::sync::Mutex<bool>,
}

#[async_trait::async_trait]
impl ModelServeCallback for DemoModel {
    async fn complete(
        &self,
        _: String,
        callback: std::sync::Arc<dyn harness::ModelStreamCallback>,
    ) -> Result<(), ModelServeError> {
        let mut model = self.first_turn.lock().unwrap();
        let response = if *model {
            *model = false;
            ModelResponse::with_tool_calls("", vec![ToolCall::new("demo-1", "echo", "hello")])
        } else {
            ModelResponse::final_text("demo complete")
        };
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
            serde_json::json!({"choices": [{"message": {
                "content": response.content,
                "tool_calls": tool_calls,
            }}]})
            .to_string(),
        );
        Ok(())
    }
}

struct EchoTool;
impl Tool for EchoTool {
    fn definition(&self) -> ToolDefinition {
        ToolDefinition::new("echo", "Returns the supplied text", "{}")
    }

    fn execute(&self, call: ToolCall) -> harness::tool::ToolFuture {
        Box::pin(async move { Ok(ToolOutput::success(call.arguments.clone())) })
    }
}

fn main() -> Result<(), AgentError> {
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .map_err(|error| AgentError::Model(format!("failed to create Tokio runtime: {error}")))?;
    let config = Configuration::default();
    let model = ModelServeWrapper::new(std::sync::Arc::new(DemoModel {
        first_turn: std::sync::Mutex::new(true),
    }));
    let mut tools = ToolRegistry::new(config.num_tool_per_load)?;
    tools.register(EchoTool)?;
    runtime.block_on(tools.initialize())?;
    println!(
        "{:?}",
        runtime.block_on(harness::r#loop::run(
            &model, &mut tools, &config, "run demo",
        ))?
    );
    Ok(())
}
