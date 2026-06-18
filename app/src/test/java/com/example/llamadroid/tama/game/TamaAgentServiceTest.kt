package com.example.llamadroid.tama.game

import com.example.llamadroid.service.OllamaService
import com.example.llamadroid.tama.data.ActivityType
import com.example.llamadroid.tama.data.Mood
import com.example.llamadroid.tama.data.Personality
import com.example.llamadroid.tama.data.PetSpeciesLine
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.db.TamaEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test

class TamaAgentServiceTest {
    @Test
    fun `mergeTamaMessages keeps in flight assistant reply visible after history reload`() {
        val history = listOf(
            OllamaService.ChatMessage(
                id = "user-1",
                role = "user",
                content = "Hi Tama",
                timestamp = 100L
            )
        )

        val merged = mergeTamaMessages(
            storedMessages = history,
            activeMessage = ActiveTamaAssistantMessage(
                petId = "pet-1",
                assistantId = "assistant-1",
                content = "Hello back",
                thinking = "internal",
                timestamp = 200L
            )
        )

        assertEquals(2, merged.size)
        assertEquals("assistant-1", merged.last().id)
        assertEquals("Hello back", merged.last().content)
        assertEquals("internal", merged.last().thinking)
    }

    @Test
    fun `upsertTamaMessage replaces placeholder assistant content`() {
        val existing = listOf(
            OllamaService.ChatMessage(
                id = "assistant-1",
                role = "assistant",
                content = "",
                timestamp = 200L
            )
        )

        val updated = upsertTamaMessage(
            messages = existing,
            newMessage = OllamaService.ChatMessage(
                id = "assistant-1",
                role = "assistant",
                content = "Final answer",
                timestamp = 210L
            )
        )

        assertEquals(1, updated.size)
        assertEquals("Final answer", updated.first().content)
        assertNotNull(updated.first().timestamp)
    }

    @Test
    fun `buildTamaSystemPrompt includes species flavor and personality`() {
        val pet = TamaPet(
            name = "Nori",
            species = "dragon",
            mood = Mood.HAPPY,
            personality = Personality.CHEERFUL
        )
        val prompt = buildTamaSystemPrompt(
            basePrompt = "You are a lovable pet.",
            pet = pet,
            speciesLine = PetSpeciesLine.DRAGON,
            memory = TamaStructuredMemory(
                shortTermSummary = "Nori played today.",
                longTermSummary = "Nori likes naps.",
                retrievalNotes = listOf(
                    TamaRetrievalNote("Nori keeps a toy nearby.", listOf("toy", "play"))
                )
            ),
            recentEvents = listOf(
                TamaEventEntity(
                    id = "event-1",
                    timestamp = 100L,
                    petId = pet.id,
                    eventType = "PLAYED",
                    details = "Played with a toy",
                    locationId = null,
                    npcId = null,
                    statsChangeJson = null
                )
            ),
            retrievalHints = setOf("play", "toy"),
            currentTime = "09:15"
        )

        assertTrue(prompt.contains("Species Line: Dragon"))
        assertTrue(prompt.contains("cute little dragon"))
        assertTrue(prompt.contains("Personality: CHEERFUL: Always optimistic and friendly"))
        assertTrue(prompt.contains("Current Local Time Right Now: 09:15"))
        assertTrue(prompt.contains("Default to 1-3 short sentences"))
    }

    @Test
    fun `buildTamaActivityContextLine describes working state`() {
        val now = 7_200_000L
        val pet = TamaPet(
            name = "Nori",
            currentActivity = ActivityType.WORKING,
            currentWorkJobId = "shop_helper",
            activityStartTime = 3_600_000L
        )

        val contextLine = buildTamaActivityContextLine(
            pet = pet,
            workJobName = "Shop Helper",
            now = now
        )

        assertTrue(contextLine.contains("working as Shop Helper"))
        assertTrue(contextLine.contains("Elapsed time: 1:00:00"))
        assertTrue(contextLine.contains("14 coins"))
    }

    @Test
    fun `buildTamaActivityContextLine describes studying state`() {
        val pet = TamaPet(
            name = "Nori",
            currentActivity = ActivityType.STUDYING,
            activityStartTime = 60_000L
        )

        val contextLine = buildTamaActivityContextLine(
            pet = pet,
            studyContext = "The pet is studying in Pomodoro mode. Current phase: Focus. Selected labels: Math.",
            now = 180_000L
        )

        assertTrue(contextLine.contains("studying right now"))
        assertTrue(contextLine.contains("Pomodoro mode"))
        assertTrue(contextLine.contains("Current phase: Focus"))
        assertTrue(contextLine.contains("Elapsed study time: 02:00"))
    }

    @Test
    fun `buildTamaActivityContextLine describes park relaxing state`() {
        val pet = TamaPet(
            name = "Nori",
            currentActivity = ActivityType.RELAXING,
            activityStartTime = 0L
        )

        val contextLine = buildTamaActivityContextLine(
            pet = pet,
            now = 30L * 60L * 1000L
        )

        assertTrue(contextLine.contains("relaxing in the park"))
        assertTrue(contextLine.contains("Elapsed time: 30:00"))
        assertTrue(contextLine.contains("20 happiness"))
    }

    @Test
    fun `buildTamaSystemPrompt includes precise current moment detail`() {
        val pet = TamaPet(
            name = "Nori",
            species = "dragon",
            currentActivity = ActivityType.RELAXING,
            activityStartTime = 0L
        )

        val prompt = buildTamaSystemPrompt(
            basePrompt = "You are a lovable pet.",
            pet = pet,
            speciesLine = PetSpeciesLine.DRAGON,
            memory = TamaStructuredMemory("", "", emptyList()),
            recentEvents = emptyList(),
            retrievalHints = emptySet(),
            currentTime = "12:00",
            activityContext = buildTamaActivityContextLine(pet, now = 60L * 60L * 1000L)
        )

        assertTrue(prompt.contains("Current moment detail: The pet is relaxing in the park right now."))
    }
}
