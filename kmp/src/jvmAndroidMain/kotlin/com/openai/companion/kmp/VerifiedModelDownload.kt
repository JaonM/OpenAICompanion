package com.openai.companion.kmp

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Pinned, resumable HTTPS download. Only a fully verified file is installed. */
suspend fun downloadVerifiedModel(model: LibraryModel, destination: File,
    connectionChanged: (HttpURLConnection?) -> Unit = {},
    connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) = withContext(Dispatchers.IO) {
    require(model.sha256.matches(Regex("[a-f0-9]{64}")) && model.bytes > 0)
    check(destination.parentFile!!.let { it.isDirectory || it.mkdirs() }) { "无法创建模型目录" }
    val partial = File(destination.parentFile, ".model-${model.sha256}.partial")
    if (partial.length() > model.bytes) partial.delete()
    var connection: HttpURLConnection? = null
    try {
        if (partial.length() != model.bytes) {
            var url = URL(model.url)
            var receivedResponse = false
            for (redirect in 0..5) {
                coroutineContext.ensureActive()
                require(url.protocol == "https") { "模型下载只允许 HTTPS" }
                val offset = partial.length()
                connection = connect(url).apply {
                    connectTimeout = 30_000; readTimeout = 60_000; instanceFollowRedirects = false
                    if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
                }
                connectionChanged(connection)
                val response = connection.responseCode
                if (response in listOf(301, 302, 303, 307, 308)) {
                    url = URL(url, connection.getHeaderField("Location") ?: error("下载重定向无效"))
                    connection.disconnect()
                    continue
                }
                check(response == 200 || response == 206) { "模型下载 HTTP $response" }
                if (response == 206) {
                    val range = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(connection.getHeaderField("Content-Range").orEmpty())
                    check(range != null && range.groupValues[1].toLong() == offset &&
                        range.groupValues[2].toLong() == model.bytes - 1 && range.groupValues[3].toLong() == model.bytes) { "下载续传范围无效" }
                }
                var received = if (response == 206) offset else 0L
                connection.inputStream.use { input -> java.io.FileOutputStream(partial, response == 206).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > model.bytes) { partial.delete(); error("模型文件超过预期大小") }
                        output.write(buffer, 0, count)
                    }
                } }
                check(received == model.bytes) { "下载中断，可重试续传" }
                receivedResponse = true
                break
            }
            check(receivedResponse) { "模型下载重定向过多" }
        }
        val hash = MessageDigest.getInstance("SHA-256")
        partial.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        if (partial.length() != model.bytes || hash.digest().joinToString("") { "%02x".format(it) } != model.sha256) {
            partial.delete(); error("模型文件校验失败，请重新下载")
        }
        coroutineContext.ensureActive()
        check(partial.renameTo(destination)) { "无法安装模型文件" }
    } finally { connection?.disconnect(); connectionChanged(null) }
}
