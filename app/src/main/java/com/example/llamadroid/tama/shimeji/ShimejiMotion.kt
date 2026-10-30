package com.example.llamadroid.tama.shimeji

import kotlin.math.abs
import kotlin.random.Random

enum class ShimejiMode {
    WALK, RUN, IDLE, SIT, JUMP, FALL, LAND, CLIMB, SIT_EDGE, DRAGGED,
    REACT, BATH, EAT, HAPPY, TALK, SLEEP,
    WOBBLE, BOUNCE
}

enum class ShimejiActivity { CALM, NORMAL, PLAYFUL }

/** Screen area in px. The floor is [floorInset] px above [height]. Sprite position is top-left. */
data class ShimejiBounds(
    val width: Float,
    val height: Float,
    val floorInset: Float,
    val spriteSize: Float
) {
    val maxX: Float get() = (width - spriteSize).coerceAtLeast(0f)
    val floorY: Float get() = (height - floorInset - spriteSize).coerceAtLeast(0f)
}

data class ShimejiProfile(
    val isEgg: Boolean,
    val isSleeping: Boolean,
    val activity: ShimejiActivity = ShimejiActivity.NORMAL,
    /** Studying, working or relaxing: the pet stays seated instead of wandering (still draggable). */
    val isOccupied: Boolean = false
)

/**
 * Deterministic Shimeji state machine. Drive it with [step]; read x/y/facingRight/mode/clip.
 * Pure Kotlin (no android.*). Coordinates: y grows downward; velocities in px/s. All physics constants are in sprite heights
 * (per second) and scaled by [ShimejiBounds.spriteSize], so feet match the stride on any screen.
 */
