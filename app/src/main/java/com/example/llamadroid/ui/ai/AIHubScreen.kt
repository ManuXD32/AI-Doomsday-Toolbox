package com.example.llamadroid.ui.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppContentColumn
import com.example.llamadroid.ui.components.AppHubCard
import com.example.llamadroid.ui.components.AppPageBackground
import com.example.llamadroid.ui.components.AppPageHeader
import com.example.llamadroid.ui.navigation.Screen

data class AIToolItem(
    val emoji: String,
    val title: String,
    val description: String,
    val gradientColors: List<Color>,
    val route: String
)

/**
 * AI Hub - 2-column square grid layout
 * Notes Manager moved to bottom nav bar
 */
@Composable
fun AIHubScreen(navController: NavController) {
    val aiTools = listOf(
        AIToolItem(
            emoji = "🌐",
            title = stringResource(R.string.ai_servers_hub_title),
            description = stringResource(R.string.ai_servers_hub_card_desc),
            gradientColors = listOf(
                Color(0xFF00A896).copy(alpha = 0.15f),
                Color(0xFF3A86FF).copy(alpha = 0.28f)
            ),
            route = Screen.AiServersHub.route
        ),
        AIToolItem(
            emoji = "💬",
            title = stringResource(R.string.ai_chat),
            description = stringResource(R.string.ai_chat_desc),
            gradientColors = listOf(
                Color(0xFF4CAF50).copy(alpha = 0.15f),
                Color(0xFF388E3C).copy(alpha = 0.3f)
            ),
            route = Screen.Chat.route
        ),
        AIToolItem(
            emoji = "🎨",
            title = stringResource(R.string.ai_image_gen),
            description = stringResource(R.string.ai_image_gen_desc),
            gradientColors = listOf(
                Color(0xFF2196F3).copy(alpha = 0.15f),
                Color(0xFF1976D2).copy(alpha = 0.3f)
            ),
            route = Screen.ImageGen.route
        ),
        AIToolItem(
            emoji = "🧠",
            title = stringResource(R.string.ai_onnx_image_gen),
            description = stringResource(R.string.ai_onnx_image_gen_desc),
            gradientColors = listOf(
                Color(0xFFFFA726).copy(alpha = 0.15f),
                Color(0xFFFB8C00).copy(alpha = 0.3f)
            ),
            route = Screen.OnnxImageGen.route
        ),
        AIToolItem(
            emoji = "🔊",
            title = stringResource(R.string.ai_onnx_tts),
            description = stringResource(R.string.ai_onnx_tts_desc),
            gradientColors = listOf(
                Color(0xFF7E57C2).copy(alpha = 0.15f),
                Color(0xFF5E35B1).copy(alpha = 0.3f)
            ),
            route = Screen.OnnxTts.route
        ),
        AIToolItem(
            emoji = "🌐",
            title = stringResource(R.string.live_translator_title),
            description = stringResource(R.string.live_translator_hub_desc),
            gradientColors = listOf(
                Color(0xFF00897B).copy(alpha = 0.15f),
                Color(0xFF3949AB).copy(alpha = 0.3f)
            ),
            route = Screen.LiveTranslator.route
        ),
        AIToolItem(
            emoji = "✂️",
            title = stringResource(R.string.ai_onnx_bgr),
            description = stringResource(R.string.ai_onnx_bgr_desc),
            gradientColors = listOf(
                Color(0xFF26A69A).copy(alpha = 0.15f),
                Color(0xFF00897B).copy(alpha = 0.3f)
            ),
            route = Screen.OnnxBackgroundRemoval.route
        ),
        AIToolItem(
            emoji = "🎥",
            title = stringResource(R.string.ai_video_gen),
            description = stringResource(R.string.ai_video_gen_desc),
            gradientColors = listOf(
                Color(0xFFE53935).copy(alpha = 0.15f),
                Color(0xFFC62828).copy(alpha = 0.3f)
            ),
            route = Screen.VideoGen.route
        ),
        AIToolItem(
            emoji = "🎙️",
            title = stringResource(R.string.ai_transcription),
            description = stringResource(R.string.ai_transcription_desc),
            gradientColors = listOf(
                Color(0xFF00BCD4).copy(alpha = 0.15f),
                Color(0xFF00ACC1).copy(alpha = 0.3f)
            ),
            route = Screen.AudioTranscription.route
        ),
        AIToolItem(
            emoji = "🎬",
            title = stringResource(R.string.ai_video_upscaler),
            description = stringResource(R.string.ai_video_upscaler_desc),
            gradientColors = listOf(
                Color(0xFF9C27B0).copy(alpha = 0.15f),
                Color(0xFF7B1FA2).copy(alpha = 0.3f)
            ),
            route = Screen.VideoUpscaler.route
        ),
        AIToolItem(
            emoji = "🔤",
            title = stringResource(R.string.subtitle_burn_title),
            description = stringResource(R.string.subtitle_burn_desc),
            gradientColors = listOf(
                Color(0xFFFF9800).copy(alpha = 0.15f),
                Color(0xFFF57C00).copy(alpha = 0.3f)
            ),
            route = Screen.SubtitleBurn.route
        ),
        AIToolItem(
            emoji = "📄",
            title = stringResource(R.string.ai_pdf_tools),
            description = stringResource(R.string.ai_pdf_tools_desc),
            gradientColors = listOf(
                Color(0xFFE91E63).copy(alpha = 0.15f),
                Color(0xFFC2185B).copy(alpha = 0.3f)
            ),
            route = "pdf_toolbox"
        ),
        AIToolItem(
            emoji = "🎥",
            title = stringResource(R.string.ai_video_sumup),
            description = stringResource(R.string.ai_video_sumup_desc),
            gradientColors = listOf(
                Color(0xFFFF5722).copy(alpha = 0.15f),
                Color(0xFFE64A19).copy(alpha = 0.3f)
            ),
            route = "video_sumup"
        ),
        AIToolItem(
            emoji = "⚙️",
            title = stringResource(R.string.hub_workflows),
            description = stringResource(R.string.hub_workflows_desc),
            gradientColors = listOf(
                Color(0xFF607D8B).copy(alpha = 0.15f),
                Color(0xFF455A64).copy(alpha = 0.3f)
            ),
            route = Screen.Workflows.route
        ),
        AIToolItem(
            emoji = "⚡",
            title = stringResource(R.string.hub_benchmark),
            description = stringResource(R.string.hub_benchmark_desc),
            gradientColors = listOf(
                Color(0xFFFFEB3B).copy(alpha = 0.15f),
                Color(0xFFFBC02D).copy(alpha = 0.3f)
            ),
            route = Screen.Benchmark.route
        ),
        AIToolItem(
            emoji = "📊",
            title = stringResource(R.string.hub_dataset),
            description = stringResource(R.string.hub_dataset_desc),
            gradientColors = listOf(
                Color(0xFF00BCD4).copy(alpha = 0.15f),
                Color(0xFF0097A7).copy(alpha = 0.3f)
            ),
            route = Screen.Dataset.route
        ),
        AIToolItem(
            emoji = "🧬",
            title = stringResource(R.string.quadtrix_title),
            description = stringResource(R.string.quadtrix_hub_desc),
            gradientColors = listOf(
                Color(0xFF26A69A).copy(alpha = 0.15f),
                Color(0xFF00897B).copy(alpha = 0.3f)
            ),
            route = Screen.QuadtrixTrainer.route
        ),
        AIToolItem(
            emoji = "🖥️",
            title = stringResource(R.string.hub_termux),
            description = stringResource(R.string.hub_termux_desc),
            gradientColors = listOf(
                Color(0xFF37474F).copy(alpha = 0.15f),
                Color(0xFF263238).copy(alpha = 0.3f)
            ),
            route = Screen.Termux.route
        ),
        AIToolItem(
            emoji = "🤖",
            title = stringResource(R.string.hub_agent),
            description = stringResource(R.string.hub_agent_desc),
            gradientColors = listOf(
                Color(0xFF673AB7).copy(alpha = 0.15f),
                Color(0xFF512DA8).copy(alpha = 0.3f)
            ),
            route = Screen.Agent.route
        ),
        AIToolItem(
            emoji = "🦙",
            title = stringResource(R.string.ollama_title),
            description = stringResource(R.string.ollama_desc),
            gradientColors = listOf(
                Color(0xFF000000).copy(alpha = 0.15f),
                Color(0xFF333333).copy(alpha = 0.3f)
            ),
            route = Screen.OllamaManager.route
        ),
        AIToolItem(
            emoji = "🐍",
            title = stringResource(R.string.llama_client_title),
            description = stringResource(R.string.llama_client_desc),
            gradientColors = listOf(
                Color(0xFFFF5722).copy(alpha = 0.15f),
                Color(0xFFFF8A65).copy(alpha = 0.3f)
            ),
            route = Screen.LlamaServerList.route
        )
    )

    AppPageBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            AppContentColumn(
                modifier = Modifier.fillMaxWidth(),
                bottomPadding = 0.dp,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AppPageHeader(
                    eyebrow = "AI",
                    title = "🤖 " + stringResource(R.string.ai_hub_title),
                    subtitle = stringResource(R.string.ai_hub_subtitle)
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(aiTools) { item ->
                    AppHubCard(
                        emoji = item.emoji,
                        title = item.title,
                        description = item.description,
                        gradientColors = item.gradientColors,
                        modifier = Modifier.aspectRatio(1f),
                        onClick = {
                            if (item.route.isNotEmpty()) {
                                navController.navigate(item.route)
                            }
                        }
                    )
                }
            }
        }
    }
}
