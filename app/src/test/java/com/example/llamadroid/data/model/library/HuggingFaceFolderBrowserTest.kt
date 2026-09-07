package com.example.llamadroid.data.model.library

import com.example.llamadroid.data.api.HfModelDto
import com.example.llamadroid.data.api.HfRepoInfoDto
import com.example.llamadroid.data.api.HfSiblingDto
import com.example.llamadroid.data.api.HfTreeItemDto
import com.example.llamadroid.data.api.HuggingFaceService
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class HuggingFaceFolderBrowserTest {
    @Test fun `pins a branch to its commit with request scoped authentication`() = runBlocking {
        val service = FakeHuggingFaceService()
        val browser = HuggingFaceFolderBrowser(service)
        assertEquals("a".repeat(40), browser.resolveRevision("acme/rocket", "main", "token"))
        assertEquals(listOf("Bearer token"), service.authorizationHeaders)
        assertEquals("b".repeat(40), browser.resolveRevision("acme/rocket", "b".repeat(40), "token"))
        assertEquals(1, service.authorizationHeaders.size)
    }

    @Test
    fun `follows bounded link cursor pages with request scoped bearer`() = runBlocking {
        val service = FakeHuggingFaceService()
        val listing = HuggingFaceFolderBrowser(service).listFolder(
            repositoryId = "acme/rocket",
            folderPath = "mobile",
            bearerToken = "secret-token",
            pageSize = 1,
            maxPages = 4
        )

        assertEquals(2, listing.pagesFetched)
        assertEquals(listOf("mobile/a.litertlm", "mobile/b.litertlm"), listing.items.map { it.path })
        assertTrue(service.authorizationHeaders.all { it == "Bearer secret-token" })
    }

    @Test
    fun `load more resumes from returned cursor and appends without duplicates`() = runBlocking {
        val service = FakeHuggingFaceService()
        val browser = HuggingFaceFolderBrowser(service)
        val first = browser.listFolder(
            repositoryId = "acme/rocket",
            folderPath = "mobile",
            pageSize = 1,
            maxPages = 1
        )
        assertEquals("next", first.nextCursor)
        assertTrue(first.truncated)

        val second = browser.listFolder(
            repositoryId = "acme/rocket",
            folderPath = "mobile",
            pageSize = 1,
            maxPages = 1,
            cursor = first.nextCursor
        )
        val merged = first.appendPage(second)
        assertEquals(2, merged.pagesFetched)
        assertEquals(listOf("mobile/a.litertlm", "mobile/b.litertlm"), merged.items.map { it.path })
        assertEquals(listOf(null, "next"), service.cursors)
    }

    private class FakeHuggingFaceService : HuggingFaceService {
        val authorizationHeaders = mutableListOf<String?>()
        val cursors = mutableListOf<String?>()

        override suspend fun searchModels(query: String, filter: String?, sort: String, direction: String, limit: Int): List<HfModelDto> = emptyList()

        override suspend fun getRepoInfo(repoId: String): HfRepoInfoDto = HfRepoInfoDto(repoId, emptyList<HfSiblingDto>())

        override suspend fun getRepoRevisionInfo(repoId: String, revision: String, authorization: String?): Response<HfRepoInfoDto> {
            authorizationHeaders += authorization
            return Response.success(HfRepoInfoDto(repoId, sha = "a".repeat(40)))
        }

        override suspend fun getRepoTree(repoId: String, recursive: Boolean): List<HfTreeItemDto> = emptyList()

        override suspend fun getRepoTreePage(
            url: String,
            recursive: Boolean,
            expand: Boolean,
            limit: Int,
            cursor: String?,
            authorization: String?
        ): Response<List<HfTreeItemDto>> {
            authorizationHeaders += authorization
            cursors += cursor
            return if (cursor == null) {
                Response.success(
                    listOf(HfTreeItemDto("file", "mobile/a.litertlm", 10L)),
                    Headers.headersOf("Link", "<https://huggingface.co/api/models/acme/rocket/tree/main?cursor=next>; rel=\"next\"")
                )
            } else {
                Response.success(listOf(HfTreeItemDto("file", "mobile/b.litertlm", 11L)))
            }
        }
    }
}
