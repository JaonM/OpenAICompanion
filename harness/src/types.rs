#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ToolDefinition {
    pub name: String,
    pub description: String,
    /// Provider-neutral JSON Schema encoded as a string at the core boundary.
    pub parameters_schema: String,
    pub retryable: bool,
}

impl ToolDefinition {
    pub fn new(
        name: impl Into<String>,
        description: impl Into<String>,
        parameters_schema: impl Into<String>,
    ) -> Self {
        Self {
            name: name.into(),
            description: description.into(),
            parameters_schema: parameters_schema.into(),
            retryable: false,
        }
    }

    pub fn with_retryable(mut self, retryable: bool) -> Self {
        self.retryable = retryable;
        self
    }
}

/// Converts an internal tool definition into the function-tool schema expected
/// by model providers such as OpenAI-compatible APIs.
///
/// `parameters_schema` remains a JSON string at the Harness boundary so that
/// callers can provide provider-neutral JSON Schema without coupling the core
/// types to a particular schema model.
pub fn tool_definition_to_function_schema(
    definition: &ToolDefinition,
) -> Result<serde_json::Value, serde_json::Error> {
    let parameters = serde_json::from_str::<serde_json::Value>(&definition.parameters_schema)?;
    Ok(serde_json::json!({
        "type": "function",
        "function": {
            "name": definition.name,
            "description": definition.description,
            "parameters": parameters,
        }
    }))
}

#[cfg(test)]
mod tests {
    use super::{ToolDefinition, tool_definition_to_function_schema};

    #[test]
    fn converts_tool_definition_to_function_schema() {
        let definition = ToolDefinition::new(
            "get_current_weather",
            "获取指定城市的当前天气情况",
            r#"{
                "type": "object",
                "properties": {
                    "location": {
                        "type": "string",
                        "description": "城市或地区名称"
                    },
                    "unit": {
                        "type": "string",
                        "enum": ["celsius", "fahrenheit"]
                    }
                },
                "required": ["location"]
            }"#,
        );

        let schema = tool_definition_to_function_schema(&definition).unwrap();
        assert_eq!(schema["type"], "function");
        assert_eq!(schema["function"]["name"], "get_current_weather");
        assert_eq!(schema["function"]["parameters"]["required"][0], "location");
    }

    #[test]
    fn rejects_invalid_parameter_schema() {
        let definition = ToolDefinition::new("broken", "Broken", "not-json");
        assert!(tool_definition_to_function_schema(&definition).is_err());
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ToolCall {
    pub id: String,
    pub name: String,
    pub arguments: String,
}

impl ToolCall {
    pub fn new(
        id: impl Into<String>,
        name: impl Into<String>,
        arguments: impl Into<String>,
    ) -> Self {
        Self {
            id: id.into(),
            name: name.into(),
            arguments: arguments.into(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Message {
    User {
        content: String,
    },
    Assistant {
        content: String,
        tool_calls: Vec<ToolCall>,
    },
    Tool {
        call_id: String,
        name: String,
        content: String,
        is_error: bool,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ModelRequest {
    pub system_prompt: String,
    pub user_input: String,
    pub history: Vec<Message>,
    pub tools: Vec<ToolDefinition>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ModelResponse {
    pub content: String,
    pub tool_calls: Vec<ToolCall>,
}

impl ModelResponse {
    pub fn final_text(content: impl Into<String>) -> Self {
        Self {
            content: content.into(),
            tool_calls: Vec::new(),
        }
    }

    pub fn with_tool_calls(content: impl Into<String>, tool_calls: Vec<ToolCall>) -> Self {
        Self {
            content: content.into(),
            tool_calls,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ToolOutput {
    pub content: String,
    pub is_error: bool,
}

impl ToolOutput {
    pub fn success(content: impl Into<String>) -> Self {
        Self {
            content: content.into(),
            is_error: false,
        }
    }
    pub fn failure(content: impl Into<String>) -> Self {
        Self {
            content: content.into(),
            is_error: true,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TerminationReason {
    Completed,
    MaxStepsReached,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AgentRun {
    pub output: String,
    pub history: Vec<Message>,
    pub steps: usize,
    pub termination: TerminationReason,
}
