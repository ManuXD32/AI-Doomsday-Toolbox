package com.example.llamadroid.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsiveComponentsTest {
    @Test
    fun `expanded navigation keeps exact 360 dp boundary`() {
        assertFalse(isCompactAppNavigation(widthDp = 360, fontScale = 1.29f))
        assertTrue(isCompactAppNavigation(widthDp = 359, fontScale = 1.29f))
    }

    @Test
    fun `large font scale opts into compact navigation`() {
        assertFalse(isCompactAppNavigation(widthDp = 480, fontScale = 1.299f))
        assertTrue(isCompactAppNavigation(widthDp = 480, fontScale = 1.3f))
        assertTrue(isCompactAppNavigation(widthDp = 480, fontScale = 1.5f))
    }

    @Test
    fun `non finite font scale fails closed to compact navigation`() {
        assertTrue(isCompactAppNavigation(widthDp = 480, fontScale = Float.NaN))
        assertTrue(isCompactAppNavigation(widthDp = 480, fontScale = Float.POSITIVE_INFINITY))
    }
}
