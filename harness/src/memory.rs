//! Local, explicit memories. Short-term conversation state lives in `Session`;
//! this store contains only cross-session medium- and long-term entries.

use std::fmt;
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

use rusqlite::{Connection, params};

const MEDIUM_TERM_DAYS: i64 = 30;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MemoryTier {
    Medium,
    Long,
}

impl MemoryTier {
    pub(crate) fn as_str(self) -> &'static str {
        match self {
            Self::Medium => "medium",
            Self::Long => "long",
        }
    }

    fn parse(value: &str) -> Result<Self, MemoryError> {
        match value {
            "medium" => Ok(Self::Medium),
            "long" => Ok(Self::Long),
            _ => Err(MemoryError::InvalidData(format!(
                "unknown memory tier: {value}"
            ))),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MemoryEntry {
    pub id: i64,
    pub tier: MemoryTier,
    pub topic_id: Option<String>,
    pub content: String,
    pub source: String,
    pub created_at: i64,
    pub updated_at: i64,
    pub expires_at: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NewMemory {
    pub tier: MemoryTier,
    pub topic_id: Option<String>,
    pub content: String,
    /// A user action or a session/turn identifier, never an unlabelled inference.
    pub source: String,
    /// Unix seconds. Medium-term entries default to 30 days; long-term entries
    /// remain until explicitly removed unless an expiry is supplied.
    pub expires_at: Option<i64>,
}

#[derive(Debug)]
pub enum MemoryError {
    Database(rusqlite::Error),
    InvalidData(String),
    NotFound(i64),
    LockPoisoned,
}

impl fmt::Display for MemoryError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Database(error) => write!(f, "memory database error: {error}"),
            Self::InvalidData(message) => write!(f, "invalid memory: {message}"),
            Self::NotFound(id) => write!(f, "memory {id} not found"),
            Self::LockPoisoned => f.write_str("memory database lock poisoned"),
        }
    }
}

impl std::error::Error for MemoryError {}

impl From<rusqlite::Error> for MemoryError {
    fn from(error: rusqlite::Error) -> Self {
        Self::Database(error)
    }
}

#[derive(Clone)]
pub struct MemoryStore {
    pub(crate) connection: Arc<Mutex<Connection>>,
}

impl MemoryStore {
    /// The embedding app chooses the file location and protects its directory.
    /// The harness never chooses a global database path on the user's behalf.
    pub fn open(path: impl AsRef<Path>) -> Result<Self, MemoryError> {
        let connection = Connection::open(path)?;
        Self::from_connection(connection)
    }

    pub fn in_memory() -> Result<Self, MemoryError> {
        Self::from_connection(Connection::open_in_memory()?)
    }

