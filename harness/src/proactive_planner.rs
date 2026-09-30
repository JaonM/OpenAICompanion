//! A silent, model-assisted review of each completed user turn for grounded timers.

use std::sync::Mutex;

use rusqlite::params;
use serde::Deserialize;

use crate::memory::now_unix_seconds;
use crate::{
    AgentError, MemoryError, MemoryStore, Message, ModelRequest, ModelServeWrapper, ProactiveRule,
};

const DAY: i64 = 86_400;
const DISCOVERY_INTERVAL: i64 = 30 * 60;
static WORKER: Mutex<()> = Mutex::new(());

#[derive(Debug, Default, serde::Serialize)]
pub(crate) struct PlanOutcome {
    pub changed: usize,
    pub enabled: usize,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct Proposal {
    action: String,
    #[serde(default)]
    evidence: String,
    #[serde(default)]
    time_evidence: String,
    #[serde(default)]
    schedule_evidence: String,
    #[serde(default)]
    condition_evidence: String,
    #[serde(default)]
    task_id: String,
    #[serde(default)]
    title: String,
    #[serde(default)]
    instruction: String,
    #[serde(default)]
    memory_query: String,
    #[serde(default)]
    recurrence: String,
    #[serde(default)]
    event_at: Option<i64>,
    #[serde(default)]
    local_minute: Option<i64>,
    #[serde(default)]
    weekday_mask: Option<i64>,
    #[serde(default)]
    lead_minutes: Option<i64>,
    #[serde(default)]
    deadline_lead_minutes: Option<i64>,
    #[serde(default)]
    allowed_tools: Vec<String>,
    #[serde(default)]
    required_tools: Vec<String>,
}

pub(crate) fn process_pending(
    store: &MemoryStore,
    model: &ModelServeWrapper,
    timezone_offset_minutes: i64,
) -> Result<PlanOutcome, AgentError> {
    if !(-840..=840).contains(&timezone_offset_minutes) {
        return Err(AgentError::Memory(
            "invalid planner time-zone offset".into(),
        ));
    }
    let _guard = WORKER
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|error| AgentError::Memory(error.to_string()))?;
    let mut outcome = PlanOutcome::default();
    for turn_id in store.pending_proactive_turns().map_err(memory_error)? {
        let change = process_turn(store, model, &runtime, turn_id, timezone_offset_minutes)?;
        outcome.changed += change.changed;
        outcome.enabled += change.enabled;
    }
    Ok(outcome)
}

