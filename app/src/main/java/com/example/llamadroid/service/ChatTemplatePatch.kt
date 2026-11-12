package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.service.ram.GgufModelShapeReader
import java.io.File
import java.security.MessageDigest

/**
 * Qwen-style chat templates only render an assistant turn's reasoning after the last user query, so
 * the thinking of earlier turns vanishes from the prompt and the cached prefix breaks on every new
 * user message. llama.cpp's `--reasoning-preserve` sets the template variable `preserve_thinking`, but
 * templates that predate it ignore it. [patch] rewrites the gate to honour it; the patched copy is
 * handed to llama-server with `--chat-template-file` (the GGUF itself is never modified).
 */
object ChatTemplatePatch {
    private const val GATE = "loop.index0 > ns.last_query_index"
    private const val PATCHED_GATE = "(loop.index0 > ns.last_query_index or (preserve_thinking is defined and preserve_thinking))"
    private const val MAX_TEMPLATE_CHARS = 256 * 1024
    private const val DIR = "chat_templates"

    /**
     * Returns a patched copy of the GGUF's chat template that honours `preserve_thinking`, or null when
     * the template already supports it, lacks the Qwen `last_query_index` reasoning gate, or cannot be
     * read. Cached by model path + modification time.
     */
    fun preparedTemplateFile(context: Context, modelPath: String): File? {
        val model = File(modelPath)
        if (!model.isFile) return null
        val dir = File(context.filesDir, DIR)
        val target = File(dir, "${sha1(modelPath + model.lastModified())}.jinja")
        if (target.isFile && target.length() > 0L) return target
        val patched = patch(GgufModelShapeReader.readChatTemplate(model, MAX_TEMPLATE_CHARS)) ?: return null
        return runCatching {
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return null
            val temp = File(dir, "${target.name}.tmp")
            temp.writeText(patched, Charsets.UTF_8)
            if (!temp.renameTo(target)) {
                temp.delete()
                return null
            }
            target
        }.getOrNull()
    }

    /** Pure template rewrite; null when nothing needs (or can safely) be patched. */
    fun patch(template: String?): String? {
        if (template.isNullOrEmpty() || template.length > MAX_TEMPLATE_CHARS) return null
        if (template.contains("preserve_thinking")) return null
        if (!template.contains(GATE)) return null
        return template.replace(GATE, PATCHED_GATE)
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
