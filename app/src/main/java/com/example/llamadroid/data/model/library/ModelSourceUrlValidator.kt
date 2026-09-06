package com.example.llamadroid.data.model.library

import com.example.llamadroid.data.db.ModelSourceEntity
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/** A user-entered source draft before it is persisted. */
data class ModelSourceDraft(
    val family: ModelFamily,
    val url: String,
    val label: String? = null,
    val expectedSha256: String? = null,
    val expectedSizeBytes: Long? = null,
    val mediaType: String? = null,
    val id: String? = null
)

data class ValidatedModelSource(
    val family: ModelFamily,
    val kind: ModelSourceKind,
    val normalizedUrl: String,
    val normalizedKey: String,
    val repositoryId: String? = null,
    val revision: String = "main",
    val filePath: String? = null,
    val label: String,
    val authRequired: Boolean
)

data class SourceValidationResult(
    val source: ValidatedModelSource? = null,
    val error: String? = null,
    val errorCode: ModelLibraryErrorCode? = null
) {
    val isValid: Boolean get() = source != null && error == null
}

/**
 * Validates and normalizes links before they become durable source metadata.
 * Only HTTPS is accepted. HF links are classified into repository/folder and
 * file sources so the browser and downloader can use the correct endpoint.
 */
object ModelSourceUrlValidator {
    private val hfHosts = setOf("huggingface.co", "www.huggingface.co", "hf.co", "www.hf.co")
    private val sensitiveQueryKeys = setOf(
        "token", "access_token", "authorization", "auth", "api_key", "apikey",
        "secret", "password", "passwd", "signature", "x-amz-signature",
        "x-amz-credential", "x-amz-security-token"
    )

    fun validate(draft: ModelSourceDraft): SourceValidationResult {
        val raw = draft.url.trim()
        if (raw.length !in 8..4096) {
            return SourceValidationResult(
                error = "Source URL length is outside the supported range",
                errorCode = ModelLibraryErrorCode.INVALID_URL
            )
        }
        val parsed = raw.toHttpUrlOrNull()
            ?: return SourceValidationResult(
                error = "Enter a valid HTTPS URL",
                errorCode = ModelLibraryErrorCode.INVALID_URL
            )
        if (parsed.scheme.lowercase(Locale.US) != "https") {
            return SourceValidationResult(
                error = "Only HTTPS source links are supported",
                errorCode = ModelLibraryErrorCode.HTTPS_REQUIRED
            )
        }
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            return SourceValidationResult(
                error = "Source links cannot contain embedded credentials",
                errorCode = ModelLibraryErrorCode.EMBEDDED_CREDENTIALS
            )
        }
        if (parsed.host.isBlank()) {
            return SourceValidationResult(
                error = "Source link is missing a host",
                errorCode = ModelLibraryErrorCode.INVALID_URL
            )
        }
        val unsafeQuery = parsed.queryParameterNames.firstOrNull { key ->
            key.lowercase(Locale.US) in sensitiveQueryKeys
        }
        if (unsafeQuery != null) {
            return SourceValidationResult(
                error = "Source links cannot persist credential query parameters",
                errorCode = ModelLibraryErrorCode.CREDENTIAL_QUERY_PARAMETER
            )
        }
        if (parsed.pathSegments.any { it == "." || it == ".." }) {
            return SourceValidationResult(
                error = "Source path contains an unsafe segment",
                errorCode = ModelLibraryErrorCode.UNSAFE_PATH
            )
        }

