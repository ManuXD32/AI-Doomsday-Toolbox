package com.example.llamadroid.service

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
}
