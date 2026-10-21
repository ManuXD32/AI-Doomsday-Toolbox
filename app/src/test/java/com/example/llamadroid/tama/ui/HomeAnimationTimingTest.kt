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

    @Test fun manifestParsesHomeEntriesAndIgnoresUnknownKeys() {
        val manifest = parseTamaAnimationManifest(
            """{"schemaVersion":1,"scope":"x","hatchFx":{"id":"fx_hatch"},"assets":[
            {"id":"home_dragon_baby","path":"p.png","frameWidth":256,"frameHeight":256,"columns":4,
             "footAnchor":{"x":128,"y":224},"productionReady":true,"rows":1,
             "clips":[{"action":"IDLE","frames":[0,1],"frameMs":640,"frameExportPaths":["a","b"]}]},
            {"id":"fx_hatch","path":"q.png","frameWidth":64,"frameHeight":64,"columns":4,"clips":[]}]}"""
        )
        assertEquals(1, manifest.schemaVersion)
        val home = manifest.assets.first { it.id == "home_dragon_baby" }
        assertEquals(224f, home.footAnchor.y)
        assertEquals(640, home.clips.single().frameMs)
        assertEquals(true, home.productionReady)
        assertEquals(false, manifest.assets.last().productionReady)
    }
}
