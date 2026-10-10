//! Extracts atomic memory points after completed turns. Model output is an
//! untrusted proposal; every stored point retains evidence from the user.

use std::sync::Mutex;

use rusqlite::{OptionalExtension, params};
use serde::Deserialize;

use crate::memory::now_unix_seconds;
use crate::{
    AgentError, MemoryError, MemoryStore, MemoryTier, Message, ModelRequest, ModelServeWrapper,
    TraceStatus,
};

static WORKER: Mutex<()> = Mutex::new(());

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Candidate {
    #[serde(default)]
    action: Option<String>,
    tier: String,
    kind: String,
    topic_id: String,
    content: String,
    evidence: String,
    #[serde(default)]
    expires_at: Option<i64>,
    #[serde(default)]
    replaces_id: Option<i64>,
}

pub(crate) fn process_pending(
    store: MemoryStore,
    model: ModelServeWrapper,
    session_id: i64,
) -> Result<(), AgentError> {
    // Serialize extractors without holding the SQLite lock during inference.
    let _guard = WORKER
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|error| AgentError::Memory(error.to_string()))?;
    let mut first_error = None;
    for turn_id in store
        .pending_profile_turns(session_id)
        .map_err(memory_error)?
    {
        let result = process_turn(&store, &model, &runtime, turn_id);
        if let Err(error) = result {
            if first_error.is_none() {
                first_error = Some(error);
            }
        }
    }
    first_error.map_or(Ok(()), Err)
}

fn process_turn(
    store: &MemoryStore,
    model: &ModelServeWrapper,
    runtime: &tokio::runtime::Runtime,
    turn_id: i64,
) -> Result<(), AgentError> {
    let turn = store.get_trace_turn(turn_id).map_err(memory_error)?;
    if !matches!(
        turn.status,
        TraceStatus::Completed | TraceStatus::MaxStepsReached
    ) {
        return Ok(());
    }
    let existing = store.list_active().map_err(memory_error)?;
    let known = existing.iter().take(40).map(|item| {
            serde_json::json!({"id": item.id, "tier": if item.tier == MemoryTier::Long {"long"} else {"medium"},
                "topic_id": item.topic_id, "content": item.content})
        }).collect::<Vec<_>>();
    let input = serde_json::json!({
        "turn_id": turn_id,
        "user_input": turn.user_input.chars().take(4000).collect::<String>(),
        "known_memories": known,
        "now_unix_seconds": now_unix_seconds(),
    });
    let response = runtime.block_on(model.complete_silent(ModelRequest {
            response_format: None,
            system_prompt: "从本轮用户输入提取未来有用的原子记忆点。只把用户明确说出的内容当作事实，不推断敏感属性，不从助手回答或工具结果提取。短期上下文无需保存。中期用于有期限的计划或进行中的事项；长期用于明确且持续有效的事实、偏好、约束或明确要求记住的内容。相同内容不重复。用户明确纠正已有记忆时填写 replaces_id；明确完成或取消中期事项时 action=archive。输出严格 JSON 数组，每项仅包含 action(upsert|archive)、tier(long|medium)、kind(preference|fact|goal|constraint)、topic_id、content、evidence（用户输入中的连续原文）、expires_at（Unix 秒，可为 null）、replaces_id（整数，可为 null）。无合适内容输出 []。中期若无明确到期时间，expires_at 为 null。最多 3 项。".into(),
            user_input: String::new(),
            history: vec![Message::User { content: input.to_string() }],
            tools: Vec::new(),
        }))?;
    let candidates: Vec<Candidate> = serde_json::from_str(response.content.trim())
        .map_err(|error| AgentError::Memory(format!("invalid profile response: {error}")))?;
    if candidates.len() > 3 {
        return Err(AgentError::Memory("too many memory candidates".into()));
    }
    store
        .apply_profile_candidates(turn_id, &turn.user_input, &candidates)
        .map_err(memory_error)?;
    Ok(())
}

