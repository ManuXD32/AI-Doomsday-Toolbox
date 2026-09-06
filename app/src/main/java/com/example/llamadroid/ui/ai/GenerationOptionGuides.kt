package com.example.llamadroid.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.llamadroid.ui.walkthrough.WalkthroughDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.llamadroid.R
import com.example.llamadroid.service.SdCacheMode
import com.example.llamadroid.service.SdCacheScmPolicy

data class GenerationOptionHelpItem(
    val label: String,
    val description: String
)

data class GenerationOptionHelpSection(
    val title: String,
    val body: String? = null,
    val items: List<GenerationOptionHelpItem> = emptyList()
)

enum class GenerationCacheGuidanceFamily {
    UNET,
    DIT,
    VIDEO_DIT
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerationCachingCard(
    title: String,
    cacheMode: SdCacheMode?,
    onCacheModeChange: (SdCacheMode?) -> Unit,
    cacheOption: String,
    onCacheOptionChange: (String) -> Unit,
    scmPolicy: SdCacheScmPolicy?,
    onScmPolicyChange: (SdCacheScmPolicy?) -> Unit,
    scmMask: String,
    onScmMaskChange: (String) -> Unit,
    guidanceFamily: GenerationCacheGuidanceFamily?,
    enabled: Boolean,
    disabledMessage: String?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (!enabled) {
                Text(
                    disabledMessage ?: stringResource(R.string.gen_cache_disabled_for_upscale),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            var modeExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = modeExpanded,
                onExpandedChange = { modeExpanded = !modeExpanded }
            ) {
                OutlinedTextField(
                    value = cacheMode?.cliName ?: stringResource(R.string.gen_cache_mode_off),
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    label = { Text(stringResource(R.string.gen_cache_mode_label)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeExpanded) },
                    shape = RoundedCornerShape(12.dp)
                )
                ExposedDropdownMenu(
                    expanded = modeExpanded,
                    onDismissRequest = { modeExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.gen_cache_mode_off)) },
                        onClick = {
                            onCacheModeChange(null)
                            onScmPolicyChange(null)
                            onScmMaskChange("")
                            modeExpanded = false
                        }
                    )
                    SdCacheMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.cliName) },
                            onClick = {
                                onCacheModeChange(mode)
                                if (mode != SdCacheMode.CACHE_DIT) {
                                    onScmPolicyChange(null)
                                    onScmMaskChange("")
                                }
                                modeExpanded = false
                            }
                        )
                    }
                }
            }

            if (cacheMode != null) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = cacheOption,
                    onValueChange = onCacheOptionChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.gen_cache_option_label)) },
                    placeholder = { Text(cacheOptionPlaceholder(cacheMode)) },
                    supportingText = {
                        Text(stringResource(R.string.gen_cache_option_support))
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                if (cacheMode == SdCacheMode.CACHE_DIT) {
                    Spacer(modifier = Modifier.height(12.dp))
                    var scmExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = scmExpanded,
                        onExpandedChange = { scmExpanded = !scmExpanded }
                    ) {
                        OutlinedTextField(
                            value = scmPolicy?.cliName ?: stringResource(R.string.gen_cache_mode_off),
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            label = { Text(stringResource(R.string.gen_cache_scm_policy_label)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = scmExpanded) },
                            shape = RoundedCornerShape(12.dp)
                        )
                        ExposedDropdownMenu(
                            expanded = scmExpanded,
                            onDismissRequest = { scmExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.gen_cache_mode_off)) },
                                onClick = {
                                    onScmPolicyChange(null)
                                    scmExpanded = false
                                }
                            )
                            SdCacheScmPolicy.entries.forEach { policy ->
                                DropdownMenuItem(
                                    text = { Text(policy.cliName) },
                                    onClick = {
                                        onScmPolicyChange(policy)
                                        scmExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = scmMask,
                        onValueChange = onScmMaskChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.gen_cache_scm_mask_label)) },
                        placeholder = { Text("1,1,1,0,0,1,0,0,1,0") },
                        supportingText = { Text(stringResource(R.string.gen_cache_scm_mask_support)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                guidanceFamily?.let { family ->
                    Spacer(modifier = Modifier.height(12.dp))
                    val recommendation = cacheRecommendationText(family)
                    val mismatch = cacheMismatchText(cacheMode, family)
                    Text(
                        recommendation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    mismatch?.let {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GenerationOptionsInfoDialog(
    title: String,
    sections: List<GenerationOptionHelpSection>,
    subtitle: String = stringResource(R.string.gen_help_powered_by_sdcpp),
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, top = 20.dp, end = 16.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                    }
                }

                HorizontalDivider()

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 24.dp, vertical = 20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    sections.forEach { section ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                section.title,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            section.body?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            section.items.forEach { item ->
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "•",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            item.label,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                                        )
                                        Text(
                                            item.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            }
        }
    }
}

@Composable
private fun cacheOptionPlaceholder(cacheMode: SdCacheMode): String {
    return when (cacheMode) {
        SdCacheMode.UCACHE -> "threshold=1.5,reset=0"
        SdCacheMode.EASYCACHE -> "threshold=0.3,start=0.15,end=0.95"
        SdCacheMode.DBCACHE,
        SdCacheMode.TAYLORSEER,
        SdCacheMode.CACHE_DIT -> "threshold=0.25,warmup=4,Fn=8,Bn=0"
        SdCacheMode.SPECTRUM -> "w=0.4,m=3,lam=1.0,window=2,flex=0.5,warmup=4,stop=0.9"
    }
}

@Composable
private fun cacheRecommendationText(family: GenerationCacheGuidanceFamily): String {
    val resId = when (family) {
        GenerationCacheGuidanceFamily.UNET -> R.string.gen_cache_recommend_unet
        GenerationCacheGuidanceFamily.DIT -> R.string.gen_cache_recommend_dit
        GenerationCacheGuidanceFamily.VIDEO_DIT -> R.string.gen_cache_recommend_video
    }
    return stringResource(resId)
}

@Composable
private fun cacheMismatchText(
    cacheMode: SdCacheMode,
    family: GenerationCacheGuidanceFamily
): String? {
    val resId = when (family) {
        GenerationCacheGuidanceFamily.UNET -> if (
            cacheMode == SdCacheMode.EASYCACHE ||
            cacheMode == SdCacheMode.DBCACHE ||
            cacheMode == SdCacheMode.TAYLORSEER ||
            cacheMode == SdCacheMode.CACHE_DIT
        ) {
            R.string.gen_cache_warning_unet
        } else {
            null
        }
        GenerationCacheGuidanceFamily.DIT -> if (cacheMode == SdCacheMode.UCACHE) {
            R.string.gen_cache_warning_dit
        } else {
            null
        }
        GenerationCacheGuidanceFamily.VIDEO_DIT -> if (cacheMode == SdCacheMode.UCACHE) {
            R.string.gen_cache_warning_video
        } else {
            null
        }
    }
    return resId?.let { stringResource(it) }
}
