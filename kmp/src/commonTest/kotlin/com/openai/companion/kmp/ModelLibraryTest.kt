package com.openai.companion.kmp

import kotlin.test.*

class ModelLibraryTest {
    private val phone = ModelDevice("iOS", 17, "arm64", 12_000_000_000, 20_000_000_000, setOf("MLX", "llama.cpp"))
    @Test fun compatiblePhoneAndMemoryLimits() {
        assertTrue(ModelLibrary.compatibility(ModelLibrary.get("qwen35-gguf"), phone).allowed)
        assertFalse(ModelLibrary.compatibility(ModelLibrary.get("qwen35-gguf"), phone.copy(memoryBytes = 8_000_000_000)).allowed)
        assertTrue(ModelLibrary.compatibility(ModelLibrary.get("qwen35-mlx"), phone.copy(memoryBytes = 8_000_000_000)).allowed)
    }
    @Test fun unknownHardwareNeverPromisesSupport() {
        assertFalse(ModelLibrary.compatibility(ModelLibrary.models.first(), phone.copy(memoryBytes = null)).allowed)
        assertFalse(ModelLibrary.compatibility(ModelLibrary.models.first(), phone.copy(freeBytes = null)).allowed)
        assertFalse(ModelLibrary.compatibility(ModelLibrary.models.first(), phone.copy(osMajor = 16)).allowed)
        assertFalse(ModelLibrary.compatibility(ModelLibrary.models.first(), phone.copy(architecture = "simulator")).allowed)
    }
    @Test fun platformEngineAndStorageAreIndependent() {
        val android = phone.copy(platform = "Android", osMajor = 26, architecture = "arm64-v8a", engines = setOf("llama.cpp"))
        assertFalse(ModelLibrary.compatibility(ModelLibrary.get("qwen35-mlx"), android).allowed)
        assertTrue(ModelLibrary.compatibility(ModelLibrary.get("qwen3-small"), android).allowed)
        assertFalse(ModelLibrary.compatibility(ModelLibrary.get("qwen3-small"), android.copy(freeBytes = 100)).allowed)
        assertTrue(ModelLibrary.compatibility(ModelLibrary.get("qwen3-small"), android.copy(freeBytes = 100), installed = true).allowed)
        assertTrue(ModelLibrary.compatibility(ModelLibrary.get("qwen3-small"), phone.copy(platform = "macOS", engines = setOf("Ollama"))).allowed)
    }
}
