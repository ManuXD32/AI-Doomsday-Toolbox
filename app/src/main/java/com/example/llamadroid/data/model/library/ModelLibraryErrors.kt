package com.example.llamadroid.data.model.library

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
    ARTIFACT_DISCARD_FAILED
}

class ModelLibraryException(
    val code: ModelLibraryErrorCode,
    override val message: String,
    val arguments: List<String> = emptyList(),
    cause: Throwable? = null
) : IllegalStateException(message, cause)
