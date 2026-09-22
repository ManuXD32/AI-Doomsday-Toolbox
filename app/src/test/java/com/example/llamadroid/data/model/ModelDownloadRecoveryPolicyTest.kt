package com.example.llamadroid.data.model

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelDownloadRecoveryPolicyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun completedCopyBecomesEligibleOnlyAfterItsPartialIsGone() {
        val file = File(temporary.newFolder(), "maple.gguf")
        val partial = downloadPartFile(file.path)
        partial.writeBytes(header())
        assertFalse(isRecoverableGgufFile(partial))
        file.writeBytes(header())
        assertFalse(isRecoverableGgufFile(file))
        assertTrue(partial.delete())
        assertTrue(isRecoverableGgufFile(file))
    }

    @Test fun shortInvalidAndEmptyTensorHeadersAreRejected() {
        val file = temporary.newFile("maple.gguf")
        listOf("GGUFx".toByteArray(), header(version = 99), header(tensors = 0), ByteArray(64)).forEach {
            file.writeBytes(it)
            assertFalse(isRecoverableGgufFile(file))
        }
    }

    @Test fun sameFilenameFromAnotherRepositoryDoesNotShadowRequestedModel() {
        val file = temporary.newFile("maple.gguf").apply { writeBytes(header()) }
        val model = ModelEntity(file.name, file.path, file.length(), ModelType.LLM, "deepgrove/maple", true)
        assertTrue(matchesCatalogModel(model, "deepgrove/maple", file.name, ModelType.LLM))
        assertFalse(matchesCatalogModel(model, "different/maple", file.name, ModelType.LLM))
        assertFalse(matchesCatalogModel(model.copy(repoId = "local-import"), "deepgrove/maple", file.name, ModelType.LLM))
        assertTrue(matchesCatalogModel(model.copy(filename = "maple-123.gguf",
            repoId = "https://huggingface.co/deepgrove/maple/resolve/main/maple.gguf"),
            "deepgrove/maple", "maple.gguf", ModelType.LLM))
    }

    @Test fun cancellationAfterPersistenceStillHandsOwnershipToTheService() = runBlocking {
        val persisted = CompletableDeferred<Unit>()
        val resumeHandoff = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val caller = launch {
            handoffModelDownload {
                events += "persist"
                persisted.complete(Unit)
                resumeHandoff.await()
                events += "enqueue_service"
            }
        }
        persisted.await()
        caller.cancel()
        resumeHandoff.complete(Unit)
        caller.join()
        assertEquals(listOf("persist", "enqueue_service"), events)
        assertTrue(caller.isCancelled)
    }

    @Test fun alreadyCancelledPickerCannotAcceptANewDownload() = runBlocking {
        var accepted = false
        val caller = launch {
            currentCoroutineContext().cancel()
            handoffModelDownload { accepted = true }
        }
        caller.join()
        assertFalse(accepted)
    }

    private fun header(version: Int = 3, tensors: Long = 1): ByteArray =
        ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
            .put(byteArrayOf(71, 71, 85, 70)).putInt(version).putLong(tensors).putLong(1).array()
}
