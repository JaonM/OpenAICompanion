package com.openai.companion.desktop

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption

/** A worker and UI must never own the same local checkpoint file simultaneously. */
internal class DesktopProcessLease private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    override fun close() { lock.release(); channel.close() }

    companion object {
        fun acquire(): DesktopProcessLease {
            val directory = File(System.getenv("COMPANION_DATA_DIR")?.takeIf(String::isNotBlank)
                ?: (System.getProperty("user.home") + "/Library/Application Support/OpenAICompanion"))
            check(directory.isDirectory || directory.mkdirs()) { "无法创建应用数据目录" }
            val channel = FileChannel.open(File(directory, "process.lock").toPath(),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = try { channel.tryLock() } catch (error: Exception) { channel.close(); throw error }
            if (lock == null) { channel.close(); error("此数据目录已由另一个助理进程使用，请先停止该进程") }
            return DesktopProcessLease(channel, lock)
        }
    }
}
