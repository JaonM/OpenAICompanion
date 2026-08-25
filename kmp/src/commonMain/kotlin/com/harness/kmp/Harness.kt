package com.harness.kmp

import com.harness.kmp.models.HarnessResponse
import com.harness.kmp.models.UserInput

/**
 * 对 Rust Harness 核心的统一 Kotlin 封装。
 *
 * 提供各前端通用的调用接口，内部通过 JNI / cinterop 与 Rust 侧通信。
 * 当前为骨架实现，后续接入实际 Rust FFI。
 */
object Harness {

    private var initialized = false

    /**
     * 初始化 Harness 引擎。需在使用前调用一次（幂等）。
     */
    fun initialize(config: Map<String, String> = emptyMap()) {
        if (initialized) return
        // TODO: 调用 Rust native init
        initialized = true
    }

    /**
     * 处理用户输入，返回结构化响应。
     */
    suspend fun process(input: UserInput): HarnessResponse {
        ensureInitialized()
        // TODO: 转发到 Rust 侧处理
        return HarnessResponse(
            text = "Echo: ${input.text}",
            status = com.harness.kmp.models.ResponseStatus.OK,
        )
    }

    /**
     * 检查引擎是否就绪。
     */
    fun isReady(): Boolean = initialized

    private fun ensureInitialized() {
        if (!initialized) {
            throw IllegalStateException("Harness not initialized. Call Harness.initialize() first.")
        }
    }
}