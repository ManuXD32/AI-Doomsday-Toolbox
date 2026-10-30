package com.example.llamadroid.ui.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.ui.components.AppHubRow
import com.example.llamadroid.ui.components.AppHubRowStyle
import com.example.llamadroid.ui.components.AppIcon
import com.example.llamadroid.ui.theme.LocalAppVisualStyle
import com.example.llamadroid.ui.theme.sprites.AppSpriteKey
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppContentColumn
import com.example.llamadroid.ui.components.AppHeroCard
import com.example.llamadroid.ui.components.AppPageBackground
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.rememberModelStorageInventory
import com.example.llamadroid.ui.components.ModelStorageCount
import com.example.llamadroid.data.model.StorageUsage
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import com.example.llamadroid.ui.walkthrough.walkthroughTarget

/**
 * Model Hub - Landing page for model management
 * Allows users to choose between LlamaCpp, Stable Diffusion, and Whisper models
 */
@Composable
fun ModelHubScreen(navController: NavController) {
    val scrollState = rememberScrollState()
    val storage = rememberModelStorageInventory()
    val guideTargets = LocalWalkthroughTargets.current
    
    AppScreenScaffold(title = stringResource(R.string.models_hub), onBack = { navController.popBackStack() }) {
        AppContentColumn(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            ModelFamilyChipRow(navController, current = null)
            com.example.llamadroid.ui.components.ModelStorageOverviewCard(storage)

            AppHubRow(
                title = stringResource(R.string.models_llm),
                style = AppHubRowStyle.Large,
                showChevron = false,
                subtitle = stringResource(R.string.models_llm_desc),
                icon = Icons.Default.Memory,
                sprite = AppSpriteKey.MODEL_LLM,
                onClick = { guideTargets?.recordEvent("models.download"); navController.navigate(Screen.LLMModels.route) },
                modifier = Modifier.walkthroughTarget("models.download"),
                extra = { ModelStorageCount(storage.usage("llm"), loaded = storage.loaded) }
            )

            AppHubRow(
                title = stringResource(R.string.models_sd),
                style = AppHubRowStyle.Large,
                showChevron = false,
                subtitle = stringResource(R.string.models_sd_desc),
                icon = Icons.Default.Image,
                sprite = AppSpriteKey.MODEL_IMAGE,
                onClick = { navController.navigate(Screen.SDModels.route) },
                extra = { ModelStorageCount(storage.usage("sd"), loaded = storage.loaded) }
            )

            AppHubRow(
                title = stringResource(R.string.models_onnx),
                style = AppHubRowStyle.Large,
                showChevron = false,
                subtitle = stringResource(R.string.models_onnx_desc),
                icon = Icons.Default.Hub,
                sprite = AppSpriteKey.MODEL_ONNX,
                onClick = { navController.navigate(Screen.OnnxModels.route) },
                extra = { ModelStorageCount(storage.usage("onnx"), loaded = storage.loaded) }
            )

            AppHubRow(
                title = stringResource(R.string.models_litert),
                style = AppHubRowStyle.Large,
                showChevron = false,
                subtitle = stringResource(R.string.models_litert_desc),
                icon = Icons.Default.Memory,
                sprite = AppSpriteKey.MODEL_LITERT,
                onClick = { navController.navigate(Screen.LiteRtModels.route) },
                extra = { ModelStorageCount(storage.usage("litert"), loaded = storage.loaded) }
            )

            AppHubRow(
                title = stringResource(R.string.models_whisper),
                style = AppHubRowStyle.Large,
                showChevron = false,
                subtitle = stringResource(R.string.models_whisper_desc),
                icon = Icons.Default.GraphicEq,
                sprite = AppSpriteKey.MODEL_WHISPER,
                onClick = { navController.navigate(Screen.WhisperModels.route) },
                extra = { ModelStorageCount(storage.usage("whisper"), loaded = storage.loaded) }
            )

            AppHubRow(
                title = stringResource(R.string.audio_models_title),
                style = AppHubRowStyle.Large,
                showChevron = false,
                subtitle = stringResource(R.string.audio_models_subtitle),
                icon = Icons.Default.GraphicEq,
                sprite = AppSpriteKey.MODEL_AUDIO,
                onClick = { navController.navigate(Screen.AudioModels.route) }
            )

            AppHubRow(
                title = stringResource(R.string.model_library_hub_title),
                style = AppHubRowStyle.Large,
                showChevron = false,
                subtitle = stringResource(R.string.model_library_hub_desc),
                icon = Icons.Default.Link,
                sprite = AppSpriteKey.MODEL_BUNDLE,
                onClick = { navController.navigate(Screen.ModelSources.route) }
            )

            AppHubRow(
                title = stringResource(R.string.model_storage_unknown_title),
                style = AppHubRowStyle.Large,
                showChevron = false,
                subtitle = stringResource(R.string.model_storage_unknown_desc),
                icon = Icons.Default.HelpOutline,
                onClick = { navController.navigate("${Screen.ModelSources.route}?tab=unknown") },
                extra = { ModelStorageCount(storage.usage("unknown"), loaded = storage.loaded) }
            )

            Text(
                stringResource(R.string.models_local_storage),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}
