package com.example.llamadroid.service.chat

import com.example.llamadroid.service.NativeChatToolRuntime
import com.example.llamadroid.service.OllamaService
import com.example.llamadroid.service.StreamingProgress
import com.example.llamadroid.util.DebugLog
import java.io.File

private val KnownToolImagePathPlaceholders = setOf(
    "image_from_user_input",
    "image_from_user",
    "image_path_to_the_provided_image",
    "image_path_to_provided_image",
    "path_to_the_provided_image",
    "path_to_provided_image",
    "provided_image_path",
    "provided_image",
    "user_image",
    "user_provided_image",
    "attached_image"
)

/**
 * Repairs the image path of `remove_image_background` calls that used a placeholder instead of a
 * real file (moved out of `LlamaClientService`, behaviour unchanged).
 */
internal object NativeToolCallPatcher {
    fun prepare(
        toolCall: OllamaService.ToolCall,
        messages: List<OllamaService.ChatMessage>,
        progress: StreamingProgress
    ): OllamaService.ToolCall {
        if (toolCall.name != NativeChatToolRuntime.TOOL_REMOVE_IMAGE_BACKGROUND) return toolCall

        val providedImagePath = firstToolArgument(
            arguments = toolCall.arguments,
            "image_path",
            "path",
            "source_image_path",
            "input_image_path",
            "image"
        ).orEmpty().trim()

        val hasExistingFile = providedImagePath.isNotBlank() &&
            !isLikelyImagePathPlaceholder(providedImagePath) &&
            File(providedImagePath).let { it.exists() && it.isFile }
        if (hasExistingFile) return toolCall

        val resolvedImagePath = resolveLatestChatImagePath(
            messages = messages,
            progress = progress
        ) ?: return toolCall

        val patchedArguments = toolCall.arguments.toMutableMap()
        val knownPathKeys = listOf("image_path", "path", "source_image_path", "input_image_path", "image")
        var updatedExistingKey = false
        knownPathKeys.forEach { key ->
            if (patchedArguments.containsKey(key)) {
                patchedArguments[key] = resolvedImagePath
                updatedExistingKey = true
            }
        }
        if (!updatedExistingKey) {
            patchedArguments["image_path"] = resolvedImagePath
        }

        DebugLog.log(
            "LlamaClientService: patched remove_image_background input path " +
                "original='${providedImagePath.take(120)}' resolved='${resolvedImagePath.take(160)}'"
        )
        return toolCall.copy(arguments = patchedArguments)
    }

    private fun firstToolArgument(
        arguments: Map<String, String>,
        vararg keys: String
    ): String? = keys
        .firstNotNullOfOrNull { key ->
            arguments[key]?.trim()?.takeIf { value -> value.isNotBlank() }
        }

    private fun isLikelyImagePathPlaceholder(value: String): Boolean {
        val normalized = value.trim().lowercase()
        if (normalized.isBlank()) return true
        if (normalized.startsWith("/") || normalized.startsWith("file://")) return false

        val compact = normalized.replace(Regex("""[^a-z0-9]+"""), "_").trim('_')
        if (compact.isBlank()) return true
        if (compact in KnownToolImagePathPlaceholders) return true

        return !normalized.contains('/') &&
            compact.contains("image") &&
            (
                compact.contains("user") ||
                    compact.contains("provided") ||
                    compact.contains("input") ||
                    compact.contains("path")
                )
    }

    private fun resolveLatestChatImagePath(
        messages: List<OllamaService.ChatMessage>,
        progress: StreamingProgress
    ): String? {
        progress.generatedImagePaths
            .asReversed()
            .firstOrNull { path ->
                path.isNotBlank() && File(path).let { file -> file.exists() && file.isFile }
            }
            ?.let { return it }

        messages.asReversed().forEach { message ->
            val candidate = message.imagePath?.trim().orEmpty()
            if (candidate.isNotBlank() && File(candidate).let { it.exists() && it.isFile }) {
                return candidate
            }
        }
        return null
    }
}
