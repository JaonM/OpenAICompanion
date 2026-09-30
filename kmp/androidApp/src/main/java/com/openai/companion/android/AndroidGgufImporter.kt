package com.openai.companion.android

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AndroidImportedGguf(val fileName: String, val displayName: String, val path: String)

/** SAF import copies the selected model into app-private storage before llama.cpp opens it. */
class AndroidGgufImporter(private val activity: ComponentActivity) {
    private var pending: CompletableDeferred<Uri?>? = null
    private val picker = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pending?.complete(uri)
    }

    suspend fun import(): AndroidImportedGguf {
        val source = withContext(Dispatchers.Main) {
            check(pending == null) { "正在导入模型" }
            val reply = CompletableDeferred<Uri?>()
            pending = reply
            try {
                picker.launch(arrayOf("application/octet-stream", "application/gguf", "*/*"))
                reply.await() ?: error("已取消导入 GGUF")
            } finally { pending = null }
        }
        return withContext(Dispatchers.IO) {
            val name = activity.contentResolver.query(source, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: source.lastPathSegment?.substringAfterLast('/') ?: error("所选文件没有名称")
            require(name.endsWith(".gguf", ignoreCase = true)) { "请选择 .gguf 模型文件" }
            val directory = File(activity.filesDir, "Models").apply { check(isDirectory || mkdirs()) }
            val fileName = "${UUID.randomUUID()}-${name.substringAfterLast('/')}"
            val destination = File(directory, fileName)
            try {
                activity.contentResolver.openInputStream(source)?.use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                } ?: error("无法读取所选 GGUF")
                AndroidImportedGguf(fileName, name, destination.absolutePath)
            } catch (error: Throwable) {
                destination.delete()
                throw error
            }
        }
    }
}
