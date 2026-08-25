use crate::{AgentError, ModelRequest, ModelResponse};
use std::{future::Future, pin::Pin};

pub type ModelFuture<'a> =
    Pin<Box<dyn Future<Output = Result<ModelResponse, AgentError>> + Send + 'a>>;

/// Model Serving adapter. Provider SDKs should implement this trait.
pub trait ModelServing: Send {
    fn complete<'a>(&'a mut self, request: ModelRequest) -> ModelFuture<'a>;
}
