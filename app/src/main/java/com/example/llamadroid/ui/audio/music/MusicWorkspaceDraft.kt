package com.example.llamadroid.ui.audio.music

import org.json.JSONArray
import org.json.JSONObject

/** Editable values retain invalid text until validation; changing tabs never resets them. */
data class MusicWorkspaceDraft(
    val kind: String,
    val values: Map<String, String> = defaults(kind),
    val components: Map<String, String> = emptyMap(),
    val loras: List<MusicLoraDraft> = emptyList()
) {
    operator fun get(key: String): String = values[key].orEmpty()
    fun withValue(key: String, value: String) = copy(values = values + (key to value))
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
