package com.example.llamadroid.harness

import android.content.Context
import android.util.Base64
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.harness.HarnessWorkspaceAccess.Companion.readBounded
import com.example.llamadroid.service.AgentRuntimeSupport
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.UUID

/** Only private temporary copies of media are passed to the LiteRT worker. */
internal class HarnessLiteRtMedia(context: Context, private val model: LiteRtModelEntity, private val client: OkHttpClient) : Closeable {
    private val root = File(context.cacheDir, "harness_inference/${UUID.randomUUID()}").apply { mkdirs() }
    private val images = mutableMapOf<JSONObject, String>()
    private val audio = mutableMapOf<JSONObject, String>()

    fun prepare(messages: List<JSONObject>) {
        messages.forEach { message ->
            val content = message.optJSONArray("content") ?: return@forEach
            for (index in 0 until content.length()) {
                val part = content.getJSONObject(index)
                when (part.getString("type")) {
                    "text" -> Unit
                    "image_url" -> {
                        require(model.supportsVision) { "MODEL_DOES_NOT_SUPPORT_IMAGES" }
                        require(message !in images) { "LITERT_SINGLE_IMAGE_PER_MESSAGE" }
                        val url = part.getJSONObject("image_url").getString("url")
                        val bytes = if (url.startsWith("data:")) {
                            require(url.substringBefore(',').matches(Regex("data:image/(png|jpeg|jpg|webp|gif);base64"))) { "IMAGE_INPUT_INVALID" }
                            decode(url.substringAfter(','))
                        } else {
                            require(AgentRuntimeSupport.blockedUrlReason(url) == null) { "IMAGE_URL_BLOCKED" }
                            client.newBuilder().followRedirects(false).build().newCall(Request.Builder().url(url).build()).execute().use { response ->
                                check(response.isSuccessful) { "IMAGE_DOWNLOAD_FAILED" }
                                requireNotNull(response.body).byteStream().use { it.readBounded(MAX_MEDIA_BYTES) }
                            }
                        }
                        images[message] = write(bytes, "image")
                    }
                    "input_audio" -> {
                        require(model.supportsAudio) { "MODEL_DOES_NOT_SUPPORT_AUDIO" }
                        require(message !in audio) { "LITERT_SINGLE_AUDIO_PER_MESSAGE" }
                        val input = part.getJSONObject("input_audio")
                        val format = input.getString("format")
                        require(format in setOf("wav", "mp3", "ogg", "flac")) { "AUDIO_INPUT_INVALID" }
                        audio[message] = write(decode(input.getString("data")), format)
                    }
                    else -> error("LITERT_CONTENT_TYPE_UNSUPPORTED")
                }
            }
        }
    }

    fun image(message: JSONObject): String? = images[message]
    fun audio(message: JSONObject): String? = audio[message]
    private fun decode(value: String): ByteArray {
        require(value.length <= MAX_MEDIA_BYTES / 3 * 4 + 4) { "MEDIA_INPUT_TOO_LARGE" }
        return Base64.decode(value, Base64.DEFAULT).also { require(it.size <= MAX_MEDIA_BYTES) { "MEDIA_INPUT_TOO_LARGE" } }
    }
    private fun write(bytes: ByteArray, extension: String): String = File(root, "${UUID.randomUUID()}.$extension")
        .apply { writeBytes(bytes) }.absolutePath

    override fun close() {
        // This directory is created solely for this request; no managed workspace path is removed.
        root.deleteRecursively()
    }
    private companion object { const val MAX_MEDIA_BYTES = 16 * 1024 * 1024 }
}
