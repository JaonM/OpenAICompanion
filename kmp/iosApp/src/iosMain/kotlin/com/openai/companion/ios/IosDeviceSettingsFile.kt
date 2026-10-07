@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.openai.companion.ios

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSUserDefaults
import platform.posix.*

/** Critical execution receipts use fsync + rename, not asynchronous preference persistence. */
internal class IosDeviceSettingsFile {
    private val directory = NSHomeDirectory() + "/Library/Application Support/OpenAICompanion"
    private val path = "$directory/device-tasks.json"

    fun load(): String {
        val fd = open(path, O_RDONLY)
        if (fd < 0) {
            check(errno == ENOENT) { "无法读取跨设备任务状态" }
            val legacy = NSUserDefaults.standardUserDefaults.stringForKey("deviceTasksConfig") ?: return "{}"
            save(legacy)
            NSUserDefaults.standardUserDefaults.removeObjectForKey("deviceTasksConfig")
            return legacy
        }
        try {
            val bytes = ByteArray(1_000_001)
            var count = 0
            bytes.usePinned { pinned ->
                while (count < bytes.size) {
                    val amount = read(fd, pinned.addressOf(count), (bytes.size - count).convert())
                    if (amount < 0 && errno == EINTR) continue
                    check(amount >= 0) { "读取跨设备任务状态失败" }
                    if (amount == 0L) break
                    count += amount.toInt()
                }
            }
            check(count <= 1_000_000) { "跨设备任务状态过大" }
            return bytes.decodeToString(0, count, throwOnInvalidSequence = true)
        } finally { close(fd) }
    }

    fun save(value: String) {
        val bytes = value.encodeToByteArray()
        check(bytes.size <= 1_000_000) { "跨设备任务状态过大" }
        check(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null))
        val temporary = "$path.tmp"
        val fd = open(temporary, O_WRONLY or O_CREAT or O_TRUNC, 384)
        check(fd >= 0) { "无法保存跨设备任务状态" }
        try {
            if (bytes.isNotEmpty()) bytes.usePinned { pinned ->
                var offset = 0
                while (offset < bytes.size) {
                    val amount = write(fd, pinned.addressOf(offset), (bytes.size - offset).convert())
                    if (amount < 0 && errno == EINTR) continue
                    check(amount > 0) { "保存跨设备任务状态失败" }
                    offset += amount.toInt()
                }
            }
            check(fsync(fd) == 0) { "跨设备任务状态未持久化" }
        } finally { close(fd) }
        check(rename(temporary, path) == 0) { "无法更新跨设备任务状态" }
    }
}
