package com.example.llamadroid.ui.library

import com.example.llamadroid.ui.navigation.AppRootDestination
import com.example.llamadroid.ui.navigation.AppRoutePresentations
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.walkthrough.FeatureGuideCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AllMediaGalleryRouteTest {
    @Test
    fun `all media route is registered as a Library feature`() {
        val guide = FeatureGuideCatalog.forRoute(Screen.AllMediaGallery.route)

        assertEquals("media_gallery", guide?.id)
        assertEquals(Screen.AllMediaGallery.route, guide?.route)
        assertTrue(FeatureGuideCatalog.recipe("media_gallery.browse")?.steps?.size == 3)
        assertEquals(
            AppRootDestination.Library,
            AppRoutePresentations.forRoute(Screen.AllMediaGallery.route).parent
        )
    }
}
