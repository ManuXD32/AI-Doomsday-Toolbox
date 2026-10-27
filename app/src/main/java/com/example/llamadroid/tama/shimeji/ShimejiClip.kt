package com.example.llamadroid.tama.shimeji

/**
 * Animation clips used by the Shimeji overlay. Clips with [fromHome] = true are reused from the
 * home animation manifest; the others come from `assets/tama/shimeji/manifest.json`.
 */
enum class ShimejiClip(
    val fromHome: Boolean,
    val loop: Boolean,
    val isEgg: Boolean = false
) {
    // Reused from the home manifest.
    IDLE(fromHome = true, loop = true),
    WALK(fromHome = true, loop = true),
    RUN(fromHome = true, loop = true),
    EAT(fromHome = true, loop = true),
    SLEEP(fromHome = true, loop = true),
    HAPPY(fromHome = true, loop = true),
    TALK(fromHome = true, loop = true),
    SIT(fromHome = true, loop = true),

    // Hatched clips from the shimeji manifest.
    JUMP(fromHome = false, loop = false),
    FALL(fromHome = false, loop = true),
    LAND(fromHome = false, loop = false),
    DRAG(fromHome = false, loop = true),
    CLIMB(fromHome = false, loop = true),
    SIT_EDGE(fromHome = false, loop = true),
    REACT(fromHome = false, loop = false),
    BATH(fromHome = false, loop = true),

    // Egg clips from the shimeji manifest.
    EGG_WOBBLE(fromHome = false, loop = true, isEgg = true),
    EGG_BOUNCE(fromHome = false, loop = true, isEgg = true),
    EGG_FALL(fromHome = false, loop = true, isEgg = true),
    EGG_LAND(fromHome = false, loop = false, isEgg = true),
    EGG_DRAG(fromHome = false, loop = true, isEgg = true);

    /** Manifest key for this clip (lower-case enum name). */
    val manifestKey: String get() = name.lowercase()
}