impl MemoryStore {
    fn pending_profile_turns(&self, session_id: i64) -> Result<Vec<i64>, MemoryError> {
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        let mut statement = connection.prepare(
            "SELECT t.id FROM trace_turns t LEFT JOIN memory_extracted_turns e ON e.turn_id = t.id
             WHERE t.session_id = ?1 AND t.status IN ('completed', 'max_steps') AND e.turn_id IS NULL ORDER BY t.id"
        )?;
        statement
            .query_map([session_id], |row| row.get(0))?
            .collect::<Result<Vec<_>, _>>()
            .map_err(Into::into)
    }

    fn apply_profile_candidates(
        &self,
        turn_id: i64,
        user_input: &str,
        candidates: &[Candidate],
    ) -> Result<(), MemoryError> {
        let now = now_unix_seconds();
        let mut connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        let transaction = connection.transaction()?;
        let cleared: bool = transaction.query_row("SELECT user_input='' FROM trace_turns WHERE id=?1", [turn_id], |row| row.get(0))?;
        if cleared { return Ok(()); }
        for candidate in candidates {
            let content = candidate.content.trim();
            let topic = candidate.topic_id.trim();
            let evidence = candidate.evidence.trim();
            let tier = match candidate.tier.as_str() {
                "medium" => MemoryTier::Medium,
                "long" => MemoryTier::Long,
                _ => continue, // Short-term content stays in the trace window.
            };
            let cutoff: i64 = transaction.query_row("SELECT COALESCE(MAX(turn_id),0) FROM memory_clear_watermarks WHERE tier=?1", [tier.as_str()], |row| row.get(0))?;
            if turn_id <= cutoff { continue; }
            if topic.is_empty()
                || topic.chars().count() > 80
                || evidence.is_empty()
                || evidence.chars().count() > 500
                || !user_input.contains(evidence)
                || !matches!(
                    candidate.kind.as_str(),
                    "preference" | "fact" | "goal" | "constraint"
                )
            {
                continue;
            }
            if candidate.action.as_deref() == Some("archive") {
                if tier == MemoryTier::Medium {
                    transaction.execute(
                        "UPDATE memories SET status = 'archived', updated_at = ?1
                         WHERE tier = 'medium' AND topic_id = ?2 AND status = 'active'",
                        params![now, topic],
                    )?;
                }
                continue;
            }
            if candidate
                .action
                .as_deref()
                .is_some_and(|action| action != "upsert")
                || content.is_empty()
                || content.chars().count() > 240
            {
                continue;
            }
            let expires_at = match tier {
                MemoryTier::Medium => Some(
                    candidate
                        .expires_at
                        .filter(|value| *value > now)
                        .unwrap_or(now + 30 * 86_400),
                ),
                MemoryTier::Long => None,
            };
            let duplicate: Option<i64> = transaction.query_row(
                "SELECT id FROM memories WHERE status = 'active' AND tier = ?1 AND topic_id = ?2 AND content = ?3 LIMIT 1",
                params![tier.as_str(), topic, content], |row| row.get(0)
            ).optional()?;
            if duplicate.is_some() {
                continue;
            }
            let source = format!("user:turn-{turn_id}");
            if let Some(replaces_id) = candidate.replaces_id {
                let current: Option<String> = transaction
                    .query_row(
                        "SELECT topic_id FROM memories WHERE id = ?1 AND status = 'active'",
                        [replaces_id],
                        |row| row.get(0),
                    )
                    .optional()?;
                if current.as_deref() == Some(topic) {
                    transaction.execute(
                        "UPDATE memories SET tier = ?1, content = ?2, source = ?3, updated_at = ?4, expires_at = ?5 WHERE id = ?6",
                        params![tier.as_str(), content, source, now, expires_at, replaces_id]
                    )?;
                    transaction.execute(
                        "INSERT INTO memory_point_sources (memory_id, turn_id, kind, evidence) VALUES (?1, ?2, ?3, ?4)
                         ON CONFLICT(memory_id) DO UPDATE SET turn_id=excluded.turn_id, kind=excluded.kind, evidence=excluded.evidence",
                        params![replaces_id, turn_id, candidate.kind, evidence]
                    )?;
                }
                continue;
            }
            // A topic is a stable key for an atomic point. Require an explicit
            // replacement ID before changing an existing topic's meaning.
            let existing_topic: Option<i64> = transaction
                .query_row(
                    "SELECT id FROM memories WHERE topic_id = ?1 AND status = 'active' LIMIT 1",
                    [topic],
                    |row| row.get(0),
                )
                .optional()?;
            if existing_topic.is_some() {
                continue;
            }
            transaction.execute(
                "INSERT INTO memories (tier, topic_id, content, source, created_at, updated_at, expires_at)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?5, ?6)",
                params![tier.as_str(), topic, content, source, now, expires_at]
            )?;
            let memory_id = transaction.last_insert_rowid();
            transaction.execute(
                "INSERT INTO memory_point_sources (memory_id, turn_id, kind, evidence) VALUES (?1, ?2, ?3, ?4)",
                params![memory_id, turn_id, candidate.kind, evidence]
            )?;
        }
        transaction.execute(
            "INSERT OR IGNORE INTO memory_extracted_turns (turn_id, processed_at) VALUES (?1, ?2)",
            params![turn_id, now],
        )?;
        transaction.commit()?;
        Ok(())
    }
}