/// Independently discovers useful recurring checks from stored memories.
/// This is invoked by the app's background cadence, even without a new turn.
pub(crate) fn discover_from_memories(
    store: &MemoryStore,
    model: &ModelServeWrapper,
    timezone_offset_minutes: i64,
) -> Result<PlanOutcome, AgentError> {
    if !(-840..=840).contains(&timezone_offset_minutes) {
        return Err(AgentError::Memory(
            "invalid planner time-zone offset".into(),
        ));
    }
    let _guard = WORKER
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let memories = active_memories(store)?;
    let available_tools = available_query_tools();
    if memories.is_empty() || available_tools.is_empty() {
        return Ok(PlanOutcome::default());
    }
    if !store.claim_proactive_discovery().map_err(memory_error)? {
        return Ok(PlanOutcome::default());
    }
    let existing = store.proactive_rules().map_err(memory_error)?;
    let input = serde_json::json!({
        "known_memories":memories,"existing_tasks":task_summaries(&existing),
        "available_query_tools":available_tools,
        "now_unix_seconds":now_unix_seconds(),"timezone_offset_minutes":timezone_offset_minutes,
    });
    let prompt = "后台主动发现：当前没有新用户 query。根据已确认记忆中的稳定时间、习惯和可用查询工具，判断是否值得建立一个未来自动检查任务。即使用户没有提过下雨，也可从通勤时间和天气工具推断出值得在出行前检查天气；但不能猜测地址或声称现在下雨。天气、交通、商家等实时条件只能在任务到点后查询。必须有可引用的记忆原文和明确帮助价值；没有稳定的检查时间、需要的查询工具或已有同义任务时输出 {\"action\":\"none\"}。每次最多创建一个任务，只允许 weekly；不得修改或取消已有任务。动态条件必须写入 instruction，且 required_tools 至少包含对应的 available_query_tools 工具。严格输出与 Turn 提取相同的 JSON 字段：action、evidence、time_evidence、schedule_evidence、condition_evidence、task_id、title、instruction、memory_query、recurrence、event_at、local_minute、weekday_mask、lead_minutes、deadline_lead_minutes、allowed_tools、required_tools。evidence、time_evidence、schedule_evidence 必须是已确认记忆中的连续原文；用户没有表达条件时 condition_evidence 为空。不要调用工具或输出额外内容。";
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|error| AgentError::Memory(error.to_string()))?;
    let response = runtime
        .block_on(async {
            tokio::time::timeout(
                std::time::Duration::from_secs(120),
                model.complete_silent(ModelRequest {
                    system_prompt: prompt.into(),
                    user_input: String::new(),
                    history: vec![Message::User {
                        content: input.to_string(),
                    }],
                    tools: Vec::new(),
                }),
            )
            .await
        })
        .map_err(|_| AgentError::Model("proactive discovery timed out".into()))??;
    let proposal: Proposal = serde_json::from_str(response.content.trim())
        .map_err(|error| AgentError::Model(format!("invalid proactive discovery: {error}")))?;
    if proposal.action != "none"
        && (proposal.action != "upsert"
            || proposal.recurrence != "weekly"
            || !proposal.task_id.is_empty()
            || proposal.required_tools.is_empty())
    {
        return Ok(PlanOutcome::default());
    }
    if existing.iter().any(|task| {
        task.title == proposal.title
            && Some(task.local_minute) == proposal.local_minute
            && Some(task.weekday_mask) == proposal.weekday_mask
    }) {
        return Ok(PlanOutcome::default());
    }
    let Some(source_id) = source_memory_id(&memories, &proposal.evidence) else {
        return Ok(PlanOutcome::default());
    };
    if store
        .is_proactive_source_dismissed(source_id)
        .map_err(memory_error)?
        || existing.iter().any(|task| {
            task.scenario
                .starts_with(&format!("auto_memory_{source_id}_"))
                && Some(task.local_minute) == proposal.local_minute
                && Some(task.weekday_mask) == proposal.weekday_mask
        })
    {
        return Ok(PlanOutcome::default());
    }
    apply_proposal(
        store,
        None,
        "",
        &memories,
        &existing,
        &available_tools,
        timezone_offset_minutes,
        proposal,
    )
    .map_err(memory_error)
}

fn active_memories(store: &MemoryStore) -> Result<Vec<serde_json::Value>, AgentError> {
    Ok(store
        .list_active()
        .map_err(memory_error)?
        .into_iter()
        .take(30)
        .map(|m| serde_json::json!({"id":m.id,"content":m.content,"topic_id":m.topic_id}))
        .collect())
}

fn available_query_tools() -> Vec<serde_json::Value> {
    crate::uniffi::current_mcp_tool_snapshot()
        .1
        .into_iter()
        .filter(|tool| query_tool_name(&tool.name))
        .map(|tool| serde_json::json!({"name":tool.name,"description":tool.description}))
        .collect()
}

fn task_summaries(existing: &[ProactiveRule]) -> Vec<serde_json::Value> {
    existing
        .iter()
        .map(|task| {
            serde_json::json!({
                "id":task.scenario,"title":task.title,"instruction":task.instruction,
                "local_minute":task.local_minute,"weekday_mask":task.weekday_mask,
                "one_shot_at":task.one_shot_at,"enabled":task.enabled,
            })
        })
        .collect()
}

