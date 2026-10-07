package com.openai.companion.kmp

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Atomic settings/checkpoint replacement; avoids java.util.prefs' 8 KiB value limit. */
class DeviceSettingsFile(private val file: File) {
    fun load(): String = if (file.isFile) file.readText() else "{}"
    fun save(value: String) {
        check(file.parentFile.isDirectory || file.parentFile.mkdirs())
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.outputStream().use { stream ->
            stream.write(value.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
