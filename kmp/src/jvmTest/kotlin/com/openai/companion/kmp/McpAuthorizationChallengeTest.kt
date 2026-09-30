package com.openai.companion.kmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class McpAuthorizationChallengeTest {
    @Test
    fun parsesBearerChallengeWithoutSplittingQuotedComma() {
        val parsed = parseMcpAuthorizationChallenge(listOf(
            "Basic realm=\"other, realm\", Bearer resource_metadata=\"https://mcp.example/.well-known/oauth-protected-resource\", scope=\"files:read files:write\", error=\"insufficient_scope\"",
        ))!!
        assertEquals("https://mcp.example/.well-known/oauth-protected-resource", parsed.resourceMetadataUrl)
        assertEquals(listOf("files:read", "files:write"), parsed.scopes)
        assertEquals("insufficient_scope", parsed.error)
    }

    @Test
    fun ignoresNonBearerChallenges() {
        assertNull(parseMcpAuthorizationChallenge(listOf("Basic realm=\"example\"")))
    }
}