fn process_turn(
    store: &MemoryStore,
    model: &ModelServeWrapper,
    runtime: &tokio::runtime::Runtime,
    turn_id: i64,
    offset: i64,
) -> Result<PlanOutcome, AgentError> {
    let turn = store.get_trace_turn(turn_id).map_err(memory_error)?;
    let memories = active_memories(store)?;
    let existing = store.proactive_rules().map_err(memory_error)?;
    let available_tools = available_query_tools();
    let tasks = task_summaries(&existing);
    let input = serde_json::json!({
        "turn_id":turn_id,"user_query":turn.user_input.chars().take(4000).collect::<String>(),
        "known_memories":memories,"existing_tasks":tasks,"available_query_tools":available_tools,
        "now_unix_seconds":now_unix_seconds(),"timezone_offset_minutes":offset,
    });
    let prompt = "仅根据本轮用户 query 与已确认记忆，判断是否应建立一个主动定时任务。此轮只创建未来的检查计划，不判断未来是否下雨等动态条件。普通问答、没有可核实时间的计划、助手或工具内容都输出 none；不猜测住址、时间和日期。用户明确要求提醒或主动帮助时可创建；对于稳定重复日程，只有存在清楚的主动帮助价值时才创建。若用户提出'下雨时帮我考虑打车'之类条件，且已知稳定的相关时间，可创建按该时间重复运行的条件检查任务；instruction 必须写明仅在实时条件成立时推送，condition_evidence 引用条件原文。动态条件必须配置对应查询工具到 allowed_tools 与 required_tools；工具名只能取自 available_query_tools，缺少可查询的数据源则输出 none，不创建无条件提醒。一次最多一个任务。已有同义任务应使用它的 task_id 更新，不重复创建；用户明确取消时使用 cancel。输出严格 JSON 对象，字段：action(none|upsert|cancel)、evidence(用户 query 中连续原文)、time_evidence(用户 query 或已确认记忆中的时间原文)、schedule_evidence(日期或重复规则原文)、condition_evidence(动态条件原文，无条件时为空)、task_id(已有任务 ID 或空)、title、instruction、memory_query、recurrence(once|weekly)、event_at(一次性事件 Unix 秒或 null)、local_minute(重复事件当地时分换算的分钟或 null)、weekday_mask(周一 bit0，周日 bit6)、lead_minutes、deadline_lead_minutes、allowed_tools(仅查询工具名数组)、required_tools(推送前至少一个必须成功调用的查询工具名数组)。没有足够依据输出 {\"action\":\"none\"}。不要调用工具，也不要添加额外字段。";
    let response = runtime
        .block_on(async {
            tokio::time::timeout(
                std::time::Duration::from_secs(120),
                model.complete_silent(ModelRequest {
                    system_prompt: prompt.into(),
                    user_input: String::new(),
                    history: vec![Message::User {
                        content: input.to_string(),
                    }],
                    tools: Vec::new(),
                }),
            )
            .await
        })
        .map_err(|_| AgentError::Model("proactive planning timed out".into()))??;
    let proposal: Proposal = serde_json::from_str(response.content.trim())
        .map_err(|error| AgentError::Model(format!("invalid proactive plan: {error}")))?;
    let changed = apply_proposal(
        store,
        Some(turn_id),
        &turn.user_input,
        &memories,
        &existing,
        &available_tools,
        offset,
        proposal,
    )
    .map_err(memory_error)?;
    store
        .mark_proactive_turn_planned(turn_id)
        .map_err(memory_error)?;
    Ok(changed)
}

