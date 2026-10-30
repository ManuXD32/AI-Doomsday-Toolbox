package com.example.llamadroid.service.delegation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/** One image input that the harness agent cannot see directly: its project path and the vision helper's description. */
internal data class DelegateImageNote(val path: String, val description: String?)

/** Pure helpers for the files and images the subagent hands to the harness agent. */
internal object DelegateHarnessInputs {
    /** Same cap as the harness composer's attachment upload. */
    const val MAX_UPLOAD_BYTES = 16L * 1024 * 1024
    const val MAX_IMAGES_PER_SEND = 4
    const val MAX_DESCRIPTION_CHARS = 600

    private val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

    fun isImageName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in imageExtensions

    /** `img55` gives message id 55; any other reference gives null (files have no stored description). */
    fun messageIdOfImageRef(ref: String): Long? =
        DelegationFileRefs.parse(ref)?.takeIf { it.first == "img" }?.second

    /**
     * Prompt content in the shape the harness composer posts: one text block, then a `file` block per
     * uploaded receipt (the harness turns image receipts into image blocks for vision models).
     */
    fun promptBlocks(text: String, receiptIds: List<String>): JsonArray = buildJsonArray {
        add(buildJsonObject { put("type", "text"); put("text", text) })
        receiptIds.forEach { id -> add(buildJsonObject { put("type", "file"); put("receiptId", id) }) }
    }

    fun inputsLine(label: String, paths: List<String>): String =
        if (paths.isEmpty()) "" else "\n\n$label: " + paths.joinToString(", ")

    /** Path lines plus descriptions for models without vision. Empty when [notes] is empty. */
    fun imageNotes(notes: List<DelegateImageNote>): String {
        if (notes.isEmpty()) return ""
        return buildString {
            append("\n\nImage inputs (you cannot view images; the app describes them):")
            notes.forEach { note ->
                append("\n- ").append(note.path)
                note.description?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    append(": ").append(if (it.length > MAX_DESCRIPTION_CHARS) it.take(MAX_DESCRIPTION_CHARS).trimEnd() + "..." else it)
                }
            }
        }
    }
}