class ShimejiMotion(
    bounds: ShimejiBounds,
    profile: ShimejiProfile,
    startX: Float = 0f,
    startY: Float = 0f
) {
    var bounds: ShimejiBounds = bounds
        private set
    var profile: ShimejiProfile = profile
        private set
    var x: Float = startX
        private set
    var y: Float = startY
        private set
    var facingRight: Boolean = true
        private set
    var mode: ShimejiMode = ShimejiMode.FALL
        private set
    var clipElapsedMs: Long = 0L
        private set

    private var vx = 0f
    private var vy = 0f
    private var remainingMs = 0L
    private var climbWallRight: Boolean? = null // set while walking toward a wall to climb

    /** True while the user has a menu open: the pet stays put (see [setHold]). */
    var isHeld: Boolean = false
        private set

    private val unit: Float get() = bounds.spriteSize.coerceAtLeast(1f)

    /** Wander durations stretch on wide screens so the pet can cross the whole width. */
    private val wanderScale: Float
        get() = (bounds.maxX / (unit * WANDER_REF_SPRITES)).coerceIn(1f, WANDER_MAX_SCALE)

    val clip: ShimejiClip get() = clipFor(mode, profile.isEgg)

    init {
        clampPosition()
        if (y < this.bounds.floorY) {
            setMode(ShimejiMode.FALL, 0L)
        } else {
            y = this.bounds.floorY
            setMode(
                when {
                    profile.isSleeping && !profile.isEgg -> ShimejiMode.SLEEP
                    seated() -> ShimejiMode.SIT
                    else -> restMode()
                },
                if (seated()) SEATED_MS else 0L
            )
        }
    }

    val isGrounded: Boolean
        get() = mode != ShimejiMode.FALL && mode != ShimejiMode.JUMP &&
            mode != ShimejiMode.DRAGGED && mode != ShimejiMode.CLIMB && mode != ShimejiMode.SIT_EDGE

    fun currentFrameIndex(frameCount: Int, frameMs: Long): Int {
        if (frameCount <= 1 || frameMs <= 0L) return 0
        val raw = (clipElapsedMs / frameMs).toInt()
        return if (clip.loop) raw % frameCount else minOf(raw, frameCount - 1)
    }

    fun updateBounds(newBounds: ShimejiBounds) {
        bounds = newBounds
        clampPosition()
        when (mode) {
            ShimejiMode.CLIMB, ShimejiMode.SIT_EDGE -> {
                x = if (facingRight) bounds.maxX else 0f
                y = y.coerceIn(0f, bounds.floorY)
            }
            else -> if (isGrounded) y = bounds.floorY
        }
    }

    fun updateProfile(newProfile: ShimejiProfile) {
        val old = profile
        profile = newProfile
        if (newProfile == old) return
        if (mode == ShimejiMode.DRAGGED) return
        if (mode == ShimejiMode.CLIMB || mode == ShimejiMode.SIT_EDGE) {
            if (newProfile.isEgg || newProfile.isSleeping || newProfile.isOccupied) letGo()
            return
        }
        if (!isGrounded) return
        climbWallRight = null
        when {
            newProfile.isEgg -> if (!old.isEgg) setMode(ShimejiMode.IDLE, 0L)
            newProfile.isSleeping -> setMode(ShimejiMode.SLEEP, 0L)
            newProfile.isOccupied -> setMode(ShimejiMode.SIT, SEATED_MS)
            mode == ShimejiMode.SLEEP || old.isEgg || old.isOccupied -> setMode(ShimejiMode.IDLE, 0L)
        }
    }

    /**
     * Hold mode for the care menu. While held the pet stops roaming and rests in place (IDLE, or SIT when
     * occupied); walking, running and the next random action are suppressed. A climber clings where it is
     * and an edge-sitter stays seated, so the menu does not have to chase it. An airborne pet still
     * finishes its fall or jump (gravity), lands and then stays. Releasing resumes normal behaviour
     * within about a second. Sleeping pets and eggs keep their own rest poses.
     */
    fun setHold(hold: Boolean) {
        if (hold == isHeld) return
        isHeld = hold
        if (!hold) {
            if (isGrounded && remainingMs > IDLE_MIN_MS) remainingMs = IDLE_MIN_MS
            return
        }
        climbWallRight = null
        when (mode) {
            ShimejiMode.WALK, ShimejiMode.RUN -> {
                vx = 0f
                setMode(heldRestMode(), IDLE_MIN_MS)
            }
            else -> Unit
        }
    }

    private fun heldRestMode(): ShimejiMode = when {
        profile.isEgg -> ShimejiMode.WOBBLE
        profile.isSleeping -> ShimejiMode.SLEEP
        profile.isOccupied -> ShimejiMode.SIT
        else -> ShimejiMode.IDLE
    }

    fun startDrag(px: Float, py: Float) {
        climbWallRight = null
        vx = 0f
        vy = 0f
        setMode(ShimejiMode.DRAGGED, 0L)
        dragTo(px, py)
    }

    /** Touch point in px; the sprite is centred on it and clamped inside the bounds. */
    fun dragTo(px: Float, py: Float) {
        if (mode != ShimejiMode.DRAGGED) return
        val half = bounds.spriteSize / 2f
        val nx = (px - half).coerceIn(0f, bounds.maxX)
        if (abs(nx - x) > 1f) facingRight = nx > x
        x = nx
        y = (py - half).coerceIn(0f, bounds.floorY)
    }

    /** Releases a drag; velocity in px/s makes a ballistic fling. */
    fun release(velX: Float, velY: Float) {
        if (mode != ShimejiMode.DRAGGED) return
        val cap = MAX_FLING * unit
        vx = velX.coerceIn(-cap, cap)
        vy = velY.coerceIn(-cap, cap)
        if (abs(vx) > MIN_FACING_SPEED * unit) facingRight = vx > 0f
        setMode(ShimejiMode.FALL, 0L)
    }

    /**
     * Plays a one-shot (REACT/BATH/EAT/HAPPY/TALK) then returns to wandering. Eggs always BOUNCE.
     * Returns false if the pet is airborne, climbing or dragged.
     */
    fun playOneShot(oneShot: ShimejiMode, durationMs: Long): Boolean {
        if (!isGrounded) return false
        climbWallRight = null
        vx = 0f
        val target = if (profile.isEgg) ShimejiMode.BOUNCE else oneShot
        setMode(target, durationMs.coerceAtLeast(1L))
        return true
    }

    fun step(dtMs: Long, rng: Random) {
        var left = dtMs
        while (left > 0L) {
            val chunk = minOf(left, MAX_SUBSTEP_MS)
            subStep(chunk, rng)
            left -= chunk
        }
    }

    /** Test/diagnostic hook: walk right for a long time. */
    internal fun forceWalk() {
        if (!isGrounded || profile.isEgg || profile.isSleeping) return
        facingRight = true
        setMode(ShimejiMode.WALK, Long.MAX_VALUE / 4)
    }

    /** Test/diagnostic hook: walk to the given wall and climb it. */
    internal fun forceClimb(rightWall: Boolean) {
        if (!isGrounded || profile.isEgg || profile.isSleeping) return
        beginClimbWalk(rightWall)
    }

    private fun subStep(ms: Long, rng: Random) {
        val dt = ms / 1000f
        clipElapsedMs += ms
        when (mode) {
            ShimejiMode.DRAGGED, ShimejiMode.SLEEP -> Unit
            ShimejiMode.FALL, ShimejiMode.JUMP -> airborne(dt)
            ShimejiMode.CLIMB -> if (!isHeld) climb(dt)
            ShimejiMode.SIT_EDGE -> if (!isHeld) tickTimer(ms, rng)
            ShimejiMode.WALK, ShimejiMode.RUN -> {
                walk(dt)
                if (mode == ShimejiMode.WALK || mode == ShimejiMode.RUN) tickTimer(ms, rng)
            }
            else -> tickTimer(ms, rng)
        }
    }

    private fun tickTimer(ms: Long, rng: Random) {
        remainingMs -= ms
        if (remainingMs <= 0L) chooseNext(rng)
    }

    private fun airborne(dt: Float) {
        vy += GRAVITY * unit * dt
        x += vx * dt
        y += vy * dt
        if (x < 0f) {
            x = 0f
            vx = abs(vx) * WALL_BOUNCE
            if (vx > MIN_FACING_SPEED * unit) facingRight = true
        } else if (x > bounds.maxX) {
            x = bounds.maxX
            vx = -abs(vx) * WALL_BOUNCE
            if (-vx > MIN_FACING_SPEED * unit) facingRight = false
        }
        if (y < 0f) {
            y = 0f
            if (vy < 0f) vy = -vy * CEILING_BOUNCE
        }
        val floor = bounds.floorY
        if (y >= floor && vy >= 0f) {
            y = floor
            vx = 0f
            vy = 0f
            setMode(ShimejiMode.LAND, if (profile.isEgg) EGG_LAND_MS else LAND_MS)
        }
    }

    private fun walk(dt: Float) {
        val speed = (if (mode == ShimejiMode.RUN) RUN_SPEED else WALK_SPEED) * unit
        x += (if (facingRight) speed else -speed) * dt
        val max = bounds.maxX
        if (x <= 0f || x >= max) {
            val atRight = x >= max
            x = if (atRight) max else 0f
            val wall = climbWallRight
            if (wall != null && wall == atRight) {
                startClimb(atRight)
            } else {
                facingRight = !atRight
            }
        }
    }

    private fun climb(dt: Float) {
        y -= CLIMB_SPEED * unit * dt
        x = if (facingRight) bounds.maxX else 0f
        if (y <= 0f) {
            y = 0f
            setMode(ShimejiMode.SIT_EDGE, SIT_EDGE_MS)
        }
    }

    private fun startClimb(rightWall: Boolean) {
        climbWallRight = null
        facingRight = rightWall // sprite faces the wall; renderer mirrors for the left wall
        x = if (rightWall) bounds.maxX else 0f
        setMode(ShimejiMode.CLIMB, 0L)
    }

    private fun letGo() {
        vx = 0f
        vy = 0f
        setMode(ShimejiMode.FALL, 0L)
    }

    private fun beginClimbWalk(rightWall: Boolean) {
        if (bounds.maxX <= 0f) {
            setMode(ShimejiMode.IDLE, IDLE_MIN_MS)
            return
        }
        val atWall = if (rightWall) x >= bounds.maxX else x <= 0f
        if (atWall) {
            startClimb(rightWall)
            return
        }
        climbWallRight = rightWall
        facingRight = rightWall
        setMode(ShimejiMode.WALK, Long.MAX_VALUE / 4)
    }

    private fun chooseNext(rng: Random) {
        climbWallRight = null
        if (mode == ShimejiMode.SIT_EDGE) {
            letGo()
            return
        }
        if (isHeld) {
            setMode(heldRestMode(), IDLE_MIN_MS)
            return
        }
        if (profile.isEgg) {
            val roll = rng.nextInt(100)
            val next = when {
                roll < 50 -> ShimejiMode.WOBBLE
                roll < 80 -> ShimejiMode.IDLE
                else -> ShimejiMode.BOUNCE
            }
            setMode(next, randomMs(rng, 1200L, 3500L))
            return
        }
        if (profile.isSleeping) {
            setMode(ShimejiMode.SLEEP, 0L)
            return
        }
        if (profile.isOccupied) {
            setMode(ShimejiMode.SIT, SEATED_MS)
            return
        }
        val w = weights(profile.activity)
        var roll = rng.nextInt(w.sum())
        var index = 0
        while (index < w.size - 1 && roll >= w[index]) {
            roll -= w[index]
            index++
        }
        when (index) {
            0 -> setMode(ShimejiMode.IDLE, randomMs(rng, IDLE_MIN_MS, 4000L))
            1 -> setMode(ShimejiMode.SIT, randomMs(rng, 2000L, 6000L))
            2 -> startWalk(rng, ShimejiMode.WALK, randomMs(rng, 1500L, 5000L, wanderScale))
            3 -> startWalk(rng, ShimejiMode.RUN, randomMs(rng, 800L, 2500L, wanderScale))
            4 -> {
                facingRight = when {
                    x <= 0f -> true
                    x >= bounds.maxX -> false
                    else -> rng.nextBoolean()
                }
                vx = (if (facingRight) JUMP_VX else -JUMP_VX) * unit
                vy = -JUMP_VY * unit
                setMode(ShimejiMode.JUMP, 0L)
            }
            else -> beginClimbWalk(rng.nextBoolean())
        }
    }

    private fun startWalk(rng: Random, walkMode: ShimejiMode, durationMs: Long) {
        facingRight = when {
            x <= 0f -> true
            x >= bounds.maxX -> false
            else -> rng.nextBoolean()
        }
        setMode(walkMode, durationMs)
    }

    /** Weights: idle, sit, walk, run, jump, climb. */
    private fun weights(activity: ShimejiActivity): IntArray = when (activity) {
        ShimejiActivity.CALM -> intArrayOf(40, 30, 25, 0, 3, 2)
        ShimejiActivity.NORMAL -> intArrayOf(25, 15, 35, 8, 10, 7)
        ShimejiActivity.PLAYFUL -> intArrayOf(10, 5, 30, 25, 20, 10)
    }

    private fun seated(): Boolean = profile.isOccupied && !profile.isEgg && !profile.isSleeping

    private fun restMode(): ShimejiMode = if (profile.isEgg) ShimejiMode.WOBBLE else ShimejiMode.IDLE

    private fun randomMs(rng: Random, min: Long, max: Long, scale: Float = 1f): Long =
        (min + rng.nextLong(max - min + 1L)).let { if (scale == 1f) it else (it * scale).toLong() }

    private fun setMode(next: ShimejiMode, durationMs: Long) {
        mode = next
        remainingMs = durationMs
        clipElapsedMs = 0L
    }

    private fun clampPosition() {
        x = x.coerceIn(0f, bounds.maxX)
        y = y.coerceIn(0f, bounds.floorY)
    }

    companion object {
        // Sprite heights per second (per second squared for GRAVITY). Walk matches a 4-frame 160 ms stride.
        const val GRAVITY = 15f
        const val WALK_SPEED = 0.55f
        const val RUN_SPEED = 1.6f
        const val CLIMB_SPEED = 0.6f
        const val JUMP_VX = 1.0f
        const val JUMP_VY = 4.2f
        const val WALL_BOUNCE = 0.6f
        const val CEILING_BOUNCE = 0.3f
        const val MAX_FLING = 20f
        const val MIN_FACING_SPEED = 0.15f
        private const val WANDER_REF_SPRITES = 5f
        private const val WANDER_MAX_SCALE = 4f
        const val MAX_SUBSTEP_MS = 50L
        const val LAND_MS = 300L
        const val EGG_LAND_MS = 350L
        const val SIT_EDGE_MS = 2000L
        private const val IDLE_MIN_MS = 1200L
        private const val SEATED_MS = 30_000L

        fun clipFor(mode: ShimejiMode, egg: Boolean): ShimejiClip = if (egg) {
            when (mode) {
                ShimejiMode.BOUNCE -> ShimejiClip.EGG_BOUNCE
                ShimejiMode.FALL -> ShimejiClip.EGG_FALL
                ShimejiMode.LAND -> ShimejiClip.EGG_LAND
                ShimejiMode.DRAGGED -> ShimejiClip.EGG_DRAG
                else -> ShimejiClip.EGG_WOBBLE
            }
        } else {
            when (mode) {
                ShimejiMode.WALK -> ShimejiClip.WALK
                ShimejiMode.RUN -> ShimejiClip.RUN
                ShimejiMode.IDLE -> ShimejiClip.IDLE
                ShimejiMode.SIT -> ShimejiClip.SIT
                ShimejiMode.JUMP -> ShimejiClip.JUMP
                ShimejiMode.FALL -> ShimejiClip.FALL
                ShimejiMode.LAND -> ShimejiClip.LAND
                ShimejiMode.CLIMB -> ShimejiClip.CLIMB
                ShimejiMode.SIT_EDGE -> ShimejiClip.SIT_EDGE
                ShimejiMode.DRAGGED -> ShimejiClip.DRAG
                ShimejiMode.REACT -> ShimejiClip.REACT
                ShimejiMode.BATH -> ShimejiClip.BATH
                ShimejiMode.EAT -> ShimejiClip.EAT
                ShimejiMode.HAPPY -> ShimejiClip.HAPPY
                ShimejiMode.TALK -> ShimejiClip.TALK
                ShimejiMode.SLEEP -> ShimejiClip.SLEEP
                ShimejiMode.WOBBLE, ShimejiMode.BOUNCE -> ShimejiClip.IDLE
            }
        }
    }
}
