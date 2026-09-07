package com.example.llamadroid.data.model.library

import com.example.llamadroid.data.api.HfTreeItemDto
import com.example.llamadroid.data.api.HuggingFaceService
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.Response
import java.io.IOException

data class HfFolderListing(
    val repositoryId: String,
    val revision: String,
    val folderPath: String,
    val items: List<HfTreeItemDto>,
    val pagesFetched: Int,
    val nextCursor: String? = null,
    val truncated: Boolean = false
) {
    /** Appends one cursor page while preserving the visible path and deduplicating entries. */
    fun appendPage(page: HfFolderListing): HfFolderListing {
        require(repositoryId == page.repositoryId && revision == page.revision && folderPath == page.folderPath) {
            "HF folder pages must belong to the same folder"
        }
        val merged = linkedMapOf<String, HfTreeItemDto>()
        items.forEach { item -> merged[item.path.trim()] = item }
        page.items.forEach { item ->
            val key = item.path.trim()
            if (key.isNotBlank()) merged[key] = item
        }
        return copy(
            items = merged.values.sortedWith(compareBy<HfTreeItemDto> { it.type != "directory" }.thenBy { it.path }),
            pagesFetched = pagesFetched + page.pagesFetched,
            nextCursor = page.nextCursor,
            truncated = page.truncated
        )
    }
}

class HuggingFaceHttpException(
    val statusCode: Int,
    message: String
) : IOException(message) {
    val errorCode: ModelLibraryErrorCode = when (statusCode) {
        401 -> ModelLibraryErrorCode.AUTHENTICATION_REQUIRED
        403 -> ModelLibraryErrorCode.AUTHENTICATION_REJECTED
        408, 504 -> ModelLibraryErrorCode.REQUEST_TIMEOUT
        else -> ModelLibraryErrorCode.HTTP_FAILURE
    }
}

/**
 * Small, bounded HF tree browser. Authentication is request-scoped: callers
 * provide a bearer token for this call, and it is never placed in a source row
 * or a result object.
 */
