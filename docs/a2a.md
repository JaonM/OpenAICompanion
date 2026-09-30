# A2A client (iOS MVP)

The companion is an A2A client in its single local conversation. The Rust Harness
exposes `list_remote_agents` and `delegate_to_agent`; KMP handles the remote
protocol and iOS presents the approval and task UI. The wire format follows
[A2A 1.0](https://a2a-protocol.org/v1.0.0/specification/).

## Supported flow

1. Add a public Agent Card URL in Settings. The app selects a declared A2A 1.x
   `JSONRPC` interface and requires `text/plain` input and output. HTTPS is required except
   for localhost. Required extensions are rejected.
2. The model lists configured agents before delegating. A delegation approval
   shows the exact task text and destination. No local trace, memory, profile,
   or system prompt is attached automatically.
3. `SendMessage` returns either a direct message or a remote task. The app saves
   the local task before sending, saves the remote IDs after acknowledgment,
   and returns the handle to the Harness instead of waiting for completion.
4. The app polls `GetTask` for active tasks, including after startup. For
   `INPUT_REQUIRED`, the task card accepts an explicit reply to the same
   `taskId/contextId`. It also supports refusal and `CancelTask`.
5. Completed results remain in the task table and are available to subsequent
   local turns as bounded, labeled external data. The chat UI shows the result
   with its remote Agent identity.

`a2a_agents` and `a2a_tasks` live in the existing SQLite store. Disabled Agent
records stay for task provenance. A timed-out or disconnected send is marked
`SEND_UNCERTAIN` and is never retried automatically. Bearer tokens are scoped
per Agent Card URL in the iOS Keychain and are only sent when the card declares
an HTTP Bearer security scheme. Redirects are disabled for the A2A client.

## Current protocol boundary

This MVP supports text-only JSON-RPC `SendMessage`, `GetTask`, and `CancelTask`
with optional manual Bearer credentials. It does not yet support SSE streaming,
push notifications, files, automatic OAuth flows, signed Agent Card verification,
or an inbound A2A server. Agent Cards requiring unsupported authentication or
extensions are not delegated to.
