package com.example.llamadroid.audio.library

import java.util.Locale

/** Stable ordering choices persisted by the Audio library. */
enum class AudioLibrarySort {
    NEWEST,
    OLDEST,
    NAME_ASC,
    NAME_DESC,
    LONGEST,
    SHORTEST,
    SIZE_ASC,
    SIZE_DESC;

    companion object {
        fun fromStored(value: String?): AudioLibrarySort =
            value?.let { raw -> runCatching { valueOf(raw.uppercase(Locale.ROOT)) }.getOrNull() }
                ?: NEWEST
    }
}

data class AudioLibraryPreferences(
    val query: String = "",
    val sort: AudioLibrarySort = AudioLibrarySort.NEWEST,
    val folderId: String? = null,
    val kind: String? = null
)

data class AudioLibraryFolder(
    val id: String,
    val name: String,
    val parentId: String?,
    val createdAt: Long,
    val updatedAt: Long
)

/** Explicit policy used when removing a folder that contains audio or nested folders. */
enum class AudioLibraryFolderDeleteMode {
    MOVE_CONTENTS_TO_PARENT,
    DELETE_CONTAINED_AUDIO
}

/** A persisted output row. Paths remain local references; exports are independent copies. */
data class AudioLibraryItem(
    val id: String,
    val originKey: String,
    val title: String,
    val audioPath: String,
    val metadataPath: String?,
    val mimeType: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val folderId: String?,
    val source: String,
    val status: String,
    val modelName: String,
    val voiceName: String?,
    val language: String,
    val kind: String = "speech"
) {
    val extension: String
        get() = audioPath.substringAfterLast('.', "wav").lowercase(Locale.ROOT)
}

data class AudioLibraryPage(
    val items: List<AudioLibraryItem>,
    val page: Int,
    val pageSize: Int,
    val totalCount: Int
) {
    val hasNext: Boolean get() = (page + 1) * pageSize < totalCount
}

/** Selection is independent of the currently loaded page. */
data class AudioLibrarySelection(
    val selectedIds: Set<String> = emptySet(),
    val allMatching: Boolean = false,
    val excludedIds: Set<String> = emptySet()
) {
    fun contains(id: String): Boolean = if (allMatching) id !in excludedIds else id in selectedIds

    fun clear(): AudioLibrarySelection = AudioLibrarySelection()

    fun toggle(id: String): AudioLibrarySelection = if (allMatching) {
        copy(excludedIds = if (id in excludedIds) excludedIds - id else excludedIds + id)
    } else {
        copy(selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id)
    }
}

enum class AudioLibraryFailureCode {
    NOT_FOUND,
    FILE_MISSING,
    UNSAFE_PATH,
    IO,
    INVALID_NAME,
    ACTIVE_JOB,
    INVALID_FOLDER,
    FOLDER_NOT_EMPTY,
    CYCLE,
    CONFLICT
}

data class AudioLibraryFailure(
    val itemId: String,
    val code: AudioLibraryFailureCode,
    val detail: String? = null
)

data class AudioLibraryBatchResult(
    val succeededIds: List<String> = emptyList(),
    val failures: List<AudioLibraryFailure> = emptyList()
) {
    val isPartial: Boolean get() = succeededIds.isNotEmpty() && failures.isNotEmpty()
    val isSuccess: Boolean get() = failures.isEmpty()
}

data class AudioLibraryRenamePreview(
    val itemId: String,
    val currentTitle: String,
    val nextTitle: String
)

class AudioLibraryOperationException(
    val code: AudioLibraryFailureCode,
    message: String
) : IllegalArgumentException(message)

internal fun AudioLibraryFolderEntity.toModel(): AudioLibraryFolder = AudioLibraryFolder(
    id = id,
    name = name,
    parentId = parentId,
    createdAt = createdAt,
    updatedAt = updatedAt
)

internal fun AudioLibraryItemEntity.toModel(): AudioLibraryItem = AudioLibraryItem(
    id = id,
    originKey = originKey,
    title = title,
    audioPath = audioPath,
    metadataPath = metadataPath,
    mimeType = mimeType,
    durationMs = durationMs,
    sizeBytes = sizeBytes,
    createdAt = createdAt,
    updatedAt = updatedAt,
    folderId = folderId,
    source = source,
    status = status,
    modelName = modelName,
    voiceName = voiceName,
    language = language,
    kind = kind
)
