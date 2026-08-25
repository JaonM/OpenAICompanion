package com.harness.kmp

private class WasmJsPlatform : Platform {
    override val name: String = "Wasm/JS"
    override val version: String = "1.0"
}

actual fun getPlatform(): Platform = WasmJsPlatform()