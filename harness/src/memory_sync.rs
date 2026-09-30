//! Device-local change tracking for explicit medium- and long-term memories.
//! Conversation traces and short-term context never enter this stream.

use std::time::{SystemTime, UNIX_EPOCH};

use rusqlite::{OptionalExtension, params};
use serde::{Deserialize, Serialize};

use crate::{MemoryError, MemoryStore};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SyncMemory {
    pub tier: String,
    pub topic_id: Option<String>,
    pub content: String,
    pub source: String,
    pub status: String,
    pub created_at: i64,
    pub updated_at: i64,
    pub expires_at: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SyncRecord {
    pub id: String,
    pub revision: i64,
    pub author: String,
    pub memory: Option<SyncMemory>,
}

pub(crate) fn create_schema(connection: &rusqlite::Connection) -> Result<(), MemoryError> {
    connection.execute_batch(
        "CREATE TABLE IF NOT EXISTS memory_sync_device (
            id INTEGER PRIMARY KEY CHECK (id=1), device_id TEXT NOT NULL
        );
        INSERT OR IGNORE INTO memory_sync_device (id,device_id)
            VALUES (1,lower(hex(randomblob(16))));
        CREATE TABLE IF NOT EXISTS memory_sync_records (
            id TEXT PRIMARY KEY,
            memory_id INTEGER UNIQUE,
            revision INTEGER NOT NULL,
            author TEXT NOT NULL,
            memory_json TEXT
        );",
    )?;
    Ok(())
}

fn now_millis() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64
}

fn decode_memory(json: Option<String>) -> Result<Option<SyncMemory>, MemoryError> {
    json.map(|raw| serde_json::from_str(&raw).map_err(|e| MemoryError::InvalidData(e.to_string())))
        .transpose()
}

