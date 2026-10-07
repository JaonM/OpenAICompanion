//! A2A delegation boundary and durable app records.

use std::sync::{Arc, Mutex, OnceLock};

use rusqlite::{Connection, params};
use serde_json::{Value, json};

use crate::ToolOutput;
use crate::memory::now_unix_seconds;
use crate::tool::ToolFuture;
use crate::{AgentError, MemoryError, MemoryStore, Tool, ToolCall, ToolDefinition};

#[::uniffi::export(with_foreign)]
#[::async_trait::async_trait]
pub trait A2aProvider: Send + Sync {
    async fn list_agents(&self) -> String;
    async fn delegate(&self, agent_id: String, task_text: String) -> String;
}

static PROVIDER: OnceLock<Mutex<Option<Arc<dyn A2aProvider>>>> = OnceLock::new();

fn slot() -> &'static Mutex<Option<Arc<dyn A2aProvider>>> {
    PROVIDER.get_or_init(|| Mutex::new(None))
}

pub fn register_provider(provider: Arc<dyn A2aProvider>) {
    *slot().lock().expect("A2A provider lock poisoned") = Some(provider);
}

pub fn unregister_provider() {
    *slot().lock().expect("A2A provider lock poisoned") = None;
}

pub(crate) fn current_provider() -> Option<Arc<dyn A2aProvider>> {
    slot().lock().ok()?.clone()
}

pub(crate) struct DelegateTool(pub Arc<dyn A2aProvider>);
pub(crate) struct ListAgentsTool(pub Arc<dyn A2aProvider>);

impl Tool for ListAgentsTool {
    fn definition(&self) -> ToolDefinition {
        ToolDefinition::new(
            "list_remote_agents",
            "List configured A2A agents and their skills before delegating a task.",
            r#"{"type":"object","properties":{},"additionalProperties":false}"#,
        )
    }

    fn execute(&self, _: ToolCall) -> ToolFuture {
        let provider = Arc::clone(&self.0);
        Box::pin(async move { Ok(ToolOutput::success(provider.list_agents().await)) })
    }
}

impl Tool for DelegateTool {
    fn definition(&self) -> ToolDefinition {
        ToolDefinition::new(
            "delegate_to_agent",
            "Delegate a bounded task to a configured remote A2A agent. Returns a task handle; use the local conversation for subsequent updates.",
            r#"{"type":"object","properties":{"agent_id":{"type":"string"},"task_text":{"type":"string"}},"required":["agent_id","task_text"],"additionalProperties":false}"#,
        )
    }

    fn execute(&self, call: ToolCall) -> ToolFuture {
        let provider = Arc::clone(&self.0);
        Box::pin(async move {
            let args: Value = serde_json::from_str(&call.arguments)
                .map_err(|error| AgentError::InvalidAction(error.to_string()))?;
            let agent_id = args["agent_id"].as_str().unwrap_or_default().trim();
            let task_text = args["task_text"].as_str().unwrap_or_default().trim();
            if agent_id.is_empty() || task_text.is_empty() {
                return Err(AgentError::InvalidAction(
                    "agent_id and task_text are required".into(),
                ));
            }
            let reply = provider.delegate(agent_id.into(), task_text.into()).await;
            let value: Value = serde_json::from_str(&reply).map_err(|error| AgentError::Tool {
                name: call.name.clone(),
                message: error.to_string(),
            })?;
            if let Some(error) = value.get("error").and_then(Value::as_str) {
                return Ok(ToolOutput::failure(error));
            }
            Ok(ToolOutput::success(reply))
        })
    }
}