fn apply_proposal(
    store: &MemoryStore,
    turn_id: Option<i64>,
    query: &str,
    memories: &[serde_json::Value],
    existing: &[ProactiveRule],
    available_tools: &[serde_json::Value],
    offset: i64,
    proposal: Proposal,
) -> Result<PlanOutcome, MemoryError> {
    if proposal.action == "none" {
        return Ok(PlanOutcome::default());
    }
    let grounded = |quote: &str| {
        !quote.is_empty()
            && quote.chars().count() <= 160
            && (query.contains(quote)
                || memories.iter().any(|m| {
                    m["content"]
                        .as_str()
                        .is_some_and(|text| text.contains(quote))
                }))
    };
    if !proposal.evidence.is_empty()
        && proposal.evidence.chars().count() <= 200
        && (query.contains(&proposal.evidence)
            || (turn_id.is_none()
                && memories.iter().any(|m| {
                    m["content"]
                        .as_str()
                        .is_some_and(|content| content.contains(&proposal.evidence))
                })))
    {
        if proposal.action == "cancel" {
            let cancel_words = [
                "取消", "不要", "别", "停止", "关掉", "删除", "不再", "cancel", "stop",
            ];
            if cancel_words
                .iter()
                .any(|word| proposal.evidence.to_lowercase().contains(word))
                && existing
                    .iter()
                    .any(|task| task.scenario == proposal.task_id)
            {
                store.delete_proactive_task(&proposal.task_id)?;
                return Ok(PlanOutcome {
                    changed: 1,
                    enabled: 0,
                });
            }
            return Ok(PlanOutcome::default());
        }
    } else {
        return Ok(PlanOutcome::default());
    }
    if proposal.action != "upsert"
        || !grounded(&proposal.time_evidence)
        || !grounded(&proposal.schedule_evidence)
        || (!proposal.condition_evidence.is_empty()
            && (!grounded(&proposal.condition_evidence)
                || proposal.required_tools.is_empty()
                || !proposal.instruction.contains(&proposal.condition_evidence)))
        || !has_clock_evidence(&proposal.time_evidence)
        || proposal.title.trim().is_empty()
        || proposal.instruction.trim().is_empty()
    {
        return Ok(PlanOutcome::default());
    }
    let now = now_unix_seconds();
    let (one_shot_at, local_minute, weekday_mask) = match proposal.recurrence.as_str() {
        "once" => {
            let Some(at) = proposal.event_at else {
                return Ok(PlanOutcome::default());
            };
            if at <= now || at > now + 366 * DAY {
                return Ok(PlanOutcome::default());
            }
            let local_day = (now + offset * 60).div_euclid(DAY);
            let event_day = (at + offset * 60).div_euclid(DAY);
            if (proposal.schedule_evidence.contains("明天") && event_day != local_day + 1)
                || (proposal.schedule_evidence.contains("后天") && event_day != local_day + 2)
                || (proposal.schedule_evidence.contains("今天") && event_day != local_day)
            {
                return Ok(PlanOutcome::default());
            }
            (Some(at), (at + offset * 60).rem_euclid(DAY) / 60, 0)
        }
        "weekly" => {
            let recurrence_words = [
                "每天",
                "每周",
                "工作日",
                "周一",
                "周二",
                "周三",
                "周四",
                "周五",
                "周六",
                "周日",
                "星期",
                "平时",
            ];
            if !recurrence_words
                .iter()
                .any(|word| proposal.schedule_evidence.contains(word))
            {
                return Ok(PlanOutcome::default());
            }
            let Some(minute) = proposal.local_minute else {
                return Ok(PlanOutcome::default());
            };
            let Some(mask) = proposal.weekday_mask else {
                return Ok(PlanOutcome::default());
            };
            (None, minute, mask)
        }
        _ => return Ok(PlanOutcome::default()),
    };
    if proposal.allowed_tools.iter().any(|name| {
        !query_tool_name(name)
            || !available_tools
                .iter()
                .any(|tool| tool["name"].as_str() == Some(name))
    }) || proposal
        .required_tools
        .iter()
        .any(|name| !proposal.allowed_tools.contains(name))
    {
        return Ok(PlanOutcome::default());
    }
    let lead_minutes = proposal.lead_minutes.unwrap_or(60);
    let deadline_lead_minutes = proposal.deadline_lead_minutes.unwrap_or(30);
    if !(0..1440).contains(&local_minute)
        || (one_shot_at.is_none() && !(1..=127).contains(&weekday_mask))
        || !(5..=180).contains(&lead_minutes)
        || !(0..lead_minutes).contains(&deadline_lead_minutes)
        || proposal.title.chars().count() > 80
        || proposal.instruction.chars().count() > 1000
        || proposal.memory_query.chars().count() > 300
        || proposal.allowed_tools.len() > 16
        || proposal.required_tools.len() > 16
    {
        return Ok(PlanOutcome::default());
    }
    let id = if existing
        .iter()
        .any(|task| task.scenario == proposal.task_id)
    {
        proposal.task_id
    } else if let Some(task) = existing.iter().find(|task| {
        task.title == proposal.title
            && task.one_shot_at == one_shot_at
            && task.local_minute == local_minute
            && task.weekday_mask == weekday_mask
    }) {
        task.scenario.clone()
    } else {
        match turn_id {
            Some(id) => format!("auto_turn_{id}"),
            None => {
                let source_id = memories
                    .iter()
                    .find(|m| {
                        m["content"]
                            .as_str()
                            .is_some_and(|content| content.contains(&proposal.evidence))
                    })
                    .and_then(|m| m["id"].as_i64())
                    .unwrap_or(0);
                format!(
                    "auto_memory_{source_id}_{}",
                    stable_title_hash(&proposal.title)
                )
            }
        }
    };
    let enabled = existing
        .iter()
        .find(|task| task.scenario == id)
        .map(|task| task.enabled)
        .unwrap_or(true);
    store.put_proactive_task(ProactiveRule {
        scenario: id,
        title: proposal.title,
        instruction: proposal.instruction,
        memory_query: proposal.memory_query,
        allowed_tools: proposal.allowed_tools,
        required_tools: proposal.required_tools,
        enabled,
        local_minute,
        weekday_mask,
        lead_minutes,
        deadline_lead_minutes,
        timezone_offset_minutes: offset,
        one_shot_at,
        next_run_at: None,
        next_event_at: None,
    })?;
    Ok(PlanOutcome {
        changed: 1,
        enabled: usize::from(enabled),
    })
}

