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
    private val lifecycleGate = Mutex()
    private val refreshGate = Mutex()
    private val servers = linkedMapOf<String, McpServerConnection>()
    private val cachedTools = linkedMapOf<String, McpToolDescriptor>()
    private var serverVersion = 0L
    private var toolsChangedListener: (suspend () -> Unit)? = null

    suspend fun attach(id: String, connection: McpServerConnection) {
        require(id.isNotBlank()) { "MCP server id must not be blank" }
        try {
            lifecycleGate.withLock {
                connection.setToolsChangedListener {
                    refresh()
                    notifyToolsChanged()
                }
                val (old, previousTools) = mutex.withLock {
                    val previous = cachedTools.filterKeys { it.startsWith("$id/") }
                    previous.keys.forEach(cachedTools::remove)
                    serverVersion++
                    servers.put(id, connection) to previous
                }
                try {
                    refresh()
                } catch (error: Throwable) {
                    mutex.withLock {
                        if (old == null) servers.remove(id) else servers[id] = old
                        previousTools.forEach { (key, tool) -> cachedTools[key] = tool }
                        serverVersion++
                    }
                    try { connection.close() } catch (_: Throwable) { }
                    try { refresh() } catch (_: Throwable) { }
                    throw error
                }
                try { old?.close() } catch (_: Throwable) { }
            }
        } catch (error: Throwable) {
            try { notifyToolsChanged() } catch (_: Throwable) { }
            throw error
        }
        notifyToolsChanged()
    }

    suspend fun detach(id: String) {
        try {
            lifecycleGate.withLock {
                val removed = mutex.withLock {
                    val connection = servers.remove(id)
                    if (connection != null) {
                        serverVersion++
                        cachedTools.keys.filter { it.startsWith("$id/") }.forEach(cachedTools::remove)
                    }
                    connection
                }
                try { removed?.close() } catch (_: Throwable) { }
                refresh()
            }
        } finally {
            notifyToolsChanged()
        }
    }

    suspend fun setToolsChangedListener(listener: suspend () -> Unit) {
        mutex.withLock {
            toolsChangedListener = listener
        }
    }

    suspend fun refresh() = refreshGate.withLock {
        while (true) {
            val (current, version) = mutex.withLock { servers.toMap() to serverVersion }
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
            val applied = mutex.withLock {
                if (serverVersion != version) false else {
                    cachedTools.clear()
                    cachedTools.putAll(discovered)
                    true
                }
            }
            if (applied) return@withLock
        }
    }

    suspend fun tools(): List<McpToolDescriptor> = mutex.withLock { cachedTools.values.toList() }

    /** Pins approval to the advertised tool and connection, not just its reusable name. */
    internal suspend fun prepareToolCall(name: String): McpToolCallTicket = mutex.withLock {
        cachedTools.entries.firstOrNull { it.value.name == name }
            ?.let { (key, tool) ->
                val connection = servers[key.substringBeforeLast('/')]
                    ?: error("MCP server is no longer connected for tool: $name")
                McpToolCallTicket(key, tool, connection)
            }
            ?: error("Unknown MCP tool: $name")
    }

    internal suspend fun callTool(ticket: McpToolCallTicket, argumentsJson: String): McpCallResult {
        val unchanged = mutex.withLock {
            cachedTools[ticket.key] == ticket.tool &&
                servers[ticket.key.substringBeforeLast('/')] === ticket.connection
        }
        if (!unchanged) {
            throw ToolExecutionException(
                ToolExecutionErrorCode.PERMISSION_DENIED,
                "MCP tool or server changed while approval was pending; approve a new call",
            )
        }
        return ticket.connection.callTool(ticket.tool.name, argumentsJson)
    }

    suspend fun callTool(name: String, argumentsJson: String): McpCallResult =
        callTool(prepareToolCall(name), argumentsJson)

    private suspend fun notifyToolsChanged() {
        val listener = mutex.withLock { toolsChangedListener }
        listener?.invoke()
    }
}

internal class McpToolCallTicket(
    val key: String,
    val tool: McpToolDescriptor,
    val connection: McpServerConnection,
)