pub(crate) fn create_schema(connection: &Connection) -> Result<(), MemoryError> {
    connection.execute_batch("SAVEPOINT a2a_schema;")?;
    connection.execute_batch(
        "CREATE TABLE IF NOT EXISTS a2a_agents (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            card_url TEXT NOT NULL,
            card_json TEXT NOT NULL,
            enabled INTEGER NOT NULL DEFAULT 1,
            updated_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS a2a_tasks (
            id INTEGER PRIMARY KEY,
            agent_id TEXT NOT NULL REFERENCES a2a_agents(id),
            remote_task_id TEXT,
            context_id TEXT,
            state TEXT NOT NULL,
            request_text TEXT NOT NULL,
            question TEXT,
            result TEXT,
            updated_at INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS a2a_tasks_state ON a2a_tasks(state, updated_at);",
    )?;
    let has_message_id = connection.prepare("PRAGMA table_info(a2a_tasks)")?
        .query_map([], |row| row.get::<_, String>(1))?
        .collect::<Result<Vec<_>, _>>()?.iter().any(|column| column == "pending_message_id");
    if !has_message_id {
        connection.execute_batch("ALTER TABLE a2a_tasks ADD COLUMN pending_message_id TEXT;")?;
    }
    connection.execute_batch("RELEASE a2a_schema;")?;
    Ok(())
}

impl MemoryStore {
    pub(crate) fn a2a_recent_results(&self) -> Result<Vec<(String, String, String)>, MemoryError> {
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        let mut statement = connection.prepare(
            "SELECT a.name,t.request_text,t.result FROM a2a_tasks t JOIN a2a_agents a ON a.id=t.agent_id
             WHERE t.state IN ('TASK_STATE_COMPLETED','DIRECT_MESSAGE') AND t.result IS NOT NULL
             ORDER BY t.id DESC LIMIT 3",
        )?;
        let rows = statement.query_map([], |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)))?;
        Ok(rows.collect::<Result<Vec<_>, _>>()?)
    }

    pub(crate) fn a2a_put_agent(&self, value: &Value) -> Result<Value, MemoryError> {
        let id = required(value, "id")?;
        let name = required(value, "name")?;
        let card_url = required(value, "cardUrl")?;
        let card = value
            .get("card")
            .ok_or_else(|| MemoryError::InvalidData("card is required".into()))?;
        let enabled = value
            .get("enabled")
            .and_then(Value::as_bool)
            .unwrap_or(true);
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        connection.execute(
            "INSERT INTO a2a_agents (id,name,card_url,card_json,enabled,updated_at)
             VALUES (?1,?2,?3,?4,?5,?6)
             ON CONFLICT(id) DO UPDATE SET name=excluded.name,card_url=excluded.card_url,
             card_json=excluded.card_json,enabled=excluded.enabled,updated_at=excluded.updated_at",
            params![
                id,
                name,
                card_url,
                card.to_string(),
                enabled,
                now_unix_seconds()
            ],
        )?;
        Ok(json!({"id":id}))
    }

    pub(crate) fn a2a_delete_agent(&self, id: &str) -> Result<Value, MemoryError> {
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        // Keep the card and name for the provenance of historical tasks.
        connection.execute(
            "UPDATE a2a_agents SET enabled=0,updated_at=?2 WHERE id=?1",
            params![id, now_unix_seconds()],
        )?;
        Ok(json!({"id":id}))
    }

    pub(crate) fn a2a_agents(&self) -> Result<Value, MemoryError> {
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        let mut statement = connection.prepare(
            "SELECT id,name,card_url,card_json,enabled FROM a2a_agents ORDER BY updated_at DESC",
        )?;
        let rows = statement.query_map([], |row| {
            let card: String = row.get(3)?;
            Ok(json!({"id":row.get::<_,String>(0)?,"name":row.get::<_,String>(1)?,
                "cardUrl":row.get::<_,String>(2)?,"card":serde_json::from_str::<Value>(&card).unwrap_or(Value::Null),
                "enabled":row.get::<_,bool>(4)?}))
        })?;
        Ok(Value::Array(rows.collect::<Result<Vec<_>, _>>()?))
    }

    pub(crate) fn a2a_put_task(&self, value: &Value) -> Result<Value, MemoryError> {
        let agent_id = required(value, "agentId")?;
        let state = required(value, "state")?;
        let request_text = required(value, "requestText")?;
        let id = value.get("id").and_then(Value::as_i64);
        let remote_task_id = optional(value, "remoteTaskId");
        let context_id = optional(value, "contextId");
        let question = optional(value, "question");
        let result = optional(value, "result");
        let pending_message_id = optional(value, "pendingMessageId");
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        let now = now_unix_seconds();
        if let Some(id) = id {
            let changed = connection.execute(
                "UPDATE a2a_tasks SET remote_task_id=?2,context_id=?3,state=?4,request_text=?5,question=?6,result=?7,updated_at=?8,pending_message_id=?10 WHERE id=?1 AND agent_id=?9",
                params![id,remote_task_id,context_id,state,request_text,question,result,now,agent_id,pending_message_id],
            )?;
            if changed == 0 {
                return Err(MemoryError::InvalidData("A2A task not found".into()));
            }
            Ok(json!({"id":id}))
        } else {
            connection.execute(
                "INSERT INTO a2a_tasks (agent_id,remote_task_id,context_id,state,request_text,question,result,updated_at,pending_message_id)
                 VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9)",
                params![agent_id,remote_task_id,context_id,state,request_text,question,result,now,pending_message_id],
            )?;
            Ok(json!({"id":connection.last_insert_rowid()}))
        }
    }

    pub(crate) fn a2a_tasks(&self) -> Result<Value, MemoryError> {
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        let mut statement = connection.prepare(
            "SELECT id,agent_id,remote_task_id,context_id,state,request_text,question,result,updated_at,pending_message_id
             FROM a2a_tasks ORDER BY id DESC",
        )?;
        let rows = statement.query_map([], |row| Ok(json!({
            "id":row.get::<_,i64>(0)?,"agentId":row.get::<_,String>(1)?,
            "remoteTaskId":row.get::<_,Option<String>>(2)?,"contextId":row.get::<_,Option<String>>(3)?,
            "state":row.get::<_,String>(4)?,"requestText":row.get::<_,String>(5)?,
            "question":row.get::<_,Option<String>>(6)?,"result":row.get::<_,Option<String>>(7)?,
            "updatedAt":row.get::<_,i64>(8)?,"pendingMessageId":row.get::<_,Option<String>>(9)?
        })))?;
        Ok(Value::Array(rows.collect::<Result<Vec<_>, _>>()?))
    }
}