fn has_clock_evidence(value: &str) -> bool {
    let numeral = value
        .chars()
        .any(|c| c.is_ascii_digit() || "一二三四五六七八九十两零半".contains(c));
    numeral && (value.contains(':') || value.contains('点') || value.contains('时'))
}

fn query_tool_name(name: &str) -> bool {
    ["get_", "list_", "search_", "query_", "fetch_", "estimate_"]
        .iter()
        .any(|prefix| name.starts_with(prefix))
}

fn stable_title_hash(title: &str) -> String {
    let hash = title
        .as_bytes()
        .iter()
        .fold(0xcbf29ce484222325_u64, |hash, byte| {
            (hash ^ u64::from(*byte)).wrapping_mul(0x100000001b3)
        });
    format!("{hash:016x}")
}

fn source_memory_id(memories: &[serde_json::Value], evidence: &str) -> Option<i64> {
    memories
        .iter()
        .find(|m| {
            m["content"]
                .as_str()
                .is_some_and(|content| content.contains(evidence))
        })
        .and_then(|m| m["id"].as_i64())
}

impl MemoryStore {
    fn is_proactive_source_dismissed(&self, source_id: i64) -> Result<bool, MemoryError> {
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        Ok(connection.query_row(
            "SELECT EXISTS(SELECT 1 FROM proactive_discovery_dismissals WHERE source_memory_id=?1)",
            [source_id],
            |row| row.get::<_, i64>(0),
        )? != 0)
    }
    fn claim_proactive_discovery(&self) -> Result<bool, MemoryError> {
        let now = now_unix_seconds();
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        connection.execute(
            "INSERT OR IGNORE INTO proactive_discovery_state (id,last_attempt_at) VALUES (1,0)",
            [],
        )?;
        Ok(connection.execute(
            "UPDATE proactive_discovery_state SET last_attempt_at=?1
             WHERE id=1 AND last_attempt_at<=?2",
            params![now, now - DISCOVERY_INTERVAL],
        )? == 1)
    }
    fn pending_proactive_turns(&self) -> Result<Vec<i64>, MemoryError> {
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        let mut statement = connection.prepare(
            "SELECT t.id FROM trace_turns t LEFT JOIN proactive_planned_turns p ON p.turn_id=t.id
             WHERE t.status IN ('completed','max_steps') AND p.turn_id IS NULL ORDER BY t.id LIMIT 20"
        )?;
        statement
            .query_map([], |row| row.get(0))?
            .collect::<Result<Vec<_>, _>>()
            .map_err(Into::into)
    }

