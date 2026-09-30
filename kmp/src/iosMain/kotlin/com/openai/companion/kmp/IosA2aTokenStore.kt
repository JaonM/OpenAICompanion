package com.openai.companion.kmp

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/** Bearer tokens are scoped to an A2A Agent Card URL and held only in Keychain. */
@OptIn(ExperimentalForeignApi::class)
class IosA2aTokenStore : A2aTokenStore {
    override suspend fun load(agentId: String): String? = withContext(Dispatchers.Default) {
        val query = query(agentId)
        try {
            CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
            memScoped {
                val result = alloc<CFTypeRefVar>()
                val status = SecItemCopyMatching(query, result.ptr)
                if (status == errSecItemNotFound) return@withContext null
                check(status == errSecSuccess) { "A2A Keychain read failed: $status" }
                val data = result.value ?: error("A2A Keychain returned no data")
                try {
                    val length = CFDataGetLength(data.reinterpret()).toInt()
                    require(length in 1..MAX_BYTES) { "Invalid A2A credential size" }
                    val source = CFDataGetBytePtr(data.reinterpret()) ?: error("Empty A2A credential")
                    ByteArray(length) { index -> source[index].toByte() }.decodeToString()
                } finally { CFRelease(data) }
            }
        } finally { CFRelease(query) }
    }

    override suspend fun save(agentId: String, token: String) = withContext(Dispatchers.Default) {
        val bytes = token.encodeToByteArray()
        require(bytes.size in 1..MAX_BYTES)
        val query = query(agentId)
        try {
            val data = bytes.usePinned { pinned ->
                CFDataCreate(kCFAllocatorDefault, pinned.addressOf(0).reinterpret(), bytes.size.toLong())
            } ?: error("Could not encode A2A credential")
            try {
                val update = dictionary()
                try {
                    CFDictionarySetValue(update, kSecValueData, data)
                    val status = SecItemUpdate(query, update)
                    if (status == errSecItemNotFound) {
                        val addition = query(agentId)
                        val added = try {
                            CFDictionarySetValue(addition, kSecValueData, data)
                            CFDictionarySetValue(addition, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
                            SecItemAdd(addition, null)
                        } finally { CFRelease(addition) }
                        check(added == errSecSuccess || added == errSecDuplicateItem) { "A2A Keychain write failed: $added" }
                        if (added == errSecDuplicateItem) check(SecItemUpdate(query, update) == errSecSuccess)
                    } else check(status == errSecSuccess) { "A2A Keychain update failed: $status" }
                } finally { CFRelease(update) }
            } finally { CFRelease(data) }
        } finally { CFRelease(query) }
    }

    override suspend fun delete(agentId: String) = withContext(Dispatchers.Default) {
        val query = query(agentId)
        try {
            val status = SecItemDelete(query)
            check(status == errSecSuccess || status == errSecItemNotFound) { "A2A Keychain delete failed: $status" }
        } finally { CFRelease(query) }
    }

    private fun query(agentId: String): CFMutableDictionaryRef = dictionary().also { query ->
        val service = cfString(SERVICE)
        val account = cfString(agentId)
        try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, service)
            CFDictionarySetValue(query, kSecAttrAccount, account)
        } finally { CFRelease(service); CFRelease(account) }
    }

    private fun dictionary(): CFMutableDictionaryRef = CFDictionaryCreateMutable(
        kCFAllocatorDefault, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
    ) ?: error("Could not create A2A Keychain query")

    private fun cfString(value: String) = CFStringCreateWithCString(
        kCFAllocatorDefault, value, kCFStringEncodingUTF8,
    ) ?: error("Could not create A2A Keychain key")

    private companion object {
        const val SERVICE = "com.openai.companion.a2a.bearer"
        const val MAX_BYTES = 16 * 1024
    }
}
