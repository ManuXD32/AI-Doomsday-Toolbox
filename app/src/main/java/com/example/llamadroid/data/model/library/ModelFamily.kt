package com.example.llamadroid.data.model.library

import java.util.Locale

/**
 * User-facing model families supported by the model library.
 *
 * This intentionally does not mirror [com.example.llamadroid.data.db.ModelType].
 * A family describes a bundle/source boundary, while ModelType remains the
 * existing runtime storage enum. Keeping the two separate lets the library add
 * provenance without changing persisted model primary keys or enum ordinals.
 */
enum class ModelFamily(val storedValue: String) {
    LLM("LLM"),
    SD("SD"),
    ONNX("ONNX"),
    LITERT("LITERT"),
    WHISPER("WHISPER");

    /** Stable lower-case key for storage/inventory filters. */
    val storageValue: String
        get() = storedValue.lowercase(Locale.US)

    companion object {
        fun fromStoredValue(value: String?): ModelFamily? = entries.firstOrNull {
            it.storedValue.equals(value?.trim(), ignoreCase = true) ||
                it.name.equals(value?.trim(), ignoreCase = true)
        }
    }
}

enum class ModelSourceKind(val storedValue: String) {
    HUGGING_FACE_REPOSITORY("HF_REPOSITORY"),
    HUGGING_FACE_FILE("HF_FILE"),
    HTTPS("HTTPS");

    companion object {
        fun fromStoredValue(value: String?): ModelSourceKind? = entries.firstOrNull {
            it.storedValue.equals(value?.trim(), ignoreCase = true) ||
                it.name.equals(value?.trim(), ignoreCase = true)
        }
    }
}

enum class PendingArtifactStatus(val storedValue: String) {
    STAGED("STAGED"),
    INSPECTING("INSPECTING"),
    NEEDS_MANUAL_PROMOTION("NEEDS_MANUAL_PROMOTION"),
    VALIDATED("VALIDATED"),
    PROMOTED("PROMOTED"),
    REJECTED("REJECTED"),
    FAILED("FAILED"),
    CANCELLED("CANCELLED");

    companion object {
        fun fromStoredValue(value: String?): PendingArtifactStatus? = entries.firstOrNull {
            it.storedValue.equals(value?.trim(), ignoreCase = true) ||
                it.name.equals(value?.trim(), ignoreCase = true)
        }
    }
}

/** A bundle component's role is deliberately free-form for future companions. */
data class ModelLibraryRole(val value: String) {
    init {
        require(value.trim().isNotEmpty()) { "A model library role cannot be blank" }
    }

    override fun toString(): String = value.trim()
}
