package com.openai.companion.kmp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTTypeData
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ImportedGguf(val fileName: String, val displayName: String, val path: String)

/** Imports a user-selected GGUF into this app's sandbox without Swift. */
@OptIn(ExperimentalForeignApi::class)
class IosGgufImporter {
    private val gate = Mutex()
    private var activeDelegate: PickerDelegate? = null

    suspend fun import(): ImportedGguf = gate.withLock {
        val source = try {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<NSURL> { continuation ->
                    val picker = UIDocumentPickerViewController(
                        forOpeningContentTypes = listOf(UTTypeData), asCopy = true,
                    )
                    val delegate = PickerDelegate(
                        onPick = { url -> if (continuation.isActive) continuation.resume(url) },
                        onCancel = {
                            if (continuation.isActive) continuation.resumeWithException(
                                IllegalStateException("已取消导入 GGUF"),
                            )
                        },
                    )
                    activeDelegate = delegate
                    picker.delegate = delegate
                    continuation.invokeOnCancellation { picker.dismissViewControllerAnimated(true, null) }
                    val presenter = UIApplication.sharedApplication.keyWindow?.rootViewController
                    if (presenter == null) {
                        continuation.resumeWithException(IllegalStateException("找不到文件选择窗口"))
                    } else presenter.presentViewController(picker, animated = true, completion = null)
                }
            }
        } finally {
            activeDelegate = null
        }
        withContext(Dispatchers.Default) { copyIntoSandbox(source) }
    }

    private fun copyIntoSandbox(source: NSURL): ImportedGguf {
        val displayName = source.lastPathComponent ?: error("所选文件没有名称")
        require(displayName.endsWith(".gguf", ignoreCase = true)) { "请选择 .gguf 模型文件" }
        val directory = NSHomeDirectory() + "/Library/Application Support/OpenAICompanion/Models"
        val files = NSFileManager.defaultManager
        check(files.createDirectoryAtPath(directory, withIntermediateDirectories = true,
            attributes = null, error = null)) { "无法创建模型目录" }
        val fileName = NSUUID().UUIDString + "-" + displayName
        val destination = "$directory/$fileName"
        val accessed = source.startAccessingSecurityScopedResource()
        try {
            check(files.copyItemAtURL(source, NSURL.fileURLWithPath(destination), error = null)) {
                "无法复制所选 GGUF 文件"
            }
        } finally {
            if (accessed) source.stopAccessingSecurityScopedResource()
        }
        return ImportedGguf(fileName, displayName, destination)
    }

    private class PickerDelegate(
        val onPick: (NSURL) -> Unit,
        val onCancel: () -> Unit,
    ) : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController,
                                    didPickDocumentsAtURLs: List<*>) {
            val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
            if (url == null) onCancel() else onPick(url)
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = onCancel()
    }
}
