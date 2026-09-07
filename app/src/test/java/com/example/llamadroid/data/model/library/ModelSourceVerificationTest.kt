package com.example.llamadroid.data.model.library

import com.example.llamadroid.data.db.ModelLibraryDao
import com.example.llamadroid.data.db.ModelSourceEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ModelSourceVerificationTest {
    @Test fun failedRecheckClearsVerifiedStatusAndPersistsWhy() = runBlocking {
        for ((code, state, error) in listOf(
            Triple(404, "unavailable", ModelLibraryErrorCode.SOURCE_NOT_FOUND),
            Triple(401, "authentication", ModelLibraryErrorCode.AUTHENTICATION_REQUIRED),
            Triple(403, "authentication", ModelLibraryErrorCode.AUTHENTICATION_REJECTED)
        )) {
            val fixture = Fixture { request -> Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("Fixture").body("error".toResponseBody()).build() }
            assertTrue(fixture.repository.verifySource("fixture").isFailure)
            assertFalse(fixture.current.verified)
            assertEquals(state, fixture.current.validationStatus)
            assertEquals(error.name, fixture.current.lastErrorCode)
            assertNotNull(fixture.current.checkedAt)
        }
    }

    @Test fun webpageRejectedButBinarySourceQueuesWithBoundedRequestAndNoForeignToken() = runBlocking {
        val page = Fixture { request -> Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK").body("<!doctype html><html>File page</html>".toResponseBody("text/html".toMediaType())).build() }
        assertEquals(ModelLibraryErrorCode.WEBPAGE_LINK,
            (page.repository.verifySource("fixture").exceptionOrNull() as ModelLibraryException).code)
        val file = Fixture { request ->
            assertEquals("bytes=0-1023", request.header("Range"))
            assertNull(request.header("Authorization"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(206).message("Partial")
                .header("Content-Range", "bytes 0-3/4096").body("GGUF".toResponseBody("application/octet-stream".toMediaType())).build()
        }
        val verified = file.repository.verifySource("fixture", "hf_fixture_secret").getOrThrow()
        assertTrue(verified.verified)
        assertEquals(4096L, verified.expectedSizeBytes)
        assertEquals("verified", verified.validationStatus)
    }

    @Test fun offlineSourceNeedsCheckingAndNeverRetainsOldVerification() = runBlocking {
        val fixture = Fixture { throw IOException("Fixture offline") }
        assertTrue(fixture.repository.verifySource("fixture").isFailure)
        assertEquals("needs_check", fixture.current.validationStatus)
        assertFalse(fixture.current.verified)
    }

    private class Fixture(response: (okhttp3.Request) -> Response) {
        var current = ModelSourceEntity(id = "fixture", kind = ModelSourceKind.HTTPS.storedValue,
            family = ModelFamily.LLM.storedValue, label = "Fixture", url = "https://fixture.example/model.gguf",
            normalizedKey = "fixture", verified = true, validationStatus = "verified")
        private val dao = mockk<ModelLibraryDao>(relaxed = true).also { dao ->
            coEvery { dao.getSourceById("fixture") } answers { current }
            coEvery { dao.upsert(any<ModelSourceEntity>()) } answers { current = firstArg(); Unit }
        }
        val repository = ModelSourceRepository(dao, mockk(relaxed = true),
            OkHttpClient.Builder().addInterceptor { response(it.request()) }.build())
    }
}
