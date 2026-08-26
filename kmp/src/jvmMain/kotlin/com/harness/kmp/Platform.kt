package com.harness.kmp

private class JvmPlatform : Platform {
    override val name: String = System.getProperty("os.name") ?: "JVM"
    override val version: String = System.getProperty("os.version") ?: "unknown"
}

actual fun getPlatform(): Platform = JvmPlatform()
