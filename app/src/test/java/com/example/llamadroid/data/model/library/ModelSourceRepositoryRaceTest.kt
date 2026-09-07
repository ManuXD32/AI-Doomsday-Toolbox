package com.example.llamadroid.data.model.library

import com.example.llamadroid.data.db.ModelLibraryDao
import com.example.llamadroid.data.db.ModelSourceEntity
import com.example.llamadroid.data.db.PendingModelArtifactEntity
import io.mockk.coVerify
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flowOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSourceRepositoryRaceTest {
    @Test
    fun `late verification cannot overwrite a source edited during the request`() = runBlocking {
        lateinit var fixture: Fixture
        fixture = Fixture { request ->
            fixture.current = fixture.current.copy(
                label = "New source",
                url = "https://fixture.example/new.gguf",
                normalizedKey = "new-key",
                verified = false,
                validationStatus = "needs_check"
            )
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(206)
                .message("Partial")
                .header("Content-Range", "bytes 0-3/4")
                .body("GGUF".toResponseBody("application/octet-stream".toMediaType()))
                .build()
        }

        val result = fixture.repository.verifySource("fixture")

        assertTrue(result.isFailure)
        assertEquals(ModelLibraryErrorCode.SOURCE_NOT_VERIFIED,
            (result.exceptionOrNull() as ModelLibraryException).code)
        assertEquals("New source", fixture.current.label)
        assertEquals("https://fixture.example/new.gguf", fixture.current.url)
        assertFalse(fixture.current.verified)
        assertEquals("needs_check", fixture.current.validationStatus)
    }

    @Test
    fun `verification evidence preserves a concurrent label edit for the same source`() = runBlocking {
        lateinit var fixture: Fixture
        fixture = Fixture { request ->
            fixture.current = fixture.current.copy(label = "Renamed source")
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(206)
                .message("Partial")
                .header("Content-Range", "bytes 0-3/4")
                .body("GGUF".toResponseBody("application/octet-stream".toMediaType()))
                .build()
        }

        val verified = fixture.repository.verifySource("fixture").getOrThrow()

        assertEquals("Renamed source", verified.label)
        assertTrue(verified.verified)
        assertEquals("verified", verified.validationStatus)
        assertEquals(4L, verified.expectedSizeBytes)
    }

    @Test
    fun `startup recovery requires both verified flag and persisted status`() {
        val source = ModelSourceEntity(
            id = "source",
            kind = ModelSourceKind.HTTPS.storedValue,
            family = ModelFamily.LLM.storedValue,
            label = "Source",
            url = "https://fixture.example/model.gguf",
            normalizedKey = "source"
        )

        assertFalse(sourceIsVerifiedForRecovery(source.copy(verified = true)))
        assertFalse(sourceIsVerifiedForRecovery(source.copy(validationStatus = "verified")))
        assertTrue(sourceIsVerifiedForRecovery(source.copy(verified = true, validationStatus = "verified")))
    }

    @Test
    fun `retry detects old task URL or persisted identity invalidation before resuming`() {
        val row = PendingModelArtifactEntity(
            id = "pending",
            filename = "model.gguf",
            stagingPath = "/tmp/model.gguf"
        )

        assertFalse(pendingSourceIdentityChanged(row, "https://fixture.example/model.gguf",
            "https://fixture.example/model.gguf"))
        assertTrue(pendingSourceIdentityChanged(row, "https://fixture.example/old.gguf",
            "https://fixture.example/model.gguf"))
        assertTrue(pendingSourceIdentityChanged(
            row.copy(validationJson = SOURCE_IDENTITY_INVALIDATED_MARKER),
            "https://fixture.example/model.gguf",
            "https://fixture.example/model.gguf"
        ))
    }

    @Test
    fun `source edit marks terminal pending payload identity stale without dropping its row`() = runBlocking {
        val source = ModelSourceEntity(
            id = "source",
            kind = ModelSourceKind.HTTPS.storedValue,
            family = ModelFamily.LLM.storedValue,
            label = "Old source",
            url = "https://fixture.example/old.gguf",
            normalizedKey = "old-key",
            verified = false,
            validationStatus = "needs_check"
        )
        val pending = PendingModelArtifactEntity(
            id = "pending",
            sourceId = source.id,
            filename = "old.gguf",
            stagingPath = "/tmp/old.gguf",
            status = PendingArtifactStatus.CANCELLED.storedValue
        )
        var observedPending = pending
        val dao = mockk<ModelLibraryDao>(relaxed = true).also { mock ->
            coEvery { mock.getByNormalizedKey(any()) } returns null
            coEvery { mock.getSourceById(source.id) } returns source
            every { mock.observePendingArtifacts() } answers { flowOf(listOf(observedPending)) }
            coEvery { mock.replaceSourceIdentity(any()) } returns Unit
            coEvery { mock.upsert(any<PendingModelArtifactEntity>()) } answers {
                observedPending = firstArg()
                Unit
            }
        }
        val repository = ModelSourceRepository(dao, mockk(relaxed = true))

        val changed = repository.saveSource(
            ModelSourceDraft(
                family = ModelFamily.LLM,
                url = "https://fixture.example/new.gguf",
                id = source.id
            )
        ).getOrThrow()

        assertEquals("https://fixture.example/new.gguf", changed.url)
        assertEquals(pending.id, observedPending.id)
        coVerify(exactly = 1) { dao.replaceSourceIdentity(changed) }
        // The real DAO transaction and pending marker are covered by ModelSourceIdentityTest.
    }

    private class Fixture(responseFactory: (okhttp3.Request) -> Response) {
        var current = ModelSourceEntity(
            id = "fixture",
            kind = ModelSourceKind.HTTPS.storedValue,
            family = ModelFamily.LLM.storedValue,
            label = "Fixture",
            url = "https://fixture.example/model.gguf",
            normalizedKey = "fixture",
            verified = true,
            validationStatus = "verified"
        )
        private val dao = mockk<ModelLibraryDao>(relaxed = true).also { dao ->
            coEvery { dao.getSourceById("fixture") } answers { current }
            coEvery { dao.upsert(any<ModelSourceEntity>()) } answers {
                current = firstArg()
                Unit
            }
        }
        val repository = ModelSourceRepository(
            dao,
            mockk(relaxed = true),
            OkHttpClient.Builder().addInterceptor { responseFactory(it.request()) }.build()
        )
    }
}