    fn mark_proactive_turn_planned(&self, turn_id: i64) -> Result<(), MemoryError> {
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        connection.execute(
            "INSERT OR IGNORE INTO proactive_planned_turns (turn_id,processed_at) VALUES (?1,?2)",
            params![turn_id, now_unix_seconds()],
        )?;
        Ok(())
    }
}

fn memory_error(error: MemoryError) -> AgentError {
    AgentError::Memory(error.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        MemoryTier, ModelServeCallback, ModelServeError, ModelStreamCallback, NewMemory,
        TraceStatus,
    };
    use std::sync::{
        Arc,
        atomic::{AtomicUsize, Ordering},
    };

    struct FixedModel {
        answer: String,
        calls: Arc<AtomicUsize>,
    }

    #[async_trait::async_trait]
    impl ModelServeCallback for FixedModel {
        async fn complete(
            &self,
            _request: String,
            callback: Arc<dyn ModelStreamCallback>,
        ) -> Result<(), ModelServeError> {
            self.calls.fetch_add(1, Ordering::SeqCst);
            callback.on_chunk(
                serde_json::json!({"choices":[{"message":{"content":self.answer}}]}).to_string(),
            );
            Ok(())
        }
    }

    fn completed_turn(store: &MemoryStore, query: &str) {
        let session = store.ensure_single_trace_session().unwrap();
        let id = store.begin_trace_turn(session.id, query).unwrap();
        store
            .finish_trace_turn(
                id,
                TraceStatus::Completed,
                None,
                Some("好的"),
                Some(1),
                None,
            )
            .unwrap();
    }

    #[test]
    fn each_completed_turn_is_planned_once_and_creates_grounded_timer() {
        let store = MemoryStore::in_memory().unwrap();
        completed_turn(&store, "以后每天晚上六点提醒我吃晚饭");
        let calls = Arc::new(AtomicUsize::new(0));
        let answer = serde_json::json!({
            "action":"upsert","evidence":"每天晚上六点提醒我吃晚饭",
            "time_evidence":"晚上六点","schedule_evidence":"每天",
            "title":"晚饭提醒","instruction":"饭前提醒用户考虑晚餐",
            "memory_query":"晚饭 饮食","recurrence":"weekly",
            "local_minute":1080,"weekday_mask":127,"lead_minutes":60,
            "deadline_lead_minutes":30
        })
        .to_string();
        let model = ModelServeWrapper::new(Arc::new(FixedModel {
            answer,
            calls: calls.clone(),
        }));
        assert_eq!(process_pending(&store, &model, 180).unwrap().enabled, 1);
        assert_eq!(process_pending(&store, &model, 180).unwrap().changed, 0);
        assert_eq!(calls.load(Ordering::SeqCst), 1);
        let tasks = store.proactive_rules().unwrap();
        assert_eq!(tasks.len(), 1);
        assert_eq!(tasks[0].local_minute, 1080);
        assert_eq!(tasks[0].timezone_offset_minutes, 180);
    }

    #[test]
    fn invented_time_does_not_create_task() {
        let store = MemoryStore::in_memory().unwrap();
        completed_turn(&store, "今天心情不错");
        let answer = serde_json::json!({
            "action":"upsert","evidence":"今天心情不错","time_evidence":"晚上六点",
            "schedule_evidence":"今天","title":"提醒","instruction":"提醒用户",
            "recurrence":"once","event_at":now_unix_seconds()+3600
        })
        .to_string();
        let model = ModelServeWrapper::new(Arc::new(FixedModel {
            answer,
            calls: Arc::new(AtomicUsize::new(0)),
        }));
        assert_eq!(process_pending(&store, &model, 0).unwrap().changed, 0);
        assert!(store.proactive_rules().unwrap().is_empty());
    }

    #[test]
    fn one_shot_plan_and_later_cancellation_use_turn_evidence() {
        let store = MemoryStore::in_memory().unwrap();
        completed_turn(&store, "明天六点提醒我出门");
        let tomorrow = (now_unix_seconds().div_euclid(DAY) + 1) * DAY + 18 * 3600;
        let answer = serde_json::json!({
            "action":"upsert","evidence":"明天六点提醒我出门",
            "time_evidence":"六点","schedule_evidence":"明天",
            "title":"出门提醒","instruction":"提醒用户出门",
            "recurrence":"once","event_at":tomorrow,
            "lead_minutes":60,"deadline_lead_minutes":30
        })
        .to_string();
        let model = ModelServeWrapper::new(Arc::new(FixedModel {
            answer,
            calls: Arc::new(AtomicUsize::new(0)),
        }));
        assert_eq!(process_pending(&store, &model, 0).unwrap().enabled, 1);
        let task = store.proactive_rules().unwrap().pop().unwrap();
        assert_eq!(task.one_shot_at, Some(tomorrow));
        completed_turn(&store, "取消这个出门提醒");
        let answer = serde_json::json!({
            "action":"cancel","evidence":"取消这个出门提醒","task_id":task.scenario
        })
        .to_string();
        let model = ModelServeWrapper::new(Arc::new(FixedModel {
            answer,
            calls: Arc::new(AtomicUsize::new(0)),
        }));
        let result = process_pending(&store, &model, 0).unwrap();
        assert_eq!((result.changed, result.enabled), (1, 0));
        assert!(store.proactive_rules().unwrap().is_empty());
    }

    #[test]
    fn conditional_monitor_requires_a_real_query_tool() {
        let store = MemoryStore::in_memory().unwrap();
        let query = "我每天六点下班，下雨时提醒我考虑打车";
        let proposal = || {
            serde_json::from_value(serde_json::json!({
                "action":"upsert","evidence":query,"time_evidence":"六点",
                "schedule_evidence":"每天","condition_evidence":"下雨时",
                "title":"雨天通勤","instruction":"下雨时提醒用户考虑打车",
                "recurrence":"weekly","local_minute":1080,"weekday_mask":127,
                "allowed_tools":["get_weather"],"required_tools":["get_weather"]
            }))
            .unwrap()
        };
        assert_eq!(
            apply_proposal(&store, Some(1), query, &[], &[], &[], 0, proposal())
                .unwrap()
                .changed,
            0
        );
        let catalog =
            vec![serde_json::json!({"name":"get_weather","description":"weather forecast"})];
        assert_eq!(
            apply_proposal(&store, Some(1), query, &[], &[], &catalog, 0, proposal())
                .unwrap()
                .enabled,
            1
        );
        assert_eq!(
            store.proactive_rules().unwrap()[0].required_tools,
            vec!["get_weather"]
        );
    }

    #[test]
    fn background_discovery_can_create_task_without_a_new_turn() {
        let store = MemoryStore::in_memory().unwrap();
        store
            .remember(NewMemory {
                tier: MemoryTier::Long,
                topic_id: None,
                content: "用户工作日六点下班，住家在市中心".into(),
                source: "confirmed user memory".into(),
                expires_at: None,
            })
            .unwrap();
        let memories = active_memories(&store).unwrap();
        let catalog =
            vec![serde_json::json!({"name":"get_weather","description":"weather forecast"})];
        let proposal = serde_json::from_value(serde_json::json!({
            "action":"upsert","evidence":"工作日六点下班",
            "time_evidence":"六点","schedule_evidence":"工作日",
            "condition_evidence":"","title":"雨天通勤",
            "instruction":"下雨时提前提醒用户考虑打车",
            "recurrence":"weekly","local_minute":1080,"weekday_mask":31,
            "allowed_tools":["get_weather"],"required_tools":["get_weather"]
        }))
        .unwrap();
        assert_eq!(
            apply_proposal(&store, None, "", &memories, &[], &catalog, 0, proposal)
                .unwrap()
                .enabled,
            1
        );
        let task = store.proactive_rules().unwrap().pop().unwrap();
        assert!(task.scenario.starts_with("auto_memory_"));
        store.delete_proactive_task(&task.scenario).unwrap();
        assert!(
            store
                .is_proactive_source_dismissed(memories[0]["id"].as_i64().unwrap())
                .unwrap()
        );
        assert!(store.claim_proactive_discovery().unwrap());
        assert!(!store.claim_proactive_discovery().unwrap());
    }
}
