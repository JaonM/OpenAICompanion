//! Device-local mutation journal. Deliberately excluded from memory synchronization.
use crate::{MemoryError, MemoryStore};
use rusqlite::{OptionalExtension, TransactionBehavior, params};
use serde_json::{Value, json};

pub(crate) fn create_schema(connection: &rusqlite::Connection) -> Result<(), MemoryError> {
    connection.execute_batch(
        "CREATE TABLE IF NOT EXISTS device_operations (
            tool TEXT NOT NULL, request_id TEXT NOT NULL, request_json TEXT NOT NULL,
            operation_id TEXT NOT NULL UNIQUE,
            state TEXT NOT NULL CHECK (state IN ('pending','succeeded','unknown')),
            result_json TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
            PRIMARY KEY(tool, request_id)
        );",
    )?;
    Ok(())
}

/// Separate repository sharing the application SQLite connection; never included in memory sync.
pub(crate) struct DeviceOperationStore {
    connection: std::sync::Arc<std::sync::Mutex<rusqlite::Connection>>,
}

impl MemoryStore {
    pub(crate) fn device_operation_store(&self) -> DeviceOperationStore {
        DeviceOperationStore {
            connection: self.connection.clone(),
        }
    }

    // Compatibility facade for existing Rust callers.
    pub fn device_operation(
        &self,
        tool: &str,
        request_id: &str,
        request_json: &str,
        claim: bool,
    ) -> Result<Value, MemoryError> {
        self.device_operation_store()
            .lookup_or_claim(tool, request_id, request_json, claim)
    }

    pub fn finish_device_operation(
        &self,
        operation_id: &str,
        succeeded: bool,
        result_json: &str,
    ) -> Result<Value, MemoryError> {
        self.device_operation_store()
            .finish(operation_id, succeeded, result_json)
    }
}

