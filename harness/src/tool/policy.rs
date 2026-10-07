use serde::{Deserialize, Serialize};

/// Supplied by the host, never inferred from a model-visible tool name or description.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct ToolPolicy {
    pub version: u32,
    pub origin: String,
    pub effect: String,
    pub data_class: String,
    pub requires_foreground: bool,
    pub background_eligible: bool,
    pub requires_approval: bool,
    pub retry_mode: String,
}

impl ToolPolicy {
    pub fn valid(&self) -> bool {
        self.version == 1
            && matches!(self.origin.as_str(), "device" | "remote")
            && matches!(self.effect.as_str(), "read" | "write" | "unknown")
            && matches!(self.data_class.as_str(), "public" | "personal" | "unknown")
            && matches!(self.retry_mode.as_str(), "never" | "safe_read")
            && (self.retry_mode != "safe_read" || self.effect == "read")
            && (self.effect != "write" || (self.requires_approval && self.requires_foreground && !self.background_eligible))
    }
    pub fn allows_background_read(&self) -> bool {
        self.valid() && self.effect == "read" && self.data_class == "public"
            && self.background_eligible && !self.requires_foreground && !self.requires_approval
    }
    pub fn allows_retry(&self) -> bool {
        self.valid() && self.effect == "read" && self.retry_mode == "safe_read" && !self.requires_approval
    }
}
