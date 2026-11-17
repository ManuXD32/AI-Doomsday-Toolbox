package com.example.llamadroid.chat.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCardFenceTest {
    private val choices = """{"type":"choices","question":"Q","options":["a","b"]}"""

    @Test fun cardLanguageIsAlwaysACard() {
        assertTrue(ChatCardFence.isCard("card", choices))
        assertTrue(ChatCardFence.isCard(" CARD ", ""))
        assertTrue(ChatCardFence.isCard("card extra", "not json"))
    }

    @Test fun jsonAndUnlabeledFencesNeedAKnownTypedCard() {
        assertTrue(ChatCardFence.isCard("json", choices))
        assertTrue(ChatCardFence.isCard("JSON", "  $choices\n"))
        assertTrue(ChatCardFence.isCard("", choices))
        assertTrue(ChatCardFence.isCard("", """{"type":"quiz","question":"Q","options":["a","b"]}"""))
    }

    @Test fun nonCardsStayCode() {
        assertFalse(ChatCardFence.isCard("json", """{"name":"x","value":1}"""))
        assertFalse(ChatCardFence.isCard("json", """{"type":"object","properties":{}}"""))
        assertFalse(ChatCardFence.isCard("json", """{"type":"choices","question":"Q","options":["a"""))
        assertFalse(ChatCardFence.isCard("", "[1,2,3]"))
        assertFalse(ChatCardFence.isCard("", ""))
        assertFalse(ChatCardFence.isCard("python", choices))
    }

    @Test fun unclosedCardFenceBeforeTheNextCardIsClosed() {
        val a = """{"type":"choices","question":"A","options":["a","b"]}"""
        val b = """{"type":"choices","question":"B","options":["a","b"]}"""
        val c = """{"type":"chart","kind":"bar","labels":["x"],"series":[{"name":"s","data":[1]}]}"""
        val broken = "Intro\n```card\n$a\n\n```card\n$b\n\n```card\n$c\n```\nEnd"
        val fixed = ChatCardFence.normalize(broken)
        assertEquals("Intro\n```card\n$a\n\n```\n```card\n$b\n\n```\n```card\n$c\n```\nEnd", fixed)
        assertEquals(fixed, ChatCardFence.normalize(fixed))
    }

    @Test fun normalizeLeavesOtherTextAlone() {
        val wellFormed = "```card\n{}\n```\ntext\n```python\nprint(1)\n```\n```card\n{}\n```"
        assertEquals(wellFormed, ChatCardFence.normalize(wellFormed))
        val codeThenCard = "```python\nx = 1\n```card\n{}\n```"
        assertEquals(codeThenCard, ChatCardFence.normalize(codeThenCard))
        assertEquals("no fences", ChatCardFence.normalize("no fences"))
        assertEquals("```card\n{\"type\":\"cho", ChatCardFence.normalize("```card\n{\"type\":\"cho"))
    }
}
