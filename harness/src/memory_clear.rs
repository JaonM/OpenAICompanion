//! Explicit tier deletion, with cutoffs preventing old background work from restoring data.
use crate::{MemoryError, MemoryStore};
use rusqlite::params;

impl MemoryStore {
    pub(crate) fn clear_memory(&self, tier: &str) -> Result<usize, MemoryError> {
        if !matches!(tier, "short" | "medium" | "long") {
            return Err(MemoryError::InvalidData("unknown memory tier".into()));
        }
        let mut connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        let transaction = connection.transaction()?;
        let cutoff: i64 =
            transaction.query_row("SELECT COALESCE(MAX(id),0) FROM trace_turns", [], |row| {
                row.get(0)
            })?;
        transaction.execute(
            "INSERT INTO memory_clear_watermarks(tier,turn_id) VALUES (?1,?2)
            ON CONFLICT(tier) DO UPDATE SET turn_id=MAX(turn_id,excluded.turn_id)",
            params![tier, cutoff],
        )?;
        let deleted = if tier == "short" {
            transaction.execute("DELETE FROM trace_messages", [])?;
            // Keep only empty relational anchors: existing summaries and extracted memories
            // belong to other tiers and must survive a short-term clear.
            transaction.execute(
                "UPDATE trace_turns SET user_input='', output=NULL, reasoning=NULL,
                error=NULL, steps=NULL, status='cancelled' WHERE user_input!=''",
                [],
            )?
        } else {
            let points = transaction.execute("DELETE FROM memories WHERE tier=?1", [tier])?;
            // Existing DELETE triggers wake sync; snapshot export records durable tombstones.
            if tier == "medium" {
                points + transaction.execute("DELETE FROM medium_summary_blocks", [])?
            } else {
                points
            }
        };
        transaction.commit()?;
        Ok(deleted)
    }