        val source = if (parsed.host.lowercase(Locale.US) in hfHosts) {
            parseHuggingFace(parsed, draft)
        } else {
            parseHttps(parsed, draft)
        }
        return source.fold(
            onSuccess = { SourceValidationResult(source = it) },
            onFailure = {
                SourceValidationResult(
                    error = it.message ?: "Source URL is invalid",
                    errorCode = (it as? SourceUrlException)?.code ?: when {
                        parsed.host.lowercase(Locale.US) in hfHosts -> ModelLibraryErrorCode.INVALID_HF_REPOSITORY
                        else -> ModelLibraryErrorCode.INVALID_URL
                    }
                )
            }
        )
    }

    fun toEntity(
        draft: ModelSourceDraft,
        now: Long = System.currentTimeMillis()
    ): Result<ModelSourceEntity> {
        val validation = validate(draft)
        val source = validation.source ?: return Result.failure(
            ModelLibraryException(
                code = validation.errorCode ?: ModelLibraryErrorCode.INVALID_URL,
                message = validation.error ?: "Source URL is invalid"
            )
        )
        return Result.success(
            ModelSourceEntity(
                id = draft.id ?: java.util.UUID.randomUUID().toString(),
                kind = source.kind.storedValue,
                family = source.family.storedValue,
                label = source.label,
                url = source.normalizedUrl,
                normalizedKey = source.normalizedKey,
                repositoryId = source.repositoryId,
                revision = source.revision,
                filePath = source.filePath,
                authRequired = source.authRequired,
                verified = false,
                expectedSha256 = draft.expectedSha256?.trim()?.lowercase(Locale.US)
                    ?.takeIf { it.matches(Regex("[a-f0-9]{64}")) },
                expectedSizeBytes = draft.expectedSizeBytes?.takeIf { it >= 0L },
                mediaType = draft.mediaType?.trim()?.takeIf { it.isNotBlank() },
                createdAt = now,
                updatedAt = now
            )
        )
    }

    private fun parseHuggingFace(
        parsed: HttpUrl,
        draft: ModelSourceDraft
    ): Result<ValidatedModelSource> {
        val segments = parsed.pathSegments.filter { it.isNotBlank() }
        if (segments.size < 2) {
            return Result.failure(SourceUrlException(ModelLibraryErrorCode.INVALID_HF_REPOSITORY, "Hugging Face links must include an owner and repository"))
        }
        val repositoryId = "${segments[0]}/${segments[1]}"
        if (!repositoryId.matches(Regex("[A-Za-z0-9_.-]{1,96}/[A-Za-z0-9_.-]{1,96}"))) {
            return Result.failure(SourceUrlException(ModelLibraryErrorCode.INVALID_HF_REPOSITORY, "Hugging Face repository ID is invalid"))
        }
        val suffix = segments.drop(2)
        var kind = ModelSourceKind.HUGGING_FACE_REPOSITORY
        var revision = "main"
        var filePath: String? = null
        if (suffix.isNotEmpty()) {
            when (suffix[0].lowercase(Locale.US)) {
                "resolve", "blob" -> {
                    if (suffix.size < 3) {
                        return Result.failure(SourceUrlException(ModelLibraryErrorCode.INVALID_HF_FILE_PATH, "Hugging Face file links need a revision and file path"))
                    }
                    kind = ModelSourceKind.HUGGING_FACE_FILE
                    revision = suffix[1]
                    filePath = suffix.drop(2).joinToString("/").takeIf { it.isNotBlank() }
                }
                "tree" -> {
                    if (suffix.size >= 2) {
                        revision = suffix[1]
                        filePath = suffix.drop(2).joinToString("/").takeIf { it.isNotBlank() }
                    }
                }
                "commit" -> {
                    if (suffix.size >= 2) {
                        revision = suffix[1]
                        filePath = suffix.drop(2).joinToString("/").takeIf { it.isNotBlank() }
                    }
                }
                else -> {
                    return Result.failure(SourceUrlException(ModelLibraryErrorCode.UNSUPPORTED_HF_PATH, "Unsupported Hugging Face link path"))
                }
            }
        }
        val normalized = canonicalUrl(parsed)
        val label = draft.label?.trim()?.takeIf { it.isNotBlank() }
            ?: filePath?.substringAfterLast('/')
            ?: repositoryId
        return Result.success(
            ValidatedModelSource(
                family = draft.family,
                kind = kind,
                normalizedUrl = normalized,
                normalizedKey = buildKey(kind, repositoryId, revision, filePath, normalized),
                repositoryId = repositoryId,
                revision = revision.ifBlank { "main" },
                filePath = filePath,
                label = label,
                authRequired = false
            )
        )
    }

    private fun parseHttps(
        parsed: HttpUrl,
        draft: ModelSourceDraft
    ): Result<ValidatedModelSource> {
        val path = parsed.encodedPath.trim('/').takeIf { it.isNotBlank() }
            ?: return Result.failure(SourceUrlException(ModelLibraryErrorCode.INVALID_URL, "HTTPS source link needs a path"))
        val normalized = canonicalUrl(parsed)
        val label = draft.label?.trim()?.takeIf { it.isNotBlank() }
            ?: path.substringAfterLast('/').ifBlank { parsed.host }
        return Result.success(
            ValidatedModelSource(
                family = draft.family,
                kind = ModelSourceKind.HTTPS,
                normalizedUrl = normalized,
                normalizedKey = buildKey(ModelSourceKind.HTTPS, null, null, path, normalized),
                filePath = path,
                label = label,
                authRequired = false
            )
        )
    }

    private fun canonicalUrl(parsed: HttpUrl): String = parsed.newBuilder()
        .fragment(null)
        .build()
        .toString()
        .removeSuffix("/")

    private fun buildKey(
        kind: ModelSourceKind,
        repositoryId: String?,
        revision: String?,
        filePath: String?,
        normalizedUrl: String
    ): String = listOf(
        // URL identity is independent of the selected runtime family. The
        // same saved link can be reused by an explicitly compatible bundle
        // role without creating a duplicate source row.
        kind.storedValue,
        repositoryId.orEmpty(),
        revision.orEmpty(),
        filePath.orEmpty(),
        normalizedUrl
    ).joinToString("|")

    private class SourceUrlException(
        val code: ModelLibraryErrorCode,
        message: String
    ) : IllegalArgumentException(message)
}
