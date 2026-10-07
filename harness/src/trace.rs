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

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct MediumSummary {
    pub first_turn_id: i64,
    pub last_turn_id: i64,
    pub content: String,
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
        );
        CREATE TABLE IF NOT EXISTS medium_summary_blocks (
            session_id INTEGER NOT NULL REFERENCES trace_sessions(id) ON DELETE CASCADE,
            first_turn_id INTEGER NOT NULL REFERENCES trace_turns(id) ON DELETE CASCADE,
            last_turn_id INTEGER NOT NULL REFERENCES trace_turns(id) ON DELETE CASCADE,
            content TEXT NOT NULL,
            updated_at INTEGER NOT NULL,
            PRIMARY KEY (session_id, last_turn_id),
            CHECK (first_turn_id <= last_turn_id)
        );",
    )?;
    Ok(())
}

impl MemoryStore {
    /// Migrates older multi-session stores into one continuous conversation.
    pub fn ensure_single_trace_session(&self) -> Result<TraceSession, MemoryError> {
        let mut connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        let transaction = connection.transaction()?;
        let id: Option<i64> =
            transaction.query_row("SELECT MIN(id) FROM trace_sessions", [], |row| row.get(0))?;
        let id = match id {
            Some(id) => {
                transaction.execute(
                    "UPDATE trace_turns SET session_id = ?1 WHERE session_id != ?1",
                    [id],
                )?;
                transaction.execute("DELETE FROM trace_sessions WHERE id != ?1", [id])?;
                id
            }
            None => {
                transaction.execute(
                    "INSERT INTO trace_sessions (created_at) VALUES (?1)",
                    [now_unix_seconds()],
                )?;
                transaction.last_insert_rowid()
            }
        };
        let created_at = transaction.query_row(
            "SELECT created_at FROM trace_sessions WHERE id = ?1",
            [id],
            |row| row.get(0),
        )?;
        transaction.commit()?;
        Ok(TraceSession { id, created_at })
    }

    pub(crate) fn medium_summaries(
        &self,
        session_id: i64,
    ) -> Result<Vec<MediumSummary>, MemoryError> {
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        let mut statement = connection.prepare(
            "SELECT first_turn_id, last_turn_id, content FROM medium_summary_blocks WHERE session_id = ?1 ORDER BY last_turn_id",
        )?;
        let rows = statement.query_map([session_id], |row| {
            Ok(MediumSummary {
                first_turn_id: row.get(0)?,
                last_turn_id: row.get(1)?,
                content: row.get(2)?,
            })
        })?;
        rows.collect::<Result<Vec<_>, _>>().map_err(Into::into)
    }

    /// A checkpoint advances only after the model output has been validated.
    pub(crate) fn save_medium_summary(
        &self,
        session_id: i64,
        first_turn_id: i64,
        last_turn_id: i64,
        content: &str,
    ) -> Result<(), MemoryError> {
        if content.trim().is_empty() || content.chars().count() > 1_200 {
            return Err(MemoryError::InvalidData(
                "summary must contain 1..1200 characters".into(),
            ));
        }
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        connection.execute(
            "INSERT INTO medium_summary_blocks (session_id, first_turn_id, last_turn_id, content, updated_at)
             VALUES (?1, ?2, ?3, ?4, ?5)
             ON CONFLICT(session_id, last_turn_id) DO NOTHING",
            params![session_id, first_turn_id, last_turn_id, content.trim(), now_unix_seconds()],
        )?;
        Ok(())
    }

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

    pub(crate) fn recent_completed_turns(
        &self,
        session_id: i64,
        after_id: i64,
        limit: usize,
    ) -> Result<Vec<TraceTurn>, MemoryError> {
        let ids = {
            let connection = self
                .connection
                .lock()
                .map_err(|_| MemoryError::LockPoisoned)?;
            let mut statement = connection.prepare(
                "SELECT id FROM trace_turns WHERE session_id = ?1 AND id > ?2
                 AND status IN ('completed', 'max_steps') ORDER BY id DESC LIMIT ?3",
            )?;
            statement
                .query_map(params![session_id, after_id, limit as i64], |row| {
                    row.get::<_, i64>(0)
                })?
                .collect::<Result<Vec<_>, _>>()?
        };
        ids.into_iter()
            .rev()
            .map(|id| self.get_trace_turn(id))
            .collect()
    }

