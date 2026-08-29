use std::sync::{Mutex, OnceLock};
use tokio_util::sync::CancellationToken;

static ACTIVE_TOKEN: OnceLock<Mutex<Option<CancellationToken>>> = OnceLock::new();

fn active_token() -> &'static Mutex<Option<CancellationToken>> {
    ACTIVE_TOKEN.get_or_init(|| Mutex::new(None))
}

pub fn begin() -> CancellationToken {
    let token = CancellationToken::new();
    *active_token()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(token.clone());
    token
}

pub fn cancel() {
    if let Some(token) = active_token()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .as_ref()
    {
        token.cancel();
    }
}
