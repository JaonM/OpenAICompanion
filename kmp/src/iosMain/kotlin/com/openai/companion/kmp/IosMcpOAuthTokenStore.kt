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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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

/** Device-only generic-password storage; OAuth secrets never enter NSUserDefaults. */
internal class IosKeychainUnavailableException(status: Int) :
    IllegalStateException("iOS Keychain read failed: $status")

@OptIn(ExperimentalForeignApi::class)
class IosMcpOAuthTokenStore : McpOAuthTokenStore {
    override suspend fun load(endpoint: String): McpOAuthTokens? = withContext(Dispatchers.Default) {
        val query = query(endpoint)
        try {
            CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
            memScoped {
                val result = alloc<CFTypeRefVar>()
                val status = SecItemCopyMatching(query, result.ptr)
                if (status == errSecItemNotFound) return@withContext null
                if (status == -34018) throw IosKeychainUnavailableException(status)
                check(status == errSecSuccess) { "iOS Keychain read failed: $status" }
                val data = result.value ?: error("iOS Keychain returned no data")
                try {
                    val length = CFDataGetLength(data.reinterpret()).toInt()
                    require(length in 1..MAX_STORED_BYTES) { "Invalid stored OAuth credential size" }
                    val source = CFDataGetBytePtr(data.reinterpret()) ?: error("iOS Keychain data is empty")
                    val bytes = ByteArray(length) { index -> source[index].toByte() }
                    decode(bytes.decodeToString())
                } finally {
                    CFRelease(data)
                }
            }
        } finally {
            CFRelease(query)
        }
    }

    override suspend fun save(endpoint: String, tokens: McpOAuthTokens) = withContext(Dispatchers.Default) {
        val bytes = encode(tokens).encodeToByteArray()
        require(bytes.size in 1..MAX_STORED_BYTES)
        val query = query(endpoint)
        try {
            val data = bytes.usePinned { pinned ->
                CFDataCreate(kCFAllocatorDefault, pinned.addressOf(0).reinterpret(), bytes.size.toLong())
            } ?: error("Could not encode OAuth credential for Keychain")
            try {
                val update = dictionary()
                try {
                    CFDictionarySetValue(update, kSecValueData, data)
                    val status = SecItemUpdate(query, update)
                    if (status == errSecItemNotFound) {
                        val addition = query(endpoint)
                        val added = try {
                            CFDictionarySetValue(addition, kSecValueData, data)
                            CFDictionarySetValue(addition, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
                            SecItemAdd(addition, null)
                        } finally {
                            CFRelease(addition)
                        }
                        check(added == errSecSuccess || added == errSecDuplicateItem) {
                            "iOS Keychain write failed: $added"
                        }
                        if (added == errSecDuplicateItem) {
                            check(SecItemUpdate(query, update) == errSecSuccess) { "iOS Keychain update failed" }
                        }
                    } else check(status == errSecSuccess) { "iOS Keychain update failed: $status" }
                } finally {
                    CFRelease(update)
                }
            } finally {
                CFRelease(data)
            }
        } finally {
            CFRelease(query)
        }
    }

    override suspend fun delete(endpoint: String) = withContext(Dispatchers.Default) {
        val query = query(endpoint)
        try {
            val status = SecItemDelete(query)
            check(status == errSecSuccess || status == errSecItemNotFound) { "iOS Keychain delete failed: $status" }
        } finally {
            CFRelease(query)
        }
    }

    private fun query(endpoint: String): CFMutableDictionaryRef = dictionary().also { query ->
        val service = cfString(SERVICE)
        val account = cfString(endpoint)
        try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, service)
            CFDictionarySetValue(query, kSecAttrAccount, account)
        } finally {
            CFRelease(service)
            CFRelease(account)
        }
    }

    private fun dictionary(): CFMutableDictionaryRef = CFDictionaryCreateMutable(
        kCFAllocatorDefault, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
    ) ?: error("Could not create Keychain query")

    private fun cfString(value: String) =
        CFStringCreateWithCString(kCFAllocatorDefault, value, kCFStringEncodingUTF8)
            ?: error("Could not create Keychain key")

    private fun encode(tokens: McpOAuthTokens): String = buildJsonObject {
        put("issuer", tokens.issuer)
        put("resource", tokens.resource)
        put("clientId", tokens.clientId)
        put("accessToken", tokens.accessToken)
        tokens.refreshToken?.let { put("refreshToken", it) }
        tokens.expiresInSeconds?.let { put("expiresInSeconds", it) }
        put("issuedAtEpochMillis", tokens.issuedAtEpochMillis)
    }.toString()

    private fun decode(raw: String): McpOAuthTokens {
        val data = Json.parseToJsonElement(raw) as? JsonObject ?: error("Stored OAuth credential is invalid")
        return McpOAuthTokens(
            issuer = data.getValue("issuer").jsonPrimitive.content,
            resource = data.getValue("resource").jsonPrimitive.content,
            clientId = data.getValue("clientId").jsonPrimitive.content,
            accessToken = data.getValue("accessToken").jsonPrimitive.content,
            refreshToken = data["refreshToken"]?.jsonPrimitive?.content,
            expiresInSeconds = data["expiresInSeconds"]?.jsonPrimitive?.content?.toLongOrNull(),
            issuedAtEpochMillis = data.getValue("issuedAtEpochMillis").jsonPrimitive.content.toLong(),
        )
    }

    private companion object {
        const val SERVICE = "com.openai.companion.mcp.oauth"
        const val MAX_STORED_BYTES = 64 * 1024
    }
}
