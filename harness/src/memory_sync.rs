//! Device-local change tracking for explicit medium- and long-term memories.
//! Conversation traces and short-term context never enter this stream.

use std::sync::{
    Arc, Mutex,
    atomic::{AtomicBool, Ordering},
};
use std::time::{SystemTime, UNIX_EPOCH};

/// Must only enqueue a wake signal: called under the store lock during commit.
#[::uniffi::export(with_foreign)]
pub trait MemorySyncWake: Send + Sync {
    fn on_pending(&self);
}
static SYNC_WAKE: Mutex<Option<Arc<dyn MemorySyncWake>>> = Mutex::new(None);

pub fn register_memory_sync_wake(sink: Arc<dyn MemorySyncWake>) {
    *SYNC_WAKE.lock().unwrap() = Some(sink);
}
pub fn unregister_memory_sync_wake() {
    *SYNC_WAKE.lock().unwrap() = None;
}

pub(crate) fn install_wake_hooks(connection: &rusqlite::Connection) {
    install_wake_hooks_with(connection, || {
        let sink = SYNC_WAKE.lock().unwrap().clone();
        if let Some(sink) = sink {
            sink.on_pending();
        }
    });
}

fn install_wake_hooks_with(connection: &rusqlite::Connection, wake: impl Fn() + Send + 'static) {
    let dirty = Arc::new(AtomicBool::new(false));
    let updated = dirty.clone();
    connection.update_hook(Some(
        move |_: rusqlite::hooks::Action, _: &str, table: &str, _: i64| {
            if table == "memory_sync_outbox" {
                updated.store(true, Ordering::Relaxed);
            }
        },
    ));
    let rolled_back = dirty.clone();
    connection.rollback_hook(Some(move || {
        rolled_back.store(false, Ordering::Relaxed);
    }));
    connection.commit_hook(Some(move || {
        if dirty.swap(false, Ordering::Relaxed) {
            wake();
        }
        false
    }));
}

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
        );
        CREATE TABLE IF NOT EXISTS memory_sync_outbox (
            id INTEGER PRIMARY KEY CHECK(id=1), generation INTEGER NOT NULL
        );
        INSERT OR IGNORE INTO memory_sync_outbox VALUES (1,1);
        CREATE TABLE IF NOT EXISTS memory_sync_ack (
            id INTEGER PRIMARY KEY CHECK(id=1), generation INTEGER NOT NULL,
            importing INTEGER NOT NULL DEFAULT 0
        );
        INSERT OR IGNORE INTO memory_sync_ack VALUES (1,0,0);
        CREATE TRIGGER IF NOT EXISTS memory_sync_insert AFTER INSERT ON memories
        WHEN (SELECT importing FROM memory_sync_ack WHERE id=1)=0 BEGIN
            UPDATE memory_sync_outbox SET generation=generation+1 WHERE id=1;
        END;
        CREATE TRIGGER IF NOT EXISTS memory_sync_update AFTER UPDATE ON memories
        WHEN (SELECT importing FROM memory_sync_ack WHERE id=1)=0 BEGIN
            UPDATE memory_sync_outbox SET generation=generation+1 WHERE id=1;
        END;
        CREATE TRIGGER IF NOT EXISTS memory_sync_delete AFTER DELETE ON memories
        WHEN (SELECT importing FROM memory_sync_ack WHERE id=1)=0 BEGIN
            UPDATE memory_sync_outbox SET generation=generation+1 WHERE id=1;
        END;",
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
    pub fn memory_sync_generation(&self) -> Result<i64, MemoryError> {
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        Ok(connection.query_row(
            "SELECT generation FROM memory_sync_outbox WHERE id=1",
            [],
            |r| r.get(0),
        )?)
    }

    pub fn acknowledge_memory_sync(&self, generation: i64) -> Result<(), MemoryError> {
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        let changed = connection.execute(
            "UPDATE memory_sync_ack SET generation=MAX(generation,?1)
             WHERE id=1 AND ?1>=0 AND ?1<=(SELECT generation FROM memory_sync_outbox WHERE id=1)",
            [generation],
        )?;
        if changed == 0 {
            return Err(MemoryError::InvalidData(
                "invalid sync acknowledgement".into(),
            ));
        }
        Ok(())
    }

    pub fn memory_sync_pending(&self) -> Result<bool, MemoryError> {
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        Ok(connection.query_row("SELECT o.generation>a.generation FROM memory_sync_outbox o, memory_sync_ack a WHERE o.id=1 AND a.id=1", [], |r| r.get(0))?)
    }

    pub fn export_memory_sync(&self) -> Result<Vec<SyncRecord>, MemoryError> {
        let mut connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        let tx = connection.transaction()?;
        capture_local_changes(&tx)?;
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
        if remote.len() > 10_000 {
            return Err(MemoryError::InvalidData("sync batch too large".into()));
        }
        let mut connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        // IMMEDIATE also serializes writers using another SQLite connection.
        let tx = connection.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        capture_local_changes(&tx)?;
        tx.execute("UPDATE memory_sync_ack SET importing=1 WHERE id=1", [])?;
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
        tx.execute("UPDATE memory_sync_ack SET importing=0 WHERE id=1", [])?;
        tx.commit()?;
        Ok(changed)
    }
}

