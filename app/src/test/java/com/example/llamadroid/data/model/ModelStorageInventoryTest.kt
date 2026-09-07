package com.example.llamadroid.data.model

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.*
import org.junit.Test

class ModelStorageInventoryTest {
    @Test fun nestedModelsAndDuplicateReferencesCountPhysicalBytesOnce() {
        val root = createTempDirectory().toFile()
        try {
            File(root, "model.onnx").writeBytes(ByteArray(4))
            File(root, "nested").mkdir()
            File(root, "nested/weights.data").writeBytes(ByteArray(7))
            File(root, "transfer.part").writeBytes(ByteArray(3))
            val inventory = measureModelStorage(listOf(StorageArtifact(root.path, setOf("onnx")),
                StorageArtifact(root.path, setOf("onnx")), StorageArtifact(File(root, "missing").path, setOf("llm"))),
                downloads = listOf(File(root, "transfer.part").path, File(root, "transfer.part").path))
            assertEquals(11L, inventory.modelsBytes)
            assertEquals(StorageUsage(1, 11L), inventory.usage("onnx"))
            assertEquals(StorageUsage(), inventory.usage("llm"))
            assertEquals(3L, inventory.downloadBytes)
        } finally { root.deleteRecursively() }
    }

    @Test fun pendingPromotionDoesNotDoubleCountAndDeletedFilesDisappear() {
        val root = createTempDirectory().toFile()
        try {
            val file = File(root, "model.gguf").apply { writeBytes(ByteArray(9)) }
            val pending = measureModelStorage(emptyList(), pending = listOf(file.path))
            assertEquals(9L, pending.pendingBytes)
            val installed = measureModelStorage(listOf(StorageArtifact(file.path, setOf("llm"))), pending = listOf(file.path))
            assertEquals(9L, installed.modelsBytes)
            assertEquals(0L, installed.pendingBytes)
            file.delete()
            assertEquals(0L, measureModelStorage(listOf(StorageArtifact(file.path, setOf("llm")))).modelsBytes)
        } finally { root.deleteRecursively() }
    }
}
