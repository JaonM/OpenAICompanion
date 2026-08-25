package com.harness.kmp

private class DesktopPlatform : Platform {
    override val name: String = System.getProperty("os.name") ?: "Desktop"
    override val version: String = System.getProperty("os.version") ?: "unknown"
}

actual fun getPlatform(): Platform = DesktopPlatform()