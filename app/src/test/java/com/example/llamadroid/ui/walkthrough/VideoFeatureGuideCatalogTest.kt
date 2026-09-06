package com.example.llamadroid.ui.walkthrough

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class VideoFeatureGuideCatalogTest {
    @Test
    fun `video creation recipes start on Create and recover through Gallery`() {
        val guide = FeatureGuideCatalog.forRoute("video_gen")
        assertNotNull(guide)

        listOf("video.quickstart", "video.families", "video.lingbot", "video.lora").forEach { id ->
            val recipe = guide!!.recipes.first { it.id == id }
            assertEquals("video.create_tab", recipe.steps[0].targetId)
            assertEquals("video.create_tab", recipe.steps[0].eventId)
            assertEquals(
                if (id == "video.lora") "video.loras" else "video.profile",
                recipe.steps[1].targetId
            )
            assertEquals(recipe.steps[1].targetId, recipe.steps[1].eventId)
            assertEquals("video.gallery_tab", recipe.steps[2].targetId)
            assertEquals("video.gallery_tab", recipe.steps[2].eventId)
        }
    }
}
