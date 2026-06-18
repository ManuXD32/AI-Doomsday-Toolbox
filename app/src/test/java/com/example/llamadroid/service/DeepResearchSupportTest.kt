package com.example.llamadroid.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepResearchSupportTest {
    @Test
    fun `scientific and institutional sources outrank generic pages`() {
        val query = "allergic reaction treatment guideline"
        val scientific = DeepResearchSupport.scoreCandidate(
            query = query,
            title = "Clinical guideline and systematic review for allergic reaction treatment",
            url = "https://pubmed.ncbi.nlm.nih.gov/123456/",
            readableText = "This journal article includes a DOI and systematic review guideline evidence. ".repeat(30)
        )
        val generic = DeepResearchSupport.scoreCandidate(
            query = query,
            title = "Allergic reaction treatment overview",
            url = "https://example.com/allergic-reaction-treatment",
            readableText = "Allergic reaction treatment overview with general advice. ".repeat(30)
        )
        val gov = DeepResearchSupport.scoreCandidate(
            query = query,
            title = "Allergic reaction treatment guideline",
            url = "https://www.cdc.gov/allergic-reaction-treatment",
            readableText = "Government clinical guideline information for allergic reaction treatment. ".repeat(30)
        )

        assertFalse(scientific.skip)
        assertFalse(generic.skip)
        assertFalse(gov.skip)
        assertTrue(scientific.score > gov.score)
        assertTrue(gov.score > generic.score)
    }

    @Test
    fun `social and unreadable candidates are skipped`() {
        val social = DeepResearchSupport.scoreCandidate(
            query = "heart failure guideline",
            title = "Heart failure thread",
            url = "https://reddit.com/r/example/comments/1",
            readableText = "heart failure guideline ".repeat(100)
        )
        val unreadable = DeepResearchSupport.scoreCandidate(
            query = "heart failure guideline",
            title = "Short page",
            url = "https://example.org/short",
            readableText = "too short"
        )

        assertTrue(social.skip)
        assertTrue(unreadable.skip)
    }

    @Test
    fun `query variants include scientific and pdf oriented searches`() {
        val variants = DeepResearchSupport.buildQueryVariants("asthma treatment")

        assertTrue(variants.any { "filetype:pdf" in it })
        assertTrue(variants.any { "pubmed" in it })
        assertTrue(variants.any { "pmc" in it })
        assertTrue(variants.any { ".gov" in it })
        assertTrue(variants.any { ".edu" in it })
    }

    @Test
    fun `higher source limits expand query variants and search breadth`() {
        val small = DeepResearchSupport.buildQueryVariants("asthma treatment", sourceLimit = 3)
        val large = DeepResearchSupport.buildQueryVariants("asthma treatment", sourceLimit = 40)

        assertTrue(large.size > small.size)
        assertTrue(large.any { "site:nih.gov" in it || "site:who.int" in it })
        assertTrue(DeepResearchSupport.maxResultsPerQuery(40) > DeepResearchSupport.maxResultsPerQuery(3))
    }

    @Test
    fun `generic pages do not meet normal import floor by relevance alone`() {
        val query = "allergic reaction treatment guideline"
        val generic = DeepResearchSupport.scoreCandidate(
            query = query,
            title = "Allergic reaction treatment overview",
            url = "https://example.com/allergic-reaction-treatment",
            readableText = "Allergic reaction treatment overview with general advice. ".repeat(30)
        )
        val gov = DeepResearchSupport.scoreCandidate(
            query = query,
            title = "Allergic reaction treatment guideline",
            url = "https://www.cdc.gov/allergic-reaction-treatment",
            readableText = "Government clinical guideline information for allergic reaction treatment. ".repeat(30)
        )

        assertFalse(DeepResearchSupport.shouldImportScore(generic))
        assertTrue(DeepResearchSupport.shouldImportScore(gov))
    }

    @Test
    fun `search parser falls back to duckduckgo redirect links`() {
        val html = """
            <html><body>
                <a class="extra" href="/l/?uddg=https%3A%2F%2Fwww.cdc.gov%2Fallergy%2Fguide.html">CDC allergy guide</a>
                <a href="/settings">Duck settings</a>
            </body></html>
        """.trimIndent()

        val results = DeepResearchSupport.parseSearchResultsHtml(html, "allergy guideline")

        assertEquals(1, results.size)
        assertEquals("CDC allergy guide", results.first().title)
        assertEquals("https://www.cdc.gov/allergy/guide.html", results.first().url)
    }
}
