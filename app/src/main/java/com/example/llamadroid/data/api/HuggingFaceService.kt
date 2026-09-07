package com.example.llamadroid.data.api

import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url
import retrofit2.Response
import kotlinx.serialization.Serializable

interface HuggingFaceService {
    @GET("models")
    suspend fun searchModels(
        @Query("search") query: String,
        @Query("filter") filter: String?,
        @Query("sort") sort: String = "downloads",
        @Query("direction") direction: String = "-1",
        @Query("limit") limit: Int = 20
    ): List<HfModelDto>
    
    @GET("models/{repoId}")
    suspend fun getRepoInfo(
        @Path("repoId", encoded = true) repoId: String
    ): HfRepoInfoDto

    @GET("models/{repoId}/revision/{revision}")
    suspend fun getRepoRevisionInfo(
        @Path("repoId", encoded = true) repoId: String,
        @Path("revision") revision: String,
        @Header("Authorization") authorization: String? = null
    ): Response<HfRepoInfoDto>
    
    @GET("models/{repoId}/tree/main")
    suspend fun getRepoTree(
        @Path("repoId", encoded = true) repoId: String,
        @Query("recursive") recursive: Boolean = false
    ): List<HfTreeItemDto>

    /**
     * Paged tree endpoint used by saved-source browsing. The URL is supplied by
     * the caller so repository revisions and folder paths can be encoded without
     * weakening the existing main-tree API used by current model screens.
     */
    @GET
    suspend fun getRepoTreePage(
        @Url url: String,
        @Query("recursive") recursive: Boolean = false,
        @Query("expand") expand: Boolean = true,
        @Query("limit") limit: Int = 100,
        @Query("cursor") cursor: String? = null,
        @Header("Authorization") authorization: String? = null
    ): Response<List<HfTreeItemDto>>
}

@Serializable
data class HfModelDto(
    val id: String, // e.g. "TheBloke/Llama-2-7b-Chat-GGUF"
    val likes: Int,
    val downloads: Int,
    val tags: List<String>
)

@Serializable
data class HfRepoInfoDto(
    val id: String,
    val siblings: List<HfSiblingDto>? = null,
    val sha: String? = null
)

@Serializable
data class HfSiblingDto(
    val rfilename: String, // The filename in the repo
    val size: Long? = null // File size in bytes
)

// Response from /tree/main endpoint - has actual file sizes
@Serializable
data class HfTreeItemDto(
    val type: String, // "file" or "directory"
    val path: String, // filename
    val size: Long = 0 // File size in bytes
)
