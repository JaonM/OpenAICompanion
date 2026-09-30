package com.openai.companion.kmp

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Full-snapshot relay for medium/long memories. Rust owns IDs, revisions and tombstones. */
class MemorySyncClient(private val http: HttpClient) {
    suspend fun exchange(endpoint: String, token: String, recordsJson: String): String {
        val url = Url(endpoint.trim())
        require(url.protocol == URLProtocol.HTTPS && url.host.isNotBlank()
            && url.encodedPath == "/v1/memories/sync" && url.parameters.isEmpty()
            && url.fragment.isEmpty()) {
            "同步地址必须是 HTTPS 的 /v1/memories/sync 接口"
        }
        require(token.isNotBlank()) { "请输入同步令牌" }
        val records = Json.parseToJsonElement(recordsJson).jsonArray
        require(records.size <= 10_000) { "记忆同步量超过单次上限" }
        val response = http.post(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("records", records) }.toString())
        }
        check(response.status.value == 200) { "记忆同步服务返回 ${response.status.value}" }
        val body = response.bodyAsText()
        require(body.length <= 4 * 1024 * 1024) { "记忆同步响应过大" }
        return Json.parseToJsonElement(body).jsonObject.getValue("records").jsonArray.toString()
    }
}
