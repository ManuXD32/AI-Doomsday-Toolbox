package com.example.llamadroid.data.model.library

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/** Exercises the production Retrofit/serialization factory with captured public HTTP responses. */
class HuggingFaceHttpFixtureTest {
    private data class Reply(val body: String, val code: Int = 200, val link: String? = null)
    private fun fixture(name: String): String = javaClass.classLoader!!
        .getResourceAsStream("model-library/http/$name")!!.bufferedReader().use { it.readText() }

    private fun browser(replies: List<Reply>, requests: MutableList<Request> = mutableListOf()): HuggingFaceFolderBrowser {
        val pending = ConcurrentLinkedQueue(replies)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            synchronized(requests) { requests.add(chain.request()) }
            val reply = checkNotNull(pending.poll()) { "Unexpected HTTP request" }
            okhttp3.Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(reply.code).message("fixture")
                .body(reply.body.toResponseBody("application/json".toMediaType()))
                .apply { reply.link?.let { header("Link", it) } }.build()
        }.build()
        return HuggingFaceFolderBrowser(ModelLibraryRepositoryFactory.createService("https://fixture.invalid/api/", client),
            "https://fixture.invalid/api/")
    }

    @Test fun `both model screen repositories resolve and parse real expanded root responses`() = runBlocking {
        for ((name, repo) in listOf("sd" to "Comfy-Org/stable-diffusion-v1-5-archive",
            "llama" to "MaziyarPanahi/Mistral-7B-Instruct-v0.3-GGUF")) {
            val requests = mutableListOf<Request>()
            val browser = browser(listOf(Reply(fixture("$name-revision.json")), Reply(fixture("$name-root.json"))), requests)
            val commit = browser.resolveRevision(repo)
            assertEquals(40, commit.length)
            val root = browser.listFolder(repo, commit)
            assertTrue(root.items.isNotEmpty())
            assertTrue(root.items.all { it.path.isNotBlank() })
            assertEquals("/api/models/$repo/tree/$commit", requests.last().url.encodedPath)
        }
    }

    @Test fun `nested folders and real cursor pages retain stable file identities`() = runBlocking {
        val folder = browser(listOf(Reply(fixture("pocket-folder.json"))))
            .listFolder("EryriLabs/pocket-tts-GGUF", "daf229a0492829811624c34a44b92d7738c158c8", "spanish")
        assertTrue(folder.items.all { it.path.startsWith("spanish/") })
        val requests = mutableListOf<Request>()
        val browser = browser(listOf(Reply(fixture("llama-page1.json"), link = fixture("llama-page1-link.txt")),
            Reply(fixture("llama-page2.json"))), requests)
        val first = browser.listFolder("MaziyarPanahi/Mistral-7B-Instruct-v0.3-GGUF", pageSize = 1, maxPages = 1)
        assertNotNull(first.nextCursor)
        val second = browser.listFolder(first.repositoryId, pageSize = 1, maxPages = 1, cursor = first.nextCursor)
        assertEquals(first.nextCursor, requests.last().url.queryParameter("cursor"))
        assertEquals(2, first.appendPage(second).items.size)
    }

    @Test fun `invalid body is a parsing error and HTTP failures retain their cause`() = runBlocking {
        val invalid = runCatching { browser(listOf(Reply("{\"unexpected\":true}"))).listFolder("owner/repo") }.exceptionOrNull()!!
        assertEquals(ModelLibraryErrorCode.RESPONSE_PARSING, modelLibraryErrorCode(invalid))
        for ((status, expected) in listOf(401 to ModelLibraryErrorCode.AUTHENTICATION_REQUIRED,
            404 to ModelLibraryErrorCode.SOURCE_NOT_FOUND, 429 to ModelLibraryErrorCode.RATE_LIMITED)) {
            val error = runCatching { browser(listOf(Reply("{}", status))).listFolder("owner/repo") }.exceptionOrNull()!!
            assertEquals(expected, modelLibraryErrorCode(error))
        }
    }
}
