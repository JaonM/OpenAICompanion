package com.openai.companion.desktop

import java.util.UUID
import org.junit.Assume.assumeTrue
import kotlin.test.*

class DesktopSecretStoreTest {
    @Test fun keychainRoundTrip() {
        // Explicit opt-in: CI must not display Keychain access dialogs.
        assumeTrue(System.getenv("COMPANION_TEST_KEYCHAIN") == "1")
        val store = DesktopSecretStore()
        val account = "test-${UUID.randomUUID()}"
        try {
            assertNull(store.read(account))
            store.write(account, "测试-secret")
            assertEquals("测试-secret", store.read(account))
            store.write(account, "updated")
            assertEquals("updated", store.read(account))
            store.remove(account)
            assertNull(store.read(account))
        } finally { store.remove(account) }
    }
}
