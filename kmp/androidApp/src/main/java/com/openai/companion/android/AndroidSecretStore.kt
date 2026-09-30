package com.openai.companion.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.openai.companion.kmp.A2aTokenStore
import com.openai.companion.kmp.McpOAuthTokenStore
import com.openai.companion.kmp.McpOAuthTokens
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Keeps OAuth and A2A credentials encrypted by a device-bound Android Keystore key. */
internal class AndroidEncryptedPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("companion_credentials", Context.MODE_PRIVATE)
    private val gate = Any()

    fun read(name: String): String? = synchronized(gate) {
        val encoded = preferences.getString(storageKey(name), null) ?: return@synchronized null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > 12) { "Invalid encrypted credential" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString()
    }

    fun write(name: String, value: String) = synchronized(gate) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(value.encodeToByteArray())
        check(preferences.edit().putString(storageKey(name), Base64.encodeToString(bytes, Base64.NO_WRAP)).commit())
    }

    fun remove(name: String) = synchronized(gate) {
        check(preferences.edit().remove(storageKey(name)).commit())
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return generator.generateKey()
    }

    private fun storageKey(name: String): String = MessageDigest.getInstance("SHA-256")
        .digest(name.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object { const val KEY_ALIAS = "openai-companion-credentials-v1" }
}

class AndroidMcpOAuthTokenStore(context: Context) : McpOAuthTokenStore {
    private val encrypted = AndroidEncryptedPreferences(context)

    override suspend fun load(endpoint: String): McpOAuthTokens? = withContext(Dispatchers.IO) {
        encrypted.read("mcp:$endpoint")?.let { raw ->
            val data = Json.parseToJsonElement(raw).jsonObject
            McpOAuthTokens(
                issuer = data.getValue("issuer").jsonPrimitive.content,
                resource = data.getValue("resource").jsonPrimitive.content,
                clientId = data.getValue("clientId").jsonPrimitive.content,
                accessToken = data.getValue("accessToken").jsonPrimitive.content,
                refreshToken = data["refreshToken"]?.let { if (it.toString() == "null") null else it.jsonPrimitive.content },
                expiresInSeconds = data["expiresInSeconds"]?.let { if (it.toString() == "null") null else it.jsonPrimitive.content.toLong() },
                issuedAtEpochMillis = data.getValue("issuedAtEpochMillis").jsonPrimitive.content.toLong(),
            )
        }
    }

    override suspend fun save(endpoint: String, tokens: McpOAuthTokens) = withContext(Dispatchers.IO) {
        encrypted.write("mcp:$endpoint", buildJsonObject {
            put("issuer", tokens.issuer); put("resource", tokens.resource)
            put("clientId", tokens.clientId); put("accessToken", tokens.accessToken)
            tokens.refreshToken?.let { put("refreshToken", it) }
            tokens.expiresInSeconds?.let { put("expiresInSeconds", it) }
            put("issuedAtEpochMillis", tokens.issuedAtEpochMillis)
        }.toString())
    }

    override suspend fun delete(endpoint: String) = withContext(Dispatchers.IO) { encrypted.remove("mcp:$endpoint") }
}

class AndroidA2aTokenStore(context: Context) : A2aTokenStore {
    private val encrypted = AndroidEncryptedPreferences(context)

    override suspend fun load(agentId: String): String? = withContext(Dispatchers.IO) { encrypted.read("a2a:$agentId") }

    override suspend fun save(agentId: String, token: String) = withContext(Dispatchers.IO) {
        require(token.isNotBlank() && token.length <= 16 * 1024)
        encrypted.write("a2a:$agentId", token)
    }

    override suspend fun delete(agentId: String) = withContext(Dispatchers.IO) { encrypted.remove("a2a:$agentId") }
}
