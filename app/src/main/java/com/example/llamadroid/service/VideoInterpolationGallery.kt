package com.example.llamadroid.service

import android.content.Context
import org.json.JSONObject
import java.io.File

data class VideoInterpolationGalleryItem(
    val id: String,
    val videoFile: File,
    val metadataFile: File,
    val createdAt: Long,
    val workflow: String,
    val modelId: String,
    val multiplier: Int,
    val backendRequested: String,
    val backendUsed: String?,
    val codec: String,
    val crf: Int,
    val sourceResolution: String?,
    val sourceFps: Double?,
    val upscaleModel: String?,
    val upscaleScale: Int?,
    val outputSizeBytes: Long
)

object VideoInterpolationGalleryStore {
    private const val SCHEMA_VERSION = 1

    private fun root(context: Context): File =
        File(context.filesDir, "video_interpolation_output").apply { mkdirs() }

    fun save(
        context: Context,
        source: File,
        config: VideoInterpolationConfig,
        info: VideoInterpolationInfo?,
        backendUsed: VideoInterpolationBackend?,
        workflow: String = "INTERPOLATE_ONLY",
        upscaleModel: String? = null,
        upscaleScale: Int? = null
    ): VideoInterpolationGalleryItem {
        val createdAt = System.currentTimeMillis()
        val id = "interpolation_$createdAt"
        val video = File(root(context), "$id.mp4")
        source.copyTo(video, overwrite = true)
        val metadata = File(root(context), "$id.json")
        val json = JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("id", id)
            put("createdAt", createdAt)
            put("workflow", workflow)
            put("modelId", config.modelId)
            put("multiplier", config.multiplier)
            put("backendRequested", config.backend.name)
            put("backendUsed", backendUsed?.name)
            put("preserveAudio", config.preserveAudio)
            put("sceneCutProtection", config.sceneCutProtection)
            put("codec", config.codec.name)
            put("crf", config.crf)
            put("sourceResolution", info?.resolution)
            put("sourceFps", info?.fps)
            put("sourceDurationSeconds", info?.durationSeconds)
            put("upscaleModel", upscaleModel)
            put("upscaleScale", upscaleScale)
            put("outputSizeBytes", video.length())
            put("videoFile", video.name)
        }
        metadata.writeText(json.toString(2))
        return fromJson(metadata, json) ?: error("Could not save interpolation gallery metadata")
    }

    fun list(context: Context): List<VideoInterpolationGalleryItem> =
        root(context).listFiles { file -> file.extension == "json" }
            .orEmpty()
            .mapNotNull { metadata ->
                runCatching { fromJson(metadata, JSONObject(metadata.readText())) }.getOrNull()
            }
            .filter { it.videoFile.isFile }
            .sortedByDescending { it.createdAt }

    fun delete(item: VideoInterpolationGalleryItem) {
        item.videoFile.delete()
        item.metadataFile.delete()
    }

    private fun fromJson(metadata: File, json: JSONObject): VideoInterpolationGalleryItem? {
        val video = File(metadata.parentFile, json.optString("videoFile"))
        if (json.optInt("schemaVersion") != SCHEMA_VERSION || !video.isFile) return null
        return VideoInterpolationGalleryItem(
            id = json.getString("id"),
            videoFile = video,
            metadataFile = metadata,
            createdAt = json.getLong("createdAt"),
            workflow = json.optString("workflow", "INTERPOLATE_ONLY"),
            modelId = json.optString("modelId"),
            multiplier = json.optInt("multiplier", 2),
            backendRequested = json.optString("backendRequested"),
            backendUsed = json.optString("backendUsed").takeIf { it.isNotBlank() && it != "null" },
            codec = json.optString("codec"),
            crf = json.optInt("crf"),
            sourceResolution = json.optString("sourceResolution").takeIf { it.isNotBlank() && it != "null" },
            sourceFps = json.optDouble("sourceFps").takeIf { !it.isNaN() },
            upscaleModel = json.optString("upscaleModel").takeIf { it.isNotBlank() && it != "null" },
            upscaleScale = json.optInt("upscaleScale", 0).takeIf { it > 0 },
            outputSizeBytes = video.length()
        )
    }
}
