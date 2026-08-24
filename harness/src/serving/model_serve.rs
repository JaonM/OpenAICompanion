use crate::{AgentError, ModelRequest, ModelResponse};

/// Model Serving adapter. Provider SDKs should implement this trait.
pub trait ModelServe: Send {
    fn complete(&mut self, request: ModelRequest) -> Result<ModelResponse, AgentError>;
}