/// Capture and merge must share one write transaction: no local edit may slip between them.
fn capture_local_changes(tx: &rusqlite::Transaction<'_>) -> Result<(), MemoryError> {
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
        let json =
            serde_json::to_string(&memory).map_err(|e| MemoryError::InvalidData(e.to_string()))?;
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
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{MemoryTier, NewMemory};

    fn remember(store: &MemoryStore) -> i64 {
        store
            .remember(NewMemory {
                tier: MemoryTier::Long,
                topic_id: None,
                content: "用户偏好安静".into(),
                source: "user".into(),
                expires_at: None,
            })
            .unwrap()
            .id
    }

    #[test]
    fn merge_captures_unexported_local_edit_and_rolls_back_invalid_batch() {
        let store = MemoryStore::in_memory().unwrap();
        let id = remember(&store);
        let stale = store.export_memory_sync().unwrap();
        store.update(id, "本地新内容", "user").unwrap();
        store.merge_memory_sync(&stale).unwrap();
        assert_eq!(store.list_active().unwrap()[0].content, "本地新内容");
        assert!(store.memory_sync_pending().unwrap());
        let mut deleted = stale[0].clone();
        deleted.memory = None;
        let mut invalid = deleted.clone();
        invalid.id = "invalid".into();
        assert!(store.merge_memory_sync(&[deleted, invalid]).is_err());
        assert_eq!(store.list_active().unwrap()[0].content, "本地新内容");
        store.update(id, "仍可写入", "user").unwrap();
        assert!(store.memory_sync_pending().unwrap());
    }

    #[test]
    fn acknowledgement_does_not_clear_writes_during_upload() {
        let store = MemoryStore::in_memory().unwrap();
        let id = remember(&store);
        let uploaded = store.memory_sync_generation().unwrap();
        store.export_memory_sync().unwrap();
        store.update(id, "用户偏好热闹", "user").unwrap();
        store.acknowledge_memory_sync(uploaded).unwrap();
        assert!(store.memory_sync_pending().unwrap());
        let latest = store.memory_sync_generation().unwrap();
        store.acknowledge_memory_sync(latest).unwrap();
        assert!(!store.memory_sync_pending().unwrap());
        store.acknowledge_memory_sync(uploaded).unwrap(); // late ACK cannot regress
        assert!(!store.memory_sync_pending().unwrap());
        assert!(store.acknowledge_memory_sync(latest + 1).is_err());
        store.forget(id).unwrap();
        assert!(store.memory_sync_pending().unwrap());
    }

    #[test]
    fn pending_upload_survives_restart_until_acknowledged() {
        let path = std::env::temp_dir().join(format!(
            "sync-outbox-{}-{}.sqlite",
            std::process::id(),
            now_millis()
        ));
        let generation = {
            let store = MemoryStore::open(&path).unwrap();
            remember(&store);
            store.export_memory_sync().unwrap(); // failed HTTP: no acknowledgement
            store.memory_sync_generation().unwrap()
        };
        {
            let store = MemoryStore::open(&path).unwrap();
            assert!(store.memory_sync_pending().unwrap());
            store.acknowledge_memory_sync(generation).unwrap();
        }
        assert!(
            !MemoryStore::open(&path)
                .unwrap()
                .memory_sync_pending()
                .unwrap()
        );
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn commit_wakes_and_rollback_or_remote_import_does_not_create_pending_work() {
        let store = MemoryStore::in_memory().unwrap();
        let woke = Arc::new(AtomicBool::new(false));
        let flag = woke.clone();
        install_wake_hooks_with(&store.connection.lock().unwrap(), move || {
            flag.store(true, Ordering::SeqCst);
        });
        let id = remember(&store);
        assert!(woke.swap(false, Ordering::SeqCst));
        let generation = store.memory_sync_generation().unwrap();
        store.acknowledge_memory_sync(generation).unwrap();
        {
            let mut connection = store.connection.lock().unwrap();
            let tx = connection.transaction().unwrap();
            tx.execute("DELETE FROM memories WHERE id=?1", [id])
                .unwrap();
            tx.rollback().unwrap();
        }
        assert!(!store.memory_sync_pending().unwrap());
        assert!(!woke.load(Ordering::SeqCst));
        let other = MemoryStore::in_memory().unwrap();
        remember(&other);
        store
            .merge_memory_sync(&other.export_memory_sync().unwrap())
            .unwrap();
        assert!(!store.memory_sync_pending().unwrap());
        assert!(!woke.load(Ordering::SeqCst));
        store.update(id, "更正", "user").unwrap();
        assert!(store.memory_sync_pending().unwrap());
        assert!(woke.load(Ordering::SeqCst));
    }

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
