package com.example.llamadroid.data.model.library

import com.example.llamadroid.data.db.ModelType
import org.json.JSONArray
import org.json.JSONObject

/**
 * The source of the effective model classification. The stored values are
 * deliberately stable strings because these values are also copied into
 * portable metadata and durable download rows.
 */
enum class ModelClassificationSource(val storedValue: String) {
    AUTO("AUTO"),
    CATALOG("CATALOG"),
    USER_OVERRIDE("USER_OVERRIDE"),
    LEGACY("LEGACY");

    companion object {
        fun fromStoredValue(value: String?): ModelClassificationSource? = entries.firstOrNull {
            it.storedValue.equals(value?.trim(), ignoreCase = true) ||
                it.name.equals(value?.trim(), ignoreCase = true)
        }
    }
}

/** A bounded, serializable classification snapshot used by all model stores. */
data class ModelClassification(
    val family: ModelFamily? = null,
    val type: ModelType? = null,
    val role: String? = null,
    val capabilities: Set<String> = emptySet()
)

data class ModelClassificationResolution(
    val detected: ModelClassification?,
    val selected: ModelClassification?,
    val source: ModelClassificationSource,
    val warning: String? = null,
    val integrityFailure: String? = null
) {
    val isBlocked: Boolean get() = !integrityFailure.isNullOrBlank()
    val hasSemanticDisagreement: Boolean get() = !warning.isNullOrBlank()
}

/**
 * Shared rules for model classification persistence and override handling.
 *
 * Structural evidence and payload integrity are intentionally separate from
 * semantic classification. A valid file may be assigned to a different
 * compatible runtime role after the user confirms the warning; an absent,
 * empty, unreadable, corrupt, or checksum-invalid file may not.
 */
object ModelClassificationPolicy {
    const val MAX_DETECTED_EVIDENCE_LENGTH = 16_384
    const val MAX_CLASSIFICATION_VALUE_LENGTH = 256

    fun sourceOrLegacy(value: String?, hasDetectedEvidence: Boolean): ModelClassificationSource =
        ModelClassificationSource.fromStoredValue(value)
            ?: if (hasDetectedEvidence) ModelClassificationSource.AUTO else ModelClassificationSource.LEGACY

    fun evidenceJson(
        detected: ModelClassification?,
        confidence: String? = null,
        validationMessage: String? = null,
        rawJson: String? = null
    ): String? {
        val raw = rawJson?.let(::boundedEvidence)?.takeIf { it != "{}" }
        if (
            raw == null &&
            detected == null &&
            confidence.isNullOrBlank() &&
            validationMessage.isNullOrBlank()
        ) return null
        return boundedEvidence(JSONObject(raw ?: "{}").apply {
            // Inspector-specific JSON is retained, but the normalized keys
            // below are always present when known so every manager can render
            // and restore the same detected classification.
            detected?.family?.storedValue?.let { put("family", it) }
            detected?.type?.name?.let { put("type", it) }
            detected?.role?.trim()?.takeIf { it.isNotBlank() }?.let { put("role", it) }
            detected?.capabilities?.takeIf { it.isNotEmpty() }?.let {
                put("capabilities", it.toList().sorted())
            }
            confidence?.trim()?.takeIf { it.isNotBlank() }?.let { put("confidence", it) }
            validationMessage?.trim()?.takeIf { it.isNotBlank() }?.let {
                put("validationMessage", it.take(MAX_CLASSIFICATION_VALUE_LENGTH))
            }
        }.toString())
    }

    fun boundedEvidence(raw: String?): String {
        if (raw.isNullOrBlank()) return "{}"
        val parsed = runCatching { JSONObject(raw) }.getOrNull() ?: return "{}"
        val serialized = parsed.toString()
        if (serialized.length <= MAX_DETECTED_EVIDENCE_LENGTH) return serialized

        // Never cut serialized JSON at an arbitrary character: persisted
        // evidence must remain parseable after Room/export round trips. Keep a
        // bounded scalar/array summary and record that large inspector details
        // were intentionally omitted.
        val summary = JSONObject()
        parsed.keys().asSequence().toList().sorted().forEach { key ->
            when (val value = parsed.opt(key)) {
                is String -> summary.put(key, value.take(MAX_CLASSIFICATION_VALUE_LENGTH))
                is Number, is Boolean -> summary.put(key, value)
                is JSONArray -> summary.put(
                    key,
                    JSONArray().apply {
                        repeat(minOf(value.length(), 32)) { index ->
                            when (val item = value.opt(index)) {
                                is String -> put(item.take(MAX_CLASSIFICATION_VALUE_LENGTH))
                                is Number, is Boolean -> put(item)
                            }
                        }
                    }
                )
            }
        }
        summary.put("evidenceTruncated", true)
        summary.put("originalLength", serialized.length)
        return summary.toString().takeIf { it.length <= MAX_DETECTED_EVIDENCE_LENGTH }
            ?: JSONObject()
                .put("evidenceTruncated", true)
                .put("originalLength", serialized.length)
                .toString()
    }

    fun resolve(
        detected: ModelClassification?,
        selected: ModelClassification?,
        source: ModelClassificationSource = if (selected == null) {
            ModelClassificationSource.AUTO
        } else {
            ModelClassificationSource.USER_OVERRIDE
        },
        integrityFailure: String? = null
    ): ModelClassificationResolution {
        val disagreement = if (detected != null && selected != null && detected != selected) {
            "Selected classification differs from detected artifact evidence"
        } else {
            null
        }
        return ModelClassificationResolution(
            detected = detected,
            selected = selected ?: detected,
            source = source,
            warning = disagreement,
            integrityFailure = integrityFailure
        )
    }

    fun warningFor(
        detectedFamily: String?,
        selectedFamily: String?,
        detectedType: String?,
        selectedType: String?,
        detectedRole: String?,
        selectedRole: String?
    ): String? {
        val familyDiffers = detectedFamily.isMeaningfullyDifferentFrom(selectedFamily)
        val typeDiffers = detectedType.isMeaningfullyDifferentFrom(selectedType)
        val roleDiffers = normalizedModelLibraryRole(detectedRole) != normalizedModelLibraryRole(selectedRole) &&
            !detectedRole.isNullOrBlank() && !selectedRole.isNullOrBlank()
        return if (familyDiffers || typeDiffers || roleDiffers) {
            "Selected classification differs from detected artifact evidence"
        } else {
            null
        }
    }

    private fun String?.isMeaningfullyDifferentFrom(other: String?): Boolean =
        !this.isNullOrBlank() && !other.isNullOrBlank() && !this.equals(other, ignoreCase = true)
}

fun ArtifactRecognitionResult.classificationEvidenceJson(): String? {
    val detectedType = detectedType?.let { value ->
        ModelType.entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
    }
    return ModelClassificationPolicy.evidenceJson(
        detected = if (family == null && detectedType == null && role.isNullOrBlank()) {
            null
        } else {
            ModelClassification(
                family = family,
                type = detectedType,
                role = role
            )
        },
        confidence = confidence.name,
        validationMessage = validationMessage,
        rawJson = validationJson
    )
}

fun String?.classificationSourceOrLegacy(hasDetectedEvidence: Boolean): ModelClassificationSource =
    ModelClassificationPolicy.sourceOrLegacy(this, hasDetectedEvidence)
