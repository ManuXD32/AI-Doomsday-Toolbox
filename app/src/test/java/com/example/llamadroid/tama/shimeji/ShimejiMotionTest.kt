package com.example.llamadroid.tama.shimeji

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShimejiMotionTest {
    private val bounds = ShimejiBounds(width = 400f, height = 800f, floorInset = 100f, spriteSize = 100f)
    private val hatched = ShimejiProfile(isEgg = false, isSleeping = false)
    private val floorY = 600f

    private fun run(m: ShimejiMotion, rng: Random, ms: Long, stepMs: Long = 16L, until: (ShimejiMotion) -> Boolean = { false }): Boolean {
        var t = 0L
        while (t < ms) {
            m.step(stepMs, rng)
            t += stepMs
            if (until(m)) return true
        }
        return false
    }

    @Test
    fun fallingLandsOnFloor() {
        val m = ShimejiMotion(bounds, hatched, startX = 100f, startY = 0f)
        assertEquals(ShimejiMode.FALL, m.mode)
        assertTrue(run(m, Random(1), 5000L) { it.mode == ShimejiMode.LAND })
        assertEquals(floorY, m.y, 0.01f)
        assertEquals(ShimejiClip.LAND, m.clip)
    }

    @Test
    fun wanderingStaysInsideBounds() {
        val m = ShimejiMotion(bounds, ShimejiProfile(false, false, ShimejiActivity.PLAYFUL), 150f, floorY)
        val rng = Random(7)
        repeat(6000) {
            m.step(16L, rng)
            assertTrue(m.x in 0f..bounds.maxX)
            assertTrue(m.y in 0f..floorY)
        }
    }

    @Test
    fun walkingIntoWallTurnsAround() {
        var sawTurn = false
        for (seed in 1..10) {
            val m = ShimejiMotion(bounds, hatched, bounds.maxX - 5f, floorY)
            val rng = Random(seed)
            repeat(20000) {
                val before = m.facingRight
                m.step(16L, rng)
                val walking = m.mode == ShimejiMode.WALK || m.mode == ShimejiMode.RUN
                if (walking && m.x >= bounds.maxX - 0.01f && before && !m.facingRight) sawTurn = true
            }
            if (sawTurn) break
        }
        assertTrue(sawTurn)
    }

    @Test
    fun climbThenSitEdgeThenFall() {
        val m = ShimejiMotion(bounds, hatched, 100f, floorY)
        val rng = Random(5)
        m.forceClimb(rightWall = true)
        assertTrue(run(m, rng, 10000L) { it.mode == ShimejiMode.CLIMB })
        assertEquals(bounds.maxX, m.x, 0.01f)
        assertTrue(m.facingRight)
        assertTrue(run(m, rng, 20000L) { it.mode == ShimejiMode.SIT_EDGE })
        assertEquals(0f, m.y, 0.01f)
        assertEquals(ShimejiClip.SIT_EDGE, m.clip)
        assertTrue(run(m, rng, 10000L) { it.mode == ShimejiMode.FALL })
        assertEquals(bounds.maxX, m.x, 0.01f)
        assertTrue(run(m, rng, 10000L) { it.mode == ShimejiMode.LAND })
        assertEquals(floorY, m.y, 0.01f)
    }

    @Test
    fun climbLeftWallMirrorsFacing() {
        val m = ShimejiMotion(bounds, hatched, 100f, floorY)
        m.forceClimb(rightWall = false)
        assertTrue(run(m, Random(2), 10000L) { it.mode == ShimejiMode.CLIMB })
        assertEquals(0f, m.x, 0.01f)
        assertFalse(m.facingRight)
    }

    @Test
    fun boundsShrinkClampsPosition() {
        val m = ShimejiMotion(bounds, hatched, 300f, floorY)
        val small = ShimejiBounds(width = 200f, height = 400f, floorInset = 50f, spriteSize = 100f)
        m.updateBounds(small)
        assertTrue(m.x <= small.maxX)
        assertTrue(m.y <= small.floorY)
        assertEquals(small.floorY, m.y, 0.01f)
    }

    @Test
    fun sleepingDoesNotRoam() {
        val m = ShimejiMotion(bounds, ShimejiProfile(false, true, ShimejiActivity.PLAYFUL), 120f, floorY)
        assertEquals(ShimejiMode.SLEEP, m.mode)
        val rng = Random(9)
        run(m, rng, 60_000L)
        assertEquals(ShimejiMode.SLEEP, m.mode)
        assertEquals(120f, m.x, 0.01f)
        assertEquals(ShimejiClip.SLEEP, m.clip)
    }

    @Test
    fun sleepingStillFallsWhenDroppedAndThenSleeps() {
        val m = ShimejiMotion(bounds, ShimejiProfile(false, true), 120f, floorY)
        m.startDrag(170f, 200f)
        m.release(0f, 0f)
        val rng = Random(4)
        assertTrue(run(m, rng, 5000L) { it.mode == ShimejiMode.LAND })
        run(m, rng, 1000L)
        assertEquals(ShimejiMode.SLEEP, m.mode)
    }

    @Test
    fun eggUsesOnlyEggModes() {
        val m = ShimejiMotion(bounds, ShimejiProfile(true, false, ShimejiActivity.PLAYFUL), 100f, floorY)
        val allowed = setOf(
            ShimejiMode.WOBBLE, ShimejiMode.BOUNCE, ShimejiMode.IDLE,
            ShimejiMode.FALL, ShimejiMode.LAND, ShimejiMode.DRAGGED
        )
        val rng = Random(11)
        val seen = mutableSetOf<ShimejiMode>()
        repeat(10000) {
            m.step(16L, rng)
            seen += m.mode
            assertTrue(m.mode in allowed)
            assertTrue(m.clip.isEgg)
            assertEquals(floorY, m.y, 0.01f)
        }
        assertTrue(ShimejiMode.WOBBLE in seen && ShimejiMode.BOUNCE in seen)
        assertTrue(m.playOneShot(ShimejiMode.BATH, 500L))
        assertEquals(ShimejiMode.BOUNCE, m.mode)
    }

    @Test
    fun dragFollowsTouchAndClamps() {
        val m = ShimejiMotion(bounds, hatched, 100f, floorY)
        m.startDrag(200f, 300f)
        assertEquals(ShimejiMode.DRAGGED, m.mode)
        assertEquals(150f, m.x, 0.01f)
        assertEquals(250f, m.y, 0.01f)
        m.dragTo(-500f, 5000f)
        assertEquals(0f, m.x, 0.01f)
        assertEquals(floorY, m.y, 0.01f)
        m.step(1000L, Random(1))
        assertEquals(0f, m.x, 0.01f)
        assertEquals(ShimejiMode.DRAGGED, m.mode)
    }

    @Test
    fun flingIsBallisticAndBouncesOffWall() {
        val m = ShimejiMotion(bounds, hatched, 100f, floorY)
        m.startDrag(150f, 300f)
        m.release(1500f, -200f)
        assertEquals(ShimejiMode.FALL, m.mode)
        val rng = Random(1)
        var hitWall = false
        var movedUp = false
        val startY = m.y
        var t = 0
        while (m.mode == ShimejiMode.FALL && t < 600) {
            m.step(16L, rng)
            if (m.y < startY) movedUp = true
            if (m.x >= bounds.maxX - 0.01f) hitWall = true
            t++
        }
        assertTrue(movedUp)
        assertTrue(hitWall)
        assertEquals(ShimejiMode.LAND, m.mode)
        assertEquals(floorY, m.y, 0.01f)
    }

    @Test
    fun oneShotReturnsToWander() {
        val m = ShimejiMotion(bounds, hatched, 100f, floorY)
        assertTrue(m.playOneShot(ShimejiMode.EAT, 500L))
        assertEquals(ShimejiMode.EAT, m.mode)
        assertEquals(ShimejiClip.EAT, m.clip)
        run(m, Random(2), 600L)
        assertNotEquals(ShimejiMode.EAT, m.mode)
    }

    @Test
    fun oneShotRejectedWhileAirborne() {
        val m = ShimejiMotion(bounds, hatched, 100f, 0f)
        assertFalse(m.playOneShot(ShimejiMode.REACT, 500L))
    }

    @Test
    fun frameIndexLoopsAndClamps() {
        val m = ShimejiMotion(bounds, hatched, 100f, floorY)
        m.playOneShot(ShimejiMode.EAT, 100_000L) // EAT loops
        m.step(450L, Random(1))
        assertEquals(3, m.currentFrameIndex(frameCount = 4, frameMs = 150L))
        m.step(250L, Random(1))
        assertEquals(0, m.currentFrameIndex(frameCount = 4, frameMs = 150L))
        val land = ShimejiMotion(bounds, hatched, 100f, 0f)
        assertTrue(run(land, Random(1), 5000L) { it.mode == ShimejiMode.LAND })
        land.step(250L, Random(1)) // LAND does not loop
        assertEquals(1, land.currentFrameIndex(2, 50L))
    }

    @Test
    fun wanderSpeedScalesWithSpriteSize() {
        fun walkedPx(size: Float): Float {
            val b = ShimejiBounds(width = 5000f, height = 3000f, floorInset = 0f, spriteSize = size)
            val m = ShimejiMotion(b, hatched, 1000f, b.floorY)
            m.forceWalk()
            val x0 = m.x
            m.step(1000L, Random(1))
            return abs(m.x - x0)
        }
        val small = walkedPx(100f)
        val big = walkedPx(300f)
        assertEquals(ShimejiMotion.WALK_SPEED * 100f, small, 0.5f)
        assertEquals(3f, big / small, 0.02f)
    }

    @Test
    fun wandersAcrossMostOfWideTabletScreen() {
        val wide = ShimejiBounds(width = 2560f, height = 1600f, floorInset = 0f, spriteSize = 200f)
        val m = ShimejiMotion(wide, ShimejiProfile(false, false, ShimejiActivity.NORMAL), 1000f, wide.floorY)
        val rng = Random(42)
        var minX = m.x
        var maxX = m.x
        repeat(5 * 60 * 1000 / 16) {
            m.step(16L, rng)
            minX = minOf(minX, m.x)
            maxX = maxOf(maxX, m.x)
        }
        assertTrue("span ${maxX - minX} of ${wide.maxX}", maxX - minX > 0.6f * wide.maxX)
    }

    @Test
    fun holdStopsWalkingAndStaysPut() {
        val m = ShimejiMotion(bounds, ShimejiProfile(false, false, ShimejiActivity.PLAYFUL), 150f, floorY)
        m.forceWalk()
        m.setHold(true)
        assertEquals(ShimejiMode.IDLE, m.mode)
        val x0 = m.x
        val rng = Random(3)
        repeat(1000) { m.step(16L, rng) }
        assertEquals(x0, m.x, 0.001f)
        assertEquals(floorY, m.y, 0.001f)
        assertTrue(m.mode == ShimejiMode.IDLE)
    }

    @Test
    fun holdLetsAFallFinishThenStays() {
        val m = ShimejiMotion(bounds, hatched, startX = 100f, startY = 0f)
        m.setHold(true)
        assertEquals(ShimejiMode.FALL, m.mode)
        val rng = Random(5)
        assertTrue(run(m, rng, 5000L) { it.mode == ShimejiMode.LAND })
        assertEquals(floorY, m.y, 0.01f)
        val x0 = m.x
        repeat(1000) { m.step(16L, rng) }
        assertEquals(x0, m.x, 0.001f)
        assertEquals(ShimejiMode.IDLE, m.mode)
    }

    @Test
    fun holdKeepsAClimberOnTheWallAndSitsWhenOccupied() {
        val m = ShimejiMotion(bounds, hatched, startX = 400f, startY = floorY)
        m.forceClimb(true)
        run(m, Random(2), 400L)
        assertEquals(ShimejiMode.CLIMB, m.mode)
        m.setHold(true)
        val heldY = m.y
        run(m, Random(2), 5_000L)
        assertEquals(ShimejiMode.CLIMB, m.mode)
        assertEquals(heldY, m.y, 0.001f)
        m.setHold(false)
        run(m, Random(2), 200L)
        assertTrue(m.y < heldY)
        val seated = ShimejiMotion(bounds, ShimejiProfile(false, false, ShimejiActivity.NORMAL, isOccupied = true), 100f, floorY)
        seated.setHold(true)
        run(seated, Random(2), 40_000L)
        assertEquals(ShimejiMode.SIT, seated.mode)
    }

    @Test
    fun releaseResumesRoaming() {
        val m = ShimejiMotion(bounds, ShimejiProfile(false, false, ShimejiActivity.PLAYFUL), 150f, floorY)
        m.setHold(true)
        val rng = Random(11)
        run(m, rng, 10_000L)
        assertFalse(m.mode == ShimejiMode.WALK)
        m.setHold(false)
        assertFalse(m.isHeld)
        val moved = run(m, rng, 30_000L) { it.mode == ShimejiMode.WALK || it.mode == ShimejiMode.RUN || it.mode == ShimejiMode.JUMP }
        assertTrue(moved)
    }
}
