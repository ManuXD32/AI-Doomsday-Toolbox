package com.example.llamadroid.ui.agent.harness

/** UI field identities preserve path boundaries; wire mutations use field.path. */
internal fun harnessSchemaKeyPath(path: List<String>): String = path.joinToString(".") { segment ->
    if (segment.isEmpty()) "\\0" else segment.replace("\\", "\\\\").replace(".", "\\.")
}

internal fun harnessSchemaNamespaceKey(namespace: String): String =
    harnessSchemaKeyPath(listOf(namespace))

/** Decoder for legacy callers that supply a field key without path metadata. */
internal fun harnessSchemaPathFromKey(value: String): List<String>? {
    if (value.isEmpty()) return null
    val encoded = mutableListOf<String>()
    val segment = StringBuilder()
    var cursor = 0
    while (cursor < value.length) {
        val character = value[cursor++]
        if (character == '\\') {
            if (cursor == value.length) return null
            segment.append(character).append(value[cursor++])
        } else if (character == '.') {
            if (segment.isEmpty()) return null
            encoded += segment.toString()
            segment.clear()
        } else {
            segment.append(character)
        }
    }
    if (segment.isEmpty()) return null
    encoded += segment.toString()
    return encoded.map { part ->
        if (part == "\\0") return@map ""
        buildString {
            var at = 0
            while (at < part.length) {
                val character = part[at++]
                if (character != '\\') {
                    append(character)
                } else {
                    if (at == part.length) return null
                    val escaped = part[at++]
                    if (escaped != '\\' && escaped != '.') return null
                    append(escaped)
                }
            }
        }
    }
}
