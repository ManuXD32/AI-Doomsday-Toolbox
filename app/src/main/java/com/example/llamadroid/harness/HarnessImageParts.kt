package com.example.llamadroid.harness

import com.example.llamadroid.service.vision.ImageFallbackLogic
import com.example.llamadroid.service.vision.VisionDescriptionCache
import com.example.llamadroid.service.vision.VisionImageDescription
import com.example.llamadroid.service.vision.VisionImageDescriptions
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Rewrites the `image_url` parts of a harness model request. The plugin sends images as data URLs;
 * the llama-server mapping only keeps text, so before this existed every image was silently dropped.
 *
 * - Vision cards: every image of a user message is handed to the llama mapping as temp files the caller
 *   owns ([KEY_IMAGE_PATHS], first also in [KEY_IMAGE_PATH]); an image that cannot be stored falls back to
 *   its description. Assistant/tool messages always use descriptions: their templates and llama-server's
 *   role handling do not reliably accept image parts there.
 * - Other models: each image becomes text, the cached helper description or a fixed note. The text
 *   depends only on the image bytes, so the prompt prefix is identical on every turn.
 *
 * Pure apart from the injected [Describer] and [store]; content is never logged.
 */
internal object HarnessImageParts {
    /** First image of the message (kept for single-image consumers); [KEY_IMAGE_PATHS] lists all of them. */
    const val KEY_IMAGE_PATH = "adt_image_path"
    const val KEY_IMAGE_PATHS = "adt_image_paths"
    private val DATA_URL = Regex("^data:(image/[A-Za-z0-9.+-]{1,32});base64,", RegexOption.IGNORE_CASE)

    fun interface Describer {
        suspend fun describe(bytes: ByteArray, mime: String): VisionImageDescription
    }

    /** [messages] is the rewritten array (images gone); [unavailableReasons] holds one reason per image that got only a note. */
    class Result(val messages: JSONArray, val unavailableReasons: List<String>)

    fun unavailableNote(sha: String, reason: String): String =
        "[Image ${VisionDescriptionCache.shortId(sha)} could not be described: $reason]"

    /**
     * @param store writes bytes to a private temp file and returns its path; null disables the
     *   vision passthrough (every image is then described).
     */
    suspend fun rewrite(
        messages: JSONArray?,
        visionCapable: Boolean,
        describer: Describer,
        store: ((ByteArray, String) -> String?)?,
    ): Result {
        val out = JSONArray()
        val reasons = mutableListOf<String>()
        if (messages == null) return Result(out, reasons)
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index)
            val content = message?.optJSONArray("content")
            if (message == null || content == null || !hasImage(content)) {
                out.put(messages.opt(index))
                continue
            }
            val copy = JSONObject(message.toString())
            val parts = JSONArray()
            val storedPaths = JSONArray()
            val canPass = visionCapable && store != null && copy.optString("role") == "user"
            val source = copy.getJSONArray("content")
            for (partIndex in 0 until source.length()) {
                val part = source.optJSONObject(partIndex)
                if (part == null || part.optString("type") != "image_url") {
                    if (part != null) parts.put(part)
                    continue
                }
                val decoded = decode(part.optJSONObject("image_url")?.optString("url").orEmpty())
                if (decoded == null) {
                    reasons += UNSUPPORTED
                    parts.put(textPart("[Image could not be described: $UNSUPPORTED]"))
                    continue
                }
                val (bytes, mime) = decoded
                if (canPass) {
                    val path = store?.invoke(bytes, mime)
                    if (path != null) {
                        if (storedPaths.length() == 0) copy.put(KEY_IMAGE_PATH, path)
                        storedPaths.put(path)
                        continue
                    }
                }
                val sha = VisionDescriptionCache.sha256Hex(bytes)
                when (val outcome = describer.describe(bytes, mime)) {
                    is VisionImageDescription.Described ->
                        parts.put(textPart(ImageFallbackLogic.descriptionBlock(outcome.text)))
                    is VisionImageDescription.Unavailable -> {
                        reasons += outcome.reason
                        parts.put(textPart(unavailableNote(sha, outcome.reason)))
                    }
                }
            }
            if (storedPaths.length() > 0) copy.put(KEY_IMAGE_PATHS, storedPaths)
            copy.put("content", parts)
            out.put(copy)
        }
        return Result(out, reasons)
    }

    /** The production describer: shared SHA-256 cache plus the helper server. */
    fun describer(context: android.content.Context): Describer =
        Describer { bytes, mime -> VisionImageDescriptions.describe(context, bytes, mime) }

    private const val UNSUPPORTED = "only inline images are supported"

    private fun hasImage(content: JSONArray): Boolean =
        (0 until content.length()).any { content.optJSONObject(it)?.optString("type") == "image_url" }

    private fun textPart(text: String): JSONObject = JSONObject().put("type", "text").put("text", text)

    private fun decode(url: String): Pair<ByteArray, String>? {
        val match = DATA_URL.find(url) ?: return null
        val payload = url.substring(match.range.last + 1)
        if (payload.length > VisionImageDescriptions.MAX_IMAGE_BYTES / 3 * 4 + 4) return ByteArray(0) to match.groupValues[1]
        val bytes = runCatching { Base64.getMimeDecoder().decode(payload) }.getOrNull() ?: return null
        return bytes to match.groupValues[1].lowercase()
    }
}
