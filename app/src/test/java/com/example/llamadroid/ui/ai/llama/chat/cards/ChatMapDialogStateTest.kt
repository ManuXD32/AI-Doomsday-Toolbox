package com.example.llamadroid.ui.ai.llama.chat.cards

import com.example.llamadroid.chat.cards.map.LeafletPageBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMapDialogStateTest {
    private val loading = MapDialogState.Loading

    @Test
    fun readyTitleMovesLoadingToReady() {
        assertEquals(MapDialogState.Ready, MapDialogReducer.onTitle(loading, "adt-map-ready"))
        assertEquals(loading, MapDialogReducer.onTitle(loading, "cardmap.adt.local/index.html"))
        assertEquals(loading, MapDialogReducer.onTitle(loading, null))
    }

    @Test
    fun errorTitleCarriesMessage() {
        assertEquals(MapDialogState.Error("boom (3)"), MapDialogReducer.onTitle(loading, "adt-map-error:boom (3)"))
    }

    @Test
    fun terminalStatesAreSticky() {
        assertEquals(MapDialogState.Ready, MapDialogReducer.onTimeout(MapDialogState.Ready))
        assertEquals(MapDialogState.Ready, MapDialogReducer.onLoadError(MapDialogState.Ready, "/leaflet/leaflet.js", 404))
        val err = MapDialogState.Error("x")
        assertEquals(err, MapDialogReducer.onTitle(err, "adt-map-ready"))
    }

    @Test
    fun loadErrorAndTimeoutFailLoading() {
        assertEquals(MapDialogState.Error("/leaflet/leaflet.js (404)"), MapDialogReducer.onLoadError(loading, "/leaflet/leaflet.js", 404))
        assertEquals(MapDialogState.Error(MapDialogReducer.TIMEOUT_DETAIL), MapDialogReducer.onTimeout(loading))
    }

    @Test
    fun criticalPathsExcludeTiles() {
        assertTrue(MapDialogReducer.isCriticalPath("/index.html"))
        assertTrue(MapDialogReducer.isCriticalPath("/leaflet/leaflet.css"))
        assertFalse(MapDialogReducer.isCriticalPath("/3/4/5.png"))
    }

    @Test
    fun pageHasResizeReadyAndErrorHooks() {
        val html = LeafletPageBuilder.build(emptyList(), false)
        listOf("requestAnimationFrame", "clientHeight", "invalidateSize", "'resize'", "adt-map-ready", "adt-map-error:", "window.onerror")
            .forEach { assertTrue("missing $it", html.contains(it)) }
    }
}
