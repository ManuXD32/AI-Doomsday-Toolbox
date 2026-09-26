package com.example.llamadroid.tama.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeAnimationTimingTest {
    @Test fun eggBlinkUsesOnlyItsTwoIdleFrames() {
        assertEquals(0, homeAnimationFrame(2179, 100, 2, true))
        assertEquals(1, homeAnimationFrame(2180, 100, 2, true))
        assertEquals(0, homeAnimationFrame(2400, 100, 2, true))
    }

    @Test fun clipUsesManifestTimingAndLoopsWithoutChangingFramePixels() {
        assertEquals(0, homeAnimationFrame(0, 120, 4, false))
        assertEquals(3, homeAnimationFrame(479, 120, 4, false))
        assertEquals(0, homeAnimationFrame(480, 120, 4, false))
    }
}
