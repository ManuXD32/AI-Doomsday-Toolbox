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
import androidx.compose.material.icons.filled.Share
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
            ModelFeatureCard(
                title = stringResource(R.string.models_llm),
                description = stringResource(R.string.models_llm_desc),
                icon = Icons.Default.Memory,
                onClick = { guideTargets?.recordEvent("models.download"); navController.navigate(Screen.LLMModels.route) },
                modifier = Modifier.walkthroughTarget("models.download"),
                usage = storage.usage("llm"),
                storageLoaded = storage.loaded
            )

            ModelFeatureCard(
                title = stringResource(R.string.models_sd),
                description = stringResource(R.string.models_sd_desc),
                icon = Icons.Default.Image,
                onClick = { navController.navigate(Screen.SDModels.route) },
                usage = storage.usage("sd"),
                storageLoaded = storage.loaded
            )

            ModelFeatureCard(
                title = stringResource(R.string.models_onnx),
                description = stringResource(R.string.models_onnx_desc),
                icon = Icons.Default.Hub,
                onClick = { navController.navigate(Screen.OnnxModels.route) },
                usage = storage.usage("onnx"),
                storageLoaded = storage.loaded
            )

            ModelFeatureCard(
                title = stringResource(R.string.models_litert),
                description = stringResource(R.string.models_litert_desc),
                icon = Icons.Default.Memory,
                onClick = { navController.navigate(Screen.LiteRtModels.route) },
                usage = storage.usage("litert"),
                storageLoaded = storage.loaded
            )

            ModelFeatureCard(
                title = stringResource(R.string.models_whisper),
                description = stringResource(R.string.models_whisper_desc),
                icon = Icons.Default.GraphicEq,
                onClick = { navController.navigate(Screen.WhisperModels.route) },
                usage = storage.usage("whisper"),
                storageLoaded = storage.loaded
            )

            ModelFeatureCard(
                title = stringResource(R.string.model_library_hub_title),
                description = stringResource(R.string.model_library_hub_desc),
                icon = Icons.Default.Link,
                onClick = { navController.navigate(Screen.ModelSources.route) }
            )

            ModelFeatureCard(
                title = stringResource(R.string.model_storage_unknown_title),
                description = stringResource(R.string.model_storage_unknown_desc),
                icon = Icons.Default.HelpOutline,
                onClick = { navController.navigate("${Screen.ModelSources.route}?tab=unknown") },
                usage = storage.usage("unknown"), storageLoaded = storage.loaded
            )

            ModelFeatureCard(
                title = stringResource(R.string.models_share),
                description = stringResource(R.string.models_share_desc),
                icon = Icons.Default.Share,
                onClick = { guideTargets?.recordEvent("models.share"); navController.navigate("model_share") },
                modifier = Modifier.walkthroughTarget("models.share")
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

@Composable
private fun ModelFeatureCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    usage: StorageUsage? = null,
    storageLoaded: Boolean = true
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.74f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                usage?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    ModelStorageCount(it, loaded = storageLoaded)
                }
            }
        }
    }
}