    fn from_connection(connection: Connection) -> Result<Self, MemoryError> {
        let version: i64 = connection.query_row("PRAGMA user_version", [], |row| row.get(0))?;
        if version > 1 {
            return Err(MemoryError::InvalidData("database requires a newer app; downgrade refused".into()));
        }
        connection.execute_batch("PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;")?;
        connection.execute_batch(
            "CREATE TABLE IF NOT EXISTS memories (
                id INTEGER PRIMARY KEY,
                tier TEXT NOT NULL CHECK (tier IN ('medium', 'long')),
                topic_id TEXT,
                content TEXT NOT NULL,
                source TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'archived')),
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                expires_at INTEGER
            );
            CREATE INDEX IF NOT EXISTS memories_tier_expiry
                ON memories(tier, expires_at);",
        )?;
        crate::trace::create_schema(&connection)?;
        crate::memory_sync::create_schema(&connection)?;
        connection.execute_batch(
            "CREATE TABLE IF NOT EXISTS memory_point_sources (
                memory_id INTEGER PRIMARY KEY REFERENCES memories(id) ON DELETE CASCADE,
                turn_id INTEGER NOT NULL REFERENCES trace_turns(id) ON DELETE CASCADE,
                kind TEXT NOT NULL,
                evidence TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS memory_extracted_turns (
                turn_id INTEGER PRIMARY KEY REFERENCES trace_turns(id) ON DELETE CASCADE,
                processed_at INTEGER NOT NULL
            );",
        )?;
        crate::device_operations::create_schema(&connection)?;
        crate::a2a::create_schema(&connection)?;
        crate::proactive::create_schema(&connection)?;
        connection.execute_batch("PRAGMA user_version = 1;")?;
        crate::memory_sync::install_wake_hooks(&connection);
        Ok(Self {
            connection: Arc::new(Mutex::new(connection)),
        })
    }

    pub fn remember(&self, input: NewMemory) -> Result<MemoryEntry, MemoryError> {
        let content = input.content.trim();
        let source = input.source.trim();
        if content.is_empty() || source.is_empty() {
            return Err(MemoryError::InvalidData(
                "content and source must not be empty".into(),
            ));
        }
        let now = now_unix_seconds();
        let expires_at = input.expires_at.or_else(|| {
            (input.tier == MemoryTier::Medium).then_some(now + MEDIUM_TERM_DAYS * 86_400)
        });
        if expires_at.is_some_and(|expiry| expiry <= now) {
            return Err(MemoryError::InvalidData(
                "expiry must be in the future".into(),
            ));
        }
        let topic_id = input.topic_id.filter(|topic| !topic.trim().is_empty());
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        connection.execute(
            "INSERT INTO memories (tier, topic_id, content, source, created_at, updated_at, expires_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?5, ?6)",
            params![input.tier.as_str(), topic_id, content, source, now, expires_at],
        )?;
        Ok(MemoryEntry {
            id: connection.last_insert_rowid(),
            tier: input.tier,
            topic_id,
            content: content.to_owned(),
            source: source.to_owned(),
            created_at: now,
            updated_at: now,
            expires_at,
        })
    }

    /// Replaces a memory after a user correction, preserving its stable id.
    pub fn update(&self, id: i64, content: &str, source: &str) -> Result<(), MemoryError> {
        if content.trim().is_empty() || source.trim().is_empty() {
            return Err(MemoryError::InvalidData(
                "content and source must not be empty".into(),
            ));
        }
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        let changed = connection.execute(
            "UPDATE memories SET content = ?1, source = ?2, updated_at = ?3 WHERE id = ?4",
            params![content.trim(), source.trim(), now_unix_seconds(), id],
        )?;
        if changed == 0 {
            return Err(MemoryError::NotFound(id));
        }
        Ok(())
    }

    /// A medium-term item can become durable after the user confirms it is a
    /// stable fact. Promotion removes its expiry.
    pub fn promote(&self, id: i64, source: &str) -> Result<(), MemoryError> {
        if source.trim().is_empty() {
            return Err(MemoryError::InvalidData("source must not be empty".into()));
        }
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        let changed = connection.execute(
            "UPDATE memories SET tier = 'long', expires_at = NULL, source = ?1, updated_at = ?2
             WHERE id = ?3 AND tier = 'medium' AND status = 'active'",
            params![source.trim(), now_unix_seconds(), id],
        )?;
        if changed == 0 {
            return Err(MemoryError::NotFound(id));
        }
        Ok(())
    }

    /// Completed topics leave the active recall set without deleting the
    /// record. `forget` is reserved for explicit removal.
    pub fn archive_topic(&self, topic_id: &str) -> Result<usize, MemoryError> {
        if topic_id.trim().is_empty() {
            return Err(MemoryError::InvalidData(
                "topic id must not be empty".into(),
            ));
        }
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        Ok(connection.execute(
            "UPDATE memories SET status = 'archived', updated_at = ?1
             WHERE tier = 'medium' AND topic_id = ?2 AND status = 'active'",
            params![now_unix_seconds(), topic_id.trim()],
        )?)
    }

    pub fn forget(&self, id: i64) -> Result<(), MemoryError> {
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        let changed = connection.execute("DELETE FROM memories WHERE id = ?1", [id])?;
        if changed == 0 {
            return Err(MemoryError::NotFound(id));
        }
        Ok(())
    }

    pub fn list_active(&self) -> Result<Vec<MemoryEntry>, MemoryError> {
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        let mut statement = connection.prepare(
            "SELECT id, tier, topic_id, content, source, created_at, updated_at, expires_at
             FROM memories WHERE status = 'active' AND (expires_at IS NULL OR expires_at > ?1)
             ORDER BY updated_at DESC, id DESC",
        )?;
        let rows = statement.query_map([now_unix_seconds()], row_to_memory)?;
        rows.map(|row| row?.try_into()).collect()
    }

    /// Deterministic local lexical retrieval. No model call or network access.
    pub fn search(&self, query: &str, limit: usize) -> Result<Vec<MemoryEntry>, MemoryError> {
        if query.trim().is_empty() || limit == 0 {
            return Ok(Vec::new());
        }
        let mut ranked = self
            .list_active()?
            .into_iter()
            .filter_map(|entry| {
                let score = relevance(query, &entry);
                (score > 0).then_some((score, entry))
            })
            .collect::<Vec<_>>();
        ranked.sort_by(|(a_score, a), (b_score, b)| {
            b_score
                .cmp(a_score)
                .then_with(|| b.updated_at.cmp(&a.updated_at))
                .then_with(|| b.id.cmp(&a.id))
        });
        Ok(ranked
            .into_iter()
            .take(limit)
            .map(|(_, entry)| entry)
            .collect())
    }
}

type RawMemory = (
    i64,
    String,
    Option<String>,
    String,
    String,
    i64,
    i64,
    Option<i64>,
);

