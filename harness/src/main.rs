use harness::{
    AgentError, AgentLoopBuilder, ModelRequest, ModelResponse, ModelServe, Tool, ToolCall,
    ToolDefinition, ToolOutput,
};

// This binary is intentionally small: real model and tool adapters belong in
// applications embedding the harness library.
struct DemoModel {
    first_turn: bool,
}

impl ModelServe for DemoModel {
    fn complete(&mut self, _request: ModelRequest) -> Result<ModelResponse, AgentError> {
        if self.first_turn {
            self.first_turn = false;
            Ok(ModelResponse::with_tool_calls(
                "",
                vec![ToolCall::new("demo-1", "echo", "hello")],
            ))
        } else {
            Ok(ModelResponse::final_text("demo complete"))
        }
    }
}

struct EchoTool;
impl Tool for EchoTool {
    fn definition(&self) -> ToolDefinition {
        ToolDefinition::new("echo", "Returns the supplied text", "text")
    }

    fn execute(&self, call: &ToolCall) -> Result<ToolOutput, AgentError> {
        Ok(ToolOutput::success(call.arguments.clone()))
    }
}

fn main() -> Result<(), AgentError> {
    let mut agent = AgentLoopBuilder::new(DemoModel { first_turn: true })
        .with_tool(EchoTool)?
        .build()?;
    println!("{:?}", agent.run("run demo")?);
    Ok(())
}
