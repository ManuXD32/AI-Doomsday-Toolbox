package com.example.llamadroid.data.model.library

import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException

/** Stable error identifiers for localized UI copy. Diagnostic text is optional. */
enum class ModelLibraryErrorCode {
    INVALID_URL,
    WEBPAGE_LINK,
    HTTPS_REQUIRED,
    EMBEDDED_CREDENTIALS,
    CREDENTIAL_QUERY_PARAMETER,
    UNSAFE_PATH,
    INVALID_HF_REPOSITORY,
    INVALID_HF_FILE_PATH,
    UNSUPPORTED_HF_PATH,
    AUTHENTICATION_REQUIRED,
    AUTHENTICATION_REJECTED,
    HTTP_FAILURE,
    RATE_LIMITED,
    NETWORK_FAILURE,
    REQUEST_TIMEOUT,
    SOURCE_NOT_FOUND,
    SOURCE_ALREADY_SAVED,
    SOURCE_HAS_PENDING_DOWNLOAD,
    SOURCE_NOT_VERIFIED,
    RECOGNITION_FAILED,
    MANUAL_PROMOTION_REQUIRED,
    BUNDLE_INVALID,
    BUNDLE_ITEM_SOURCE_MISSING,
    BUNDLE_ITEM_PATH_INVALID,
    DOWNLOAD_FAILED,
    DOWNLOAD_TIMEOUT,
    GROUPED_ARTIFACT_RENAME_UNSUPPORTED,
    ARTIFACT_DISCARD_UNSAFE_PATH,
    ARTIFACT_DISCARD_PROTECTED,
    ARTIFACT_DISCARD_PROMOTED,
    ARTIFACT_DISCARD_FAILED,
    /** A deletion preflight found a live runtime/provenance dependency. */
    DELETION_BLOCKED,
    /** A deletion failed after the journal was opened and can be retried. */
    DELETION_RECOVERABLE,
    /** A deletion could not be committed; the journal remains inspectable. */
    DELETION_FAILED,
    AUDIO_MODEL_FILE_REQUIRED,
    AUDIO_STRUCTURE_INVALID,
    AUDIO_ARCHITECTURE_UNRESOLVED,
    AUDIO_ROLE_INVALID,
    AUDIO_ROLE_MISMATCH,
    RESPONSE_PARSING,
    INTERNAL_ERROR,
    REVISION_NOT_FOUND
}

class ModelLibraryException(
    val code: ModelLibraryErrorCode,
    override val message: String,
    val arguments: List<String> = emptyList(),
    cause: Throwable? = null
) : IllegalStateException(message, cause)

/** Maps Retrofit, HF, and transport failures to stable localized error IDs. */
fun modelLibraryErrorCode(error: Throwable): ModelLibraryErrorCode = when (error) {
    is ModelLibraryException -> error.code
    is HuggingFaceHttpException -> error.errorCode
    is HttpException -> modelLibraryHttpErrorCode(error.code())
    is kotlinx.serialization.SerializationException -> ModelLibraryErrorCode.RESPONSE_PARSING
    is com.google.gson.JsonParseException -> ModelLibraryErrorCode.RESPONSE_PARSING
    is SocketTimeoutException -> ModelLibraryErrorCode.REQUEST_TIMEOUT
    is IOException -> ModelLibraryErrorCode.NETWORK_FAILURE
    else -> ModelLibraryErrorCode.INTERNAL_ERROR
}

fun modelLibraryHttpErrorCode(statusCode: Int): ModelLibraryErrorCode = when (statusCode) {
    401 -> ModelLibraryErrorCode.AUTHENTICATION_REQUIRED
    403 -> ModelLibraryErrorCode.AUTHENTICATION_REJECTED
    404 -> ModelLibraryErrorCode.SOURCE_NOT_FOUND
    408, 504 -> ModelLibraryErrorCode.REQUEST_TIMEOUT
    429 -> ModelLibraryErrorCode.RATE_LIMITED
    in 500..599 -> ModelLibraryErrorCode.NETWORK_FAILURE
    else -> ModelLibraryErrorCode.HTTP_FAILURE
}

/** Non-content diagnostic metadata; exception messages may contain URLs, tokens or response bodies. */
fun modelLibraryFailureMetadata(error: Throwable, phase: String): String =
    "phase=${phase.filter { it.isLetterOrDigit() || it == '_' }.take(32)} " +
        "code=${modelLibraryErrorCode(error).name} type=${error.javaClass.simpleName.take(80)}"
