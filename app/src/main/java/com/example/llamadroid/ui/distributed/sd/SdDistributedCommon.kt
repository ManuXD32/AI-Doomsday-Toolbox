@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.llamadroid.ui.distributed.sd

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppContentColumn
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppTaskActionFooter
import com.example.llamadroid.ui.navigation.Screen
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun copyDistributedInputImageToCache(
    context: Context,
    uri: Uri,
    prefix: String
): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        val mimeType = context.contentResolver.getType(uri).orEmpty()
        val extension = when {
            mimeType.contains("png", ignoreCase = true) -> "png"
            mimeType.contains("webp", ignoreCase = true) -> "webp"
            mimeType.contains("bmp", ignoreCase = true) -> "bmp"
            else -> "jpg"
        }
        val dir = File(context.cacheDir, "sd_distributed_inputs").apply { mkdirs() }
        val output = File(dir, "${prefix}_${System.currentTimeMillis()}.$extension")
        context.contentResolver.openInputStream(uri)?.use { input ->
            output.outputStream().use { stream -> input.copyTo(stream) }
        } ?: error(context.getString(R.string.sd_dist_input_image_import_failed))
        require(output.exists() && output.length() > 0L) {
            context.getString(R.string.sd_dist_input_image_import_failed)
        }
        output.absolutePath
    }
}

@Composable
internal fun SdDistributedPage(
    navController: NavController,
    title: String,
    subtitle: String,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    AppScreenScaffold(
        title = title,
        onBack = { navController.popBackStack() },
        bottomBar = { if (footer != null) AppTaskActionFooter(content = footer) }
    ) {
        CompositionLocalProvider(LocalSdGetModels provides { navController.navigate(Screen.SDModels.route) }) {
            AppContentColumn(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                content()
            }
        }
    }
}

internal val LocalSdGetModels = compositionLocalOf<(() -> Unit)?> { null }

@Composable
internal fun SdGetModelsButton() {
    val onGetModels = LocalSdGetModels.current ?: return
    TextButton(onClick = onGetModels) {
        Text(stringResource(R.string.common_get_models_link), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun HubActionCard(
    icon: ImageVector,
    title: String,
    description: String,
    action: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AppSectionCard(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Text(action)
        }
    }
}

internal object RenderFarmPalette {
    val graphite = Color(0xFF161A1D)
    val graphiteSoft = Color(0xFF242A2E)
    val lime = Color(0xFF93D94E)
    val orange = Color(0xFFFFB14A)
    val violet = Color(0xFF8D7CFF)
    val coral = Color(0xFFFF6E6C)
}
