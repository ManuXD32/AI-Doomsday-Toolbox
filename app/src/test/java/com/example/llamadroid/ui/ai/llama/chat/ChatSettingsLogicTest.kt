package com.example.llamadroid.ui.ai.llama.chat

import com.example.llamadroid.data.repository.UnifiedServerStatus
import com.example.llamadroid.ui.ai.llama.LlamaSamplerField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSettingsLogicTest {

    @Test
    fun chatBindingWinsOverRouteAndLastUsed() {
        assertEquals(7L, resolveEffectiveServerId(chatServerId = 7L, routeServerId = 3L))
    }

    @Test
    fun routeServerUsedWhenChatIsUnbound() {
        assertEquals(3L, resolveEffectiveServerId(chatServerId = null, routeServerId = 3L))
    }

    @Test
    fun noBindingAndNoRouteFallsBackToLastUsed() {
        assertNull(resolveEffectiveServerId(chatServerId = null, routeServerId = -1L))
        assertNull(resolveEffectiveServerId(chatServerId = 0L, routeServerId = 0L))
    }

    @Test
    fun toolsSummaryCountsEnabledFlags() {
        val summary = ChatToolsSummary.of(true, false, true, false, false)
        assertEquals(2, summary.on)
        assertEquals(5, summary.total)
        assertEquals(0, ChatToolsSummary.of(false, false).on)
    }

    @Test
    fun invalidSamplerTextBlocksSavingForThatField() {
        val invalid = firstInvalidSamplerField(
            liteRt = false,
            temperatureText = "0.8",
            topPText = "2.5",
            topKText = "40",
            minPText = "0.05",
            repPenText = "1.1"
        )
        assertEquals(LlamaSamplerField.TOP_P, invalid)
    }

    @Test
    fun liteRtIgnoresMinPAndRepetitionPenalty() {
        val invalid = firstInvalidSamplerField(
            liteRt = true,
            temperatureText = "0.8",
            topPText = "0.9",
            topKText = "40",
            minPText = "garbage",
            repPenText = ""
        )
        assertNull(invalid)
    }

    @Test
    fun liteRtTemperatureAboveOneIsInvalid() {
        val invalid = firstInvalidSamplerField(true, "1.5", "0.9", "40", "", "")
        assertEquals(LlamaSamplerField.TEMPERATURE, invalid)
    }

    @Test
    fun autoSaveRequiresLoadBaselineAndRealChange() {
        assertFalse(shouldAutoSave(loaded = false, lastSaved = "a", current = "b"))
        assertFalse(shouldAutoSave(loaded = true, lastSaved = null, current = "b"))
        assertFalse(shouldAutoSave(loaded = true, lastSaved = "a", current = "a"))
        assertTrue(shouldAutoSave(loaded = true, lastSaved = "a", current = "b"))
    }

    @Test
    fun bannerShowsRemovedAndNoServerFirst() {
        assertEquals(
            ChatServerBannerKind.SERVER_REMOVED,
            banner(boundServerMissing = true, hasActiveServer = false)
        )
        assertEquals(ChatServerBannerKind.NO_SERVER, banner(hasActiveServer = false))
    }

    @Test
    fun bannerStoppedStartingAndError() {
        assertEquals(ChatServerBannerKind.STOPPED, banner(status = UnifiedServerStatus.STOPPED))
        assertEquals(ChatServerBannerKind.STARTING, banner(status = UnifiedServerStatus.STARTING))
        assertEquals(ChatServerBannerKind.STARTING, banner(status = UnifiedServerStatus.STOPPED, actionBusy = true))
        assertEquals(ChatServerBannerKind.ERROR, banner(status = UnifiedServerStatus.ERROR))
        assertEquals(ChatServerBannerKind.ERROR, banner(status = UnifiedServerStatus.STOPPED, actionHasError = true))
    }

    @Test
    fun bannerUsesGenerationStatusOnlyWhileNotRunning() {
        assertEquals(
            ChatServerBannerKind.STARTING,
            banner(status = UnifiedServerStatus.STOPPED, generationStatusText = "Starting local server…")
        )
        assertEquals(
            ChatServerBannerKind.NONE,
            banner(status = UnifiedServerStatus.RUNNING, generationStatusText = "Using tool")
        )
    }

    @Test
    fun remoteServersNeverShowStartBanner() {
        assertEquals(ChatServerBannerKind.NONE, banner(isLocal = false, status = UnifiedServerStatus.STOPPED))
    }

    private fun banner(
        boundServerMissing: Boolean = false,
        hasActiveServer: Boolean = true,
        isLocal: Boolean = true,
        status: UnifiedServerStatus? = UnifiedServerStatus.RUNNING,
        actionBusy: Boolean = false,
        actionHasError: Boolean = false,
        generationStatusText: String? = null
    ) = resolveChatServerBanner(
        boundServerMissing = boundServerMissing,
        hasActiveServer = hasActiveServer,
        isLocal = isLocal,
        status = status,
        actionBusy = actionBusy,
        actionHasError = actionHasError,
        generationStatusText = generationStatusText
    )
}
