package com.example.llamadroid.tama.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Only the fields the home animation renderer reads from `tama/animations/manifest.json`. */
@Serializable
internal data class TamaAnimationManifest(
    val schemaVersion: Int,
    val assets: List<TamaAnimationEntry>
)

@Serializable
internal data class TamaAnimationPoint(val x: Float, val y: Float)

@Serializable
internal data class TamaAnimationClip(
    val action: String,
    val frames: List<Int>,
    val frameMs: Int
)

@Serializable
internal data class TamaAnimationEntry(
    val id: String,
    val path: String,
    val frameWidth: Int,
    val frameHeight: Int,
    val columns: Int,
    val clips: List<TamaAnimationClip>,
    val footAnchor: TamaAnimationPoint = TamaAnimationPoint(0.5f, 1f),
    /** Release readiness must be explicit; omitted flags are not ready. */
    val productionReady: Boolean = false
)

private val animationManifestJson = Json { ignoreUnknownKeys = true }

internal fun parseTamaAnimationManifest(text: String): TamaAnimationManifest =
    animationManifestJson.decodeFromString(TamaAnimationManifest.serializer(), text)
