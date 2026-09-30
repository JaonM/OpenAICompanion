package com.openai.companion.kmp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProactiveSettings(
    val enabled: Boolean = false,
    @SerialName("discovery_interval_minutes") val discoveryIntervalMinutes: Long = 30,
)

/** User-configured local task. `scenario` is a stable task ID kept for DB compatibility. */
@Serializable
data class ProactiveTask(
    val scenario: String,
    val title: String,
    val instruction: String,
    @SerialName("memory_query") val memoryQuery: String,
    @SerialName("allowed_tools") val allowedTools: List<String> = emptyList(),
    @SerialName("required_tools") val requiredTools: List<String> = emptyList(),
    val enabled: Boolean = true,
    @SerialName("local_minute") val localMinute: Long,
    @SerialName("weekday_mask") val weekdayMask: Long = 127,
    @SerialName("lead_minutes") val leadMinutes: Long = 60,
    @SerialName("deadline_lead_minutes") val deadlineLeadMinutes: Long = 30,
    @SerialName("timezone_offset_minutes") val timezoneOffsetMinutes: Long = 0,
    @SerialName("one_shot_at") val oneShotAt: Long? = null,
    @SerialName("next_run_at") val nextRunAt: Long? = null,
    @SerialName("next_event_at") val nextEventAt: Long? = null,
)
