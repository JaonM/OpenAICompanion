package com.harness.kmp

import kotlinx.serialization.Serializable

/**
 * 用户输入模型 — 从各前端（TUI、App、Web）统一收敛到此结构。
 */
@Serializable
data class UserInput(
    val text: String,
    val sessionId: String = "",
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Harness 响应模型 — 封装 Rust 核心处理后的结构化输出。
 */
@Serializable
data class HarnessResponse(
    val text: String,
    val status: ResponseStatus = ResponseStatus.OK,
    val error: String? = null,
    val data: Map<String, String> = emptyMap(),
)

@Serializable
enum class ResponseStatus {
    OK,
    ERROR,
    PENDING,
}

/**
 * 会话上下文 — 在 Rust Harness 与 KMP 前端之间传递的轻量级状态。
 */
@Serializable
data class SessionContext(
    val sessionId: String,
    val userId: String = "",
    val preferences: Map<String, String> = emptyMap(),
)