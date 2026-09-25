package com.openai.companion.desktop

import com.openai.companion.kmp.AppAgentEventSink
import com.openai.companion.kmp.GeneratedHarnessBindingsAdapter
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import uniffi.harness.appOpenStore
import uniffi.harness.appSendMessage
import uniffi.harness.appStartSession

/** Opt-in live smoke test: LOCAL_MODEL_INTEGRATION=1 ./gradlew jvmTest. */
class DesktopLocalModelTest {
    @Test
    fun rustHarnessUsesLocalQuantizedModel() = runBlocking {
        if (System.getenv("LOCAL_MODEL_INTEGRATION") != "1") return@runBlocking
        val library = System.getenv("HARNESS_LIBRARY_PATH")
            ?: error("HARNESS_LIBRARY_PATH must point to libharness.dylib")
        System.setProperty("uniffi.component.harness.libraryOverride", library)
        val model = DesktopModelServe().apply {
            endpoint = "http://127.0.0.1:11434/v1/chat/completions"
            this.model = "hf.co/unsloth/Qwen3.8-27B-GGUF:UD-Q4_K_M"
        }
        assertEquals("本地模型已就绪", model.localStatus())
        val bindings = GeneratedHarnessBindingsAdapter()
        val database = Files.createTempFile("companion-local-model-", ".sqlite")
        var reasoningEvents = 0
        var textEvents = 0
        try {
            bindings.registerModelServeCallback(model)
            bindings.registerAgentEventSink(object : AppAgentEventSink {
                override fun onReasoningDelta(text: String) { reasoningEvents++ }
                override fun onTextDelta(text: String) { textEvents++ }
                override fun onCompleted(finalText: String) = Unit
                override fun onError(errorJson: String) = Unit
            })
            assertTrue(appOpenStore(database.toString()).ok)
            assertTrue(appStartSession().ok)
            val response = appSendMessage("只输出 2+2 的答案，不要其他内容。")
            assertTrue(response.ok, response.error)
            val output = Json.parseToJsonElement(response.valueJson).jsonObject
                .getValue("output").jsonPrimitive.content
            assertTrue(output.contains("4"), output)
            assertTrue(reasoningEvents > 0, "Expected streaming reasoning events")
            assertTrue(textEvents > 0, "Expected streaming text events")
        } finally {
            bindings.unregisterAgentEventSink()
            bindings.unregisterModelServeCallback()
            Files.deleteIfExists(database)
        }
    }
}
