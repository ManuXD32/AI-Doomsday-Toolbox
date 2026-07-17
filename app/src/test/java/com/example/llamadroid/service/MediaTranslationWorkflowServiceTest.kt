package com.example.llamadroid.service

import com.example.llamadroid.data.RemoteSummarySettingsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTranslationWorkflowServiceTest {
    @Test
    fun srtParserReadsMultilineSegmentsAndCommaMillis() {
        val srt = """
            1
            00:00:01,250 --> 00:00:03,500
            Hello
            world

            2
            00:00:04,000 --> 00:00:05,125
            Next line
        """.trimIndent()

        val segments = SrtParser.parse(srt)

        assertEquals(2, segments.size)
        assertEquals(1_250L, segments[0].startMs)
        assertEquals(3_500L, segments[0].endMs)
        assertEquals("Hello\nworld", segments[0].text)
        assertEquals(4_000L, segments[1].startMs)
        assertEquals(5_125L, segments[1].endMs)
    }

    @Test
    fun srtWriterPreservesTranslatedTimestamps() {
        val translated = listOf(
            TranslatedTranscriptSegment(
                id = 7,
                startMs = 61_001L,
                endMs = 62_250L,
                originalText = "Hello",
                translatedText = "Hola"
            )
        )

        val srt = SrtWriter.write(translated)

        assertTrue(srt.contains("7"))
        assertTrue(srt.contains("00:01:01,001 --> 00:01:02,250"))
        assertTrue(srt.contains("Hola"))
    }

    @Test
    fun translationValidatorRejectsMissingAndDuplicateIds() {
        val expected = listOf(
            TimedTranscriptSegment(1, 0, 1_000, "A"),
            TimedTranscriptSegment(2, 1_000, 2_000, "B")
        )

        val missing = TranslationJsonValidator.parseAndValidate(
            """{"segments":[{"id":1,"translatedText":"Uno"}]}""",
            expected
        )
        val duplicate = TranslationJsonValidator.parseAndValidate(
            """{"segments":[{"id":1,"translatedText":"Uno"},{"id":1,"translatedText":"Otra vez"}]}""",
            expected
        )

        assertTrue(missing.isFailure)
        assertTrue(duplicate.isFailure)
    }

    @Test
    fun translationValidatorAcceptsJsonInsideText() {
        val expected = listOf(TimedTranscriptSegment(3, 0, 1_000, "A"))

        val parsed = TranslationJsonValidator.parseAndValidate(
            """Here is JSON: {"segments":[{"id":3,"translatedText":"Tres"}]}""",
            expected
        ).getOrThrow()

        assertEquals("Tres", parsed[3])
    }

    @Test
    fun malformedTranslationJsonFailsValidation() {
        val expected = listOf(TimedTranscriptSegment(1, 0, 1_000, "A"))

        val parsed = TranslationJsonValidator.parseAndValidate("not json", expected)

        assertTrue(parsed.isFailure)
    }

    @Test
    fun timingTempoUsesSourceOverTargetDuration() {
        assertEquals(2.0f, MediaTranslationAudioTiming.tempoForDuration(4f, 2_000L), 0.001f)
        assertEquals(1.0f, MediaTranslationAudioTiming.tempoForDuration(2f, 2_000L), 0.001f)
        assertEquals(0.5f, MediaTranslationAudioTiming.tempoForDuration(1f, 4_000L), 0.001f)
    }

    @Test
    fun transcriptionProgressPercentUsesLatestTimestampOverTotalDuration() {
        assertEquals(0, mediaTranslationProgressPercent(0L, 100_000L))
        assertEquals(42, mediaTranslationProgressPercent(42_000L, 100_000L))
        assertEquals(100, mediaTranslationProgressPercent(120_000L, 100_000L))
        assertEquals(0, mediaTranslationProgressPercent(42_000L, 0L))
    }

    @Test
    fun translationProgressPercentUsesTranslatedLinesOverTotalLines() {
        assertEquals(0, mediaTranslationLineProgressPercent(0, 20))
        assertEquals(65, mediaTranslationLineProgressPercent(13, 20))
        assertEquals(100, mediaTranslationLineProgressPercent(30, 20))
        assertEquals(0, mediaTranslationLineProgressPercent(5, 0))
    }

    @Test
    fun translatedSubtitleFileNameIncludesSourceAndTimestamp() {
        val fileName = mediaTranslationTranslatedSubtitleFileName("My Video!.mp4", 1_234_567_890L)

        assertEquals("translated_My_Video_1234567890.srt", fileName)
    }

    @Test
    fun originalSubtitleFileNameIncludesSourceAndTimestamp() {
        val fileName = mediaTranslationOriginalSubtitleFileName("My Video!.mp4", 1_234_567_890L)

        assertEquals("original_My_Video_1234567890.srt", fileName)
    }

    @Test
    fun latestTranscriptTimestampParsesWhisperOutputAndSrtTimestamps() {
        val raw = """
            [00:00:00.000 --> 00:00:02.000] hello
            2
            00:00:02,500 --> 00:00:04,250
            world
        """.trimIndent()

        assertEquals(4_250L, mediaTranslationLatestTranscriptTimestampMs(raw))
    }

    @Test
    fun whisperOutputSegmentParsesTimestampedLineForPartialSrtCheckpoint() {
        val segment = mediaTranslationWhisperOutputSegment(
            "[01:02:03.450 --> 01:02:05.670]   hola que tal buenos dias"
        )

        assertNotNull(segment)
        val parsed = segment!!
        assertEquals(3_723_450L, parsed.startMs)
        assertEquals(3_725_670L, parsed.endMs)
        assertEquals("hola que tal buenos dias", parsed.text)
    }

    @Test
    fun whisperLinkerOutOfMemoryOutputRequestsLaunchRetry() {
        val output = """
            CANNOT LINK EXECUTABLE "/data/app/pkg/lib/arm64/libwhisper-cli_baseline.so":
            can't enable GNU RELRO protection for "/data/app/pkg/lib/arm64/libwhisper-cli_baseline.so": Out of memory
        """.trimIndent()

        assertTrue(mediaTranslationWhisperLaunchNeedsMemoryRetry(output))
    }

    @Test
    fun compactWhisperProcessOutputKeepsTailAndLimitsLength() {
        val output = (1..20).joinToString("\n") { "line-$it ${"x".repeat(80)}" }

        val compact = mediaTranslationCompactProcessOutput(output, maxChars = 120)

        assertTrue(compact.startsWith("line-13"))
        assertTrue(compact.length <= 120)
        assertTrue(compact.endsWith("..."))
    }

    @Test
    fun whisperLinkerRetryDelayIsBounded() {
        assertEquals(1_500L, mediaTranslationWhisperLinkerRetryDelayMs(1))
        assertEquals(8_000L, mediaTranslationWhisperLinkerRetryDelayMs(99))
    }

    @Test
    fun transcriptionResumeStartBacksUpOneMinute() {
        assertEquals(60_000L, mediaTranslationResumeStartMs(120_000L))
    }

    @Test
    fun transcriptionResumeStartClampsToZero() {
        assertEquals(0L, mediaTranslationResumeStartMs(45_000L))
    }

    @Test
    fun transcriptionResumeTrimDropsSegmentsAfterResumePointAndRenumbers() {
        val segments = listOf(
            TimedTranscriptSegment(7, 0L, 30_000L, "keep one"),
            TimedTranscriptSegment(8, 35_000L, 60_000L, "keep two"),
            TimedTranscriptSegment(9, 61_000L, 75_000L, "rewrite this")
        )

        val trimmed = mediaTranslationTrimSegmentsForResume(segments, resumeStartMs = 60_000L)

        assertEquals(2, trimmed.size)
        assertEquals(listOf(1, 2), trimmed.map { it.id })
        assertEquals(listOf("keep one", "keep two"), trimmed.map { it.text })
    }

    @Test
    fun transcriptionResumeMergeOffsetsTailAndRenumbers() {
        val preserved = mediaTranslationTrimSegmentsForResume(
            listOf(
                TimedTranscriptSegment(1, 0L, 30_000L, "kept"),
                TimedTranscriptSegment(2, 65_000L, 70_000L, "old partial")
            ),
            resumeStartMs = 60_000L
        )
        val relativeTail = TimedTranscriptSegment(1, 5_000L, 15_000L, "rewritten tail")
        val timelineTail = relativeTail.copy(
            startMs = relativeTail.startMs + 60_000L,
            endMs = relativeTail.endMs + 60_000L
        )

        val merged = mediaTranslationMergeTranscriptSegments(preserved, listOf(timelineTail))

        assertEquals(2, merged.size)
        assertEquals(listOf(1, 2), merged.map { it.id })
        assertEquals(65_000L, merged[1].startMs)
        assertEquals(75_000L, merged[1].endMs)
        assertEquals("rewritten tail", merged[1].text)
    }

    @Test
    fun checkpointTranslationsKeepOnlyExpectedNonBlankSegmentsInOrder() {
        val expected = listOf(
            TimedTranscriptSegment(2, 0, 1_000, "B"),
            TimedTranscriptSegment(4, 1_000, 2_000, "D")
        )
        val checkpoint = mapOf(
            4 to " Cuatro ",
            3 to "Unexpected",
            2 to "Dos",
            5 to "",
        )

        val sanitized = mediaTranslationSanitizeCheckpointTranslations(checkpoint, expected)

        assertEquals(listOf(2, 4), sanitized.keys.toList())
        assertEquals("Dos", sanitized[2])
        assertEquals("Cuatro", sanitized[4])
    }

    @Test
    fun lineTranslationPromptIncludesTargetAndContextWindow() {
        val segments = listOf(
            TimedTranscriptSegment(1, 0, 1_000, "Before line"),
            TimedTranscriptSegment(2, 1_000, 2_000, "Translate me"),
            TimedTranscriptSegment(3, 2_000, 3_000, "After line")
        )

        val prompt = mediaTranslationBuildLineTranslationPrompt(
            sourceLanguage = "ja",
            targetLanguage = "English",
            segments = segments,
            targetIndex = 1,
            includeContext = true,
            contextLines = 1
        )

        assertTrue(prompt.contains("Use the CONTEXT to translate the TARGET line."))
        assertTrue(prompt.contains("[TARGET] Translate me"))
        assertTrue(prompt.contains("[CONTEXT]\nBefore line\nTranslate me\nAfter line\n[/CONTEXT]"))
    }

    @Test
    fun lineTranslationPromptCanOmitContext() {
        val segments = listOf(TimedTranscriptSegment(1, 0, 1_000, "Translate me"))

        val prompt = mediaTranslationBuildLineTranslationPrompt(
            sourceLanguage = "auto",
            targetLanguage = "Spanish",
            segments = segments,
            targetIndex = 0,
            includeContext = false,
            contextLines = 2
        )

        assertTrue(prompt.contains("[TARGET] Translate me"))
        assertTrue(!prompt.contains("[CONTEXT]"))
    }

    @Test
    fun lineTranslationCleanupAcceptsPlainJsonAndLabelledAnswers() {
        assertEquals("Hola mundo", mediaTranslationCleanLineTranslation("Translation: Hola mundo"))
        assertEquals("Hola mundo", mediaTranslationCleanLineTranslation("""{"translatedText":"Hola mundo"}"""))
        assertEquals("Hola mundo", mediaTranslationCleanLineTranslation("```text\nHola mundo\n```"))
    }

    @Test
    fun promptEchoDetectionRejectsContextMarkers() {
        assertTrue(mediaTranslationLooksLikePromptEcho("[CONTEXT]\nTranslate me\n[/CONTEXT]"))
    }

    @Test
    fun lineTranslationRetryPolicyRetriesBlankAndPromptEchoAnswers() {
        assertTrue(mediaTranslationShouldRetryLineTranslation(""))
        assertTrue(mediaTranslationShouldRetryLineTranslation("[TARGET] Translate me\n[CONTEXT]\nMore prompt"))
        assertTrue(mediaTranslationShouldRetryLineTranslation(null))
        assertTrue(mediaTranslationShouldRetryLineTranslation("Hola", IllegalStateException("network")))
        assertTrue(!mediaTranslationShouldRetryLineTranslation("Translation: Hola mundo"))
    }

    @Test
    fun translationDiagnosticSnippetKeepsErrorsReadable() {
        assertEquals("<empty>", mediaTranslationDiagnosticSnippet(" \n\t "))
        assertEquals("a b c", mediaTranslationDiagnosticSnippet("a\n b\t c"))
        assertEquals("12345...", mediaTranslationDiagnosticSnippet("123456789", maxChars = 5))
    }

    @Test
    fun resumeOverrideCarriesSkipFailedLinesIntoMediaSpecs() {
        val override = testResumeOverride(skipFailedTranslationLines = true)

        val updated = mediaTranslationApplyResumeOverride(testMediaJobSpec(), override)

        assertEquals("Spanish", updated.targetLanguage)
        assertTrue(updated.skipFailedTranslationLines)
        assertEquals(10, updated.translationContextLines)
    }

    @Test
    fun resumeOverrideCarriesSkipFailedLinesIntoSubtitleSpecs() {
        val override = testResumeOverride(skipFailedTranslationLines = true)

        val updated = mediaTranslationApplyResumeOverride(testSubtitleJobSpec(), override)

        assertEquals("Spanish", updated.targetLanguage)
        assertTrue(updated.skipFailedTranslationLines)
        assertEquals(10, updated.translationContextLines)
    }

    @Test
    fun isolatedLineTranslationPromptAvoidsContextMarkers() {
        val prompt = mediaTranslationBuildIsolatedLineTranslationPrompt(
            sourceLanguage = "auto",
            targetLanguage = "English",
            sourceText = "そろそろ帰らないと。"
        )

        assertTrue(prompt.contains("Return exactly one non-empty line."))
        assertTrue(prompt.contains("Source:\nそろそろ帰らないと。"))
        assertTrue(!prompt.contains("[TARGET]"))
        assertTrue(!prompt.contains("[CONTEXT]"))
    }

    private fun testResumeOverride(skipFailedTranslationLines: Boolean): MediaTranslationResumeTranslationOverride =
        MediaTranslationResumeTranslationOverride(
            targetLanguage = "Spanish",
            backendSnapshot = testRemoteSummarySnapshot(targetLanguage = "Spanish"),
            translationContextEnabled = false,
            translationContextLines = 42,
            skipFailedTranslationLines = skipFailedTranslationLines
        )

    private fun testMediaJobSpec(): MediaTranslationJobSpec =
        MediaTranslationJobSpec(
            sourcePath = "/tmp/source.mp4",
            sourceName = "source.mp4",
            sourceMimeType = "video/mp4",
            whisperModelPath = "/tmp/whisper.bin",
            whisperLanguage = "auto",
            whisperThreads = 2,
            targetLanguage = "English",
            ttsModelPath = "/tmp/tts.onnx",
            ttsModelName = "tts",
            ttsLanguage = "en",
            ttsVoiceName = null,
            ttsSteps = 4,
            outputMode = MediaTranslationOutputMode.AUTO,
            replaceOriginalAudio = false,
            backendSnapshot = testRemoteSummarySnapshot(),
            translationContextEnabled = true,
            translationContextLines = 2,
            skipFailedTranslationLines = false
        )

    private fun testSubtitleJobSpec(): SubtitleTranslationJobSpec =
        SubtitleTranslationJobSpec(
            videoPath = "/tmp/source.mp4",
            videoName = "source.mp4",
            sourceSubtitlePath = "/tmp/source.srt",
            sourceSubtitleName = "source.srt",
            whisperModelPath = null,
            whisperLanguage = "auto",
            whisperThreads = 2,
            targetLanguage = "English",
            translateSubtitles = true,
            burnIntoVideo = false,
            burnStyle = SubtitleBurnStyleSpec(
                fontSize = 24,
                alignment = 2,
                marginV = 24,
                marginL = 12,
                primaryColorRed = 1f,
                primaryColorGreen = 1f,
                primaryColorBlue = 1f,
                fontName = "default"
            ),
            backendSnapshot = testRemoteSummarySnapshot(),
            translationContextEnabled = true,
            translationContextLines = 2,
            skipFailedTranslationLines = false
        )

    private fun testRemoteSummarySnapshot(targetLanguage: String = "English"): RemoteSummarySettingsSnapshot =
        RemoteSummarySettingsSnapshot(
            backend = "ollama",
            ollamaUrl = "http://localhost:11434",
            llamaServerUrl = "",
            llamaSwapUrl = "",
            ollamaModel = "test-model",
            llamaSwapModel = null,
            liteRtModelId = null,
            thinkingEnabled = false,
            llamaServerModelLabel = null,
            llamaServerContextTokens = 4096,
            llamaServerContextLabel = null,
            chunkContext = 4096,
            chunkMaxTokens = 1024,
            mergeContext = 4096,
            mergeMaxTokens = 1024,
            temperature = 0.2f,
            timeoutMinutes = 2,
            targetLanguage = targetLanguage,
            summaryPrompt = null,
            mergePrompt = null
        )
}
