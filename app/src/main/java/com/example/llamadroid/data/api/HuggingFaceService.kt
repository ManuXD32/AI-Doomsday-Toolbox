package com.example.llamadroid.data.api

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
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
    
    @GET("models/{repoId}/tree/main")
    suspend fun getRepoTree(
        @Path("repoId", encoded = true) repoId: String,
        @Query("recursive") recursive: Boolean = false
    ): List<HfTreeItemDto>
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
    val siblings: List<HfSiblingDto>? = null
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
