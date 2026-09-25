package com.openai.companion.kmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CompanionConversationCodecTest {
    private val codec = CompanionConversationCodec()

    @Test
    fun decodesHarnessSessionAndMessageResponses() {
        assertEquals(
            listOf(CompanionSessionSummary(12, "你好")),
            codec.sessions("""[{"id":12,"preview":"你好"}]"""),
        )
        assertEquals(
            listOf(CompanionChatMessage("user", "你好"), CompanionChatMessage("status", "生成已取消。")),
            codec.messages("""{"id":12,"messages":[{"role":"user","content":"你好"},{"role":"status","content":"生成已取消。"}]}"""),
        )
        assertEquals(12, codec.sessionId("""{"id":12}"""))
        assertEquals("4", codec.output("""{"output":"4"}"""))
    }

    @Test
    fun rejectsMalformedHarnessResponse() {
        assertFailsWith<IllegalArgumentException> { codec.sessions("{}") }
        assertFailsWith<IllegalArgumentException> { codec.messages("not json") }
    }

    @Test
    fun accumulatesStreamingTextAndReasoningIndependently() {
        val stream = CompanionStreamAccumulator()
        assertEquals("思考", stream.addReasoning("思考"))
        assertEquals("答案", stream.addText("答案"))
        assertEquals("思考中", stream.addReasoning("中"))
        assertEquals("答案。", stream.addText("。"))
        stream.reset()
        assertEquals("", stream.reasoning)
        assertEquals("", stream.text)
    }
}