impl DeviceOperationStore {
    /// Lookup, or atomically claim a new request. Pending requests are NEVER reclaimed on timeout.
    pub fn lookup_or_claim(
        &self,
        tool: &str,
        request_id: &str,
        request_json: &str,
        claim: bool,
    ) -> Result<Value, MemoryError> {
        if tool != "device_calendar_create_event"
            || !(8..=128).contains(&request_id.len())
            || !request_id
                .bytes()
                .all(|c| c.is_ascii_alphanumeric() || c == b'-' || c == b'_')
            || request_json.len() > 65536
        {
            return Err(MemoryError::InvalidData("invalid device operation".into()));
        }
        let request: Value = serde_json::from_str(request_json)
            .map_err(|_| MemoryError::InvalidData("invalid operation JSON".into()))?;
        if !request.is_object() {
            return Err(MemoryError::InvalidData(
                "operation must be an object".into(),
            ));
        }
        let canonical = request.to_string();
        let mut connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        let tx = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let inserted = if claim {
            tx.execute(
                "INSERT OR IGNORE INTO device_operations
                (tool,request_id,request_json,operation_id,state,created_at,updated_at)
                VALUES (?1,?2,?3,lower(hex(randomblob(16))),'pending',unixepoch(),unixepoch())",
                params![tool, request_id, canonical],
            )? == 1
        } else {
            false
        };
        let row: Option<(String, String, String, Option<String>)> = tx.query_row(
            "SELECT request_json,operation_id,state,result_json FROM device_operations WHERE tool=?1 AND request_id=?2",
            params![tool, request_id], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?))).optional()?;
        let value = match row {
            None => json!({"state":"absent","claimed":false}),
            Some((stored, _, _, _)) if stored != canonical => {
                json!({"state":"conflict","claimed":false})
            }
            Some((_, operation_id, state, result_json)) => json!({
                "claimed":inserted,"operation_id":operation_id,"state":state,"result_json":result_json
            }),
        };
        tx.commit()?;
        Ok(value)
    }

    /// A recovery may resolve pending/unknown to success; success is immutable and cannot be downgraded.
    pub fn finish(
        &self,
        operation_id: &str,
        succeeded: bool,
        result_json: &str,
    ) -> Result<Value, MemoryError> {
        if result_json.len() > 65536 {
            return Err(MemoryError::InvalidData(
                "operation result too large".into(),
            ));
        }
        let result: Value = serde_json::from_str(result_json)
            .map_err(|_| MemoryError::InvalidData("invalid operation result".into()))?;
        if !result.is_object() || (result["status"] == "ok") != succeeded {
            return Err(MemoryError::InvalidData(
                "operation result status mismatch".into(),
            ));
        }
        let mut connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        let tx = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        tx.execute(
            "UPDATE device_operations SET state=?2,result_json=?3,updated_at=unixepoch()
            WHERE operation_id=?1 AND state!='succeeded'",
            params![
                operation_id,
                if succeeded { "succeeded" } else { "unknown" },
                result.to_string()
            ],
        )?;
        let stored: String = tx.query_row(
            "SELECT result_json FROM device_operations WHERE operation_id=?1",
            [operation_id],
            |r| r.get(0),
        )?;
        tx.commit()?;
        Ok(json!({"result_json":stored}))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn journal_survives_reopen_and_never_reclaims_an_unfinished_write() {
        let path = std::env::temp_dir().join(format!(
            "device-journal-{}-{}.sqlite",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let op = {
            let store = MemoryStore::open(&path).unwrap();
            assert_eq!(
                store
                    .device_operation("device_calendar_create_event", "request-001", "{}", false)
                    .unwrap()["state"],
                "absent"
            );
            let first = store
                .device_operation("device_calendar_create_event", "request-001", "{}", true)
                .unwrap();
            assert_eq!(first["claimed"], true);
            first["operation_id"].as_str().unwrap().to_owned()
        };
        let store = MemoryStore::open(&path).unwrap();
        let replay = store
            .device_operation("device_calendar_create_event", "request-001", "{}", true)
            .unwrap();
        assert_eq!(replay["claimed"], false);
        assert_eq!(replay["state"], "pending");
        assert_eq!(replay["operation_id"], op);
        assert_eq!(
            store
                .device_operation(
                    "device_calendar_create_event",
                    "request-001",
                    "{\"changed\":true}",
                    true
                )
                .unwrap()["state"],
            "conflict"
        );
        store
            .finish_device_operation(&op, false, r#"{"status":"error"}"#)
            .unwrap();
        store
            .finish_device_operation(&op, true, r#"{"status":"ok","data":{"event_id":"saved"}}"#)
            .unwrap();
        let stable = store
            .finish_device_operation(&op, false, r#"{"status":"error"}"#)
            .unwrap();
        assert!(stable["result_json"].as_str().unwrap().contains("saved"));
        drop(store);
        let reopened = MemoryStore::open(&path).unwrap();
        assert_eq!(
            reopened
                .device_operation("device_calendar_create_event", "request-001", "{}", false)
                .unwrap()["state"],
            "succeeded"
        );
        drop(reopened);
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn competing_connections_only_claim_once() {
        let path = std::env::temp_dir().join(format!(
            "device-journal-race-{}-{}.sqlite",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let first = MemoryStore::open(&path).unwrap();
        let second = MemoryStore::open(&path).unwrap();
        let barrier = std::sync::Arc::new(std::sync::Barrier::new(2));
        let b = barrier.clone();
        let worker = std::thread::spawn(move || {
            b.wait();
            first
                .device_operation("device_calendar_create_event", "request-001", "{}", true)
                .unwrap()
        });
        barrier.wait();
        let right = second
            .device_operation("device_calendar_create_event", "request-001", "{}", true)
            .unwrap();
        let left = worker.join().unwrap();
        assert_ne!(left["claimed"], right["claimed"]);
        assert_eq!(left["operation_id"], right["operation_id"]);
        drop(second);
        std::fs::remove_file(path).unwrap();
    }
}
