package com.example.llamadroid.ui.agent.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessInlineLinksTest {
    @Test
    fun fileDestinationsPreserveEscapedNamesAndLineAnchors() {
        assertEquals(HarnessInlineTarget.File("src/a+b #?.kt", 41),
            parseHarnessInlineDestination("src/a+b%20%23%3F.kt#L41-L45"))
        assertEquals(HarnessInlineTarget.File("docs/niño.txt"), parseHarnessInlineDestination("docs/ni%C3%B1o.txt"))
        assertEquals(HarnessInlineTarget.External("https://example.test/a?x=1#part"),
            parseHarnessInlineDestination("https://example.test/a?x=1#part"))
        assertEquals(HarnessInlineTarget.External("mailto:qa@example.test"),
            parseHarnessInlineDestination("mailto:qa@example.test"))
    }

    @Test
    fun invalidDestinationsCannotBecomeFileOrIntentActions() {
        listOf("", "#fragment", "/tmp/x?bad", "x#L0", "x#L4-L2", "x#L2147483648",
            "x%00", "x%FF", "x%C3%28", "x%", "//remote/x", "javascript:alert(1)", "intent://x",
            "file:///data/private", "https://user:secret@example.test/x").forEach {
            assertNull(it, parseHarnessInlineDestination(it))
        }
    }

    @Test
    fun markdownLinksAndReferencesRetainBalancedPathsWithoutDuplicates() {
        val value = "[report](docs/report(1).md#L8) [site](https://example.test/a(b)) [again][r]\n[r]: <docs/with%20spaces.md>"
        assertEquals(listOf(HarnessInlineTarget.File("docs/report(1).md", 8),
            HarnessInlineTarget.External("https://example.test/a(b)"), HarnessInlineTarget.File("docs/with spaces.md")),
            harnessInlineLinks(value).map { it.target })
    }

    @Test
    fun userReferencesDecorateOnlyLoadedSkillNamesAndNeverCodeOrSessionMentions() {
        val tick = 96.toChar()
        val value = "@\"src/with spaces.kt\" /review /missing /review.md /review。 @[Other](dsh-session:other) " +
            tick + "@secret /review https://hidden.test" + tick + " @readme.md."
        val links = harnessInlineLinks(value, userReferences = true, skillNames = setOf("review"))
        assertEquals(listOf(HarnessInlineTarget.File("src/with spaces.kt"), HarnessInlineTarget.Skill("review"),
            HarnessInlineTarget.File("readme.md")), links.map { it.target })
        assertTrue(links.all { it.start >= 0 && it.end <= value.length })
    }

    @Test
    fun largeStreamingPreviewsKeepAnnotationWorkBounded() {
        val value = (1..2_000).joinToString(" ") { "[file](a" + it + ".txt)" }
        val links = harnessInlineLinks(value)
        assertTrue(links.size <= 128)
        assertTrue(links.all { it.end <= 24_000 })
        val fence = 96.toChar().toString().repeat(3)
        assertTrue(harnessInlineLinks(fence + "\nhttps://hidden.test\n[hidden](secret)\n" + fence).isEmpty())
    }
}
