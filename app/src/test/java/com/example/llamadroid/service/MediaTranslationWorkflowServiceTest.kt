package com.example.llamadroid.service

import org.junit.Assert.assertEquals
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
}
