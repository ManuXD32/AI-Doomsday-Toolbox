package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.data.db.NoteType
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.junit.runner.RunWith
import java.io.File

@RunWith(RobolectricTestRunner::class)
class VideoRecognitionSummaryServiceRoutingTest {
    private lateinit var context: Context
    private val temporaryFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        UnifiedNotificationManager.init(context)
        VideoRecognitionSummaryService.clear()
        VideoRecognitionRuntimeRegistry.clear()
        VideoSummaryStateHolder.reset()
        mockkObject(VideoSumupService)
    }

    @After
    fun tearDown() {
        VideoRecognitionSummaryService.clear()
        VideoRecognitionRuntimeRegistry.clear()
        VideoRecognitionSettingsRepository(context).setSavedTargetId(null)
        unmockkObject(VideoSumupService)
        temporaryFiles.forEach(File::delete)
    }

    @Test
    fun `no visual target routes to the existing audio service`() = runBlocking {
        val source = temporaryFile("video", ".mp4")
        val fallbackCalls = mutableListOf<String>()
        every {
            VideoSumupService.startSummarization(
                context = any(),
                videoPath = any(),
                videoFileName = any(),
                whisperModelPath = any(),
                language = any(),
                threads = any(),
                vadConfig = any(),
                saveToNotes = false,
                noteType = any(),
                audioSourcePath = any(),
                settingsOverride = any()
            )
        } answers {
            fallbackCalls += firstArg<Context>().packageName
            VideoSummaryStateHolder.setSummary("legacy audio summary")
            VideoSummaryStateHolder.setError(null)
            VideoSummaryStateHolder.setCancelled(false)
            VideoSummaryStateHolder.setIsRunning(false)
            Unit
        }

        VideoRecognitionSummaryService.start(
            context,
            request(
                source = source,
                localTargets = emptyList(),
                audioFallback = audioFallback()
            )
        )
        awaitState { it.status == VideoRecognitionSummaryStatus.SUCCESS }

        assertEquals(1, fallbackCalls.size)
        verify(exactly = 1) {
            VideoSumupService.startSummarization(
                context = any(),
                videoPath = source.absolutePath,
                videoFileName = "fixture.mp4",
                whisperModelPath = "/tmp/whisper.gguf",
                language = "en",
                threads = 2,
                vadConfig = null,
                saveToNotes = false,
                noteType = NoteType.VIDEO_SUMMARY,
                audioSourcePath = null,
                settingsOverride = null
            )
        }
    }

    @Test
    fun `working visual target never enters the legacy service`() = runBlocking {
        val source = temporaryFile("video", ".mp4")
        val model = temporaryFile("model", ".gguf")
        val projector = temporaryFile("projector", ".gguf")
        val runtime = RecordingRuntime()
        VideoRecognitionRuntimeRegistry.install(runtime)
        justRun {
            VideoSumupService.startSummarization(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        }

        VideoRecognitionSummaryService.start(
            context,
            request(
                source = source,
                localTargets = listOf(localTarget(model, projector)),
                audioFallback = audioFallback()
            )
        )
        awaitState { it.status == VideoRecognitionSummaryStatus.SUCCESS }

        assertEquals(listOf("fixture-local"), runtime.visualTargets)
        assertTrue(runtime.frameFallbackTargets.isEmpty())
        verify(exactly = 0) { VideoSumupService.startSummarization(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `native visual failure retries the same model with bounded frames`() = runBlocking {
        val source = temporaryFile("video", ".mp4")
        val model = temporaryFile("model", ".gguf")
        val projector = temporaryFile("projector", ".gguf")
        val runtime = RecordingRuntime(failVideo = true)
        VideoRecognitionRuntimeRegistry.install(runtime)
        justRun {
            VideoSumupService.startSummarization(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        }

        VideoRecognitionSummaryService.start(
            context,
            request(
                source = source,
                localTargets = listOf(localTarget(model, projector)),
                audioFallback = audioFallback()
            )
        )
        awaitState { it.status == VideoRecognitionSummaryStatus.SUCCESS }

        assertEquals(listOf("fixture-local"), runtime.visualTargets)
        assertEquals(listOf("fixture-local"), runtime.frameFallbackTargets)
        assertTrue(VideoRecognitionSummaryService.state.value.usedFrameFallback)
        verify(exactly = 0) { VideoSumupService.startSummarization(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an old run token cannot cancel a newer run`() {
        val source = temporaryFile("video", ".mp4")
        val model = temporaryFile("model", ".gguf")
        val projector = temporaryFile("projector", ".gguf")
        val runtime = RecordingRuntime()
        VideoRecognitionRuntimeRegistry.install(runtime)

        val firstRun = VideoRecognitionSummaryService.start(
            context,
            request(source, listOf(localTarget(model, projector)), audioFallback())
        )
        val secondRun = VideoRecognitionSummaryService.start(
            context,
            request(source, listOf(localTarget(model, projector)), audioFallback())
        )

        assertTrue(secondRun > firstRun)
        assertFalse(VideoRecognitionSummaryService.cancelIfCurrent(firstRun))
        assertEquals(secondRun, VideoRecognitionSummaryService.currentRunId())
        assertTrue(VideoRecognitionSummaryService.cancelIfCurrent(secondRun))
        assertEquals(VideoRecognitionSummaryStatus.CANCELLED, VideoRecognitionSummaryService.state.value.status)
    }

    private suspend fun awaitState(predicate: (VideoRecognitionSummaryState) -> Boolean) {
        repeat(200) {
            if (predicate(VideoRecognitionSummaryService.state.value)) return
            delay(10)
        }
        throw AssertionError("Timed out waiting for video summary state: ${VideoRecognitionSummaryService.state.value}")
    }

    private fun request(
        source: File,
        localTargets: List<VideoRecognitionTarget>,
        audioFallback: VideoRecognitionAudioFallbackRequest?
    ) = VideoRecognitionSummaryRequest(
        sourcePath = source.absolutePath,
        sourceName = "fixture.mp4",
        localTargets = localTargets,
        settings = VideoRecognitionSettingsSnapshot(
            savedTargetId = null,
            remoteEnabled = false,
            remoteEndpoint = "",
            remoteModel = "",
            remoteVideoEnabled = false,
            segmentSeconds = 30,
            maxFrames = 24,
            maxFps = 2f,
            targetLanguage = "English",
            prompt = "",
            contextSize = 8_192,
            maxTokens = 768,
            temperature = 0.2f,
            timeoutMinutes = 1
        ),
        audioFallback = audioFallback,
        saveToNotes = false
    )

    private fun audioFallback() = VideoRecognitionAudioFallbackRequest(
        whisperModelPath = "/tmp/whisper.gguf",
        language = "en",
        threads = 2,
        vadConfig = null,
        settingsOverride = null,
        saveToNotes = false
    )

    private fun localTarget(model: File, projector: File) = VideoRecognitionTarget(
        id = "fixture-local",
        label = "Fixture local",
        kind = VideoRecognitionTarget.Kind.LOCAL_BUNDLE,
        modelPath = model.absolutePath,
        mmprojPath = projector.absolutePath
    )

    private fun temporaryFile(prefix: String, suffix: String): File =
        File.createTempFile(prefix, suffix).also { temporaryFiles += it }

    private class RecordingRuntime(
        private val failVideo: Boolean = false
    ) : VideoRecognitionRuntime {
        val visualTargets = mutableListOf<String>()
        val frameFallbackTargets = mutableListOf<String>()

        override suspend fun probeDurationSeconds(sourceFile: File): Double = 1.0

        override suspend fun summarizeSegment(
            request: VideoRecognitionSegmentRequest,
            onProgress: (String, Float) -> Unit
        ): String {
            visualTargets += request.target.id
            if (failVideo) throw IllegalStateException("native video failed")
            return "visual summary"
        }

        override suspend fun summarizeFramesFallback(
            request: VideoRecognitionSegmentRequest,
            onProgress: (String, Float) -> Unit
        ): String {
            frameFallbackTargets += request.target.id
            return "frame summary"
        }

        override suspend fun mergeSummaries(
            request: VideoRecognitionMergeRequest,
            onProgress: (String, Float) -> Unit
        ): String = request.summaries.joinToString(" ")
    }
}
