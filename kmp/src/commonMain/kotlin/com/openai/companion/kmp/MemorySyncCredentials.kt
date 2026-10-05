package com.openai.companion.kmp

import io.ktor.http.Url

/** Blank input means keep credentials only for the same service, never for a new destination. */
object MemorySyncCredentials {
    fun key(endpoint: String): String = "memory-sync:" + MemorySyncClient.validateEndpoint(endpoint).toString()

    suspend fun resolve(endpoint: String, previousEndpoint: String, supplied: String, load: suspend () -> String?): String {
        MemorySyncClient.validateEndpoint(endpoint)
        if (supplied.isNotBlank()) return supplied
        require(previousEndpoint.isNotBlank() && Url(endpoint.trim()) == Url(previousEndpoint.trim())) {
            "更换同步服务时请输入该服务的令牌"
        }
        return load()?.takeIf { it.isNotBlank() } ?: error("请输入同步令牌")
    }
}
