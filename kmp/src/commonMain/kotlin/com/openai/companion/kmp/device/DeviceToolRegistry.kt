package com.openai.companion.kmp.device

import com.openai.companion.kmp.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.json.*

/** Descriptors and handlers are published atomically; handlers never execute under the registry lock. */
class DeviceToolRegistry(val platform: String, private val isForeground: suspend () -> Boolean = { false }) {
    private val gate = Mutex()
    private val usedNames = mutableSetOf<String>()
    private val tools = linkedMapOf<String, DeviceTool>()
    private var listener: (suspend () -> Unit)? = null
    private var closed = false

    suspend fun register(tool: DeviceTool) {
        require(tool.descriptor.name.matches(Regex("device_[a-z0-9_]+"))) { "Device tools must use the device_ namespace" }
        require(Json.parseToJsonElement(tool.descriptor.inputSchemaJson).jsonObject["type"] == JsonPrimitive("object"))
        require(tool.policy.valid() && tool.policy.origin == "device") { "Invalid device tool policy" }
        gate.withLock {
            check(!closed) { "Device tools closed" }
            check(usedNames.add(tool.descriptor.name)) { "Duplicate device tool" }
            tools[tool.descriptor.name] = tool
        }
        listener?.invoke()
    }

    suspend fun remove(name: String) {
        val changed = gate.withLock { tools.remove(name) != null }
        if (changed) listener?.invoke()
    }

    suspend fun descriptors(): List<McpToolDescriptor> = gate.withLock { tools.values.map { it.descriptor.copy(policy = it.policy) } }

    internal fun onChanged(listener: suspend () -> Unit) { this.listener = listener }

    suspend fun call(name: String, argumentsJson: String): McpCallResult {
        val tool = gate.withLock { if (closed) null else tools[name] }
        val result = try {
            if (tool == null) DeviceToolResult("unavailable", code = "TOOL_UNAVAILABLE", message = "Tool is unavailable on this device")
            else {
                require(argumentsJson.length <= 16_384) { "Tool arguments too large" }
                if (tool.policy.requiresForeground && !isForeground()) throw ToolExecutionException(
                    ToolExecutionErrorCode.PERMISSION_DENIED, "Open the app in the foreground to use this tool")
                val result = tool.execute(Json.parseToJsonElement(argumentsJson).jsonObject)
                if (tool.policy.effect == "read" && tool.policy.requiresForeground && !isForeground()) throw ToolExecutionException(
                    ToolExecutionErrorCode.PERMISSION_DENIED, "App left the foreground; retry after reopening it")
                if (tool.policy.effect == "write" || gate.withLock { !closed && tools[name] === tool }) result
                else DeviceToolResult("unavailable", code = "TOOL_UNAVAILABLE", message = "Tool was removed during execution")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ToolExecutionException) {
            DeviceToolResult(
                status = if (error.code == ToolExecutionErrorCode.PERMISSION_DENIED) "requires_user_action" else "error",
                code = error.code.name,
                message = error.message,
            )
        } catch (error: IllegalArgumentException) {
            DeviceToolResult("error", code = "INVALID_ARGUMENTS", message = error.message)
        } catch (_: Exception) {
            DeviceToolResult("error", code = "DEVICE_QUERY_FAILED", message = "Device query failed")
        }
        return McpCallResult(result.json(platform, Clock.System.now().toString()), result.status != "ok")
    }

    suspend fun close() {
        gate.withLock { closed = true; tools.clear() }
        listener?.invoke()
        listener = null
    }
}

class DeviceToolConnection(private val registry: DeviceToolRegistry) : McpServerConnection {
    override suspend fun listTools() = registry.descriptors()
    override suspend fun callTool(name: String, argumentsJson: String) = registry.call(name, argumentsJson)
    override fun setToolsChangedListener(listener: suspend () -> Unit) = registry.onChanged(listener)
    override suspend fun close() = registry.close()
}
