package com.openai.companion.kmp

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class VerifiedModelDownloadTest {
    private val bytes = "verified weights".toByteArray()
    private val model = ModelLibrary.get("qwen3-small").copy(bytes = bytes.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    private class Response(url: URL, private val status: Int, private val input: InputStream,
        private val range: String? = null) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = input
        override fun getHeaderField(name: String) = if (name == "Content-Range") range else null
    }
    @Test fun interruptionResumesPinnedBytesAndInstallsOnlyAfterChecksum() = runBlocking {
        val directory = Files.createTempDirectory("companion-model-resume").toFile()
        val destination = File(directory, "weights.gguf")
        try {
            val failing = object : InputStream() {
                var index = 0
                override fun read(): Int = if (index < 4) bytes[index++].toInt() else throw IOException("disconnected")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (index >= 4) throw IOException("disconnected")
                    bytes.copyInto(buffer, offset, index, 4); val count = 4 - index; index = 4; return count
                }
            }
            assertFailsWith<IOException> { downloadVerifiedModel(model, destination,
                connect = { Response(it, 200, failing) }) }
            assertFalse(destination.exists())
            downloadVerifiedModel(model, destination, connect = {
                Response(it, 206, ByteArrayInputStream(bytes.copyOfRange(4, bytes.size)), "bytes 4-${bytes.size - 1}/${bytes.size}")
            }, connectionChanged = { connection -> if (connection != null) assertEquals("bytes=4-", connection.getRequestProperty("Range")) })
            assertContentEquals(bytes, destination.readBytes())
            assertEquals(listOf(destination.name), directory.listFiles()!!.map { it.name })
        } finally { directory.deleteRecursively() }
    }
    @Test fun corruptFullPartialIsDiscardedAndNeverInstalled() = runBlocking {
        val directory = Files.createTempDirectory("companion-model-corrupt").toFile()
        val destination = File(directory, "weights.gguf")
        val partial = File(directory, ".model-${model.sha256}.partial")
        try {
            partial.writeBytes(ByteArray(bytes.size))
            assertFailsWith<IllegalStateException> { downloadVerifiedModel(model, destination, connect = { error("no network expected") }) }
            assertFalse(partial.exists()); assertFalse(destination.exists())
        } finally { directory.deleteRecursively() }
    }
    @Test fun serverIgnoringRangeRestartsWithoutAppendingDuplicateBytes() = runBlocking {
        val directory = Files.createTempDirectory("companion-model-range").toFile()
        val destination = File(directory, "weights.gguf")
        try {
            File(directory, ".model-${model.sha256}.partial").writeBytes(bytes.copyOfRange(0, 4))
            downloadVerifiedModel(model, destination, connect = { Response(it, 200, ByteArrayInputStream(bytes)) })
            assertContentEquals(bytes, destination.readBytes())
        } finally { directory.deleteRecursively() }
    }
}