    pub(crate) fn summary_target(
        &self,
        session_id: i64,
        recent_limit: usize,
        block_size: usize,
    ) -> Result<Option<i64>, MemoryError> {
        if block_size == 0 {
            return Err(MemoryError::InvalidData(
                "summary block size must be positive".into(),
            ));
        }
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        let completed_count: i64 = connection.query_row(
            "SELECT COUNT(*) FROM trace_turns WHERE session_id = ?1 AND status IN ('completed', 'max_steps')",
            [session_id], |row| row.get(0),
        )?;
        let older_count = (completed_count as usize).saturating_sub(recent_limit);
        if older_count == 0 {
            return Ok(None);
        }
        let target_position = older_count.div_ceil(block_size) * block_size - 1;
        connection.query_row(
            "SELECT id FROM trace_turns WHERE session_id = ?1 AND status IN ('completed', 'max_steps')
             ORDER BY id ASC LIMIT 1 OFFSET ?2",
            params![session_id, target_position as i64], |row| row.get(0),
        ).optional().map_err(Into::into)
    }

    pub(crate) fn completed_turns_between(
        &self,
        session_id: i64,
        after_id: i64,
        through_id: i64,
    ) -> Result<Vec<TraceTurn>, MemoryError> {
        let ids = {
            let connection = self
                .connection
                .lock()
                .map_err(|_| MemoryError::LockPoisoned)?;
            let mut statement = connection.prepare(
                "SELECT id FROM trace_turns WHERE session_id = ?1 AND id > ?2 AND id <= ?3
                 AND status IN ('completed', 'max_steps') ORDER BY id",
            )?;
            statement
                .query_map(params![session_id, after_id, through_id], |row| {
                    row.get::<_, i64>(0)
                })?
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
        let turn_id = store.begin_trace_turn(session.id, "查询jin历").unwrap();
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
        store
            .save_medium_summary(session.id, turn_id, turn_id, "日程已查询")
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
        assert_eq!(
            reopened.medium_summaries(session.id).unwrap()[0].content,
            "日程已查询"
        );
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

    #[test]
    fn existing_sessions_merge_into_one_continuous_trace() {
        let store = MemoryStore::in_memory().unwrap();
        let first = store.create_trace_session().unwrap();
        let second = store.create_trace_session().unwrap();
        let old_turn = store.begin_trace_turn(first.id, "以前").unwrap();
        let new_turn = store.begin_trace_turn(second.id, "现在").unwrap();
        let canonical = store.ensure_single_trace_session().unwrap();
        assert_eq!(canonical.id, first.id);
        assert_eq!(store.list_trace_sessions().unwrap().len(), 1);
        assert_eq!(
            store.get_trace_turn(old_turn).unwrap().session_id,
            canonical.id
        );
        assert_eq!(
            store.get_trace_turn(new_turn).unwrap().session_id,
            canonical.id
        );
        assert_eq!(
            store.ensure_single_trace_session().unwrap().id,
            canonical.id
        );
    }

    #[test]
    fn raw_window_and_summary_target_ignore_failed_turns() {
        let store = MemoryStore::in_memory().unwrap();
        let session = store.create_trace_session().unwrap();
        let mut completed = Vec::new();
        for index in 0..10 {
            let id = store
                .begin_trace_turn(session.id, &format!("{index}"))
                .unwrap();
            store
                .finish_trace_turn(id, TraceStatus::Completed, None, Some("ok"), Some(1), None)
                .unwrap();
            completed.push(id);
            if index == 3 {
                let failed = store.begin_trace_turn(session.id, "failed").unwrap();
                store
                    .finish_trace_turn(failed, TraceStatus::Failed, None, None, None, Some("error"))
                    .unwrap();
            }
        }
        assert_eq!(
            store.summary_target(session.id, 8, 4).unwrap(),
            Some(completed[3])
        );
        let recent = store
            .recent_completed_turns(session.id, completed[3], 8)
            .unwrap();
        assert_eq!(recent.first().unwrap().id, completed[4]);
        assert_eq!(recent.last().unwrap().id, completed[9]);
    }
}