fn row_to_memory(row: &rusqlite::Row<'_>) -> rusqlite::Result<RawMemory> {
    Ok((
        row.get(0)?,
        row.get(1)?,
        row.get(2)?,
        row.get(3)?,
        row.get(4)?,
        row.get(5)?,
        row.get(6)?,
        row.get(7)?,
    ))
}

impl TryFrom<RawMemory> for MemoryEntry {
    type Error = MemoryError;

    fn try_from(raw: RawMemory) -> Result<Self, Self::Error> {
        Ok(Self {
            id: raw.0,
            tier: MemoryTier::parse(&raw.1)?,
            topic_id: raw.2,
            content: raw.3,
            source: raw.4,
            created_at: raw.5,
            updated_at: raw.6,
            expires_at: raw.7,
        })
    }
}

pub(crate) fn now_unix_seconds() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64
}

fn relevance(query: &str, entry: &MemoryEntry) -> usize {
    let haystack = format!(
        "{} {}",
        entry.topic_id.as_deref().unwrap_or_default(),
        entry.content
    )
    .to_lowercase();
    let query = query.to_lowercase();
    let words = query
        .split(|c: char| !c.is_alphanumeric())
        .filter(|word| !word.is_empty())
        .collect::<Vec<_>>();
    let word_score = words
        .iter()
        .filter(|word| haystack.contains(**word))
        .count()
        * 3;
    let chars = query
        .chars()
        .filter(|c| c.is_alphanumeric())
        .collect::<Vec<_>>();
    let pair_score = chars
        .windows(2)
        .filter(|pair| haystack.contains(&pair.iter().collect::<String>()))
        .count();
    word_score + pair_score
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn persists_across_connections_and_forgets_immediately() {
        let path = std::env::temp_dir().join(format!(
            "harness-memory-{}-{}.sqlite",
            std::process::id(),
            now_unix_seconds()
        ));
        let store = MemoryStore::open(&path).unwrap();
        let memory = store
            .remember(NewMemory {
                tier: MemoryTier::Medium,
                topic_id: Some("旅行计划".into()),
                content: "下周继续讨论北京旅行".into(),
                source: "user:turn-1".into(),
                expires_at: None,
            })
            .unwrap();
        assert!(memory.expires_at.is_some());
        drop(store);
        let reopened = MemoryStore::open(&path).unwrap();
        assert_eq!(reopened.search("北京旅行", 5).unwrap()[0].id, memory.id);
        reopened.forget(memory.id).unwrap();
        assert!(reopened.search("北京旅行", 5).unwrap().is_empty());
        drop(reopened);
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn long_term_is_durable_and_expired_entries_are_hidden() {
        let store = MemoryStore::in_memory().unwrap();
        let entry = store
            .remember(NewMemory {
                tier: MemoryTier::Long,
                topic_id: None,
                content: "不坐红眼航班".into(),
                source: "user:turn-2".into(),
                expires_at: None,
            })
            .unwrap();
        assert_eq!(entry.expires_at, None);
        store
            .update(entry.id, "不坐夜间航班", "user:turn-3")
            .unwrap();
        assert_eq!(
            store.search("夜间航班", 5).unwrap()[0].source,
            "user:turn-3"
        );
        assert!(
            store
                .remember(NewMemory {
                    tier: MemoryTier::Medium,
                    topic_id: None,
                    content: "过期事项".into(),
                    source: "user:turn-4".into(),
                    expires_at: Some(now_unix_seconds() - 1),
                })
                .is_err()
        );
    }

    #[test]
    fn completed_topics_disappear_and_promoted_items_survive() {
        let store = MemoryStore::in_memory().unwrap();
        let medium = store
            .remember(NewMemory {
                tier: MemoryTier::Medium,
                topic_id: Some("travel".into()),
                content: "旅行安排待确认".into(),
                source: "user:turn-1".into(),
                expires_at: None,
            })
            .unwrap();
        store.promote(medium.id, "user:turn-2").unwrap();
        assert_eq!(store.list_active().unwrap()[0].tier, MemoryTier::Long);
        assert_eq!(store.list_active().unwrap()[0].expires_at, None);
        let another = store
            .remember(NewMemory {
                tier: MemoryTier::Medium,
                topic_id: Some("travel".into()),
                content: "旅行酒店已预订".into(),
                source: "user:turn-3".into(),
                expires_at: None,
            })
            .unwrap();
        assert_eq!(store.archive_topic("travel").unwrap(), 1);
        assert!(
            store
                .list_active()
                .unwrap()
                .iter()
                .all(|item| item.id != another.id)
        );
        assert!(
            store
                .list_active()
                .unwrap()
                .iter()
                .any(|item| item.id == medium.id)
        );
    }
}
