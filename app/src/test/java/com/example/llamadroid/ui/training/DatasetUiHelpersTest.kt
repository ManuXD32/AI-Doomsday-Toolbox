package com.example.llamadroid.ui.training

import com.example.llamadroid.training.DatasetFormat
import com.example.llamadroid.training.HfFileEntry
import com.example.llamadroid.training.HfParquetFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DatasetUiHelpersTest {
    @Test
    fun nonCommercialLicencesAreDetected() {
        assertTrue(DatasetUiHelpers.isNonCommercialLicence("cc-by-nc-4.0"))
        assertTrue(DatasetUiHelpers.isNonCommercialLicence("CC-BY-NC-SA-4.0"))
        assertFalse(DatasetUiHelpers.isNonCommercialLicence("apache-2.0"))
        assertFalse(DatasetUiHelpers.isNonCommercialLicence("mit"))
        assertFalse(DatasetUiHelpers.isNonCommercialLicence(null))
    }

    @Test
    fun capTextAndCompactCount() {
        assertNull(DatasetUiHelpers.capText(0))
        assertEquals("200 MB", DatasetUiHelpers.capText(200))
        assertEquals("950", DatasetUiHelpers.compactCount(950))
        assertEquals("1.2k", DatasetUiHelpers.compactCount(1_234))
        assertEquals("12k", DatasetUiHelpers.compactCount(12_300))
        assertEquals("4.5M", DatasetUiHelpers.compactCount(4_500_000))
    }

    @Test
    fun columnMapsFollowTheFormat() {
        assertTrue(DatasetUiHelpers.defaultColumnMap(DatasetFormat.AUTO).isEmpty())
        assertEquals(listOf("text"), DatasetUiHelpers.columnKeys(DatasetFormat.TEXT))
        assertEquals(
            mapOf("messages" to "conversations", "role" to "from"),
            DatasetUiHelpers.cleanColumnMap(
                DatasetFormat.CHAT,
                mapOf("messages" to " conversations ", "role" to "from", "content" to " ", "text" to "x")
            )
        )
    }

    @Test
    fun trainableFilesSkipReadmesAndUnsupportedTypes() {
        val tree = listOf(
            HfFileEntry("README.md", 10), HfFileEntry(".gitattributes", 1), HfFileEntry("data/train.parquet", 100),
            HfFileEntry("train.jsonl", 50), HfFileEntry("model.safetensors", 999)
        )
        assertEquals(listOf("data/train.parquet", "train.jsonl"), DatasetUiHelpers.trainableFiles(tree).map { it.path })
    }

    @Test
    fun parquetSelectionHelpers() {
        val files = listOf(
            HfParquetFile("default", "train", "0000.parquet", "u1", 10),
            HfParquetFile("default", "train", "0001.parquet", "u2", 20),
            HfParquetFile("default", "test", "0000.parquet", "u3", 5),
            HfParquetFile("other", "train", "0000.parquet", "u4", 7)
        )
        assertEquals(listOf("default", "other"), DatasetUiHelpers.parquetConfigs(files))
        assertEquals(listOf("train", "test"), DatasetUiHelpers.parquetSplits(files, "default"))
        assertEquals(2, DatasetUiHelpers.parquetFor(files, "default", "train").size)
        assertEquals("default/train/0001.parquet", DatasetUiHelpers.parquetPath(files[1]))
    }

    @Test
    fun downloadProgressAveragesDatasetTasks() {
        val progress = mapOf("dataset:7:0" to 1f, "dataset:7:1" to 0.5f, "dataset:70:0" to 0.1f, "other" to 0.9f)
        assertEquals(0.75f, DatasetUiHelpers.downloadProgress(progress, 7)!!, 0.0001f)
        assertNull(DatasetUiHelpers.downloadProgress(progress, 8))
        assertNull(DatasetUiHelpers.downloadProgress(mapOf("dataset:7:0" to -2f), 7))
    }

    @Test
    fun repoDisplayNameDropsOwner() {
        assertEquals("smol-smoltalk", DatasetUiHelpers.repoDisplayName("HuggingFaceTB/smol-smoltalk"))
        assertEquals("solo", DatasetUiHelpers.repoDisplayName("solo"))
    }
}