fn memory_error(error: MemoryError) -> AgentError {
    AgentError::Memory(error.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{ModelServeCallback, ModelServeError, ModelStreamCallback};
    use std::sync::Arc;

    #[test]
    fn cleared_tier_rejects_inflight_profile_output_but_accepts_new_turns() {
        for cleared in ["short", "medium", "long"] {
            let store = MemoryStore::in_memory().unwrap();
            let session = store.ensure_single_trace_session().unwrap().id;
            let old = finished(&store, session, "记住我喜欢 Kotlin", TraceStatus::Completed);
            let tier = if cleared == "medium" { "medium" } else { "long" };
            let candidates: Vec<Candidate> = serde_json::from_value(serde_json::json!([{
                "tier": tier, "kind": "preference", "topic_id": "language",
                "content": "喜欢 Kotlin", "evidence": "我喜欢 Kotlin"
            }])).unwrap();
            store.clear_memory(cleared).unwrap();
            store.apply_profile_candidates(old, "记住我喜欢 Kotlin", &candidates).unwrap();
            assert!(store.list_active().unwrap().is_empty());
            let new = finished(&store, session, "记住我喜欢 Kotlin", TraceStatus::Completed);
            store.apply_profile_candidates(new, "记住我喜欢 Kotlin", &candidates).unwrap();
            assert_eq!(store.list_active().unwrap().len(), 1);
        }
    }

    struct FixedModel(&'static str);

    #[async_trait::async_trait]
    impl ModelServeCallback for FixedModel {
        async fn complete(
            &self,
            _request_json: String,
            callback: Arc<dyn ModelStreamCallback>,
        ) -> Result<(), ModelServeError> {
            callback.on_chunk(
                serde_json::json!({"choices":[{"message":{"content":self.0}}]}).to_string(),
            );
            Ok(())
        }
    }

    fn finished(store: &MemoryStore, session_id: i64, input: &str, status: TraceStatus) -> i64 {
        let id = store.begin_trace_turn(session_id, input).unwrap();
        store
            .finish_trace_turn(id, status, None, Some("ok"), Some(1), None)
            .unwrap();
        id
    }

    #[test]
    fn extracts_once_from_completed_turn_and_preserves_evidence() {
        let store = MemoryStore::in_memory().unwrap();
        let session_id = store.create_trace_session().unwrap().id;
        let turn_id = finished(
            &store,
            session_id,
            "以后代码示例优先用 Kotlin",
            TraceStatus::Completed,
        );
        finished(&store, session_id, "不要处理失败轮", TraceStatus::Failed);
        let model = ModelServeWrapper::new(Arc::new(FixedModel(
            r#"[{"tier":"long","kind":"preference","topic_id":"code-language","content":"代码示例优先用 Kotlin","evidence":"以后代码示例优先用 Kotlin"}]"#,
        )));
        process_pending(store.clone(), model.clone(), session_id).unwrap();
        process_pending(store.clone(), model, session_id).unwrap();
        let entries = store.list_active().unwrap();
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].tier, MemoryTier::Long);
        assert_eq!(entries[0].source, format!("user:turn-{turn_id}"));
        let connection = store.connection.lock().unwrap();
        let evidence: String = connection
            .query_row(
                "SELECT evidence FROM memory_point_sources WHERE memory_id = ?1",
                [entries[0].id],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(evidence, "以后代码示例优先用 Kotlin");
    }

    #[test]
    fn rejects_candidate_without_user_evidence_and_retries_invalid_response() {
        let store = MemoryStore::in_memory().unwrap();
        let session_id = store.create_trace_session().unwrap().id;
        finished(&store, session_id, "你好", TraceStatus::Completed);
        let invalid = ModelServeWrapper::new(Arc::new(FixedModel("not json")));
        assert!(process_pending(store.clone(), invalid, session_id).is_err());
        let unsupported = ModelServeWrapper::new(Arc::new(FixedModel(
            r#"[{"tier":"long","kind":"fact","topic_id":"city","content":"住在北京","evidence":"我住在北京"}]"#,
        )));
        process_pending(store.clone(), unsupported, session_id).unwrap();
        assert!(store.list_active().unwrap().is_empty());
        assert!(store.pending_profile_turns(session_id).unwrap().is_empty());
    }

    #[test]
    fn medium_points_expire_and_matching_correction_updates_in_place() {
        let store = MemoryStore::in_memory().unwrap();
        let session_id = store.create_trace_session().unwrap().id;
        let first = finished(
            &store,
            session_id,
            "周五前完成演示稿",
            TraceStatus::Completed,
        );
        let candidate = Candidate {
            action: None,
            tier: "medium".into(),
            kind: "goal".into(),
            topic_id: "slides".into(),
            content: "周五前完成演示稿".into(),
            evidence: "周五前完成演示稿".into(),
            expires_at: None,
            replaces_id: None,
        };
        store
            .apply_profile_candidates(first, "周五前完成演示稿", &[candidate])
            .unwrap();
        let entry = store.list_active().unwrap().pop().unwrap();
        assert_eq!(entry.tier, MemoryTier::Medium);
        assert!(entry.expires_at.unwrap() > now_unix_seconds());
        let second = finished(
            &store,
            session_id,
            "改成下周完成演示稿",
            TraceStatus::Completed,
        );
        let correction = Candidate {
            action: None,
            tier: "medium".into(),
            kind: "goal".into(),
            topic_id: "slides".into(),
            content: "下周完成演示稿".into(),
            evidence: "改成下周完成演示稿".into(),
            expires_at: None,
            replaces_id: Some(entry.id),
        };
        store
            .apply_profile_candidates(second, "改成下周完成演示稿", &[correction])
            .unwrap();
        let entries = store.list_active().unwrap();
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].id, entry.id);
        assert_eq!(entries[0].content, "下周完成演示稿");
        let third = finished(&store, session_id, "演示稿已完成", TraceStatus::Completed);
        let archive = Candidate {
            action: Some("archive".into()),
            tier: "medium".into(),
            kind: "goal".into(),
            topic_id: "slides".into(),
            content: String::new(),
            evidence: "演示稿已完成".into(),
            expires_at: None,
            replaces_id: None,
        };
        store
            .apply_profile_candidates(third, "演示稿已完成", &[archive])
            .unwrap();
        assert!(store.list_active().unwrap().is_empty());
    }
}