impl MemoryStore {
    pub fn export_memory_sync(&self) -> Result<Vec<SyncRecord>, MemoryError> {
        let mut connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        let tx = connection.transaction()?;
        let device_id: String = tx.query_row(
            "SELECT device_id FROM memory_sync_device WHERE id=1",
            [],
            |row| row.get(0),
        )?;
        let current = {
            let mut statement = tx.prepare(
                "SELECT id,tier,topic_id,content,source,status,created_at,updated_at,expires_at FROM memories")?;
            statement
                .query_map([], |row| {
                    Ok((
                        row.get::<_, i64>(0)?,
                        SyncMemory {
                            tier: row.get(1)?,
                            topic_id: row.get(2)?,
                            content: row.get(3)?,
                            source: row.get(4)?,
                            status: row.get(5)?,
                            created_at: row.get(6)?,
                            updated_at: row.get(7)?,
                            expires_at: row.get(8)?,
                        },
                    ))
                })?
                .collect::<Result<Vec<_>, _>>()?
        };
        for (memory_id, memory) in current {
            let json = serde_json::to_string(&memory)
                .map_err(|e| MemoryError::InvalidData(e.to_string()))?;
            let tracked: Option<(String, i64, Option<String>)> = tx
                .query_row(
                    "SELECT id,revision,memory_json FROM memory_sync_records WHERE memory_id=?1",
                    [memory_id],
                    |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
                )
                .optional()?;
            match tracked {
                Some((id, revision, old)) if old.as_deref() != Some(&json) => {
                    tx.execute("UPDATE memory_sync_records SET revision=?1,author=?2,memory_json=?3 WHERE id=?4",
                        params![now_millis().max(revision + 1), device_id, json, id])?;
                }
                None => {
                    tx.execute(
                        "INSERT INTO memory_sync_records (id,memory_id,revision,author,memory_json)
                        VALUES (lower(hex(randomblob(16))),?1,?2,?3,?4)",
                        params![memory_id, now_millis(), device_id, json],
                    )?;
                }
                _ => {}
            }
        }
        tx.execute(
            "UPDATE memory_sync_records SET revision=MAX(?1,revision+1),author=?2,memory_id=NULL,memory_json=NULL
             WHERE memory_id IS NOT NULL AND NOT EXISTS
                 (SELECT 1 FROM memories WHERE memories.id=memory_sync_records.memory_id)",
            params![now_millis(), device_id],
        )?;
        let records = {
            let mut statement = tx.prepare(
                "SELECT id,revision,author,memory_json FROM memory_sync_records ORDER BY id",
            )?;
            statement
                .query_map([], |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, i64>(1)?,
                        row.get::<_, String>(2)?,
                        row.get::<_, Option<String>>(3)?,
                    ))
                })?
                .collect::<Result<Vec<_>, _>>()?
        };
        tx.commit()?;
        records
            .into_iter()
            .map(|(id, revision, author, json)| {
                Ok(SyncRecord {
                    id,
                    revision,
                    author,
                    memory: decode_memory(json)?,
                })
            })
            .collect()
    }

    pub fn merge_memory_sync(&self, remote: &[SyncRecord]) -> Result<usize, MemoryError> {
        // Capture local edits before comparing them with remote revisions.
        self.export_memory_sync()?;
        if remote.len() > 10_000 {
            return Err(MemoryError::InvalidData("sync batch too large".into()));
        }
        let mut connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        let tx = connection.transaction()?;
        let mut changed = 0;
        for record in remote {
            if record.id.len() != 32
                || !record.id.bytes().all(|b| b.is_ascii_hexdigit())
                || record.author.len() != 32
                || !record.author.bytes().all(|b| b.is_ascii_hexdigit())
                || record.revision <= 0
                || record.revision > now_millis() + 86_400_000
            {
                return Err(MemoryError::InvalidData(
                    "invalid sync record metadata".into(),
                ));
            }
            if let Some(memory) = &record.memory {
                if !["medium", "long"].contains(&memory.tier.as_str())
                    || !["active", "archived"].contains(&memory.status.as_str())
                    || memory.content.trim().is_empty()
                    || memory.content.len() > 16_384
                    || memory.source.len() > 1024
                {
                    return Err(MemoryError::InvalidData("invalid synced memory".into()));
                }
            }
            let existing: Option<(Option<i64>, i64, String)> = tx
                .query_row(
                    "SELECT memory_id,revision,author FROM memory_sync_records WHERE id=?1",
                    [&record.id],
                    |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
                )
                .optional()?;
            // Explicit forgetting wins over concurrent edits. Re-adding a fact gets a new ID.
            if existing
                .as_ref()
                .is_some_and(|(memory_id, _, _)| memory_id.is_none())
                && record.memory.is_some()
            {
                continue;
            }
            if existing.as_ref().is_some_and(|(_, revision, author)| {
                (*revision, author.as_str()) >= (record.revision, record.author.as_str())
            }) && !(record.memory.is_none()
                && existing
                    .as_ref()
                    .is_some_and(|(memory_id, _, _)| memory_id.is_some()))
            {
                continue;
            }
            let memory_id = match (&record.memory, existing.as_ref().and_then(|x| x.0)) {
                (Some(memory), Some(id)) => {
                    tx.execute(
                        "UPDATE memories SET tier=?1,topic_id=?2,content=?3,source=?4,status=?5,
                        created_at=?6,updated_at=?7,expires_at=?8 WHERE id=?9",
                        params![
                            memory.tier,
                            memory.topic_id,
                            memory.content,
                            memory.source,
                            memory.status,
                            memory.created_at,
                            memory.updated_at,
                            memory.expires_at,
                            id
                        ],
                    )?;
                    Some(id)
                }
                (Some(memory), None) => {
                    tx.execute("INSERT INTO memories (tier,topic_id,content,source,status,created_at,updated_at,expires_at)
                        VALUES (?1,?2,?3,?4,?5,?6,?7,?8)",
                        params![memory.tier,memory.topic_id,memory.content,memory.source,memory.status,
                            memory.created_at,memory.updated_at,memory.expires_at])?;
                    Some(tx.last_insert_rowid())
                }
                (None, Some(id)) => {
                    tx.execute("DELETE FROM memories WHERE id=?1", [id])?;
                    None
                }
                (None, None) => None,
            };
            let json = record
                .memory
                .as_ref()
                .map(serde_json::to_string)
                .transpose()
                .map_err(|e| MemoryError::InvalidData(e.to_string()))?;
            tx.execute(
                "INSERT INTO memory_sync_records (id,memory_id,revision,author,memory_json)
                VALUES (?1,?2,?3,?4,?5) ON CONFLICT(id) DO UPDATE SET memory_id=excluded.memory_id,
                revision=excluded.revision,author=excluded.author,memory_json=excluded.memory_json",
                params![record.id, memory_id, record.revision, record.author, json],
            )?;
            changed += 1;
        }
        tx.commit()?;
        Ok(changed)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{MemoryTier, NewMemory};

    #[test]
    fn syncs_changes_and_tombstones_across_devices() {
        let a = MemoryStore::in_memory().unwrap();
        let b = MemoryStore::in_memory().unwrap();
        let first = a
            .remember(NewMemory {
                tier: MemoryTier::Long,
                topic_id: None,
                content: "下班时间十八点".into(),
                source: "user".into(),
                expires_at: None,
            })
            .unwrap();
        assert_eq!(
            b.merge_memory_sync(&a.export_memory_sync().unwrap())
                .unwrap(),
            1
        );
        assert_eq!(b.list_active().unwrap()[0].content, "下班时间十八点");
        a.update(first.id, "下班时间十九点", "user correction")
            .unwrap();
        b.merge_memory_sync(&a.export_memory_sync().unwrap())
            .unwrap();
        assert_eq!(b.list_active().unwrap()[0].content, "下班时间十九点");
        a.forget(first.id).unwrap();
        b.merge_memory_sync(&a.export_memory_sync().unwrap())
            .unwrap();
        assert!(b.list_active().unwrap().is_empty());
        let tombstone = a.export_memory_sync().unwrap();
        let mut stale_edit = tombstone[0].clone();
        stale_edit.revision += 1;
        stale_edit.memory = Some(SyncMemory {
            tier: "long".into(),
            topic_id: None,
            content: "旧内容".into(),
            source: "other device".into(),
            status: "active".into(),
            created_at: 1,
            updated_at: 1,
            expires_at: None,
        });
        b.merge_memory_sync(&[stale_edit]).unwrap();
        assert!(b.list_active().unwrap().is_empty());
    }
}
