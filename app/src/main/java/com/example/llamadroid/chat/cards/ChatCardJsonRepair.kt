package com.example.llamadroid.chat.cards

/**
 * Bounded single-pass repair of almost-JSON produced by small models: smart/single quotes,
 * trailing commas, raw newlines in strings, and truncated output (open string/brackets are closed).
 * Linear in the input size; callers cap the input length.
 */
internal object ChatCardJsonRepair {
    private const val SMART_DOUBLE_OPEN = '“'
    private const val SMART_DOUBLE_CLOSE = '”'
    private const val SMART_SINGLE_OPEN = '‘'
    private const val SMART_SINGLE_CLOSE = '’'

    fun repair(input: String): String {
        val out = StringBuilder(input.length + 8)
        val stack = ArrayList<Char>()
        var i = 0
        val n = input.length
        while (i < n) {
            val c = input[i]
            when {
                c == '"' || c == '\'' || c == SMART_DOUBLE_OPEN || c == SMART_DOUBLE_CLOSE ||
                    c == SMART_SINGLE_OPEN || c == SMART_SINGLE_CLOSE -> {
                    i = copyString(input, i, out)
                }
                c == '{' -> { stack.add('}'); out.append(c); i++ }
                c == '[' -> { stack.add(']'); out.append(c); i++ }
                c == '}' || c == ']' -> {
                    removeTrailingComma(out)
                    if (stack.isNotEmpty() && stack.last() == c) stack.removeAt(stack.size - 1)
                    out.append(c); i++
                }
                else -> { out.append(c); i++ }
            }
        }
        removeTrailingComma(out)
        // A dangling key ("a": with no value) cannot be completed meaningfully; drop the colon.
        while (out.isNotEmpty() && out.last().isWhitespace()) out.setLength(out.length - 1)
        if (out.endsWith(":")) {
            out.setLength(out.length - 1)
            // remove the orphan key string too
            val q = out.lastIndexOf("\"", out.length - 2)
            if (q >= 0) out.setLength(q)
            removeTrailingComma(out)
        }
        for (idx in stack.indices.reversed()) out.append(stack[idx])
        return out.toString()
    }

    /** Copies one string literal starting at [start] as a double-quoted JSON string. Returns the next index. */
    private fun copyString(input: String, start: Int, out: StringBuilder): Int {
        val open = input[start]
        out.append('"')
        var i = start + 1
        while (i < input.length) {
            val c = input[i]
            when {
                c == '\\' && i + 1 < input.length -> {
                    val next = input[i + 1]
                    if (next == '\'') out.append('\'') else { out.append(c); out.append(next) }
                    i += 2
                }
                c == '\\' -> { i++ } // dangling escape at end of truncated input
                isCloser(open, c) -> { out.append('"'); return i + 1 }
                c == '"' -> { out.append("\\\""); i++ }
                c == '\n' -> { out.append("\\n"); i++ }
                c == '\r' -> { i++ }
                c == '\t' -> { out.append("\\t"); i++ }
                c < ' ' -> { i++ }
                else -> { out.append(c); i++ }
            }
        }
        out.append('"') // truncated string: close it
        return i
    }

    private fun isCloser(open: Char, c: Char): Boolean = when (open) {
        '"' -> c == '"'
        '\'' -> c == '\''
        SMART_SINGLE_OPEN, SMART_SINGLE_CLOSE -> c == '\'' || c == SMART_SINGLE_OPEN || c == SMART_SINGLE_CLOSE
        else -> c == SMART_DOUBLE_OPEN || c == SMART_DOUBLE_CLOSE
    }

    private fun removeTrailingComma(out: StringBuilder) {
        var j = out.length - 1
        while (j >= 0 && out[j].isWhitespace()) j--
        if (j >= 0 && out[j] == ',') out.deleteCharAt(j)
    }
}
