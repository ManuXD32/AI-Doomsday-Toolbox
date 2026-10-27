package com.example.llamadroid.tama.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TamaNoticeStateTest {
    @Test
    fun showSetsCurrentAndDismissClears() {
        val state = TamaNoticeState()
        state.show("Planted", isError = false)
        assertEquals("Planted", state.current?.message)
        assertFalse(state.current!!.isError)
        state.dismiss()
        assertNull(state.current)
    }

    @Test
    fun blankMessageIsIgnored() {
        val state = TamaNoticeState()
        state.show("  ")
        assertNull(state.current)
    }

    @Test
    fun newNoticeReplacesOldWithNewId() {
        val state = TamaNoticeState()
        state.show("one")
        val first = state.current!!
        state.show("two", isError = true)
        val second = state.current!!
        assertNotEquals(first.id, second.id)
        assertEquals("two", second.message)
        assertTrue(second.isError)
    }

    @Test
    fun staleTimerCannotDismissNewerNotice() {
        val state = TamaNoticeState()
        state.show("one")
        val staleId = state.current!!.id
        state.show("two")
        state.dismiss(staleId)
        assertEquals("two", state.current?.message)
        state.dismiss(state.current!!.id)
        assertNull(state.current)
    }

    @Test
    fun autoHideDurations() {
        assertEquals(2_500L, TamaNoticeState.autoHideMillis(false))
        assertEquals(4_000L, TamaNoticeState.autoHideMillis(true))
    }

    @Test
    fun onlyTopMostHostDraws() {
        val state = TamaNoticeState()
        val root = Any()
        val dialog = Any()
        assertFalse(state.isTopHost(root))
        state.registerHost(root)
        assertTrue(state.isTopHost(root))
        state.registerHost(dialog)
        assertFalse(state.isTopHost(root))
        assertTrue(state.isTopHost(dialog))
        state.unregisterHost(dialog)
        assertTrue(state.isTopHost(root))
        state.unregisterHost(root)
        assertFalse(state.isTopHost(root))
    }

    @Test
    fun reRegisteringMovesHostToTop() {
        val state = TamaNoticeState()
        val a = Any()
        val b = Any()
        state.registerHost(a)
        state.registerHost(b)
        state.registerHost(a)
        assertTrue(state.isTopHost(a))
    }

    private fun contrast(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble() + 0.05
        val l2 = b.luminance().toDouble() + 0.05
        return maxOf(l1, l2) / minOf(l1, l2)
    }

    @Test
    fun noticeColorsMeetAa() {
        val s = tamaLcdDarkScheme
        assertTrue(contrast(s.surface, s.onSurface) >= 4.5)
        assertTrue(contrast(s.errorContainer, s.onErrorContainer) >= 4.5)
    }
}