    pub(crate) fn summary_clear_cutoff(&self) -> Result<i64, MemoryError> {
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        connection
            .query_row(
                "SELECT COALESCE(MAX(turn_id),0) FROM memory_clear_watermarks
            WHERE tier IN ('short','medium')",
                [],
                |row| row.get(0),
            )
            .map_err(Into::into)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{MemoryTier, Message, NewMemory, TraceStatus};

    fn fixture() -> (MemoryStore, i64, i64) {
        let store = MemoryStore::in_memory().unwrap();
        let session = store.ensure_single_trace_session().unwrap().id;
        let turn = store.begin_trace_turn(session, "原始用户输入").unwrap();
        store
            .append_trace_message(
                turn,
                &Message::User {
                    content: "原始用户输入".into(),
                },
            )
            .unwrap();
        store
            .finish_trace_turn(
                turn,
                TraceStatus::Completed,
                Some("思考"),
                Some("回答"),
                Some(1),
                None,
            )
            .unwrap();
        store
            .save_medium_summary(session, turn, turn, "对话摘要")
            .unwrap();
        for tier in [MemoryTier::Medium, MemoryTier::Long] {
            store
                .remember(NewMemory {
                    tier,
                    topic_id: None,
                    content: "已提取记忆".into(),
                    source: "用户".into(),
                    expires_at: None,
                })
                .unwrap();
        }
        (store, session, turn)
    }

    #[test]
    fn short_clear_erases_raw_content_but_preserves_other_tiers_and_ids() {
        let (store, session, turn) = fixture();
        store.clear_memory("short").unwrap();
        assert!(store.list_trace_turns(session).unwrap().is_empty());
        assert!(
            store
                .recent_completed_turns(session, 0, 8)
                .unwrap()
                .is_empty()
        );
        let anchor = store.get_trace_turn(turn).unwrap();
        assert!(anchor.user_input.is_empty() && anchor.messages.is_empty());
        assert!(anchor.output.is_none() && anchor.reasoning.is_none());
        assert_eq!(store.medium_summaries(session).unwrap().len(), 1);
        assert_eq!(store.list_active().unwrap().len(), 2);
        let next = store.begin_trace_turn(session, "新的对话").unwrap();
        assert!(next > turn);
    }

    #[test]
    fn medium_clear_preserves_chat_and_long_memory_and_blocks_old_summary_jobs() {
        let (store, session, turn) = fixture();
        store.clear_memory("medium").unwrap();
        assert_eq!(store.list_trace_turns(session).unwrap().len(), 1);
        assert_eq!(store.list_active().unwrap()[0].tier, MemoryTier::Long);
        store
            .save_medium_summary(session, turn, turn, "过期后台输出")
            .unwrap();
        assert!(store.medium_summaries(session).unwrap().is_empty());
        assert_eq!(store.summary_target(session, 0, 1).unwrap(), None);
        let next = store.begin_trace_turn(session, "新输入").unwrap();
        store
            .finish_trace_turn(
                next,
                TraceStatus::Completed,
                None,
                Some("新回答"),
                Some(1),
                None,
            )
            .unwrap();
        assert_eq!(store.summary_target(session, 0, 1).unwrap(), Some(next));
        store
            .save_medium_summary(session, next, next, "新摘要")
            .unwrap();
        assert_eq!(
            store.medium_summaries(session).unwrap()[0].content,
            "新摘要"
        );
    }

    #[test]
    fn long_clear_syncs_tombstone_without_deleting_other_tiers() {
        let (store, session, _) = fixture();
        let before = store.export_memory_sync().unwrap();
        let long_id = before
            .iter()
            .find(|r| r.memory.as_ref().unwrap().tier == "long")
            .unwrap()
            .id
            .clone();
        store
            .acknowledge_memory_sync(store.memory_sync_generation().unwrap())
            .unwrap();
        store.clear_memory("long").unwrap();
        assert!(store.memory_sync_pending().unwrap());
        let after = store.export_memory_sync().unwrap();
        assert!(
            after
                .iter()
                .find(|r| r.id == long_id)
                .unwrap()
                .memory
                .is_none()
        );
        assert_eq!(store.list_active().unwrap()[0].tier, MemoryTier::Medium);
        assert_eq!(store.medium_summaries(session).unwrap().len(), 1);
        assert_eq!(store.list_trace_turns(session).unwrap().len(), 1);
        let remote = MemoryStore::in_memory().unwrap();
        remote.merge_memory_sync(&before).unwrap();
        remote.merge_memory_sync(&after).unwrap();
        assert_eq!(remote.list_active().unwrap().len(), 1);
        // Replaying an older page must not restore the deleted long-term memory.
        remote.merge_memory_sync(&before).unwrap();
        assert_eq!(remote.list_active().unwrap().len(), 1);
    }

    #[test]
    fn invalid_tier_cannot_mutate_memory() {
        let (store, session, _) = fixture();
        assert!(store.clear_memory("all").is_err());
        assert_eq!(store.list_active().unwrap().len(), 2);
        assert_eq!(store.medium_summaries(session).unwrap().len(), 1);
        assert_eq!(store.summary_clear_cutoff().unwrap(), 0);
    }

    #[test]
    fn cutoff_survives_reopen_and_version_one_upgrade() {
        let path = std::env::temp_dir().join(format!(
            "companion-clear-{}.sqlite",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let store = MemoryStore::open(&path).unwrap();
        let session = store.ensure_single_trace_session().unwrap().id;
        let turn = store.begin_trace_turn(session, "旧内容").unwrap();
        store
            .finish_trace_turn(
                turn,
                TraceStatus::Completed,
                None,
                Some("旧回答"),
                Some(1),
                None,
            )
            .unwrap();
        store
            .connection
            .lock()
            .unwrap()
            .execute_batch("DROP TABLE memory_clear_watermarks; PRAGMA user_version=1")
            .unwrap();
        drop(store);
        let store = MemoryStore::open(&path).unwrap();
        store.clear_memory("medium").unwrap();
        drop(store);
        let store = MemoryStore::open(&path).unwrap();
        assert_eq!(store.summary_clear_cutoff().unwrap(), turn);
        store
            .save_medium_summary(session, turn, turn, "旧摘要")
            .unwrap();
        assert!(store.medium_summaries(session).unwrap().is_empty());
        drop(store);
        std::fs::remove_file(path).unwrap();
    }
}
