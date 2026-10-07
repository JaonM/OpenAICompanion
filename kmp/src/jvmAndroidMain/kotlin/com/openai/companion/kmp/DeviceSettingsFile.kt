package com.openai.companion.kmp

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.nio.file.attribute.PosixFilePermission

/** Atomic settings/checkpoint replacement; avoids java.util.prefs' 8 KiB value limit. */
class DeviceSettingsFile(private val file: File) {
    fun load(): String {
        if (!file.isFile) return "{}"
        check(file.length() <= 1_000_000) { "跨设备任务状态过大" }
        return file.readBytes().decodeToString(throwOnInvalidSequence = true)
    }
    fun save(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        check(bytes.size <= 1_000_000) { "跨设备任务状态过大" }
        check(file.parentFile.isDirectory || file.parentFile.mkdirs())
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.outputStream().use { stream ->
            Files.setPosixFilePermissions(temporary.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            stream.write(bytes)
            stream.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        FileChannel.open(file.parentFile.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
}
