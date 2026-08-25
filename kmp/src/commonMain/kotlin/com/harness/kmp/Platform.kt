package com.harness.kmp

/**
 * 平台抽象接口，各平台提供 actual 实现。
 */
interface Platform {
    val name: String
    val version: String
}

expect fun getPlatform(): Platform