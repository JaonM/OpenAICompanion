use harness::{
    AgentError, Configuration, ModelRequest, ModelResponse, ModelServing, Tool, ToolCall,
    ToolDefinition, ToolExecutor, ToolOutput, ToolRegistry,
};

// This binary is intentionally small: real model and tool adapters belong in
// applications embedding the harness library.
struct DemoModel {
    first_turn: bool,
}

impl ModelServing for DemoModel {
    fn complete<'a>(&'a mut self, _request: ModelRequest) -> harness::ModelFuture<'a> {
        Box::pin(async move {
            if self.first_turn {
                self.first_turn = false;
                Ok(ModelResponse::with_tool_calls(
                    "",
                    vec![ToolCall::new("demo-1", "echo", "hello")],
                ))
            } else {
                Ok(ModelResponse::final_text("demo complete"))
            }
        })
    }
}

struct EchoTool;
impl Tool for EchoTool {
    fn definition(&self) -> ToolDefinition {
        ToolDefinition::new("echo", "Returns the supplied text", "text")
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
    let mut model = DemoModel { first_turn: true };
    let mut tools = ToolRegistry::new(config.num_tool_per_load)?;
    tools.register(EchoTool)?;
    runtime.block_on(tools.initialize())?;
    println!(
        "{:?}",
        runtime.block_on(harness::r#loop::run(
            &mut model, &mut tools, &config, "run demo",
        ))?
    );
    Ok(())
}
