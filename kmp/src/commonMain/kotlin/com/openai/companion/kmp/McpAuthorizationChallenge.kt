package com.openai.companion.kmp

/** Bearer challenge data needed by MCP OAuth discovery and scope step-up. */
data class McpAuthorizationChallenge(
    val resourceMetadataUrl: String?,
    val scopes: List<String>,
    val error: String?,
)

/** Parses a Bearer challenge without treating commas inside quoted values as separators. */
fun parseMcpAuthorizationChallenge(headers: List<String>): McpAuthorizationChallenge? {
    for (header in headers) {
        val segments = splitAuthenticateHeader(header)
        val first = segments.indexOfFirst { it.trimStart().startsWith("Bearer", ignoreCase = true) &&
            it.trimStart().drop(6).firstOrNull()?.let(Char::isWhitespace) != false }
        if (first < 0) continue
        val values = mutableMapOf<String, String>()
        val bearer = segments[first].trimStart().drop(6).trim()
        for ((index, segment) in segments.withIndex()) {
            if (index < first) continue
            val part = if (index == first) bearer else segment.trim()
            if (part.isEmpty()) continue
            val equals = part.indexOf('=')
            if (equals <= 0) break // Next authentication scheme, not a parameter.
            val key = part.substring(0, equals).trim().lowercase()
            if (key.isEmpty() || !key.all { it.isLetterOrDigit() || it == '_' || it == '-' }) break
            val raw = part.substring(equals + 1).trim()
            val value = if (raw.startsWith('"')) {
                if (raw.length < 2 || !raw.endsWith('"')) break
                buildString {
                    var position = 1
                    while (position < raw.lastIndex) {
                        val character = raw[position++]
                        append(if (character == '\\' && position < raw.lastIndex) raw[position++] else character)
                    }
                }
            } else raw
            values[key] = value
        }
        return McpAuthorizationChallenge(
            resourceMetadataUrl = values["resource_metadata"],
            scopes = values["scope"]?.split(Regex("\\s+"))?.filter(String::isNotBlank).orEmpty(),
            error = values["error"],
        )
    }
    return null
}

private fun splitAuthenticateHeader(header: String): List<String> {
    val parts = mutableListOf<String>()
    var quoted = false
    var escaped = false
    var start = 0
    for (index in header.indices) {
        val character = header[index]
        when {
            escaped -> escaped = false
            quoted && character == '\\' -> escaped = true
            character == '"' -> quoted = !quoted
            character == ',' && !quoted -> {
                parts += header.substring(start, index)
                start = index + 1
            }
        }
    }
    parts += header.substring(start)
    return parts
}
