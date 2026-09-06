package com.example.llamadroid.ui.models

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelProvenanceEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.library.InstalledModelAsset
import com.example.llamadroid.data.model.library.ModelFamily
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSourceAttachmentTest {
    @Test
    fun blankSourceIsAllowedForLocalImport() {
        val result = optionalModelSourceDraft(ModelFamily.LLM, "  ", "")

        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
    }

    @Test
    fun onlyDirectFileLinksCanBeAttached() {
        assertTrue(
            optionalModelSourceDraft(
                ModelFamily.LLM,
                "https://huggingface.co/acme/weights/resolve/main/model.gguf",
                "GGUF"
            ).isSuccess
        )
        assertTrue(
            optionalModelSourceDraft(ModelFamily.LLM, "https://example.com/model.bin", "").isSuccess
        )
        assertTrue(
            optionalModelSourceDraft(ModelFamily.LLM, "https://huggingface.co/acme/weights", "").isFailure
        )
        assertTrue(
            optionalModelSourceDraft(ModelFamily.LLM, "https://huggingface.co/acme/weights/tree/main", "").isFailure
        )
    }

    @Test
    fun exactCanonicalMemberPathWinsOverSharedRuntimeKey() {
        val file = File.createTempFile("model-source", ".gguf")
        try {
            val model = ModelEntity(
                filename = "primary.gguf",
                path = file.absolutePath,
                sizeBytes = file.length(),
                type = ModelType.LLM,
                repoId = ""
            )
            val asset = InstalledModelAsset.fromModel(model, ModelFamily.LLM, "llm")
            val companion = ModelProvenanceEntity(
                id = "companion",
                sourceId = "source-companion",
                modelKey = asset.stableId,
                family = ModelFamily.LLM.storedValue,
                localPath = File(file.parentFile, "companion.gguf").path,
                updatedAt = Long.MAX_VALUE
            )
            val primary = ModelProvenanceEntity(
                id = "primary",
                sourceId = "source-primary",
                modelKey = asset.stableId,
                family = ModelFamily.LLM.storedValue,
                localPath = file.canonicalPath,
                updatedAt = 1L
            )

            assertEquals(primary.id, findProvenanceForAsset(asset, listOf(companion, primary))?.id)
        } finally {
            file.delete()
        }
    }

    @Test
    fun unlinkedDirectoryMemberDoesNotInheritSiblingSource() {
        val directory = java.nio.file.Files.createTempDirectory("model-source-members").toFile()
        try {
            val primary = File(directory, "model.onnx").apply { writeText("primary") }
            val tokenizer = File(directory, "tokenizer.json").apply { writeText("tokenizer") }
            val model = ModelEntity(
                filename = "fixture-package",
                path = directory.path,
                sizeBytes = primary.length() + tokenizer.length(),
                type = ModelType.ONNX_IMAGE_GEN,
                repoId = "fixture"
            )
            val packageAsset = InstalledModelAsset.fromModel(model, ModelFamily.ONNX, "image_generation")
            val memberAsset = packageAsset.copy(
                displayName = "${packageAsset.displayName}/tokenizer.json",
                path = tokenizer.path,
                filename = tokenizer.name
            )
            val sibling = ModelProvenanceEntity(
                id = "primary-edge",
                sourceId = "source-primary",
                modelKey = packageAsset.stableId,
                family = ModelFamily.ONNX.storedValue,
                localPath = primary.canonicalPath,
                updatedAt = Long.MAX_VALUE
            )

            assertNull(findProvenanceForAsset(memberAsset, listOf(sibling), requireExactPath = true))
        } finally {
            directory.deleteRecursively()
        }
    }
}