class HuggingFaceFolderBrowser(
    private val service: HuggingFaceService,
    private val endpointBaseUrl: String = "https://huggingface.co/api/"
) {
    /** Pin browsing and selected downloads to the same commit whenever HF supplies one. */
    suspend fun resolveRevision(repositoryId: String, revision: String = "main", bearerToken: String? = null): String {
        require(repositoryId.matches(REPOSITORY_PATTERN)) { "Invalid Hugging Face repository ID" }
        if (revision.matches(Regex("[a-fA-F0-9]{40}"))) return revision
        val token = bearerToken?.trim()?.takeIf { it.isNotEmpty() }
        val response = service.getRepoRevisionInfo(repositoryId, revision,
            token?.let { if (it.startsWith("Bearer ", true)) it else "Bearer $it" })
        if (!response.isSuccessful) throw HuggingFaceHttpException(response.code(), "Repository revision request failed")
        return response.body()?.sha?.takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) } ?: revision
    }

    suspend fun listFolder(
        repositoryId: String,
        revision: String = "main",
        folderPath: String? = null,
        bearerToken: String? = null,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        maxPages: Int = DEFAULT_MAX_PAGES,
        cursor: String? = null
    ): HfFolderListing {
        val repo = repositoryId.trim()
        require(repo.matches(REPOSITORY_PATTERN)) { "Invalid Hugging Face repository ID" }
        val resolvedRevision = revision.trim().ifBlank { "main" }
        require(resolvedRevision.length <= MAX_REVISION_LENGTH) { "Hugging Face revision is too long" }
        val normalizedFolder = normalizeFolder(folderPath)
        val boundedPageSize = pageSize.coerceIn(1, MAX_PAGE_SIZE)
        val boundedMaxPages = maxPages.coerceIn(1, MAX_PAGES)
        var nextPageCursor: String? = cursor?.trim()?.takeIf { it.isNotBlank() }
        val seenCursors = mutableSetOf<String>().apply { nextPageCursor?.let(::add) }
        val items = linkedMapOf<String, HfTreeItemDto>()
        var pages = 0
        var truncated = false
        var nextCursor: String? = null
        val authorization = bearerToken
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith("Bearer ", ignoreCase = true)) it else "Bearer $it" }

        while (pages < boundedMaxPages) {
            val response = service.getRepoTreePage(
                url = buildTreeUrl(repo, resolvedRevision, normalizedFolder),
                recursive = false,
                expand = true,
                limit = boundedPageSize,
                cursor = nextPageCursor,
                authorization = authorization
            )
            if (!response.isSuccessful) {
                throw HuggingFaceHttpException(
                    statusCode = response.code(),
                    message = "Hugging Face folder request failed with HTTP ${response.code()}"
                )
            }
            val body = response.body().orEmpty()
            body.forEach { item ->
                val key = item.path.trim()
                if (key.isNotBlank()) items[key] = item
            }
            pages += 1
            nextCursor = nextCursorFrom(response)
            if (nextCursor.isNullOrBlank()) break
            if (!seenCursors.add(nextCursor!!)) {
                truncated = true
                nextCursor = null
                break
            }
            nextPageCursor = nextCursor
        }
        if (nextCursor != null && pages >= boundedMaxPages) truncated = true
        return HfFolderListing(
            repositoryId = repo,
            revision = resolvedRevision,
            folderPath = normalizedFolder.orEmpty(),
            items = items.values.sortedWith(compareBy<HfTreeItemDto> { it.type != "directory" }.thenBy { it.path }),
            pagesFetched = pages,
            nextCursor = nextCursor,
            truncated = truncated
        )
    }

    private fun buildTreeUrl(repositoryId: String, revision: String, folderPath: String?): String {
        val base = endpointBaseUrl.trimEnd('/').toHttpUrl()
        val builder = base.newBuilder()
            .addPathSegment("models")
        repositoryId.split('/').forEach(builder::addPathSegment)
        builder.addPathSegment("tree")
            .addPathSegment(revision)
        folderPath.orEmpty()
            .split('/')
            .filter { it.isNotBlank() }
            .forEach(builder::addPathSegment)
        return builder.build().toString()
    }

    private fun nextCursorFrom(response: Response<List<HfTreeItemDto>>): String? {
        val link = response.headers()["Link"]
            ?: response.headers()["link"]
        link?.split(',')?.firstNotNullOfOrNull { candidate ->
            val relation = candidate.substringAfter(';', "")
            if (!relation.contains("rel=\"next\"", ignoreCase = true) &&
                !relation.contains("rel=next", ignoreCase = true)
            ) {
                null
            } else {
                candidate.substringBefore(';').trim().trim('<', '>')
                    .toHttpUrlOrNullSafe()
                    ?.queryParameter("cursor")
            }
        }?.let { return it }
        return response.headers()["X-Next-Cursor"]
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun normalizeFolder(folderPath: String?): String? {
        val normalized = folderPath.orEmpty().trim().trim('/')
        if (normalized.isBlank()) return null
        require(normalized.length <= MAX_FOLDER_LENGTH) { "Hugging Face folder path is too long" }
        require(normalized.split('/').none { it == "." || it == ".." }) {
            "Hugging Face folder path contains an unsafe segment"
        }
        return normalized
    }

    private fun String.toHttpUrlOrNullSafe() = runCatching { toHttpUrl() }.getOrNull()

    companion object {
        const val DEFAULT_PAGE_SIZE = 100
        const val DEFAULT_MAX_PAGES = 20
        const val MAX_PAGE_SIZE = 1000
        const val MAX_PAGES = 100
        private const val MAX_REVISION_LENGTH = 256
        private const val MAX_FOLDER_LENGTH = 2048
        private val REPOSITORY_PATTERN = Regex("[A-Za-z0-9_.-]{1,96}/[A-Za-z0-9_.-]{1,96}")
    }
}
