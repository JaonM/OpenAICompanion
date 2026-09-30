package com.openai.companion.kmp

/** Secrets are keyed by the exact MCP endpoint; platform implementations must use secure storage. */
interface McpOAuthTokenStore {
    suspend fun load(endpoint: String): McpOAuthTokens?
    suspend fun save(endpoint: String, tokens: McpOAuthTokens)
    suspend fun delete(endpoint: String)
}
