package com.example.llamadroid.tama.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * Owned livestock wandering along a bottom strip of the farm field: up to 2 chickens plus one cow when
 * [cows] > 0. The strip height sets the cow size; chickens are half of it. Decorative, draws nothing
 * until the strips load and a still first frame under reduced motion.
 */
@Composable
internal fun FarmFieldEdgeAnimals(chickens: Int, cows: Int, modifier: Modifier = Modifier) {
    val chickenCount = chickens.coerceIn(0, 2)
    val cowCount = cows.coerceIn(0, 1)
    if (chickenCount + cowCount == 0) return
    val context = LocalContext.current
    val reduced = remember { farmReducedMotion(context) }
    val clock = rememberFarmAnimalClock(enabled = !reduced)
    val chickenStrips = if (chickenCount > 0) rememberFarmAnimalStrips("chicken") else null
    val cowStrips = if (cowCount > 0) rememberFarmAnimalStrips("cow") else null
    val chickenWanders = remember(chickenCount) { List(chickenCount) { FarmAnimalWander(101 + it) } }
    val cowWander = remember { FarmAnimalWander(211) }
    Canvas(modifier = modifier.clearAndSetSemantics {}) {
        val t = clock.value
        fun draw(strips: FarmAnimalStrips?, wander: FarmAnimalWander, cell: Float, staticX: Float) {
            strips ?: return
            val sample = if (reduced) FarmAnimalSample(staticX, facingRight = true, walking = true) else wander.sampleAt(t)
            drawFarmAnimal(strips, sample, if (reduced) 0 else wander.frameAt(t, sample.walking), cell, if (reduced) 0f else t)
        }
        if (cowCount > 0) draw(cowStrips, cowWander, size.height, 0.7f)
        chickenWanders.forEachIndexed { i, w -> draw(chickenStrips, w, size.height * 0.5f, 0.2f + 0.3f * i) }
    }
}
