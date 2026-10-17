package com.example.llamadroid.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalSearchMatcherTest {
    private fun fields(title: String, vararg keywords: String, text: String = "") =
        SearchFields(listOf(title), keywords.toList(), listOf(text))

    @Test
    fun `normalize strips accents case and extra spaces`() {
        assertEquals("transcripcion de audio", GlobalSearchMatcher.normalize("  Transcripción   DE audio "))
    }

    @Test
    fun `matching ignores accents in both directions`() {
        assertTrue(GlobalSearchMatcher.score("transcripcion", fields("Transcripción")) > 0)
        assertTrue(GlobalSearchMatcher.score("Transcripción", fields("Transcripcion")) > 0)
    }

    @Test
    fun `english and spanish titles and keywords both match`() {
        val tool = SearchFields(listOf("Image generation", "Generación de imágenes"), listOf("stable diffusion", "imagen", "dibujar"))
        assertTrue(GlobalSearchMatcher.score("image", tool) > 0)
        assertTrue(GlobalSearchMatcher.score("generacion", tool) > 0)
        assertTrue(GlobalSearchMatcher.score("dibujar", tool) > 0)
        assertEquals(0, GlobalSearchMatcher.score("calendar", tool))
    }

    @Test
    fun `prefix outranks contains and title outranks keyword`() {
        val prefix = fields("Whisper")
        val contains = fields("Openwhisper models")
        val wordPrefix = fields("Speech whisper")
        val keywordOnly = fields("Transcribe", "whisper")
        val ranked = GlobalSearchMatcher.rank("whis", listOf(keywordOnly, contains, wordPrefix, prefix)) { it }
        assertEquals(listOf(prefix, wordPrefix, contains, keywordOnly).map { it.titles }, ranked.map { it.titles })
    }

    @Test
    fun `all words must match and blank queries return nothing`() {
        val item = fields("Video upscaler", "enlarge")
        assertTrue(GlobalSearchMatcher.score("video enlarge", item) > 0)
        assertEquals(0, GlobalSearchMatcher.score("video calendar", item))
        assertEquals(0, GlobalSearchMatcher.score("   ", item))
        assertTrue(GlobalSearchMatcher.rank("", listOf(item)) { it }.isEmpty())
    }

    @Test
    fun `description text matches last`() {
        val byText = fields("Alpha", text = "browse offline articles")
        val byTitle = fields("Offline library")
        val ranked = GlobalSearchMatcher.rank("offline", listOf(byText, byTitle)) { it }
        assertEquals(listOf(byTitle, byText), ranked)
    }

    @Test
    fun `ties keep catalog order`() {
        val a = fields("Chat one")
        val b = fields("Chat two")
        assertEquals(listOf(a, b), GlobalSearchMatcher.rank("chat", listOf(a, b)) { it })
    }

    @Test
    fun `recent searches are deduplicated capped and newest first`() {
        var recent = emptyList<String>()
        (1..10).forEach { recent = GlobalSearchMatcher.addRecent(recent, "query $it") }
        assertEquals(8, recent.size)
        assertEquals("query 10", recent.first())
        recent = GlobalSearchMatcher.addRecent(recent, "QUERY 5")
        assertEquals("QUERY 5", recent.first())
        assertEquals(1, recent.count { GlobalSearchMatcher.normalize(it) == "query 5" })
        assertEquals(recent, GlobalSearchMatcher.addRecent(recent, " a "))
    }
}
