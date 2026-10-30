package com.example.llamadroid.service.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBubbleGestureTest {
    @Test
    fun smallMovementIsATap() {
        val g = ChatBubbleGesture(touchSlopPx = 8f)
        g.onDown(100f, 100f, 50, 60)
        assertFalse(g.onMove(105f, 104f))
        assertEquals(ChatBubbleGesture.Result.TAP, g.onUp())
    }

    @Test
    fun movementBeyondSlopIsADragThatFollowsTheFinger() {
        val g = ChatBubbleGesture(8f)
        g.onDown(100f, 100f, 50, 60)
        assertTrue(g.onMove(130f, 90f))
        assertEquals(80, g.windowX)
        assertEquals(50, g.windowY)
        // Returning near the origin stays a drag once slop was exceeded.
        assertTrue(g.onMove(100f, 100f))
        assertEquals(ChatBubbleGesture.Result.DRAG_END, g.onUp())
    }

    @Test
    fun upWithoutDownIsNone() {
        assertEquals(ChatBubbleGesture.Result.NONE, ChatBubbleGesture(8f).onUp())
    }

    @Test
    fun snapPicksNearestHorizontalEdge() {
        assertEquals(4, ChatBubbleGesture.snapX(x = 100, size = 100, screenW = 1000, marginPx = 4))
        assertEquals(896, ChatBubbleGesture.snapX(x = 600, size = 100, screenW = 1000, marginPx = 4))
    }

    @Test
    fun clampKeepsBubbleOnScreen() {
        assertEquals(0, ChatBubbleGesture.clampX(-30, 100, 1000))
        assertEquals(900, ChatBubbleGesture.clampX(1200, 100, 1000))
        assertEquals(0, ChatBubbleGesture.clampY(-5, 100, 2000))
        assertEquals(1900, ChatBubbleGesture.clampY(5000, 100, 2000))
        assertEquals(0, ChatBubbleGesture.clampX(50, 100, 80))
    }
}
