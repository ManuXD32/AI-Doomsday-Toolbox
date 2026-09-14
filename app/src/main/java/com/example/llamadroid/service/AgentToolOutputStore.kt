package com.example.llamadroid.service

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * App-private, immutable storage for the full result produced at a tool boundary.
 *
 * Model-facing history receives a bounded envelope and this opaque reference. The
 * original bytes are written once and are never re-rendered or rewritten during
 * context packing/compaction.
 */
internal object AgentToolOutputStore {
    private const val STORE_DIRECTORY = "agent_tool_outputs"

    fun persist(
        context: Context,
        conversationId: Long,
        toolName: String,
        toolCallId: String,
        output: String
    ): Result<String> = runCatching {
        require(conversationId > 0L) { "A conversation is required for tool output storage." }
        require(toolCallId.isNotBlank()) { "A stable tool call ID is required." }

        val digest = sha256("$conversationId\u001f$toolName\u001f$toolCallId\u001f$output")
        val conversationDirectory = File(context.filesDir, "$STORE_DIRECTORY/$conversationId")
        check(conversationDirectory.exists() || conversationDirectory.mkdirs()) {
            "Unable to create the private tool output directory."
        }
        val target = File(conversationDirectory, "$digest.txt")
        if (!target.exists()) {
            val temporary = File(conversationDirectory, ".$digest.tmp")
            temporary.outputStream().buffered().use { stream ->
                stream.write(output.toByteArray(Charsets.UTF_8))
                stream.flush()
            }
            check(temporary.renameTo(target) || (target.exists() && temporary.delete())) {
                "Unable to commit the private tool output."
            }
        }
        "agent-output://$conversationId/$digest"
    }

    /** Read only a constrained prefix for an explicitly opened UI viewer. */
    fun readBounded(
        context: Context,
        reference: String,
        maxCharacters: Int = 60_000
    ): Result<String> = runCatching {
        require(maxCharacters in 1..250_000) { "Invalid output viewer bound." }
        val match = OUTPUT_REFERENCE.matchEntire(reference.trim())
            ?: error("Invalid private tool output reference.")
        val conversationId = match.groupValues[1]
        val digest = match.groupValues[2]
        val root = File(context.filesDir, "$STORE_DIRECTORY/$conversationId").canonicalFile
        val source = File(root, "$digest.txt").canonicalFile
        require(source.parentFile == root && source.isFile) {
            "Private tool output is unavailable."
        }
        val value = StringBuilder(minOf(maxCharacters, 8_192))
        var truncated = false
        source.bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(4_096)
            while (value.length <= maxCharacters) {
                val count = reader.read(buffer)
                if (count < 0) break
                val remaining = maxCharacters - value.length
                if (count > remaining) {
                    value.append(buffer, 0, remaining.coerceAtLeast(0))
                    truncated = true
                    break
                }
                value.append(buffer, 0, count)
            }
            if (!truncated && value.length == maxCharacters && reader.read() >= 0) {
                truncated = true
            }
        }
        if (truncated) value.append("\n…")
        value.toString()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private val OUTPUT_REFERENCE = Regex("agent-output://([0-9]+)/([a-f0-9]{64})")
}
