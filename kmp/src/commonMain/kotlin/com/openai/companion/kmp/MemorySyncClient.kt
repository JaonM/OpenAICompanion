package com.openai.companion.kmp

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*

/** Bounded upload batches and sequential download pages. Rust owns revisions and tombstones.
 * The cursor advances only after the page is durably merged. A new exchange starts at zero,
 * so interrupted syncs and relay restores need no shared client/server checkpoint state.
 */
class MemorySyncClient(private val http: HttpClient) {
    suspend fun exchange(endpoint: String, token: String, recordsJson: String, merge: suspend (String) -> Unit) {
        val url = validateEndpoint(endpoint)
        require(token.isNotBlank()) { "请输入同步令牌" }
        val records = Json.parseToJsonElement(recordsJson).jsonArray
        var offset = 0
        var cursor = 0L
        var more: Boolean
        do {
            val batch = mutableListOf<JsonElement>()
            var bytes = 128
            while (offset < records.size && batch.size < PAGE_RECORDS) {
                val record = records[offset]
                val size = record.toString().encodeToByteArray().size + 1
                require(size + 128 <= PAGE_BYTES) { "单条记忆超过同步容量" }
                if (bytes + size > PAGE_BYTES) break
                batch += record
                bytes += size
                offset++
            }
            val body = http.preparePost(url) {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("version", 2); put("records", JsonArray(batch)); put("cursor", cursor)
                }.toString())
            }.execute { response ->
                check(response.status.value == 200) { "记忆同步服务返回 ${response.status.value}；请确认服务支持分页同步" }
                val channel = response.bodyAsChannel()
                val body = try { channel.readRemaining(MAX_BODY.toLong() + 1).readByteArray() }
                    finally { channel.cancel(null) }
                require(body.size <= MAX_BODY) { "记忆同步响应过大" }
                body
            }
            val page = Json.parseToJsonElement(body.decodeToString()).jsonObject
            require(page["version"]?.jsonPrimitive?.intOrNull == 2) { "请升级记忆同步服务以支持分页" }
            val incoming = page.getValue("records").jsonArray
            require(incoming.size <= PAGE_RECORDS) { "同步分页记录过多" }
            val next = page.getValue("cursor").jsonPrimitive.long
            more = page.getValue("has_more").jsonPrimitive.boolean
            require(next >= cursor && (!more || next > cursor) && (incoming.isEmpty() || next > cursor)) {
                "同步服务返回无效游标"
            }
            merge(incoming.toString())
            cursor = next
        } while (offset < records.size || more)
    }

    companion object {
        internal const val PAGE_RECORDS = 256
        internal const val PAGE_BYTES = 512 * 1024
        private const val MAX_BODY = 4 * 1024 * 1024
        fun validateEndpoint(endpoint: String): Url {
            val url = Url(endpoint.trim())
            require(url.protocol == URLProtocol.HTTPS && url.host.isNotBlank()
                && url.encodedPath == "/v1/memories/sync" && url.parameters.isEmpty()
                && url.fragment.isEmpty() && url.user.isNullOrEmpty() && url.password.isNullOrEmpty()) {
                "同步地址必须是无凭据的 HTTPS /v1/memories/sync 接口"
            }
            return url
        }
    }
}
