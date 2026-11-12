package com.example.llamadroid.ui.distributed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppHubRow
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.AppSectionTitle
import com.example.llamadroid.ui.distributed.common.DistributedHowItWorksStrip
import com.example.llamadroid.ui.distributed.common.DistributedInfoCard
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import com.example.llamadroid.ui.walkthrough.walkthroughTarget

/**
 * Entry point of distributed inference. Two clear cards (Main device / Helper device) for text models,
 * a link to the media (images and video) variant, and one info card with the network notes.
 */
@Composable
fun DistributedScreen(navController: NavController) {
    val scrollState = rememberScrollState()
    val walkthroughTargets = LocalWalkthroughTargets.current

    AppScreenScaffold(
        title = stringResource(R.string.dist_ui_hub_title),
        onBack = { navController.popBackStack() }
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            DistributedHowItWorksStrip(
                steps = listOf(
                    stringResource(R.string.dist_ui_hub_how_1),
                    stringResource(R.string.dist_ui_hub_how_2),
                    stringResource(R.string.dist_ui_hub_how_3)
                )
            )

            Column(
                modifier = Modifier.walkthroughTarget("distributed.roles"),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AppSectionTitle(title = stringResource(R.string.dist_ui_hub_section_llm))
                RoleCard(
                    icon = Icons.Default.Star,
                    title = stringResource(R.string.dist_ui_hub_main_title),
                    description = stringResource(R.string.dist_ui_hub_main_desc),
                    buttonText = stringResource(R.string.dist_ui_hub_main_btn),
                    onClick = {
                        walkthroughTargets?.recordEvent("distributed.roles")
                        navController.navigate(Screen.MasterMode.route)
                    }
                )
                RoleCard(
                    icon = Icons.Default.Link,
                    title = stringResource(R.string.dist_ui_hub_helper_title),
                    description = stringResource(R.string.dist_ui_hub_helper_desc),
                    buttonText = stringResource(R.string.dist_ui_hub_helper_btn),
                    onClick = {
                        walkthroughTargets?.recordEvent("distributed.roles")
                        navController.navigate(Screen.WorkerMode.route)
                    }
                )
            }

            AppSectionTitle(title = stringResource(R.string.dist_ui_hub_section_media))
            AppHubRow(
                title = stringResource(R.string.dist_ui_hub_media_title),
                subtitle = stringResource(R.string.dist_ui_hub_media_desc),
                icon = Icons.Default.PhotoLibrary,
                onClick = { navController.navigate(Screen.SdDistributedHub.route) }
            )

            Spacer(modifier = Modifier.height(4.dp))

            DistributedInfoCard(
                title = stringResource(R.string.dist_ui_hub_info_title),
                paragraphs = listOf(
                    stringResource(R.string.dist_ui_hub_info_wifi),
                    stringResource(R.string.dist_ui_hub_info_vpn),
                    stringResource(R.string.dist_ui_hub_info_security)
                )
            )
        }
    }
}

@Composable
private fun RoleCard(
    icon: ImageVector,
    title: String,
    description: String,
    buttonText: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppChromeDefaults.CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(10.dp)
                            .size(26.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(buttonText, maxLines = 2)
            }
        }
    }
}
