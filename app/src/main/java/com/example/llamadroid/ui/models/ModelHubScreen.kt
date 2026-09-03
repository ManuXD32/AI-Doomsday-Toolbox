package com.example.llamadroid.ui.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppContentColumn
import com.example.llamadroid.ui.components.AppHeroCard
import com.example.llamadroid.ui.components.AppPageBackground
import com.example.llamadroid.ui.components.AppPageHeader
import com.example.llamadroid.ui.navigation.Screen

/**
 * Model Hub - Landing page for model management
 * Allows users to choose between LlamaCpp, Stable Diffusion, and Whisper models
 */
@Composable
fun ModelHubScreen(navController: NavController) {
    val scrollState = rememberScrollState()
    
    AppPageBackground {
        AppContentColumn(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            AppPageHeader(
                eyebrow = stringResource(R.string.models_hub_eyebrow),
                title = stringResource(R.string.models_hub),
                subtitle = stringResource(R.string.models_hub_subtitle)
            )

            AppHeroCard(
                title = stringResource(R.string.models_hub),
                subtitle = stringResource(R.string.models_local_storage),
                badge = stringResource(R.string.nav_models)
            )

            ModelFeatureCard(
                title = "🤖 " + stringResource(R.string.models_llm),
                description = stringResource(R.string.models_llm_desc),
                icon = Icons.Default.Star,
                gradientColors = listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                ),
                onClick = { navController.navigate(Screen.LLMModels.route) }
            )

            ModelFeatureCard(
                title = "🎨 " + stringResource(R.string.models_sd),
                description = stringResource(R.string.models_sd_desc),
                icon = Icons.Default.Create,
                gradientColors = listOf(
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                ),
                onClick = { navController.navigate(Screen.SDModels.route) }
            )

            ModelFeatureCard(
                title = "🧠 " + stringResource(R.string.models_onnx),
                description = stringResource(R.string.models_onnx_desc),
                icon = Icons.Default.Create,
                gradientColors = listOf(
                    Color(0xFFFFB74D).copy(alpha = 0.15f),
                    Color(0xFFF57C00).copy(alpha = 0.3f)
                ),
                onClick = { navController.navigate(Screen.OnnxModels.route) }
            )

            ModelFeatureCard(
                title = stringResource(R.string.models_litert),
                description = stringResource(R.string.models_litert_desc),
                icon = Icons.Default.Star,
                gradientColors = listOf(
                    Color(0xFF7E57C2).copy(alpha = 0.15f),
                    Color(0xFF26A69A).copy(alpha = 0.25f)
                ),
                onClick = { navController.navigate(Screen.LiteRtModels.route) }
            )

            ModelFeatureCard(
                title = "🎙️ " + stringResource(R.string.models_whisper),
                description = stringResource(R.string.models_whisper_desc),
                icon = Icons.Default.Create,
                gradientColors = listOf(
                    Color(0xFF00BCD4).copy(alpha = 0.15f),
                    Color(0xFF00ACC1).copy(alpha = 0.3f)
                ),
                onClick = { navController.navigate(Screen.WhisperModels.route) }
            )

            ModelFeatureCard(
                title = "📤 " + stringResource(R.string.models_share),
                description = stringResource(R.string.models_share_desc),
                icon = Icons.Default.Star,
                gradientColors = listOf(
                    Color(0xFF4CAF50).copy(alpha = 0.15f),
                    Color(0xFF388E3C).copy(alpha = 0.3f)
                ),
                onClick = { navController.navigate("model_share") }
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
    gradientColors: List<androidx.compose.ui.graphics.Color>,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.horizontalGradient(gradientColors)
                )
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.width(20.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
