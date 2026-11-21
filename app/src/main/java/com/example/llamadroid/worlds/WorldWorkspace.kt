package com.example.llamadroid.worlds

import android.content.Context
import com.example.llamadroid.service.NativeChatWorkspaceEdits
import com.example.llamadroid.service.NativeChatWorkspaceSupport
import com.example.llamadroid.service.chat.NativeChatStepStatus
import com.example.llamadroid.service.chat.NativeChatStepType
import com.example.llamadroid.service.chat.NativeChatStepsCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** Keeps 3D worlds as real `worlds/<slug>.html` files in the chat workspace so file tools can edit them. */
object WorldWorkspace {
    const val DIR = "worlds"
    private const val INDEX = ".messages"
    private val EDIT_TOOLS = setOf(
        "write_workspace_file", "append_workspace_file", "edit_workspace_lines",
        "apply_workspace_patch", NativeChatWorkspaceEdits.TOOL_REPLACE
    )
    private val PATCH_PATH = Regex("""^\s*\*\*\*\s*(?:Update|Add) File:\s*(.+?)\s*$""", RegexOption.MULTILINE)
    private val lock = Any()

    /** Lowercase ASCII slug of [title], at most 40 chars, never empty. */
    fun slugFor(title: String, fallback: String = "world"): String {
        val s = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(40)
            .trim('-')
        return s.ifBlank { fallback }
    }

    /**
     * Saves [html] as `worlds/<slug>.html` once per [messageId]. Idempotent: a message that already has a
     * file keeps it (even after the model edited it), identical text is reused, and a different world with
     * the same slug gets `-2`, `-3`... Returns the workspace-relative path, or null on failure.
     */
    fun saveOnce(context: Context, chatId: Long, messageId: Long, title: String, html: String): String? =
        runCatching {
            synchronized(lock) {
                val dir = NativeChatWorkspaceSupport.subDir(context, chatId, DIR)
                val index = File(dir, INDEX)
                val known = index.takeIf { it.isFile }?.readLines().orEmpty()
                    .firstOrNull { it.substringBefore('=') == messageId.toString() }
                    ?.substringAfter('=')
                if (known != null && File(dir, known).isFile) return@synchronized "$DIR/$known"
                val slug = slugFor(title)
                var n = 1
                var target: File
                while (true) {
                    target = File(dir, if (n == 1) "$slug.html" else "$slug-$n.html")
                    if (!target.exists() || target.readText(Charsets.UTF_8) == html || n > 99) break
                    n++
                }
                if (!target.exists() || target.readText(Charsets.UTF_8) != html) target.writeText(html, Charsets.UTF_8)
                index.appendText("$messageId=${target.name}\n", Charsets.UTF_8)
                "$DIR/${target.name}"
            }
        }.getOrNull()

    /** Workspace `.html` paths that tool steps of one reply wrote or edited successfully (distinct, in order). */
    fun editedHtmlPaths(stepsJson: String?): List<String> {
        val steps = NativeChatStepsCodec.decode(stepsJson) ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (step in steps.steps) {
            if (step.type != NativeChatStepType.TOOL || step.toolName !in EDIT_TOOLS) continue
            if (step.status != NativeChatStepStatus.OK) continue
            val obj = runCatching { Json.parseToJsonElement(step.arguments.orEmpty()) as? JsonObject }.getOrNull() ?: continue
            val paths = if (step.toolName == "apply_workspace_patch") {
                val patch = (obj["patch"] as? JsonPrimitive)?.content.orEmpty()
                PATCH_PATH.findAll(patch).map { it.groupValues[1] }.toList()
            } else {
                listOfNotNull((obj["path"] as? JsonPrimitive)?.content)
            }
            paths.map { it.trim().trimStart('/') }
                .filter { it.endsWith(".html", ignoreCase = true) && !it.contains("..") }
                .forEach { out.add(it) }
        }
        return out.toList()
    }

    /** Current file text when it is an `.html` workspace file that [WorldPageBuilder.detect] accepts. */
    fun readWorldFile(context: Context, chatId: Long, path: String): String? = runCatching {
        val file = NativeChatWorkspaceSupport.resolve(context, chatId, path)
        if (!file.isFile || file.length() > 1_048_576L) return@runCatching null
        file.readText(Charsets.UTF_8).takeIf { WorldPageBuilder.detect(it) != null }
    }.getOrNull()
}
