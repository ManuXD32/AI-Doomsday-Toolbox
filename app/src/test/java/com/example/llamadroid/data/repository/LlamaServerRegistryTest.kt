package com.example.llamadroid.data.repository

import com.example.llamadroid.data.model.LlamaServerCardEntity
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.service.LlamaServerLaunchProfile
import com.example.llamadroid.service.LlamaServerSessionStatus
import com.example.llamadroid.service.ServerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LlamaServerRegistryTest {
    private val profile = LlamaServerLaunchProfile(modelPath = "/m/model.gguf")

    @Test fun `preset edit plan copies only when another card shares the preset`() {
        assertEquals(PresetEditPlan.UPDATE_IN_PLACE, presetEditPlan(0))
        assertEquals(PresetEditPlan.COPY_ON_WRITE, presetEditPlan(1))
        assertEquals(true, shouldDeletePresetWithCard(0))
        assertEquals(false, shouldDeletePresetWithCard(2))
    }

    @Test fun `managed session status maps to unified status`() {
        fun map(s: LlamaServerSessionStatus?) = managedStatusOf(s?.let { ManagedRuntimeView(it) })
        assertEquals(UnifiedServerStatus.STOPPED, map(null))
        assertEquals(UnifiedServerStatus.STARTING, map(LlamaServerSessionStatus.LOADING))
        assertEquals(UnifiedServerStatus.RUNNING, map(LlamaServerSessionStatus.RUNNING))
        assertEquals(UnifiedServerStatus.ERROR, map(LlamaServerSessionStatus.ERROR))
    }

    @Test fun `merge covers card without row, legacy, remote and bound counts`() {
        val card = LlamaServerCardEntity(id = 1, name = "A", savedCommandId = 10, port = 50000)
        val linkedCard = LlamaServerCardEntity(id = 2, name = "B", savedCommandId = 11, port = 50001, easyModelId = "x.gguf")
        val linkedRow = LlamaServerEntity(id = 7, name = "B", host = "127.0.0.1", port = 50001, managedServerCardId = 2)
        val legacy = LlamaServerEntity(id = 8, name = "L", host = "localhost", port = 8080)
        val remote = LlamaServerEntity(id = 9, name = "R", host = "10.0.0.2", port = 8080)
        val merged = mergeUnifiedServers(
            cards = listOf(card, linkedCard), rows = listOf(linkedRow, legacy, remote),
            profiles = mapOf(10L to profile, 11L to profile),
            sessions = mapOf("card:2" to ManagedRuntimeView(LlamaServerSessionStatus.RUNNING)),
            legacyState = ServerState.Running(8080), boundCounts = mapOf(7L to 3, 8L to 1))
        // Card B and its linked row merge into one entry: A, B, legacy and remote.
        assertEquals(4, merged.size)
        val a = merged.first { it.cardId == 1L }
        assertNull(a.rowId); assertEquals(UnifiedServerStatus.STOPPED, a.status)
        val b = merged.first { it.cardId == 2L }
        assertEquals(7L, b.rowId); assertEquals(UnifiedServerStatus.RUNNING, b.status)
        assertEquals(3, b.boundChatCount); assertEquals(true, b.isEasy)
        val l = merged.first { it.rowId == 8L }
        assertEquals(UnifiedServerKind.LOCAL_LEGACY, l.kind); assertEquals(UnifiedServerStatus.RUNNING, l.status)
        val r = merged.first { it.rowId == 9L }
        assertEquals(UnifiedServerKind.REMOTE, r.kind); assertEquals(UnifiedServerStatus.UNKNOWN, r.status)
    }

    @Test fun `missing preset or model surfaces as error unless running`() {
        val card = LlamaServerCardEntity(id = 1, name = "A", savedCommandId = 10, port = 50000)
        val missingPreset = mergeUnifiedServers(listOf(card), emptyList(), mapOf(10L to null), emptyMap(),
            ServerState.Stopped, emptyMap()).single()
        assertEquals(UnifiedServerStatus.ERROR, missingPreset.status)
        assertEquals("MANAGED_PRESET_MISSING", missingPreset.errorCode)
        val missingModel = mergeUnifiedServers(listOf(card), emptyList(), mapOf(10L to profile), emptyMap(),
            ServerState.Stopped, emptyMap()) { false }.single()
        assertEquals("MANAGED_MODEL_MISSING", missingModel.errorCode)
    }

    @Test fun `orphan managed row is reported with card missing`() {
        val row = LlamaServerEntity(id = 7, name = "B", host = "127.0.0.1", port = 1, managedServerCardId = 99)
        val s = mergeUnifiedServers(emptyList(), listOf(row), emptyMap(), emptyMap(), ServerState.Stopped, emptyMap()).single()
        assertEquals("MANAGED_CARD_MISSING", s.errorCode)
        assertEquals(UnifiedServerKind.LOCAL_MANAGED, s.kind)
    }
}
