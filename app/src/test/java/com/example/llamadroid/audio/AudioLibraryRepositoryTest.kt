package com.example.llamadroid.audio

import androidx.room.Room
import com.example.llamadroid.audio.library.AudioLibraryFailureCode
import com.example.llamadroid.audio.library.AudioLibraryFolderDeleteMode
import com.example.llamadroid.audio.library.AudioLibraryItemEntity
import com.example.llamadroid.audio.library.AudioLibraryQuery
import com.example.llamadroid.audio.library.AudioLibraryRepository
import com.example.llamadroid.audio.library.AudioLibrarySelection
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.onnx.OnnxTtsStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AudioLibraryRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: AudioLibraryRepository
    private val filesToDelete = mutableListOf<File>()

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = AudioLibraryRepository(context, database)
    }

    @After
    fun tearDown() {
        filesToDelete.forEach { it.delete() }
        database.close()
    }

    @Test
    fun `paged query and all matching selection cover more than one SQL page`() = runBlocking {
        repository.getPreferences()
        database.audioLibraryDao().insertItems((0 until 1_005).map { index ->
            item("item-$index", title = "Track $index", createdAt = index.toLong(), sizeBytes = index.toLong())
        })

        val first = repository.observePage(
            AudioLibraryQuery(sort = com.example.llamadroid.audio.library.AudioLibrarySort.SIZE_DESC, page = 0, pageSize = 100)
        ).first()
        val last = repository.observePage(
            AudioLibraryQuery(sort = com.example.llamadroid.audio.library.AudioLibrarySort.SIZE_DESC, page = 10, pageSize = 100)
        ).first()
        val matchingIds = repository.listMatchingIds(AudioLibraryQuery(query = "Track"))

        assertEquals(100, first.items.size)
        assertEquals(5, last.items.size)
        assertEquals(1_005, first.totalCount)
        assertEquals(1_005, matchingIds.size)
        val selection = AudioLibrarySelection(allMatching = true, excludedIds = setOf("item-1004"))
        assertFalse(selection.contains("item-1004"))
        assertTrue(selection.contains("item-4"))
    }

    @Test
    fun `folder transactions enforce duplicate and cycle rules under concurrent creation`() = runBlocking {
        repository.getPreferences()
        val parent = repository.createFolder("Parent")
        val child = repository.createFolder("Child", parent.id)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.createFolder("child", parent.id) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.moveFolder(parent.id, child.id) }
        }

        val results = coroutineScope {
            (0 until 8).map {
                async(Dispatchers.Default) {
                    runCatching { repository.createFolder("Concurrent", parent.id) }
                }
            }.awaitAll()
        }
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, database.audioLibraryDao().getFolders().count { it.name == "Concurrent" })
    }

    @Test
    fun `move folder contents preserves nested folders and item placement`() = runBlocking {
        repository.getPreferences()
        val root = repository.createFolder("Move root")
        val child = repository.createFolder("Nested", root.id)
        val path = ownedFile("move-item.wav")
        database.audioLibraryDao().insertItem(item("move-item", child.id, path.absolutePath))

        val result = repository.deleteFolderContents(root.id, AudioLibraryFolderDeleteMode.MOVE_CONTENTS_TO_PARENT)

        assertTrue(result.isSuccess)
        assertNull(database.audioLibraryDao().getFolder(root.id))
        assertEquals(null, database.audioLibraryDao().getFolder(child.id)?.parentId)
        assertEquals(child.id, database.audioLibraryDao().getItem("move-item")?.folderId)
        assertTrue(path.isFile)
    }

    @Test
    fun `delete folder contents removes nested local rows while retaining typed partial failures`() = runBlocking {
        repository.getPreferences()
        val root = repository.createFolder("Delete root")
        val child = repository.createFolder("Nested", root.id)
        val good = ownedFile("delete-good.wav")
        val missing = File(AudioWorkspaceStorage.outputRoot(RuntimeEnvironment.getApplication()), "delete-missing.wav")
        filesToDelete += missing
        database.audioLibraryDao().insertItems(
            listOf(
                item("delete-good", root.id, good.absolutePath),
                item("delete-missing", child.id, missing.absolutePath)
            )
        )

        val partial = repository.deleteFolderContents(root.id, AudioLibraryFolderDeleteMode.DELETE_CONTAINED_AUDIO)

        assertTrue(partial.isPartial)
        assertTrue(partial.succeededIds.contains("delete-good"))
        assertEquals("delete-missing", partial.failures.single().itemId)
        assertEquals(com.example.llamadroid.audio.library.AudioLibraryFailureCode.FILE_MISSING, partial.failures.single().code)
        assertNull(database.audioLibraryDao().getItem("delete-good"))
        assertNotNull(database.audioLibraryDao().getItem("delete-missing"))
        assertNotNull(database.audioLibraryDao().getFolder(root.id))
        assertNotNull(database.audioLibraryDao().getFolder(child.id))
        assertFalse(good.exists())
    }

    @Test
    fun `job reindex retains friendly title and legacy indexing is incremental`() = runBlocking {
        repository.getPreferences()
        val app = RuntimeEnvironment.getApplication()
        val jobId = "library-title-job"
        val output = AudioWorkspaceStorage.jobWavFile(app, jobId)
        output.writeBytes(byteArrayOf(1, 2, 3))
        filesToDelete += output.parentFile ?: output
        val job = AudioGenerationJobEntity(
            id = jobId,
            adapterId = AudioAdapterIds.SUPERTONIC,
            family = AudioModelFamilies.SUPERTONIC,
            modelId = "supertonic",
            modelPath = "/models/supertonic",
            modelDisplayName = "Supertonic",
            status = AudioJobStatuses.COMPLETE,
            outputPath = output.absolutePath,
            createdAt = 10L,
            completedAt = 10L
        )
        val indexed = repository.upsertJob(job)
        assertNotNull(indexed)
        repository.renameItem("job:$jobId", "Friendly title")
        repository.upsertJob(job.copy(modelDisplayName = "Renamed model"))
        assertEquals("Friendly title", database.audioLibraryDao().getItem("job:$jobId")?.title)

        val legacy = File(OnnxTtsStorage.outputDir(app), "library-incremental-${System.nanoTime()}.wav")
        legacy.writeBytes(byteArrayOf(1, 2, 3))
        filesToDelete += legacy
        repository.indexLegacyOutput(legacy)
        val legacyRow = database.audioLibraryDao().getItem("legacy:${legacy.absolutePath}")
        assertEquals("legacy_onnx_tts", legacyRow?.source)
        assertEquals("speech", legacyRow?.kind)
    }

    @Test
    fun `delete returns per item partial failure and leaves missing row retryable`() = runBlocking {
        repository.getPreferences()
        val good = ownedFile("partial-good.wav")
        val missing = File(AudioWorkspaceStorage.outputRoot(RuntimeEnvironment.getApplication()), "partial-missing.wav")
        filesToDelete += missing
        database.audioLibraryDao().insertItems(
            listOf(item("partial-good", path = good.absolutePath), item("partial-missing", path = missing.absolutePath))
        )

        val result = repository.deleteItems(listOf("partial-good", "partial-missing"))

        assertTrue(result.isPartial)
        assertEquals(listOf("partial-good"), result.succeededIds)
        assertEquals(com.example.llamadroid.audio.library.AudioLibraryFailureCode.FILE_MISSING, result.failures.single().code)
        assertNull(database.audioLibraryDao().getItem("partial-good"))
        assertNotNull(database.audioLibraryDao().getItem("partial-missing"))
    }

    @Test
    fun `delete blocks workspace directory when active job references a sibling output`() = runBlocking {
        repository.getPreferences()
        val app = RuntimeEnvironment.getApplication()
        val sourceJobId = "active-source-job"
        val sourceWav = AudioWorkspaceStorage.jobWavFile(app, sourceJobId)
        val sourceMp3 = AudioWorkspaceStorage.jobAudioFile(app, sourceJobId, "mp3")
        sourceWav.writeBytes(byteArrayOf(1, 2, 3))
        sourceMp3.writeBytes(byteArrayOf(4, 5, 6))
        filesToDelete += sourceWav
        filesToDelete += sourceMp3
        database.audioLibraryDao().insertItem(
            item(
                id = "active-source",
                path = sourceWav.absolutePath
            ).copy(originKey = "job:$sourceJobId")
        )
        database.audioDao().insertJob(
            AudioGenerationJobEntity(
                id = "active-remix-job",
                adapterId = AudioAdapterIds.STABLE_AUDIO,
                family = AudioModelFamilies.STABLE_AUDIO_MUSIC,
                modelId = "stable-audio",
                modelPath = "/models/stable-audio",
                modelDisplayName = "Stable Audio",
                status = AudioJobStatuses.RUNNING,
                metadataJson = """
                    {"stableAudio":{"initAudioPath":"${sourceMp3.absolutePath}"}}
                """.trimIndent()
            )
        )

        val result = repository.deleteItem("active-source")

        assertEquals(AudioLibraryFailureCode.ACTIVE_JOB, result.failures.single().code)
        assertNotNull(database.audioLibraryDao().getItem("active-source"))
        assertTrue(sourceWav.isFile)
        assertTrue(sourceMp3.isFile)
    }

    private fun item(
        id: String,
        folderId: String? = null,
        path: String = "/missing/$id.wav",
        title: String = id,
        createdAt: Long = 1L,
        sizeBytes: Long = 1L
    ) = AudioLibraryItemEntity(
        id = id,
        originKey = "manual:$id",
        title = title,
        audioPath = path,
        createdAt = createdAt,
        updatedAt = createdAt,
        folderId = folderId,
        sizeBytes = sizeBytes,
        status = AudioJobStatuses.COMPLETE
    )

    private fun ownedFile(name: String): File {
        val file = File(AudioWorkspaceStorage.outputRoot(RuntimeEnvironment.getApplication()), name)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        filesToDelete += file
        return file
    }
}
