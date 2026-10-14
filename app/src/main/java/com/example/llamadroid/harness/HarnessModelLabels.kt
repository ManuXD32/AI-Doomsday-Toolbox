package com.example.llamadroid.harness

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide cache mapping ADT wire ids (`llama:<cardId>`, `litert:<id>`) to a readable
 * model name. It is filled off the main thread whenever the local model catalog is built, so
 * transcript formatting never performs database I/O while rendering.
 */
object HarnessModelLabels {
    private val labels = ConcurrentHashMap<String, String>()

    fun register(wireId: String, name: String?) {
        val clean = name?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (isManagedWireId(wireId)) labels[wireId] = clean
    }

    /** "Gemma4 E4B (llama:3)" for a known managed id; any other value is returned unchanged. */
    fun display(value: String): String {
        if (!isManagedWireId(value)) return value
        return format(value, labels[value])
    }

    internal fun format(wireId: String, name: String?): String =
        name?.takeIf { it.isNotBlank() && it != wireId }?.let { "$it ($wireId)" } ?: wireId

    internal fun isManagedWireId(value: String): Boolean =
        MANAGED_ID.matches(value)

    internal fun clearForTest() = labels.clear()

    private val MANAGED_ID = Regex("^(llama|litert):\\d+$")
}
