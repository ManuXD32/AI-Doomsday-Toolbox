package com.example.llamadroid.sd

import android.os.Parcelable
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import kotlinx.parcelize.Parcelize
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * One ordered Stable Diffusion LoRA application.
 *
 * The native stable-diffusion.cpp contract has one global apply mode, while
 * every adapter keeps its own strength.  [highNoiseOnly] is used by Wan 2.2
 * workflows; it is deliberately metadata on the item rather than a second
 * global switch so ordering and per-item intent survive round trips.
 */
@Parcelize
data class SdLoraSpec(
    val path: String,
    val strength: Float = 1.0f,
    val enabled: Boolean = true,
    val highNoiseOnly: Boolean = false
) : Parcelable {
    val filename: String
        get() = File(path).name

    val promptTokenName: String
        get() = File(path).nameWithoutExtension

    fun normalized(): SdLoraSpec = copy(
        path = path.trim(),
        strength = strength.coerceIn(MIN_STRENGTH, MAX_STRENGTH)
    )

    fun toJson(): JSONObject = JSONObject()
        .put("path", path)
        .put("strength", strength.toDouble())
        .put("enabled", enabled)
        .put("highNoiseOnly", highNoiseOnly)

    companion object {
        const val MIN_STRENGTH = -4.0f
        const val MAX_STRENGTH = 4.0f

        fun fromJson(json: JSONObject): SdLoraSpec = SdLoraSpec(
            path = json.optString("path").trim(),
            strength = json.optDouble("strength", 1.0).toFloat(),
            enabled = json.optBoolean("enabled", true),
            highNoiseOnly = json.optBoolean("highNoiseOnly", false)
        )

        /** Map the pre-multi-LoRA fields to the new ordered representation. */
        fun fromLegacy(path: String?, strength: Float = 1.0f): List<SdLoraSpec> =
            path?.trim()?.takeIf { it.isNotBlank() }?.let {
                listOf(SdLoraSpec(path = it, strength = strength))
            }.orEmpty()
    }
}

fun List<SdLoraSpec>.toJsonArray(): JSONArray = JSONArray().also { array ->
    forEach { array.put(it.toJson()) }
}

fun JSONArray?.toSdLoraSpecs(): List<SdLoraSpec> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.let { add(SdLoraSpec.fromJson(it)) }
        }
    }
}

/**
 * Return active adapters in exactly their stored order.  Blank or malformed
 * entries are not silently discarded; callers should run [validateSdLoras]
 * before launch and surface the resulting issue.
 */
fun List<SdLoraSpec>.activeInOrder(): List<SdLoraSpec> = filter { it.enabled }

data class SdLoraValidationIssue(
    val index: Int,
    val code: Code,
    val path: String? = null
) {
    enum class Code {
        EMPTY_PATH,
        UNREADABLE_PATH,
        INVALID_STRENGTH,
        DUPLICATE_PATH,
        INCOMPATIBLE_MODEL_TYPE,
        INCOMPATIBLE_FAMILY
    }
}

class SdLoraConfigurationException(
    val issues: List<SdLoraValidationIssue>
) : IllegalArgumentException(
    issues.joinToString(prefix = "Invalid Stable Diffusion LoRA list: ") { issue ->
        "${issue.index}:${issue.code.name.lowercase(Locale.US)}${issue.path?.let { "($it)" } ?: ""}"
    }
)

/** Validate user-provided LoRA items before building a native command. */
fun validateSdLoras(
    loras: List<SdLoraSpec>,
    requireReadableFiles: Boolean = false
): List<SdLoraSpec> {
    val issues = mutableListOf<SdLoraValidationIssue>()
    val seen = mutableSetOf<String>()
    loras.forEachIndexed { index, raw ->
        val item = raw.normalized()
        if (item.path.isBlank()) {
            issues += SdLoraValidationIssue(index, SdLoraValidationIssue.Code.EMPTY_PATH)
            return@forEachIndexed
        }
        if (!item.strength.isFinite() || raw.strength !in SdLoraSpec.MIN_STRENGTH..SdLoraSpec.MAX_STRENGTH) {
            issues += SdLoraValidationIssue(index, SdLoraValidationIssue.Code.INVALID_STRENGTH, item.path)
        }
        val key = item.path.lowercase(Locale.US)
        if (!seen.add(key)) {
            issues += SdLoraValidationIssue(index, SdLoraValidationIssue.Code.DUPLICATE_PATH, item.path)
        }
        if (requireReadableFiles && (!File(item.path).isFile || !File(item.path).canRead())) {
            issues += SdLoraValidationIssue(index, SdLoraValidationIssue.Code.UNREADABLE_PATH, item.path)
        }
    }
    if (issues.isNotEmpty()) throw SdLoraConfigurationException(issues)
    return loras.map { it.normalized() }
}

/**
 * Validate selected catalog/model rows against the base checkpoint family.
 * This is intentionally separate from file validation: a readable LoRA with
 * the wrong family must remain an explicit compatibility error, never a
 * silent fallback to another adapter.
 */
fun validateSdLoraModelCompatibility(
    baseModel: ModelEntity,
    loraModels: List<ModelEntity>
): List<SdLoraValidationIssue> {
    val (family, variant) = baseModel.resolvedSdFamily()
    val issues = mutableListOf<SdLoraValidationIssue>()
    loraModels.forEachIndexed { index, lora ->
        when {
            lora.type != ModelType.SD_LORA -> issues += SdLoraValidationIssue(
                index,
                SdLoraValidationIssue.Code.INCOMPATIBLE_MODEL_TYPE,
                lora.path
            )
            family == null || !lora.matchesSdFamily(family, variant) -> issues += SdLoraValidationIssue(
                index,
                SdLoraValidationIssue.Code.INCOMPATIBLE_FAMILY,
                lora.path
            )
        }
    }
    return issues
}

