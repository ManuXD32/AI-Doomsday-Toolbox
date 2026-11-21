package com.example.llamadroid.ui.ai

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.example.llamadroid.R
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.sd.SdBundleGenerationDefaults
import com.example.llamadroid.sd.SdComponentFit
import com.example.llamadroid.sd.SdComponentRole
import com.example.llamadroid.sd.SdImageDefaults
import com.example.llamadroid.sd.SdImg2ImgBlock
import com.example.llamadroid.sd.sdComponentCandidates
import com.example.llamadroid.sd.sdImg2ImgBlock

/** Installed files of [role] for [main], family matches first, never hiding unverified files. */
internal fun imageGenComponentModels(
    main: ModelEntity?,
    pool: List<ModelEntity>,
    role: SdComponentRole
): List<ModelEntity> = sdComponentCandidates(main, pool, role).map { it.model }

/** Path of the first family-matching file of [role]; never auto-picks an unverified file. */
internal fun firstMatchPath(main: ModelEntity?, pool: List<ModelEntity>, role: SdComponentRole): String? =
    sdComponentCandidates(main, pool, role).firstOrNull { it.fit == SdComponentFit.MATCH }?.model?.path

/**
 * Calls [onApply] with the curated defaults of [main] whenever the user picks a main model.
 * The path present at first composition (draft restore) is skipped so restored settings
 * survive; picking a model on a fresh screen or switching models applies the defaults.
 */
@Composable
internal fun ImageGenBundleDefaultsEffect(
    selectedPath: String?,
    main: ModelEntity?,
    onApply: (SdImageDefaults) -> Unit
) {
    val currentMain by rememberUpdatedState(main)
    val currentOnApply by rememberUpdatedState(onApply)
    var initialSeen by remember { mutableStateOf(false) }
    LaunchedEffect(selectedPath) {
        if (!initialSeen) {
            initialSeen = true
            if (!selectedPath.isNullOrBlank()) return@LaunchedEffect
        }
        SdBundleGenerationDefaults.forMain(currentMain?.takeIf { it.path == selectedPath })?.let(currentOnApply)
    }
}

/** Why img2img cannot run with [main] (null = allowed): the block plus its inline hint and launch-error strings. */
internal class ImageGenImg2ImgBlock(val hintRes: Int, val errorRes: Int)

internal fun imageGenImg2ImgBlock(main: ModelEntity?, llmVisionPath: String?): ImageGenImg2ImgBlock? =
    when (sdImg2ImgBlock(main, llmVisionPath.orEmpty())) {
        null -> null
        SdImg2ImgBlock.NOT_SUPPORTED -> ImageGenImg2ImgBlock(
            R.string.media_picker_img2img_not_supported, R.string.sd_dist_error_img2img_not_supported
        )
        SdImg2ImgBlock.NEEDS_VISION_PROJECTOR -> ImageGenImg2ImgBlock(
            R.string.media_picker_img2img_needs_projector, R.string.sd_dist_error_img2img_needs_projector
        )
    }

@Composable
internal fun ImageGenImg2ImgHint(block: ImageGenImg2ImgBlock) {
    Text(
        text = stringResource(block.hintRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 8.dp)
    )
}
