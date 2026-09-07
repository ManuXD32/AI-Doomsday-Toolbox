package com.example.llamadroid.data.model.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSourceUrlValidatorTest {
    @Test fun `download URL retains encoded branch and nested filename`() {
        val source = com.example.llamadroid.data.db.ModelSourceEntity(
            kind = ModelSourceKind.HUGGING_FACE_FILE.storedValue, family = ModelFamily.LLM.storedValue,
            label = "model.gguf", url = "https://huggingface.co/owner/repo/blob/feature%2Fvideo/nested/my%20model.gguf",
            normalizedKey = "fixture", repositoryId = "owner/repo", revision = "feature/video",
            filePath = "nested/my model.gguf")
        assertEquals("https://huggingface.co/owner/repo/resolve/feature%2Fvideo/nested/my%20model.gguf", source.resolvedDownloadUrl())
    }
    @Test
    fun `classifies hugging face resolve link without persisting credentials`() {
        val result = ModelSourceUrlValidator.validate(
            ModelSourceDraft(
                family = ModelFamily.LLM,
                url = "https://huggingface.co/acme/rocket/resolve/main/weights/model.gguf?download=true"
            )
        )

        assertTrue(result.isValid)
        assertEquals(ModelSourceKind.HUGGING_FACE_FILE, result.source?.kind)
        assertEquals("acme/rocket", result.source?.repositoryId)
        assertEquals("weights/model.gguf", result.source?.filePath)
        assertNotNull(result.source?.normalizedKey)
    }

    @Test
    fun `rejects non https and embedded credentials`() {
        val http = ModelSourceUrlValidator.validate(
            ModelSourceDraft(ModelFamily.SD, "http://example.com/model.safetensors")
        )
        val embedded = ModelSourceUrlValidator.validate(
            ModelSourceDraft(ModelFamily.SD, "https://user:secret@example.com/model.safetensors")
        )

        assertFalse(http.isValid)
        assertEquals(ModelLibraryErrorCode.HTTPS_REQUIRED, http.errorCode)
        assertFalse(embedded.isValid)
        assertEquals(ModelLibraryErrorCode.EMBEDDED_CREDENTIALS, embedded.errorCode)
    }

    @Test
    fun `rejects credential query parameters`() {
        val result = ModelSourceUrlValidator.validate(
            ModelSourceDraft(ModelFamily.ONNX, "https://example.com/model.onnx?token=secret")
        )

        assertFalse(result.isValid)
        assertEquals(ModelLibraryErrorCode.CREDENTIAL_QUERY_PARAMETER, result.errorCode)
    }

    @Test
    fun `keeps folder source as repository source`() {
        val result = ModelSourceUrlValidator.validate(
            ModelSourceDraft(ModelFamily.LITERT, "https://huggingface.co/acme/rocket/tree/main/mobile")
        )

        assertTrue(result.isValid)
        assertEquals(ModelSourceKind.HUGGING_FACE_REPOSITORY, result.source?.kind)
        assertEquals("mobile", result.source?.filePath)
    }

    @Test
    fun `entity conversion keeps a typed validation error`() {
        val failure = ModelSourceUrlValidator.toEntity(
            ModelSourceDraft(ModelFamily.ONNX, "http://example.com/model.onnx")
        ).exceptionOrNull()

        assertEquals(ModelLibraryErrorCode.HTTPS_REQUIRED, (failure as? ModelLibraryException)?.code)
    }

    @Test
    fun `source identity is reusable across compatible runtime families`() {
        val llm = ModelSourceUrlValidator.validate(
            ModelSourceDraft(ModelFamily.LLM, "https://example.com/qwen.gguf")
        )
        val sd = ModelSourceUrlValidator.validate(
            ModelSourceDraft(ModelFamily.SD, "https://example.com/qwen.gguf")
        )
        assertEquals(llm.source?.normalizedKey, sd.source?.normalizedKey)
    }
}
