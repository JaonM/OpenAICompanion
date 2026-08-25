package com.harness.kmp

private class AndroidPlatform : Platform {
    override val name: String = "Android"
    override val version: String = android.os.Build.VERSION.SDK_INT.toString()
}

actual fun getPlatform(): Platform = AndroidPlatform()