fn required<'a>(value: &'a Value, key: &str) -> Result<&'a str, MemoryError> {
    value
        .get(key)
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .ok_or_else(|| MemoryError::InvalidData(format!("{key} is required")))
}

fn optional<'a>(value: &'a Value, key: &str) -> Option<&'a str> {
    value
        .get(key)
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
}

#[cfg(test)]
mod tests {
    use super::*;

    struct FakeProvider;

    #[async_trait::async_trait]
    impl A2aProvider for FakeProvider {
        async fn list_agents(&self) -> String {
            r#"[{"agent_id":"research"}]"#.into()
        }

        async fn delegate(&self, agent_id: String, task_text: String) -> String {
            json!({"agent_id":agent_id,"task_text":task_text,"state":"TASK_STATE_SUBMITTED"})
                .to_string()
        }
    }

    #[test]
    fn delegate_tool_forwards_only_explicit_agent_and_task() {
        let tool = DelegateTool(Arc::new(FakeProvider));
        let run = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let output = run
            .block_on(tool.execute(ToolCall::new(
                "call-1",
                "delegate_to_agent",
                r#"{"agent_id":"research","task_text":"report"}"#,
            )))
            .unwrap();
        assert!(!output.is_error);
        assert_eq!(
            serde_json::from_str::<Value>(&output.content).unwrap()["task_text"],
            "report"
        );
    }

    #[test]
    fn legacy_task_schema_preserves_records_and_adds_durable_message_identity() {
        let connection = Connection::open_in_memory().unwrap();
        connection.execute_batch("CREATE TABLE a2a_tasks (
            id INTEGER PRIMARY KEY, agent_id TEXT NOT NULL, remote_task_id TEXT, context_id TEXT,
            state TEXT NOT NULL, request_text TEXT NOT NULL, question TEXT, result TEXT, updated_at INTEGER NOT NULL);
            INSERT INTO a2a_tasks VALUES(1,'agent',NULL,NULL,'SEND_UNCERTAIN','original',NULL,NULL,1);").unwrap();
        create_schema(&connection).unwrap();
        create_schema(&connection).unwrap();
        let text: String = connection.query_row("SELECT request_text FROM a2a_tasks WHERE id=1", [], |row| row.get(0)).unwrap();
        assert_eq!(text, "original");
        let id: Option<String> = connection.query_row("SELECT pending_message_id FROM a2a_tasks WHERE id=1", [], |row| row.get(0)).unwrap();
        assert_eq!(id, None);
    }

    #[test]
    fn task_and_agent_survive_reopen_and_disabled_agent_keeps_provenance() {
        let path = std::env::temp_dir().join(format!(
            "companion-a2a-{}-{}.sqlite",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos(),
        ));
        let store = MemoryStore::open(&path).unwrap();
        store
            .a2a_put_agent(&json!({
                "id":"https://agent.example/card", "name":"Research",
                "cardUrl":"https://agent.example/card", "card":{"name":"Research"}
            }))
            .unwrap();
        let created = store
            .a2a_put_task(&json!({
                "agentId":"https://agent.example/card", "state":"TASK_STATE_INPUT_REQUIRED",
                "requestText":"summarize", "remoteTaskId":"remote-1", "question":"Which year?", "pendingMessageId":"message-1"
            }))
            .unwrap();
        let id = created["id"].as_i64().unwrap();
        drop(store);
        let reopened = MemoryStore::open(&path).unwrap();
        assert_eq!(reopened.a2a_tasks().unwrap()[0]["question"], "Which year?");
        assert_eq!(reopened.a2a_tasks().unwrap()[0]["pendingMessageId"], "message-1");
        reopened
            .a2a_put_task(&json!({
                "id":id, "agentId":"https://agent.example/card", "state":"TASK_STATE_COMPLETED",
                "requestText":"summarize", "remoteTaskId":"remote-1", "result":"done"
            }))
            .unwrap();
        reopened
            .a2a_delete_agent("https://agent.example/card")
            .unwrap();
        assert_eq!(reopened.a2a_agents().unwrap()[0]["enabled"], false);
        assert_eq!(reopened.a2a_recent_results().unwrap()[0].2, "done");
        drop(reopened);
        std::fs::remove_file(path).unwrap();
    }
}
