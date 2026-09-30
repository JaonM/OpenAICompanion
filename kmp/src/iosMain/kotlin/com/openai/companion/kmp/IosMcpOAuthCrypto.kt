package com.openai.companion.kmp

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.UByteVar
import platform.CoreCrypto.CC_SHA256
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

/** Cryptographic primitives for the iOS PKCE flow, implemented without Swift. */
@OptIn(ExperimentalForeignApi::class)
class IosMcpOAuthCrypto : McpOAuthCrypto {
    override fun randomBytes(count: Int): ByteArray {
        require(count > 0)
        return ByteArray(count).also { bytes ->
            val status = bytes.usePinned { pinned ->
                SecRandomCopyBytes(kSecRandomDefault, count.convert(), pinned.addressOf(0))
            }
            check(status == errSecSuccess) { "iOS secure random generator failed" }
        }
    }

    override fun sha256(bytes: ByteArray): ByteArray {
        val digest = ByteArray(32)
        val inputBytes = if (bytes.isEmpty()) byteArrayOf(0) else bytes
        inputBytes.usePinned { input ->
            digest.usePinned { output ->
                check(CC_SHA256(input.addressOf(0), bytes.size.convert(),
                    output.addressOf(0).reinterpret<UByteVar>()) != null) {
                    "iOS SHA-256 failed"
                }
            }
        }
        return digest
    }
}
