//! A silent, model-assisted review of each completed user turn for grounded timers.

use std::sync::Mutex;

use rusqlite::params;
use serde::Deserialize;

use crate::memory::now_unix_seconds;
use crate::{
    AgentError, MemoryError, MemoryStore, Message, ModelRequest, ModelServeWrapper, ProactiveRule,
};

const DAY: i64 = 86_400;
static WORKER: Mutex<()> = Mutex::new(());

#[derive(Debug, Default, serde::Serialize)]
pub(crate) struct PlanOutcome {
    pub changed: usize,
    pub enabled: usize,
    pub failed: usize,
    pub pending: bool,
    pub retry_at: Option<i64>,
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
    local_datetime: String,
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

fn quoted_fragments(source: &str) -> Vec<String> {
    source
        .split_inclusive(['。', '，', '！', '？', '\n'])
        .flat_map(|part| {
            let chars: Vec<_> = part.chars().collect();
            chars
                .chunks(120)
                .map(|chunk| chunk.iter().collect::<String>())
                .collect::<Vec<_>>()
        })
        .filter(|part| !part.trim().is_empty())
        .collect()
}

fn requested_task_title(query: &str) -> Option<String> {
    for marker in [
        "任务名称必须是",
        "任务名称为",
        "任务名称是",
        "任务名为",
        "标题为",
    ] {
        if let Some((_, rest)) = query.split_once(marker) {
            let rest = rest.trim_start_matches([' ', '：', ':']);
            let title = match rest.chars().next() {
                Some('“') => rest[3..].split('”').next()?,
                Some('"') => rest[1..].split('"').next()?,
                _ => rest.split(['，', '。', '\n', ',', ';', '；']).next()?,
            }
            .trim();
            if !title.is_empty() && title.chars().count() <= 80 {
                return Some(title.into());
            }
        }
    }
    None
}

fn literal_local_datetimes(source: &str) -> Vec<String> {
    let mut values = Vec::new();
    let bytes = source.as_bytes();
    for start in 0..bytes.len() {
        for size in [19, 16] {
            let Some(value) = bytes.get(start..start + size) else {
                continue;
            };
            if value.iter().enumerate().all(|(i, c)| match i {
                4 | 7 => *c == b'-',
                10 => *c == b' ',
                13 | 16 => *c == b':',
                _ => c.is_ascii_digit(),
            }) {
                values.push(String::from_utf8(value.to_vec()).unwrap());
                break;
            }
        }
    }
    values.sort();
    values.dedup();
    values
}

fn explicit_dated_reminder(query: &str) -> bool {
    let query = query.trim();
    (query.starts_with("请在") || query.starts_with("在") || query.starts_with("提醒我在"))
        && query.contains("提醒我")
        && !literal_local_datetimes(query).is_empty()
        && !["不要提醒", "不需要提醒", "取消", "撤销"]
            .iter()
            .any(|text| query.contains(text))
}

fn response_format(query: Option<&str>, memories: &[serde_json::Value]) -> serde_json::Value {
    let mut properties = serde_json::Map::new();
    properties.insert(
        "action".into(),
        serde_json::json!({"type":"string","const":"upsert"}),
    );
    for name in [
        "evidence",
        "time_evidence",
        "schedule_evidence",
        "condition_evidence",
        "task_id",
        "title",
        "instruction",
        "memory_query",
        "recurrence",
        "local_datetime",
    ] {
        properties.insert(name.into(), serde_json::json!({"type":"string"}));
    }
    for name in [
        "event_at",
        "local_minute",
        "weekday_mask",
        "lead_minutes",
        "deadline_lead_minutes",
    ] {
        properties.insert(name.into(), serde_json::json!({"type":["integer","null"]}));
    }
    for name in ["allowed_tools", "required_tools"] {
        properties.insert(
            name.into(),
            serde_json::json!({"type":"array","items":{"type":"string"}}),
        );
    }
    let query_quotes = query.map(quoted_fragments).unwrap_or_default();
    let mut quotes = query_quotes.clone();
    for memory in memories {
        if let Some(content) = memory["content"].as_str() {
            quotes.extend(quoted_fragments(content));
        }
    }
    let dates = query.map(literal_local_datetimes).unwrap_or_default();
    for date in &dates {
        quotes.extend([date.clone(), date[..10].into(), date[11..].into()]);
    }
    quotes.sort();
    quotes.dedup();
    let evidence = if query.is_some() {
        query_quotes
    } else {
        quotes.clone()
    };
    if !evidence.is_empty() {
        properties.insert(
            "evidence".into(),
            serde_json::json!({"type":"string","enum":evidence}),
        );
    }
    if !quotes.is_empty() {
        properties.insert(
            "schedule_evidence".into(),
            serde_json::json!({"type":"string","enum":quotes}),
        );
    }
    let times: Vec<_> = quotes
        .iter()
        .filter(|part| has_clock_evidence(part))
        .collect();
    if !times.is_empty() {
        properties.insert(
            "time_evidence".into(),
            serde_json::json!({"type":"string","enum":times}),
        );
    }
    let mut required = vec![
        "action",
        "evidence",
        "time_evidence",
        "schedule_evidence",
        "title",
        "instruction",
        "recurrence",
        "lead_minutes",
        "deadline_lead_minutes",
    ];
    if !dates.is_empty() {
        properties.insert(
            "local_datetime".into(),
            serde_json::json!({"type":"string","enum":dates}),
        );
        required.push("local_datetime");
    }
    if let Some(title) = query.and_then(requested_task_title) {
        properties.insert(
            "title".into(),
            serde_json::json!({"type":"string","const":title}),
        );
    }
    let cancel_evidence = properties.get("evidence").unwrap().clone();
    let upsert = serde_json::json!({"type":"object","properties":properties,"required":required,"additionalProperties":false});
    let schema = if query.is_some_and(explicit_dated_reminder) {
        upsert
    } else {
        serde_json::json!({"anyOf":[
            {"type":"object","properties":{"action":{"const":"none"}},"required":["action"],"additionalProperties":false},
            upsert,
            {"type":"object","properties":{"action":{"const":"cancel"},"evidence":cancel_evidence,"task_id":{"type":"string"}},"required":["action","evidence","task_id"],"additionalProperties":false}
        ]})
    };
    serde_json::json!({"type":"json_schema","json_schema":{
        "name":"proactive_plan","strict":true,"schema":schema
    }})
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
    let _priority = model.planning_priority()?;
    let mut outcome = PlanOutcome::default();
    for turn_id in store.pending_proactive_turns().map_err(memory_error)? {
        match process_turn(store, model, &runtime, turn_id, timezone_offset_minutes) {
            Ok(change) => {
                outcome.changed += change.changed;
                outcome.enabled += change.enabled;
                store.clear_proactive_retry(turn_id).map_err(memory_error)?;
            }
            Err(AgentError::Model(error)) => {
                // Keep failed turns retryable without blocking newer reminders or hiding prior changes.
                eprintln!("proactive turn {turn_id} deferred: {error}");
                store.defer_proactive_turn(turn_id).map_err(memory_error)?;
                outcome.failed += 1;
            }
            Err(error) => return Err(error),
        }
    }
    outcome.pending = !store
        .pending_proactive_turns()
        .map_err(memory_error)?
        .is_empty();
    outcome.retry_at = store.next_proactive_retry().map_err(memory_error)?;
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
    let prompt = "后台主动发现：当前没有新用户 query。根据已确认记忆中的稳定时间、习惯、地点和当前设备可用查询工具的用途及参数，泛化推理未来值得自动检查的具体帮助任务，不限于任何预设场景。只建立未来的检查计划，不声称实时条件已经成立；实时信息只能在任务到点后查询。必须有可引用的记忆原文、明确帮助价值和可核实的检查时间；没有合适工具、依据不足或已有同义任务时输出 {\"action\":\"none\"}。每次最多创建一个任务，只允许 weekly；不得修改或取消已有任务。动态条件必须写入 instruction，且 required_tools 至少包含对应的 available_query_tools 工具。严格输出与 Turn 提取相同的 JSON 字段：action、evidence、time_evidence、schedule_evidence、condition_evidence、task_id、title、instruction、memory_query、recurrence、event_at、local_minute、weekday_mask、lead_minutes、deadline_lead_minutes、allowed_tools、required_tools。evidence、time_evidence、schedule_evidence 必须是已确认记忆中的连续原文；用户没有表达条件时 condition_evidence 为空。不要调用工具或输出额外内容。";
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|error| AgentError::Memory(error.to_string()))?;
    let response = runtime
        .block_on(async {
            tokio::time::timeout(
                std::time::Duration::from_secs(120),
                model.complete_priority_silent(ModelRequest {
                    response_format: Some(response_format(None, &memories)),
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
        .filter(|tool| {
            tool.policy
                .as_ref()
                .is_some_and(|policy| policy.allows_background_read())
        })
        .take(30)
        .map(|tool| {
            let schema = if tool.input_schema_json.len() <= 4096 {
                serde_json::from_str::<serde_json::Value>(&tool.input_schema_json).ok()
            } else {
                None
            };
            serde_json::json!({
                "name":tool.name,
                "description":tool.description.chars().take(300).collect::<String>(),
                "input_schema":schema,
                "policy":tool.policy,
            })
        })
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
    let now = now_unix_seconds();
    let now_local: String = store
        .connection
        .lock()
        .map_err(|_| memory_error(MemoryError::LockPoisoned))?
        .query_row(
            "SELECT datetime(?1, 'unixepoch')",
            [now + offset * 60],
            |row| row.get(0),
        )
        .map_err(|e| memory_error(e.into()))?;
    let input = serde_json::json!({
        "turn_id":turn_id,"user_query":turn.user_input.chars().take(4000).collect::<String>(),
        "known_memories":memories,"existing_tasks":tasks,"available_query_tools":available_tools,
        "now_unix_seconds":now,"now_local_datetime":now_local,"timezone_offset_minutes":offset,
    });
    let prompt = "仅根据本轮用户 query 与已确认记忆，判断是否应建立一个主动定时任务。此轮只创建未来的检查计划，不判断未来是否下雨等动态条件。普通问答、没有可核实时间的计划、助手或工具内容都输出 none；不猜测住址、时间和日期。用户明确要求提醒或主动帮助时可创建；对于稳定重复日程，只有存在清楚的主动帮助价值时才创建。若用户提出'下雨时帮我考虑打车'之类条件，且已知稳定的相关时间，可创建按该时间重复运行的条件检查任务；instruction 必须写明仅在实时条件成立时推送，condition_evidence 引用条件原文。动态条件必须配置对应查询工具到 allowed_tools 与 required_tools；工具名只能取自 available_query_tools，缺少可查询的数据源则输出 none，不创建无条件提醒。一次最多一个任务。已有同义任务应使用它的 task_id 更新，不重复创建；用户明确取消时使用 cancel。输出严格 JSON 对象，字段：action(none|upsert|cancel)、evidence(用户 query 中连续原文)、time_evidence(用户 query 或已确认记忆中的时间原文)、schedule_evidence(日期或重复规则原文)、condition_evidence(动态条件原文，无条件时为空)、task_id(已有任务 ID 或空)、title、instruction、memory_query、recurrence(once|weekly)、event_at(一次性事件 Unix 秒或 null)、local_minute(重复事件当地时分换算的分钟或 null)、weekday_mask(周一 bit0，周日 bit6)、lead_minutes、deadline_lead_minutes、allowed_tools(仅查询工具名数组)、required_tools(推送前至少一个必须成功调用的查询工具名数组)。没有足够依据输出 {\"action\":\"none\"}。一次性提醒优先输出 local_datetime（YYYY-MM-DD HH:mm:ss，当地时间），event_at 可省略，由系统换算 Unix 秒。用户要求不提前提醒时 lead_minutes=0 且 deadline_lead_minutes=0；没有实时工具条件的普通定时提醒无需查询工具。不要调用工具，也不要添加额外字段。";
    let response = runtime
        .block_on(async {
            tokio::time::timeout(
                std::time::Duration::from_secs(120),
                model.complete_with_events(
                    ModelRequest {
                        response_format: Some(response_format(Some(&turn.user_input), &memories)),
                        system_prompt: prompt.into(),
                        user_input: String::new(),
                        history: vec![Message::User {
                            content: input.to_string(),
                        }],
                        tools: Vec::new(),
                    },
                    false,
                    false,
                ),
            )
            .await
        })
        .map_err(|_| AgentError::Model("proactive planning timed out".into()))??;
    let proposal: Proposal = serde_json::from_str(response.content.trim())
        .map_err(|error| AgentError::Model(format!("invalid proactive plan: {error}")))?;
    if proposal.action == "none" && explicit_dated_reminder(&turn.user_input) {
        return Err(AgentError::Model(
            "explicit reminder was not planned".into(),
        ));
    }
    if std::env::var("COMPANION_ACCEPTANCE_MODEL_DIAGNOSTICS").as_deref() == Ok("1") {
        eprintln!("proactive turn {turn_id}: action={}", proposal.action);
    }
    if std::env::var("COMPANION_ACCEPTANCE_MODEL_DIAGNOSTICS").as_deref() == Ok("1")
        && proposal.action == "upsert"
    {
        let grounded = |value: &str| {
            !value.is_empty()
                && (turn.user_input.contains(value)
                    || memories.iter().any(|m| {
                        m["content"]
                            .as_str()
                            .is_some_and(|content| content.contains(value))
                    }))
        };
        eprintln!(
            "proactive turn {turn_id} validation: evidence={} time={} schedule={} clock={} window={} local_datetime={}",
            grounded(&proposal.evidence),
            grounded(&proposal.time_evidence),
            grounded(&proposal.schedule_evidence),
            has_clock_evidence(&proposal.time_evidence),
            crate::proactive::valid_planning_window(
                proposal.lead_minutes.unwrap_or(60),
                proposal.deadline_lead_minutes.unwrap_or(30),
                proposal.required_tools.is_empty()
            ),
            !proposal.local_datetime.is_empty()
        );
    }
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
    if std::env::var("COMPANION_ACCEPTANCE_MODEL_DIAGNOSTICS").as_deref() == Ok("1") {
        eprintln!(
            "proactive turn {turn_id} applied: changed={}, enabled={}",
            changed.changed, changed.enabled
        );
    }
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
                    ..PlanOutcome::default()
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
            let at = if proposal.local_datetime.is_empty() {
                proposal.event_at
            } else {
                store.parse_proactive_local_datetime(&proposal.local_datetime, offset)?
            };
            let Some(at) = at else {
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
        !available_tools
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
        || (!crate::proactive::valid_planning_window(
            lead_minutes,
            deadline_lead_minutes,
            proposal.required_tools.is_empty(),
        ))
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
        ..PlanOutcome::default()
    })
}

fn has_clock_evidence(value: &str) -> bool {
    let numeral = value
        .chars()
        .any(|c| c.is_ascii_digit() || "一二三四五六七八九十两零半".contains(c));
    numeral && (value.contains(':') || value.contains('点') || value.contains('时'))
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
        let interval_seconds = self.proactive_settings()?.discovery_interval_minutes * 60;
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
            params![now, now - interval_seconds],
        )? == 1)
    }
    fn pending_proactive_turns(&self) -> Result<Vec<i64>, MemoryError> {
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        let mut statement = connection.prepare(
            "SELECT t.id FROM trace_turns t LEFT JOIN proactive_planned_turns p ON p.turn_id=t.id
             LEFT JOIN proactive_planner_retries r ON r.turn_id=t.id
             WHERE t.status IN ('completed','max_steps') AND p.turn_id IS NULL
             AND (r.retry_at IS NULL OR r.retry_at<=?1) ORDER BY t.id LIMIT 5",
        )?;
        statement
            .query_map([now_unix_seconds()], |row| row.get(0))?
            .collect::<Result<Vec<_>, _>>()
            .map_err(Into::into)
    }

    fn parse_proactive_local_datetime(
        &self,
        value: &str,
        offset: i64,
    ) -> Result<Option<i64>, MemoryError> {
        if !value.is_ascii() || !matches!(value.len(), 16 | 19) {
            return Ok(None);
        }
        let value = if value.len() == 16 {
            format!("{value}:00")
        } else {
            value.to_owned()
        };
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        let (canonical, unix): (Option<String>, Option<i64>) = connection.query_row(
            "SELECT datetime(?1, '+0 days'), CAST(strftime('%s', ?1) AS INTEGER)",
            [&value],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )?;
        Ok(if canonical.as_deref() == Some(value.as_str()) {
            unix.map(|at| at - offset * 60)
        } else {
            None
        })
    }
    fn defer_proactive_turn(&self, turn_id: i64) -> Result<(), MemoryError> {
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        connection.execute(
            "INSERT OR REPLACE INTO proactive_planner_retries (turn_id,retry_at) VALUES (?1,?2)",
            params![turn_id, now_unix_seconds() + 300],
        )?;
        Ok(())
    }
    fn clear_proactive_retry(&self, turn_id: i64) -> Result<(), MemoryError> {
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        connection.execute(
            "DELETE FROM proactive_planner_retries WHERE turn_id=?1",
            [turn_id],
        )?;
        Ok(())
    }
    fn next_proactive_retry(&self) -> Result<Option<i64>, MemoryError> {
        let connection = self
            .connection
            .lock()
            .map_err(|_| MemoryError::LockPoisoned)?;
        Ok(connection.query_row(
            "SELECT MIN(retry_at) FROM proactive_planner_retries",
            [],
            |row| row.get(0),
        )?)
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

    struct FailsFirstModel {
        answer: String,
        calls: AtomicUsize,
    }
    #[async_trait::async_trait]
    impl ModelServeCallback for FailsFirstModel {
        async fn complete(
            &self,
            request: String,
            callback: Arc<dyn ModelStreamCallback>,
        ) -> Result<(), ModelServeError> {
            let request: serde_json::Value = serde_json::from_str(&request).unwrap();
            assert_eq!(request["response_format"]["type"], "json_schema");
            let content = if self.calls.fetch_add(1, Ordering::SeqCst) == 0 {
                "not JSON"
            } else {
                &self.answer
            };
            callback.on_chunk(
                serde_json::json!({"choices":[{"message":{"content":content}}]}).to_string(),
            );
            Ok(())
        }
    }
    #[test]
    fn failed_old_turn_does_not_block_new_reminder_and_is_retried_without_duplicates() {
        let store = MemoryStore::in_memory().unwrap();
        completed_turn(&store, "旧的普通问答");
        completed_turn(&store, "每天晚上六点提醒我吃晚饭");
        let answer = serde_json::json!({"action":"upsert","evidence":"每天晚上六点提醒我吃晚饭",
            "time_evidence":"晚上六点","schedule_evidence":"每天","title":"晚饭提醒","instruction":"提醒晚饭",
            "recurrence":"weekly","local_minute":1080,"weekday_mask":127,"lead_minutes":0,"deadline_lead_minutes":0}).to_string();
        let model = ModelServeWrapper::new(Arc::new(FailsFirstModel {
            answer,
            calls: AtomicUsize::new(0),
        }));
        let outcome = process_pending(&store, &model, 0).unwrap();
        assert_eq!(
            (outcome.changed, outcome.enabled, outcome.failed),
            (1, 1, 1)
        );
        assert!(!outcome.pending);
        assert!(outcome.retry_at.unwrap() > now_unix_seconds());
        assert_eq!(store.proactive_rules().unwrap().len(), 1);
        let calls = Arc::new(AtomicUsize::new(0));
        let retry = ModelServeWrapper::new(Arc::new(FixedModel {
            answer: "{\"action\":\"none\"}".into(),
            calls: calls.clone(),
        }));
        process_pending(&store, &retry, 0).unwrap();
        assert_eq!(
            calls.load(Ordering::SeqCst),
            0,
            "failed turn respects persisted backoff"
        );
        store
            .connection
            .lock()
            .unwrap()
            .execute("UPDATE proactive_planner_retries SET retry_at=0", [])
            .unwrap();
        let outcome = process_pending(&store, &retry, 0).unwrap();
        assert_eq!(calls.load(Ordering::SeqCst), 1);
        assert!(outcome.retry_at.is_none());
        assert_eq!(store.proactive_rules().unwrap().len(), 1);
    }
    #[test]
    fn explicit_reminder_none_is_retryable_not_marked_as_completed() {
        let store = MemoryStore::in_memory().unwrap();
        completed_turn(&store, "请在2026-10-10 12:00:00提醒我打开 App");
        let model = ModelServeWrapper::new(Arc::new(FixedModel {
            answer: r#"{"action":"none"}"#.into(),
            calls: Arc::new(AtomicUsize::new(0)),
        }));
        let outcome = process_pending(&store, &model, 0).unwrap();
        assert_eq!(outcome.failed, 1);
        assert!(outcome.retry_at.is_some());
        let completed: i64 = store
            .connection
            .lock()
            .unwrap()
            .query_row("SELECT COUNT(*) FROM proactive_planned_turns", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(completed, 0);
    }

    #[test]
    fn schema_only_offers_literal_evidence_and_explicit_local_times() {
        let query = "请在2026-10-09 20:30:15提醒我打开 App。任务名称是测试，不提前提醒。";
        let format = response_format(Some(query), &[]);
        let schema = &format["json_schema"]["schema"];
        assert_eq!(schema["properties"]["action"]["const"], "upsert");
        for query in [
            "柿子能和螃蟹一起吃吗",
            "请在2026-10-09 20:30:15不要提醒我",
            "取消2026-10-09 20:30:15的提醒",
        ] {
            assert!(!explicit_dated_reminder(query));
            assert!(response_format(Some(query), &[])["json_schema"]["schema"]["anyOf"].is_array());
        }
        for field in [
            "evidence",
            "time_evidence",
            "schedule_evidence",
            "local_datetime",
        ] {
            for value in schema["properties"][field]["enum"].as_array().unwrap() {
                assert!(
                    query.contains(value.as_str().unwrap()),
                    "evidence must be literal user text"
                );
            }
        }
        assert_eq!(schema["properties"]["title"]["const"], "测试");
        assert_eq!(
            schema["properties"]["local_datetime"]["enum"],
            serde_json::json!(["2026-10-09 20:30:15"])
        );
        assert!(
            schema["required"]
                .as_array()
                .unwrap()
                .contains(&serde_json::json!("local_datetime"))
        );
    }

    #[test]
    fn explicit_local_time_creates_zero_lead_reminder_at_exact_unix_second() {
        let store = MemoryStore::in_memory().unwrap();
        let at = now_unix_seconds() + 600;
        let local: String = store
            .connection
            .lock()
            .unwrap()
            .query_row("SELECT datetime(?1, 'unixepoch')", [at + 480 * 60], |row| {
                row.get(0)
            })
            .unwrap();
        completed_turn(&store, &format!("请在{local}提醒我出门，不提前提醒"));
        let answer = serde_json::json!({"action":"upsert","evidence":"提醒我出门",
            "time_evidence":&local[11..],"schedule_evidence":&local[..10],"title":"出门提醒","instruction":"提醒出门",
            "recurrence":"once","local_datetime":local,"lead_minutes":0,"deadline_lead_minutes":0}).to_string();
        let model = ModelServeWrapper::new(Arc::new(FixedModel {
            answer,
            calls: Arc::new(AtomicUsize::new(0)),
        }));
        assert_eq!(process_pending(&store, &model, 480).unwrap().enabled, 1);
        let task = store.proactive_rules().unwrap().pop().unwrap();
        assert_eq!(task.one_shot_at, Some(at));
        assert_eq!((task.lead_minutes, task.deadline_lead_minutes), (0, 0));
        assert_eq!(task.next_run_at, Some(at));
        assert!(
            store
                .parse_proactive_local_datetime("2026-02-30 12:00:00", 480)
                .unwrap()
                .is_none()
        );
        assert!(!crate::proactive::valid_planning_window(0, 0, false));
    }

    #[test]
    fn bounded_batches_report_remaining_work_in_chronological_order() {
        let store = MemoryStore::in_memory().unwrap();
        for _ in 0..6 {
            completed_turn(&store, "普通问答");
        }
        let calls = Arc::new(AtomicUsize::new(0));
        let model = ModelServeWrapper::new(Arc::new(FixedModel {
            answer: "{\"action\":\"none\"}".into(),
            calls: calls.clone(),
        }));
        assert!(process_pending(&store, &model, 0).unwrap().pending);
        assert_eq!(calls.load(Ordering::SeqCst), 5);
        assert!(!process_pending(&store, &model, 0).unwrap().pending);
        assert_eq!(calls.load(Ordering::SeqCst), 6);
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
