package com.example.llamadroid.ui.audio.music

import com.example.llamadroid.audio.music.StableAudio3CodecPrecision
import com.example.llamadroid.audio.music.StableAudio3ComponentManifest
import com.example.llamadroid.audio.music.StableAudio3Kind
import com.example.llamadroid.audio.music.StableAudio3DitPrecision
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Editable values retain invalid text until validation; changing tabs never resets them. */
data class MusicWorkspaceDraft(
    val kind: String,
    val values: Map<String, String> = defaults(kind),
    val components: Map<String, String> = emptyMap(),
    val loras: List<MusicLoraDraft> = emptyList()
) {
    operator fun get(key: String): String = values[key].orEmpty()
    fun withValue(key: String, value: String) = copy(values = values + (key to value))

    /**
     * Repairs precision values against the currently pinned, verified graph
     * catalog. Drafts are persisted independently of the manifest, so an
     * entry removed or invalidated by a later manifest must never reach the
     * request enums directly.
     */
    fun repairedFor(manifest: StableAudio3ComponentManifest): MusicWorkspaceDraft {
        val stableKind = if (kind == "sfx") StableAudio3Kind.SFX else StableAudio3Kind.MUSIC
        val candidates = manifest.verifiedEntries().filter { it.kind == stableKind }
        val selected = candidates.firstOrNull { entry ->
            matchesPrecision(values["ditPrecision"], entry.ditPrecision) &&
                matchesPrecision(values["decoderPrecision"], entry.decoderPrecision) &&
                matchesPrecision(values["encoderPrecision"], entry.encoderPrecision)
        } ?: candidates.firstOrNull() ?: return this
        return copy(values = values + mapOf(
            "ditPrecision" to selected.ditPrecision.wireValue,
            "decoderPrecision" to selected.decoderPrecision.wireValue,
            "encoderPrecision" to selected.encoderPrecision.wireValue
        ))
    }

    private fun matchesPrecision(value: String?, precision: StableAudio3DitPrecision): Boolean {
        val normalized = value?.trim()?.lowercase(Locale.US)?.replace('_', '-') ?: return false
        return normalized == precision.wireValue ||
            normalized == precision.name.lowercase(Locale.US).replace('_', '-') ||
            (precision == StableAudio3DitPrecision.W8A8_DYNAMIC && normalized in setOf("w8a8-dynamic", "w8a8dyn"))
    }

    private fun matchesPrecision(value: String?, precision: StableAudio3CodecPrecision): Boolean {
        val normalized = value?.trim()?.lowercase(Locale.US)?.replace('_', '-') ?: return false
        return normalized == precision.wireValue ||
            normalized == precision.name.lowercase(Locale.US).replace('_', '-')
    }

    fun toJson(): JSONObject = JSONObject().put("kind", kind)
        .put("values", JSONObject(values)).put("components", JSONObject(components))
        .put("loras", JSONArray(loras.map { JSONObject().put("path", it.path).put("strength", it.strength) }))

    companion object {
        fun defaults(kind: String): Map<String, String> = mapOf(
            "operation" to "generate", "prompt" to "", "negativePrompt" to "",
            "durationSeconds" to if (kind == "sfx") "5" else "30",
            "seed" to "", "steps" to "8", "cfg" to "1", "apg" to "1",
            "cfgBatched" to "true", "initAudio" to "", "initNoiseLevel" to "0.7",
            "maskStartSeconds" to "0", "maskEndSeconds" to "5", "threads" to "4",
            "freeModels" to "true", "ditPrecision" to "fp32", "decoderPrecision" to "w8a8", "encoderPrecision" to "w8a8",
            "maxRung" to "64", "outputFormat" to "wav", "includeMetadata" to "true"
        )
        fun fromJson(kind: String, json: JSONObject): MusicWorkspaceDraft {
            fun values(key: String): Map<String, String> = json.optJSONObject(key)?.let { obj ->
                obj.keys().asSequence().associateWith { obj.optString(it) }
            }.orEmpty()
            val adapters = json.optJSONArray("loras")
            return MusicWorkspaceDraft(kind, defaults(kind) + values("values"), values("components"),
                (0 until (adapters?.length() ?: 0)).mapNotNull { index ->
                    adapters?.optJSONObject(index)?.let { MusicLoraDraft(it.optString("path"), it.optString("strength", "1")) }
                })
        }
    }
}

data class MusicLoraDraft(val path: String, val strength: String = "1")

data class MusicComponentChoice(val path: String, val name: String, val role: String)
