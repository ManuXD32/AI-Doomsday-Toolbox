package com.example.llamadroid.tama.game

import com.example.llamadroid.service.OllamaService
import com.example.llamadroid.tama.data.PetSpeciesLine
import com.example.llamadroid.tama.data.TamaPet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TamaChatPromptLayoutTest {
    private val memory = TamaStructuredMemory("short", "long", emptyList())

    private fun history(count: Int): List<OllamaService.ChatMessage> =
        (0 until count).map {
            OllamaService.ChatMessage(
                id = "m$it",
                role = if (it % 2 == 0) "user" else "assistant",
                content = "message $it"
            )
        }

    private fun turn(pet: TamaPet, time: String, hist: List<OllamaService.ChatMessage>, text: String): List<OllamaService.ChatMessage> {
        val stable = buildTamaStableSystemPrompt("Base.", pet, PetSpeciesLine.DRAGON, memory)
        val volatile = buildTamaVolatileContext(pet, memory, emptyList(), emptySet(), time)
        return assembleTamaChatMessages(
            stable,
            hist,
            OllamaService.ChatMessage(id = "u-$text", role = "user", content = text),
            volatile
        )
    }

    @Test
    fun `consecutive turns share byte identical system message and history prefix`() {
        val base = TamaPet(name = "Nori", species = "dragon")
        val changed = base.copy(money = base.money + 50, stats = base.stats.copy(hunger = 12f))
        val h1 = history(6)
        val h2 = h1 + OllamaService.ChatMessage(id = "u-one", role = "user", content = "one") +
            OllamaService.ChatMessage(id = "a-one", role = "assistant", content = "reply")
        val first = turn(base, "09:15", h1, "one")
        val second = turn(changed, "09:16", h2, "two")

        assertEquals(first.first().content, second.first().content)
        // Everything before the latest user message of turn 1 is a prefix of turn 2.
        val prefix = first.dropLast(1)
        assertEquals(prefix, second.take(prefix.size))
        assertEquals("one", first.last().content.substringAfter("[End Live Context]\n\n"))
    }

    @Test
    fun `volatile block only on latest user message and exactly one system message`() {
        val pet = TamaPet(name = "Nori", species = "dragon")
        val messages = turn(pet, "09:15", history(5), "hello")

        assertEquals(1, messages.count { it.role == "system" })
        assertEquals("system", messages.first().role)
        assertFalse(messages.first().content.contains("Current Local Time"))
        assertFalse(messages.first().content.contains("Stats:"))
        val withContext = messages.filter { it.content.contains("[Live Context]") && it.role != "system" }
        assertEquals(listOf(messages.last()), withContext)
        assertTrue(messages.last().content.endsWith("hello"))
    }

    @Test
    fun `anchored window moves in blocks`() {
        val starts = (25..45).map { n ->
            val window = anchoredTamaHistoryWindow(history(n))
            assertTrue(window.size <= TAMA_HISTORY_WINDOW_MAX)
            window.first().id
        }
        assertEquals("m0", starts[0])
        // Up to 30 messages nothing is dropped, then the start jumps by 10 and stays there.
        assertEquals(listOf("m0", "m10", "m20"), starts.distinct())
        assertEquals("m10", anchoredTamaHistoryWindow(history(31)).first().id)
        assertEquals("m10", anchoredTamaHistoryWindow(history(40)).first().id)
        assertEquals("m20", anchoredTamaHistoryWindow(history(41)).first().id)
    }

    @Test
    fun `system rows in history are dropped`() {
        val pet = TamaPet(name = "Nori", species = "dragon")
        val hist = history(2) + OllamaService.ChatMessage(role = "system", content = "note")
        assertEquals(1, turn(pet, "09:15", hist, "hi").count { it.role == "system" })
    }
}
