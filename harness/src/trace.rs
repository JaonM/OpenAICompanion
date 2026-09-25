//! Durable conversation trajectories stored beside medium- and long-term memory.

use rusqlite::{Connection, OptionalExtension, params};

use crate::memory::now_unix_seconds;
use crate::{MemoryError, MemoryStore, Message};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TraceStatus {
    Running,
    Completed,
    MaxStepsReached,
    Failed,
    Cancelled,
}

impl TraceStatus {
    fn as_str(self) -> &'static str {
        match self {
            Self::Running => "running",
            Self::Completed => "completed",
            Self::MaxStepsReached => "max_steps",
            Self::Failed => "failed",
            Self::Cancelled => "cancelled",
        }
    }

    fn parse(value: &str) -> Result<Self, MemoryError> {
        match value {
            "running" => Ok(Self::Running),
            "completed" => Ok(Self::Completed),
            "max_steps" => Ok(Self::MaxStepsReached),
            "failed" => Ok(Self::Failed),
            "cancelled" => Ok(Self::Cancelled),
            _ => Err(MemoryError::InvalidData(format!(
                "unknown trace status: {value}"
            ))),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TraceSession {
    pub id: i64,
    pub created_at: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TraceTurn {
    pub id: i64,
    pub session_id: i64,
    pub status: TraceStatus,
    pub user_input: String,
    pub started_at: i64,
    pub finished_at: Option<i64>,
    pub reasoning: Option<String>,
    pub output: Option<String>,
    pub steps: Option<usize>,
    pub error: Option<String>,
    pub messages: Vec<Message>,
}

pub(crate) fn create_schema(connection: &Connection) -> Result<(), MemoryError> {
    connection.execute_batch(
        "CREATE TABLE IF NOT EXISTS trace_sessions (
            id INTEGER PRIMARY KEY,
            created_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS trace_turns (
            id INTEGER PRIMARY KEY,
            session_id INTEGER NOT NULL REFERENCES trace_sessions(id) ON DELETE CASCADE,
            status TEXT NOT NULL CHECK (status IN
                ('running', 'completed', 'max_steps', 'failed', 'cancelled')),
            user_input TEXT NOT NULL,
            started_at INTEGER NOT NULL,
            finished_at INTEGER,
            reasoning TEXT,
            output TEXT,
            steps INTEGER,
            error TEXT
        );
        CREATE INDEX IF NOT EXISTS trace_turns_session ON trace_turns(session_id, id);
        CREATE TABLE IF NOT EXISTS trace_messages (
            turn_id INTEGER NOT NULL REFERENCES trace_turns(id) ON DELETE CASCADE,
            sequence INTEGER NOT NULL,
            message_json TEXT NOT NULL,
            PRIMARY KEY (turn_id, sequence)
        );",
    )?;
    Ok(())
}

impl MemoryStore {
    pub fn create_trace_session(&self) -> Result<TraceSession, MemoryError> {
        let created_at = now_unix_seconds();
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        connection.execute(
            "INSERT INTO trace_sessions (created_at) VALUES (?1)",
            [created_at],
        )?;
        Ok(TraceSession {
            id: connection.last_insert_rowid(),
            created_at,
        })
    }

    pub fn list_trace_sessions(&self) -> Result<Vec<TraceSession>, MemoryError> {
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        let mut statement =
            connection.prepare("SELECT id, created_at FROM trace_sessions ORDER BY id DESC")?;
        let rows = statement.query_map([], |row| {
            Ok(TraceSession {
                id: row.get(0)?,
                created_at: row.get(1)?,
            })
        })?;
        rows.collect::<Result<Vec<_>, _>>().map_err(Into::into)
    }

    pub fn get_trace_session(&self, id: i64) -> Result<TraceSession, MemoryError> {
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        connection
            .query_row(
                "SELECT id, created_at FROM trace_sessions WHERE id = ?1",
                [id],
                |row| {
                    Ok(TraceSession {
                        id: row.get(0)?,
                        created_at: row.get(1)?,
                    })
                },
            )
            .optional()?
            .ok_or(MemoryError::NotFound(id))
    }

    pub fn delete_trace_session(&self, id: i64) -> Result<(), MemoryError> {
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        if connection.execute("DELETE FROM trace_sessions WHERE id = ?1", [id])? == 0 {
            return Err(MemoryError::NotFound(id));
        }
        Ok(())
    }

    pub(crate) fn begin_trace_turn(
        &self,
        session_id: i64,
        user_input: &str,
    ) -> Result<i64, MemoryError> {
        let user_message = serde_json::to_string(&Message::User {
            content: user_input.into(),
        })
        .map_err(|error| MemoryError::InvalidData(error.to_string()))?;
        let mut connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        let transaction = connection.transaction()?;
        transaction.execute(
            "INSERT INTO trace_turns (session_id, status, user_input, started_at)
             VALUES (?1, 'running', ?2, ?3)",
            params![session_id, user_input, now_unix_seconds()],
        )?;
        let turn_id = transaction.last_insert_rowid();
        transaction.execute(
            "INSERT INTO trace_messages (turn_id, sequence, message_json) VALUES (?1, 0, ?2)",
            params![turn_id, user_message],
        )?;
        transaction.commit()?;
        Ok(turn_id)
    }

    pub(crate) fn append_trace_message(
        &self,
        turn_id: i64,
        message: &Message,
    ) -> Result<(), MemoryError> {
        let json = serde_json::to_string(message)
            .map_err(|error| MemoryError::InvalidData(error.to_string()))?;
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        connection.execute(
            "INSERT INTO trace_messages (turn_id, sequence, message_json)
             SELECT ?1, COALESCE(MAX(sequence) + 1, 0), ?2 FROM trace_messages WHERE turn_id = ?1",
            params![turn_id, json],
        )?;
        Ok(())
    }

    pub(crate) fn finish_trace_turn(
        &self,
        id: i64,
        status: TraceStatus,
        reasoning: Option<&str>,
        output: Option<&str>,
        steps: Option<usize>,
        error: Option<&str>,
    ) -> Result<(), MemoryError> {
        if status == TraceStatus::Running {
            return Err(MemoryError::InvalidData(
                "cannot finish with running status".into(),
            ));
        }
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        let changed = connection.execute(
            "UPDATE trace_turns SET status = ?1, finished_at = ?2, reasoning = ?3,
             output = ?4, steps = ?5, error = ?6 WHERE id = ?7 AND status = 'running'",
            params![
                status.as_str(),
                now_unix_seconds(),
                reasoning,
                output,
                steps.map(|n| n as i64),
                error,
                id
            ],
        )?;
        if changed == 0 {
            return Err(MemoryError::NotFound(id));
        }
        Ok(())
    }

    pub fn list_trace_turns(&self, session_id: i64) -> Result<Vec<TraceTurn>, MemoryError> {
        self.get_trace_session(session_id)?;
        let ids = {
            let connection = self
                .connection
                .lock()
                .map_err(|_| MemoryError::LockPoisoned)?;
            let mut statement = connection
                .prepare("SELECT id FROM trace_turns WHERE session_id = ?1 ORDER BY id")?;
            statement
                .query_map([session_id], |row| row.get::<_, i64>(0))?
                .collect::<Result<Vec<_>, _>>()?
        };
        ids.into_iter().map(|id| self.get_trace_turn(id)).collect()
    }

    pub fn get_trace_turn(&self, id: i64) -> Result<TraceTurn, MemoryError> {
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        let raw: (
            i64,
            String,
            String,
            i64,
            Option<i64>,
            Option<String>,
            Option<String>,
            Option<i64>,
            Option<String>,
        ) = connection
            .query_row(
                "SELECT session_id, status, user_input, started_at, finished_at,
                    reasoning, output, steps, error FROM trace_turns WHERE id = ?1",
                [id],
                |row| {
                    Ok((
                        row.get(0)?,
                        row.get(1)?,
                        row.get(2)?,
                        row.get(3)?,
                        row.get(4)?,
                        row.get(5)?,
                        row.get(6)?,
                        row.get(7)?,
                        row.get(8)?,
                    ))
                },
            )
            .optional()?
            .ok_or(MemoryError::NotFound(id))?;
        let mut statement = connection.prepare(
            "SELECT message_json FROM trace_messages WHERE turn_id = ?1 ORDER BY sequence",
        )?;
        let jsons = statement
            .query_map([id], |row| row.get::<_, String>(0))?
            .collect::<Result<Vec<_>, _>>()?;
        let messages = jsons
            .into_iter()
            .map(|json| {
                serde_json::from_str(&json).map_err(|error| {
                    MemoryError::InvalidData(format!("invalid trace message: {error}"))
                })
            })
            .collect::<Result<Vec<_>, _>>()?;
        Ok(TraceTurn {
            id,
            session_id: raw.0,
            status: TraceStatus::parse(&raw.1)?,
            user_input: raw.2,
            started_at: raw.3,
            finished_at: raw.4,
            reasoning: raw.5,
            output: raw.6,
            steps: raw.7.map(|n| n as usize),
            error: raw.8,
            messages,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ToolCall;

    #[test]
    fn trace_round_trips_after_reopen_and_deletes_with_session() {
        let path = std::env::temp_dir().join(format!(
            "harness-trace-{}-{}.sqlite",
            std::process::id(),
            now_unix_seconds()
        ));
        let store = MemoryStore::open(&path).unwrap();
        let session = store.create_trace_session().unwrap();
        let turn_id = store.begin_trace_turn(session.id, "查询日历").unwrap();
        store
            .append_trace_message(
                turn_id,
                &Message::Assistant {
                    content: String::new(),
                    tool_calls: vec![ToolCall::new("call-1", "get_events", "{}")],
                },
            )
            .unwrap();
        store
            .append_trace_message(
                turn_id,
                &Message::Tool {
                    call_id: "call-1".into(),
                    name: "get_events".into(),
                    content: "[]".into(),
                    is_error: false,
                },
            )
            .unwrap();
        store
            .append_trace_message(
                turn_id,
                &Message::Assistant {
                    content: "没有日程".into(),
                    tool_calls: Vec::new(),
                },
            )
            .unwrap();
        store
            .finish_trace_turn(
                turn_id,
                TraceStatus::Completed,
                Some("reason"),
                Some("没有日程"),
                Some(2),
                None,
            )
            .unwrap();
        drop(store);

        let reopened = MemoryStore::open(&path).unwrap();
        assert_eq!(
            reopened.list_trace_sessions().unwrap(),
            vec![session.clone()]
        );
        let turn = reopened.list_trace_turns(session.id).unwrap().remove(0);
        assert_eq!(turn.status, TraceStatus::Completed);
        assert_eq!(turn.reasoning.as_deref(), Some("reason"));
        assert_eq!(turn.output.as_deref(), Some("没有日程"));
        assert_eq!(turn.steps, Some(2));
        assert_eq!(turn.messages.len(), 4);
        assert!(
            matches!(&turn.messages[1], Message::Assistant { tool_calls, .. }
            if tool_calls[0].name == "get_events")
        );
        reopened.delete_trace_session(session.id).unwrap();
        assert!(reopened.get_trace_turn(turn_id).is_err());
        drop(reopened);
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn running_and_failed_attempts_remain_inspectable() {
        let store = MemoryStore::in_memory().unwrap();
        let session = store.create_trace_session().unwrap();
        let running = store.begin_trace_turn(session.id, "请求一").unwrap();
        assert_eq!(
            store.get_trace_turn(running).unwrap().status,
            TraceStatus::Running
        );
        let failed = store.begin_trace_turn(session.id, "请求二").unwrap();
        store
            .finish_trace_turn(
                failed,
                TraceStatus::Failed,
                None,
                None,
                None,
                Some("model unavailable"),
            )
            .unwrap();
        let turn = store.get_trace_turn(failed).unwrap();
        assert_eq!(turn.error.as_deref(), Some("model unavailable"));
        assert_eq!(
            turn.messages,
            vec![Message::User {
                content: "请求二".into()
            }]
        );
    }
}
