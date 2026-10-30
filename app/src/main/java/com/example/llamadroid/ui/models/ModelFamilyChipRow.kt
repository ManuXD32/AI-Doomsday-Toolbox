package com.example.llamadroid.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.model.library.ModelFamily
import com.example.llamadroid.ui.navigation.Screen

/** One destination of [ModelFamilyChipRow]; [family] is null for the Sources entry. */
internal data class ModelFamilyChip(
    val id: String,
    val family: ModelFamily?,
    val labelRes: Int,
    val route: String
)

internal val modelFamilyChips: List<ModelFamilyChip> = listOf(
    ModelFamilyChip("chat", ModelFamily.LLM, R.string.model_family_chip_chat, Screen.LLMModels.route),
    ModelFamilyChip("images", ModelFamily.SD, R.string.model_family_chip_images, Screen.SDModels.route),
    ModelFamilyChip("onnx", ModelFamily.ONNX, R.string.model_family_chip_onnx, Screen.OnnxModels.route),
    ModelFamilyChip("litert", ModelFamily.LITERT, R.string.model_family_chip_litert, Screen.LiteRtModels.createRoute()),
    ModelFamilyChip("stt", ModelFamily.WHISPER, R.string.model_family_chip_stt, Screen.WhisperModels.route),
    ModelFamilyChip("audio", ModelFamily.AUDIO, R.string.model_family_chip_audio, Screen.AudioModels.route),
    ModelFamilyChip("sources", null, R.string.model_family_chip_sources, Screen.ModelSources.route)
)

/**
 * Horizontally scrollable family switcher shown at the top of every model manager and the hub.
 * [current] is the family whose manager is on screen (null on the hub or the Sources screen).
 */
@Composable
internal fun ModelFamilyChipRow(
    navController: NavController,
    current: ModelFamily?,
    modifier: Modifier = Modifier,
    sourcesSelected: Boolean = false
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("model_family_chip_row"),
        contentPadding = PaddingValues(horizontal = 0.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(modelFamilyChips, key = { it.id }) { chip ->
            val selected = if (chip.family == null) sourcesSelected else chip.family == current
            FilterChip(
                selected = selected,
                onClick = {
                    if (!selected) navController.navigate(chip.route) { launchSingleTop = true }
                },
                label = {
                    Text(
                        text = stringResource(chip.labelRes),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                modifier = Modifier.testTag("model_family_chip_${chip.id}")
            )
        }
    }
}
