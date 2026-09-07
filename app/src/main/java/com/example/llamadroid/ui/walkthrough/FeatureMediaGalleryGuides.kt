package com.example.llamadroid.ui.walkthrough

import com.example.llamadroid.R
import com.example.llamadroid.ui.navigation.Screen

/** Guide for the shared saved image and video gallery opened from Library. */
internal object FeatureMediaGalleryGuides {
    val guides: List<FeatureGuide> = listOf(
        FeatureGuide(
            id = "media_gallery",
            titleRes = R.string.feature_guide_media_gallery_title,
            route = Screen.AllMediaGallery.route,
            routeBases = setOf(Screen.AllMediaGallery.route),
            recipes = listOf(
                FeatureRecipe(
                    id = "media_gallery.browse",
                    titleRes = R.string.feature_recipe_media_gallery_browse_title,
                    steps = listOf(
                        FeatureGuideStep(
                            id = "media_gallery.browse.purpose",
                            titleRes = R.string.feature_step_purpose_title,
                            bodyRes = R.string.feature_step_media_gallery_purpose,
                            previewKey = "library",
                            targetId = "media.all_gallery",
                            eventId = "media.all_gallery",
                            route = Screen.AllMediaGallery.route
                        ),
                        FeatureGuideStep(
                            id = "media_gallery.browse.result",
                            titleRes = R.string.feature_step_result_title,
                            bodyRes = R.string.feature_step_media_gallery_result,
                            previewKey = "library"
                        ),
                        FeatureGuideStep(
                            id = "media_gallery.browse.recover",
                            titleRes = R.string.feature_step_recover_title,
                            bodyRes = R.string.feature_step_media_gallery_recover,
                            previewKey = "library"
                        )
                    )
                )
            )
        )
    )
}
