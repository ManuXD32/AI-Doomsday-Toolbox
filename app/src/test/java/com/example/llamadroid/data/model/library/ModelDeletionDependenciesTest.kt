package com.example.llamadroid.data.model.library

import androidx.room.Room
import com.example.llamadroid.audio.AudioAdapterIds
import com.example.llamadroid.audio.AudioJobStatuses
import com.example.llamadroid.audio.AudioModelFamilies
import com.example.llamadroid.audio.AudioGenerationJobEntity
import com.example.llamadroid.audio.music.StableAudio3ComponentRef
import com.example.llamadroid.audio.music.StableAudio3Components
import com.example.llamadroid.audio.music.StableAudio3Kind
import com.example.llamadroid.audio.music.StableAudio3Lora
import com.example.llamadroid.audio.music.StableAudio3Operation
import com.example.llamadroid.audio.music.StableAudio3Request
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_ACTIVE
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_COMPLETED
import com.example.llamadroid.data.db.DownloadTaskEntity
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.DownloadProgressHolder
import com.example.llamadroid.data.model.partFile
import com.example.llamadroid.data.model.library.ModelArtifactLifecycle
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ModelDeletionDependenciesTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        listOf("active-progress", "complete-progress").forEach(DownloadProgressHolder::removeProgress)
        database.close()
    }

    @Test
    fun `queued music protects every nested component lora and input while completed job is ignored`() = runBlocking {
        val active = stableAudioRequest("active")
        val completed = stableAudioRequest("completed")
        database.audioDao().insertJob(audioJob("queued-music", AudioJobStatuses.QUEUED, active))
        database.audioDao().insertJob(audioJob("complete-music", AudioJobStatuses.COMPLETE, completed))

        val dependencies = activeAudioJobDependencies(
            RuntimeEnvironment.getApplication(),
            database
        )
        val activePaths = dependencies
            .filter { it.targetKey == "audio-job:queued-music" }
            .map { it.path }
            .toSet()

        assertEquals(
            setOf(
                active.components.dit.path,
                active.components.tokenizer.path,
                active.components.textEncoder.path,
                active.components.dit.path,
                active.components.codecDecoder.path,
                active.components.codecEncoder!!.path,
                active.loras.single().path,
                active.initAudioPath!!
            ),
            activePaths
        )
        assertTrue(dependencies.none { it.targetKey == "audio-job:complete-music" })
        assertTrue(dependencies.none { it.path == active.outputPath })
        assertTrue(dependencies.none { it.path == completed.components.dit.path })
    }

    @Test
    fun `active download rows and incomplete progress are protected while completed work is ignored`() = runBlocking {
        val activeDestination = temporary.newFile("active-download.gguf")
        val completedDestination = temporary.newFile("completed-download.gguf")
        val activeTask = downloadTask("active-task", activeDestination, DOWNLOAD_TASK_STATUS_ACTIVE)
        val completedTask = downloadTask("completed-task", completedDestination, DOWNLOAD_TASK_STATUS_COMPLETED)
        database.downloadTaskDao().upsert(activeTask)
        database.downloadTaskDao().upsert(completedTask)

        val activeProgressModel = temporary.newFile("active-progress.gguf")
        val completeProgressModel = temporary.newFile("complete-progress.gguf")
        database.modelDao().insertModel(
            model(activeProgressModel, "active-progress.gguf")
        )
        database.modelDao().insertModel(
            model(completeProgressModel, "complete-progress.gguf")
        )
        DownloadProgressHolder.updateProgress("active-progress", "active-progress.gguf", 0.5f)
        DownloadProgressHolder.updateProgress("complete-progress", "complete-progress.gguf", 1.0f)

        val dependencies = activeDownloadDependencies(database)

        assertEquals(
            setOf(activeDestination.absolutePath, activeTask.partFile().absolutePath),
            dependencies.filter { it.targetKey == "download:active-task" }.map { it.path }.toSet()
        )
        assertTrue(
            dependencies.any {
                it.relation == "download-progress" && it.path == activeProgressModel.absolutePath
            }
        )
        assertFalse(
            dependencies.any {
                it.path == completeProgressModel.absolutePath ||
                    it.path == completedDestination.absolutePath ||
                    it.path == completedTask.partFile().absolutePath
            }
        )
    }

    @Test
    fun `deletion progress journals successful and failed paths independently`() = runBlocking {
        val root = temporary.newFolder("partial-delete")
        val deleted = File(root, "deleted.bin").apply { writeText("owned") }
        val blocked = File(root, "blocked").apply {
            mkdirs()
            File(this, "child.bin").writeText("busy")
        }
        val progress = mutableListOf<Triple<String, Boolean, ModelDeletionPathFailure?>>()

        val deletedPaths = ModelArtifactLifecycle.deleteOwnedPathsWithProgress(
            candidates = listOf(deleted, blocked),
            protectedPaths = emptyList(),
            onPathResult = { path, wasDeleted, failure ->
                progress += Triple(path, wasDeleted, failure)
            },
            deleteRecursively = { file ->
                if (file.canonicalFile == blocked.canonicalFile) {
                    throw IOException("directory is busy")
                }
                file.deleteRecursively()
            }
        )

        assertEquals(listOf(deleted.canonicalPath), deletedPaths)
        assertFalse(deleted.exists())
        assertTrue(blocked.exists())
        assertTrue(progress.any { it.first == deleted.canonicalPath && it.second && it.third == null })
        val failure = progress.single { it.first == blocked.canonicalPath }
        assertFalse(failure.second)
        assertEquals(ModelLibraryErrorCode.DELETION_RECOVERABLE, failure.third?.code)
    }

    private fun stableAudioRequest(prefix: String): StableAudio3Request {
        fun path(name: String): String = temporary.newFile("$prefix-$name").absolutePath
        return StableAudio3Request(
            kind = StableAudio3Kind.MUSIC,
            operation = StableAudio3Operation.REMIX,
            components = StableAudio3Components(
                tokenizer = StableAudio3ComponentRef(path("tokenizer.model")),
                textEncoder = StableAudio3ComponentRef(path("text.tflite")),
                dit = StableAudio3ComponentRef(path("dit.tflite")),
                codecDecoder = StableAudio3ComponentRef(path("decoder.tflite")),
                codecEncoder = StableAudio3ComponentRef(path("encoder.tflite"))
            ),
            prompt = "$prefix prompt",
            initAudioPath = path("input.wav"),
            loras = listOf(StableAudio3Lora(path("adapter.safetensors"), 0.5f)),
            outputPath = path("output.wav")
        )
    }

    private fun audioJob(
        id: String,
        status: String,
        request: StableAudio3Request
    ) = AudioGenerationJobEntity(
        id = id,
        adapterId = AudioAdapterIds.STABLE_AUDIO,
        family = AudioModelFamilies.STABLE_AUDIO_MUSIC,
        modelId = id,
        modelPath = request.components.dit.path,
        modelDisplayName = id,
        text = request.prompt,
        status = status,
        metadataJson = JSONObject().put("stableAudio", request.toJson()).toString()
    )

    private fun downloadTask(id: String, destination: File, status: String) = DownloadTaskEntity(
        id = id,
        url = "https://example.invalid/$id",
        destPath = destination.absolutePath,
        filename = destination.name,
        repoId = "test/repository",
        progressKey = id,
        modelType = ModelType.LLAMA_TTS.name,
        status = status
    )

    private fun model(file: File, filename: String) = ModelEntity(
        filename = filename,
        path = file.absolutePath,
        sizeBytes = file.length(),
        type = ModelType.LLAMA_TTS,
        repoId = "test/repository",
        isDownloaded = true
    )
}
