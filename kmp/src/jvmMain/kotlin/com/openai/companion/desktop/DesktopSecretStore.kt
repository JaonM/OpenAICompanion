package com.openai.companion.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

/** Generic-password Keychain access. Secrets never enter command-line arguments or Preferences. */
internal class DesktopSecretStore {
    private interface CoreFoundation : Library {
        fun CFStringCreateWithCString(allocator: Pointer?, text: String, encoding: Int): Pointer
        fun CFDictionaryCreateMutable(allocator: Pointer?, capacity: Long, keys: Pointer, values: Pointer): Pointer
        fun CFDictionarySetValue(dictionary: Pointer, key: Pointer, value: Pointer)
        fun CFDataCreate(allocator: Pointer?, bytes: ByteArray, length: Long): Pointer
        fun CFDataGetLength(data: Pointer): Long
        fun CFDataGetBytePtr(data: Pointer): Pointer
        fun CFRelease(value: Pointer)
    }
    private interface Security : Library {
        fun SecItemCopyMatching(query: Pointer, result: PointerByReference): Int
        fun SecItemAdd(query: Pointer, result: PointerByReference?): Int
        fun SecItemUpdate(query: Pointer, attributes: Pointer): Int
        fun SecItemDelete(query: Pointer): Int
    }
    private val cfLibrary by lazy { NativeLibrary.getInstance("CoreFoundation") }
    private val securityLibrary by lazy { NativeLibrary.getInstance("Security") }
    private val cf by lazy { Native.load("CoreFoundation", CoreFoundation::class.java) }
    private val security by lazy { Native.load("Security", Security::class.java) }
    private fun constant(name: String) = securityLibrary.getGlobalVariableAddress(name).getPointer(0)

    private fun <T> dictionary(block: (Pointer, MutableList<Pointer>) -> T): T {
        val owned = mutableListOf<Pointer>()
        val dictionary = cf.CFDictionaryCreateMutable(null, 0,
            cfLibrary.getGlobalVariableAddress("kCFTypeDictionaryKeyCallBacks"),
            cfLibrary.getGlobalVariableAddress("kCFTypeDictionaryValueCallBacks"))
        try { return block(dictionary, owned) }
        finally { cf.CFRelease(dictionary); owned.forEach(cf::CFRelease) }
    }
    private fun <T> query(account: String, block: (Pointer) -> T): T = dictionary { query, owned ->
        cf.CFDictionarySetValue(query, constant("kSecClass"), constant("kSecClassGenericPassword"))
        fun string(key: String, value: String) {
            val pointer = cf.CFStringCreateWithCString(null, value, 0x08000100)
            owned += pointer
            cf.CFDictionarySetValue(query, constant(key), pointer)
        }
        string("kSecAttrService", "com.openai.companion.memory-sync")
        string("kSecAttrAccount", account)
        block(query)
    }
    fun read(account: String): String? = query(account) { query ->
        cf.CFDictionarySetValue(query, constant("kSecReturnData"),
            cfLibrary.getGlobalVariableAddress("kCFBooleanTrue").getPointer(0))
        val result = PointerByReference()
        val status = security.SecItemCopyMatching(query, result)
        if (status == -25300) return@query null
        check(status == 0) { "读取同步 Keychain 失败：$status" }
        val data = result.value
        try { cf.CFDataGetBytePtr(data).getByteArray(0, cf.CFDataGetLength(data).toInt()).decodeToString() }
        finally { cf.CFRelease(data) }
    }
    fun write(account: String, value: String) = query(account) { query ->
        dictionary { attributes, owned ->
            val bytes = value.encodeToByteArray()
            val data = cf.CFDataCreate(null, bytes, bytes.size.toLong())
            owned += data
            cf.CFDictionarySetValue(attributes, constant("kSecValueData"), data)
            var status = security.SecItemUpdate(query, attributes)
            if (status == -25300) {
                cf.CFDictionarySetValue(query, constant("kSecValueData"), data)
                status = security.SecItemAdd(query, null)
            }
            check(status == 0) { "保存同步 Keychain 失败：$status" }
        }
    }
    fun remove(account: String) = query(account) { query ->
        val status = security.SecItemDelete(query)
        check(status == 0 || status == -25300) { "删除同步 Keychain 失败：$status" }
    }
}
