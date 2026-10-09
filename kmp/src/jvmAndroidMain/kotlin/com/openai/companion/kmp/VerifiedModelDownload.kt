package com.openai.companion.kmp

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Bounded streaming download; never buffers model weights in RAM. */
suspend fun downloadVerifiedModel(model: LibraryModel, destination: File) = withContext(Dispatchers.IO) {
    require(model.sha256.length == 64 && model.bytes > 0)
    check(destination.parentFile!!.let { it.isDirectory || it.mkdirs() }) { "无法创建模型目录" }
    val temporary = File.createTempFile(".download-", ".partial", destination.parentFile)
    var connection: HttpURLConnection? = null
    try {
        var url = URL(model.url)
        repeat(6) {
            require(url.protocol == "https") { "模型下载只允许 HTTPS" }
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000; readTimeout = 60_000; instanceFollowRedirects = false
            }
            val response = connection!!.responseCode
            if (response in listOf(301, 302, 303, 307, 308)) {
                url = URL(url, connection!!.getHeaderField("Location") ?: error("下载重定向无效"))
                connection!!.disconnect()
            } else {
                check(response == 200) { "模型下载 HTTP $response" }
                val hash = MessageDigest.getInstance("SHA-256")
                var received = 0L
                connection!!.inputStream.use { input -> temporary.outputStream().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        check(received <= model.bytes) { "模型文件超过预期大小" }
                        hash.update(buffer, 0, count); output.write(buffer, 0, count)
                    }
                } }
                check(received == model.bytes && hash.digest().joinToString("") { "%02x".format(it) } == model.sha256) { "模型文件校验失败" }
                check(temporary.renameTo(destination)) { "无法安装模型文件" }
                return@withContext
            }
        }
        error("模型下载重定向过多")
    } finally { connection?.disconnect(); temporary.delete() }
}
