package com.harness.kmp.utils

/**
 * 日志工具 — 各前端可注入不同后端（Android Log、os_log、println）。
 */
object Logger {
    private var enabled: Boolean = true

    fun enable() { enabled = true }
    fun disable() { enabled = false }

    fun d(tag: String, message: String) {
        if (enabled) log("DEBUG", tag, message)
    }

    fun i(tag: String, message: String) {
        if (enabled) log("INFO", tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (enabled) {
            log("ERROR", tag, message)
            throwable?.printStackTrace()
        }
    }

    private fun log(level: String, tag: String, message: String) {
        println("[$level] [$tag] $message")
    }
}

/**
 * 结果包装 — 统一各平台的错误处理风格。
 */
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val message: String, val cause: Throwable? = null) : Result<Nothing>()
}