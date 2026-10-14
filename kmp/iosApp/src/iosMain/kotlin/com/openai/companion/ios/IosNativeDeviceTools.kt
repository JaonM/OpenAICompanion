@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.openai.companion.ios

import com.openai.companion.ios.llama.*
import com.openai.companion.kmp.McpToolDescriptor
import com.openai.companion.kmp.device.*
import kotlinx.cinterop.*
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import platform.posix.free
import kotlin.coroutines.resume

internal val nativeDeviceGroups = linkedMapOf(
    "health" to "健康（只读）", "home" to "家庭（实验功能 · 真实配件待验收）",
    "reminders" to "提醒事项", "alarms" to "闹钟", "contacts" to "联系人选择",
    "calendar_edit" to "日历修改", "files" to "文件读取", "capture" to "照片、扫描与 OCR",
    "audio" to "录音与本地转写",
)
internal fun nativeDeviceGroupState() = nativeDeviceGroups.mapValues {
    NSUserDefaults.standardUserDefaults.boolForKey("deviceGroup.${it.key}")
}
internal fun nativeDeviceTools(journal: DeviceOperationJournal): List<DeviceTool> {
    val pointer = oc_device_catalog() ?: return emptyList()
    val catalog = try { Json.parseToJsonElement(pointer.toKString()).jsonArray } finally { free(pointer) }
    return catalog.map { it.jsonObject }.filter {
        NSUserDefaults.standardUserDefaults.boolForKey("deviceGroup.${it.getValue("group").jsonPrimitive.content}")
    }.map { spec ->
        val name = spec.getValue("name").jsonPrimitive.content
        NativeDeviceTool(McpToolDescriptor(name, spec.getValue("description").jsonPrimitive.content,
            spec.getValue("schema").toString()), spec.getValue("write").jsonPrimitive.boolean, journal) { arguments ->
            invokeNativeDevice(name, arguments)
        }
    }
}
private suspend fun invokeNativeDevice(name: String, arguments: JsonObject): DeviceToolResult =
    suspendCancellableCoroutine { continuation ->
        val callId = NSUUID().UUIDString
        val ref = StableRef.create(continuation)
        oc_device_call(callId, name, arguments.toString(), staticCFunction { result, context ->
            val stable = context!!.asStableRef<CancellableContinuation<DeviceToolResult>>()
            val pending = stable.get()
            try {
                val parsed = Json.decodeFromString<DeviceToolResult>(result!!.toKString())
                pending.resume(parsed)
            } catch (_: Exception) {
                pending.resume(DeviceToolResult("error", code = "NATIVE_RESULT_INVALID", message = "Invalid native result"))
            } finally { stable.dispose() }
        }, ref.asCPointer())
        continuation.invokeOnCancellation { oc_device_cancel(callId) }
    }
