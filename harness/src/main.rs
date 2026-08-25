use harness::{
    AgentError, Configuration, ModelRequest, ModelResponse, ModelServing, Tool, ToolCall,
    ToolDefinition, ToolOutput, ToolRegistry,
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

    fn execute<'a>(&'a self, call: &'a ToolCall) -> harness::tool::ToolFuture<'a> {
        Box::pin(async move { Ok(ToolOutput::success(call.arguments.clone())) })
    }
}

fn main() -> Result<(), AgentError> {
    let config = Configuration::default();
    let mut model = DemoModel { first_turn: true };
    let mut tools = ToolRegistry::new(config.num_tool_per_load)?;
    tools.register(EchoTool)?;
    println!(
        "{:?}",
        block_on(harness::r#loop::run(
            &mut model, &mut tools, &config, "run demo",
        ))?
    );
    Ok(())
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
