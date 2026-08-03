package com.example.llamadroid.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppContentColumn
import com.example.llamadroid.ui.components.AppHubCard
import com.example.llamadroid.ui.components.AppPageBackground
import com.example.llamadroid.ui.components.AppPageHeader
import com.example.llamadroid.ui.navigation.Screen

data class SettingsItem(
    val emoji: String,
    val title: String,
    val description: String,
    val gradientColors: List<Color>,
    val route: String
)

/**
 * Settings Hub - 2-column square grid layout
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHubScreen(navController: NavController) {
    val settingsItems = listOf(
        SettingsItem(
            emoji = "📂",
            title = stringResource(R.string.settings_general),
            description = stringResource(R.string.settings_general_desc),
            gradientColors = listOf(
                Color(0xFF607D8B).copy(alpha = 0.15f),
                Color(0xFF455A64).copy(alpha = 0.3f)
            ),
            route = "settings_general"
        ),
        SettingsItem(
            emoji = "💬",
            title = stringResource(R.string.settings_llm),
            description = stringResource(R.string.settings_llm_desc),
            gradientColors = listOf(
                Color(0xFF4CAF50).copy(alpha = 0.15f),
                Color(0xFF388E3C).copy(alpha = 0.3f)
            ),
            route = "settings_llm"
        ),
        SettingsItem(
            emoji = "📝",
            title = stringResource(R.string.settings_prompts),
            description = stringResource(R.string.settings_prompts_desc),
            gradientColors = listOf(
                Color(0xFF009688).copy(alpha = 0.15f),
                Color(0xFF00796B).copy(alpha = 0.3f)
            ),
            route = "settings_prompts"
        ),
        SettingsItem(
            emoji = "🔧",
            title = stringResource(R.string.settings_debug),
            description = stringResource(R.string.settings_debug_desc),
            gradientColors = listOf(
                Color(0xFF795548).copy(alpha = 0.15f),
                Color(0xFF5D4037).copy(alpha = 0.3f)
            ),
            route = Screen.Logs.route
        ),
        SettingsItem(
            emoji = "📊",
            title = stringResource(R.string.settings_stats),
            description = stringResource(R.string.settings_stats_desc),
            gradientColors = listOf(
                Color(0xFF00897B).copy(alpha = 0.15f),
                Color(0xFF00695C).copy(alpha = 0.3f)
            ),
            route = Screen.Stats.route
        ),
        SettingsItem(
            emoji = "ℹ️",
            title = stringResource(R.string.settings_about),
            description = stringResource(R.string.settings_about_desc),
            gradientColors = listOf(
                Color(0xFF3F51B5).copy(alpha = 0.15f),
                Color(0xFF303F9F).copy(alpha = 0.3f)
            ),
            route = "about"
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
                    eyebrow = "SETTINGS",
                    title = "⚙️ " + stringResource(R.string.settings_title),
                    subtitle = stringResource(R.string.settings_subtitle)
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(settingsItems) { item ->
                    AppHubCard(
                        emoji = item.emoji,
                        title = item.title,
                        description = item.description,
                        gradientColors = item.gradientColors,
                        onClick = { navController.navigate(item.route) },
                        modifier = Modifier.aspectRatio(1f)
                    )
                }
            }
        }
    }
}
