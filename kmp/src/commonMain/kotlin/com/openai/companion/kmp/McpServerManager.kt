package com.openai.companion.kmp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns every MCP connection visible to the application.
 *
 * Rust receives a snapshot from [McpToolProvider]. The aggregate cache is
 * refreshed on add/remove/refresh, and the provider reads it on every callback,
 * so disconnected servers cannot leave stale tools in Rust.
 */
class McpServerManager {
    private val mutex = Mutex()
    private val servers = linkedMapOf<String, McpServerConnection>()
    private val cachedTools = linkedMapOf<String, McpToolDescriptor>()

    suspend fun attach(id: String, connection: McpServerConnection) {
        require(id.isNotBlank()) { "MCP server id must not be blank" }
        val old = mutex.withLock { servers.put(id, connection) }
        old?.close()
        refresh()
    }

    suspend fun detach(id: String) {
        val removed = mutex.withLock { servers.remove(id) }
        removed?.close()
        refresh()
    }

    suspend fun refresh() {
        val current = mutex.withLock { servers.toMap() }
        val discovered = linkedMapOf<String, McpToolDescriptor>()
        val names = mutableSetOf<String>()
        current.forEach { (serverId, connection) ->
            connection.listTools().forEach { tool ->
                require(tool.name.isNotBlank()) { "MCP tool name must not be blank" }
                check(names.add(tool.name)) { "Duplicate MCP tool name: ${tool.name}" }
                val key = "$serverId/${tool.name}"
                check(discovered.put(key, tool) == null) { "Duplicate MCP tool: $key" }
            }
        }
        mutex.withLock {
            cachedTools.clear()
            cachedTools.putAll(discovered)
        }
    }

    suspend fun tools(): List<McpToolDescriptor> = mutex.withLock { cachedTools.values.toList() }

    suspend fun callTool(name: String, argumentsJson: String): McpCallResult {
        val (serverId, toolName) = mutex.withLock {
            cachedTools.entries.firstOrNull { it.value.name == name }
                ?.let { it.key.substringBeforeLast('/') to it.value.name }
                ?: error("Unknown MCP tool: $name")
        }
        val connection = mutex.withLock { servers[serverId] }
            ?: error("MCP server is no longer connected: $serverId")
        return connection.callTool(toolName, argumentsJson)
    }
}
