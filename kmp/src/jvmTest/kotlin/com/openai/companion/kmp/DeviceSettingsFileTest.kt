package com.openai.companion.kmp

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.*

class DeviceSettingsFileTest {
    @Test fun replacementSurvivesReopeningAndCorruptionIsNotSilentlyDiscarded() {
        val directory = Files.createTempDirectory("device-settings").toFile()
        try {
            val file = directory.resolve("state.json")
            val store = DeviceSettingsFile(file)
            assertEquals("{}", store.load())
            store.save("{\"active\":\"attempt-1\"}")
            assertEquals("{\"active\":\"attempt-1\"}", DeviceSettingsFile(file).load())
            store.save("{\"pending\":\"result\"}")
            assertEquals("{\"pending\":\"result\"}", DeviceSettingsFile(file).load())
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file.toPath()))
            file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
            assertFails { store.load() }
            file.writeBytes(ByteArray(1_000_001))
            assertFailsWith<IllegalStateException> { store.load() }
        } finally { directory.deleteRecursively() }
    }
}
