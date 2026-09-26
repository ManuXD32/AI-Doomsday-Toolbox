package com.example.llamadroid.audio

import com.example.llamadroid.audio.music.StableAudio3Request
import org.json.JSONObject
import java.io.File

/** Paths claimed by a queued request, including every music component and conditioning input. */
internal fun AudioGenerationRequest.localAssetPaths(): Set<String> = buildSet {
    add(model.modelPath)
    model.companionPath?.let(::add)
    referenceAudioPath?.let(::add)
    if (AudioModelFamilies.isMusicOrSfx(model.family)) {
        val request = StableAudio3Request.fromJson(JSONObject(metadataJson).getJSONObject("stableAudio"))
        request.components.all().forEach { (_, component) -> add(component.path) }
        request.loras.forEach { add(it.path) }
        request.initAudioPath?.let(::add)
    }
}.filter { it.isNotBlank() }.map { File(it).canonicalPath }.toSet()

/** Must run inside the same process mutex used by local asset deletion. */
internal fun AudioGenerationRequest.requireAvailableAssets() {
    require(localAssetPaths().all { File(it).exists() }) { "Audio model or input file is missing" }
}
