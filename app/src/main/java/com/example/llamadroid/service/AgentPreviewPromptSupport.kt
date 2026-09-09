package com.example.llamadroid.service

import org.json.JSONObject

/** Keep preview controls valid JSON when packing a successful observation. */
internal fun projectAgentPreviewPromptContent(content: String, maxChars: Int): String? {
    val marker = "important_output:\n"
    val start = content.indexOf(marker).takeIf { it >= 0 }?.plus(marker.length) ?: return null
    val end = content.indexOf("\nnext_hint:", start).takeIf { it >= 0 } ?: content.length
    val observation = runCatching { JSONObject(content.substring(start, end).trim()) }.getOrNull()
        ?: return null
    if (!observation.has("controls")) return null
    val prefix = content.substring(0, start)
    val suffix = content.substring(end)
    fun render() = prefix + observation.toString() + suffix
    if (render().length <= maxChars) return render()

    // Full observation remains in the canonical tool result. This is an
    // explicitly truncated model projection, not a replacement of that result.
    observation.put("dom_truncated", true)
    observation.remove("body_text")
    observation.remove("visual_context")
    observation.remove("screenshot_path")
    observation.remove("screenshot_bytes")
    observation.remove("title")
    val controls = observation.optJSONArray("controls") ?: return null
    while (controls.length() > 0 && render().length > maxChars) {
        controls.remove(controls.length() - 1)
    }
    return render().takeIf { it.length <= maxChars }
}
