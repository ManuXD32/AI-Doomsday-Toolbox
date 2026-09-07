package com.example.llamadroid.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R

/**
 * A bottom action region for long-running task forms and result pages.
 *
 * The footer consumes the bottom navigation inset so callers can keep their
 * body scrollable without placing actions behind the gesture area. The shared
 * [AppScreenScaffold] owns IME padding for task pages; callers using another
 * scaffold should apply its IME inset at that outer level.
 */
@Composable
fun AppTaskActionFooter(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            // The outer task scaffold owns IME padding. Consuming only the
            // bottom navigation inset here prevents nested footer double-padding.
            .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

/**
 * A saveable disclosure section for expert controls and optional drafts.
 * Only the expanded flag is saved, so callers can safely keep draft state in
 * their own ViewModel or repository without coupling it to this component.
 */
@Composable
fun AppAdvancedSection(
    title: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    revealContent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    LaunchedEffect(revealContent) { if (revealContent) expanded = true }
    val stateHolder = rememberSaveableStateHolder()
    val expansionDescription = stringResource(
        if (expanded) R.string.soft_studio_advanced_expanded
        else R.string.soft_studio_advanced_collapsed
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = AppChromeDefaults.InnerCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .heightIn(min = 48.dp)
                    .semantics {
                        role = Role.Button
                        stateDescription = expansionDescription
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                stateHolder.SaveableStateProvider("content") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = content
                    )
                }
            }
        }
    }
}

enum class AppStateKind {
    Empty,
    Blocked,
    Running,
    Cancelling,
    Interrupted,
    Error,
    Success
}

private data class AppStateVisuals(
    val icon: ImageVector,
    val containerColor: Color,
    val contentColor: Color
)

@Composable
private fun appStateVisuals(kind: AppStateKind): AppStateVisuals {
    val colors = MaterialTheme.colorScheme
    return when (kind) {
        AppStateKind.Empty -> AppStateVisuals(
            icon = Icons.Default.Info,
            containerColor = colors.surfaceVariant,
            contentColor = colors.onSurfaceVariant
        )
        AppStateKind.Blocked -> AppStateVisuals(
            icon = Icons.Default.Block,
            containerColor = colors.surfaceContainerHigh,
            contentColor = colors.onSurface
        )
        AppStateKind.Running -> AppStateVisuals(
            icon = Icons.Default.Refresh,
            containerColor = colors.primaryContainer,
            contentColor = colors.onPrimaryContainer
        )
        AppStateKind.Cancelling -> AppStateVisuals(
            icon = Icons.Default.Pause,
            containerColor = colors.primaryContainer,
            contentColor = colors.onPrimaryContainer
        )
        AppStateKind.Interrupted -> AppStateVisuals(
            icon = Icons.Default.ErrorOutline,
            containerColor = colors.errorContainer,
            contentColor = colors.onErrorContainer
        )
        AppStateKind.Error -> AppStateVisuals(
            icon = Icons.Default.ErrorOutline,
            containerColor = colors.errorContainer,
            contentColor = colors.onErrorContainer
        )
        AppStateKind.Success -> AppStateVisuals(
            icon = Icons.Default.CheckCircle,
            containerColor = colors.secondaryContainer,
            contentColor = colors.onSecondaryContainer
        )
    }
}

/**
 * Shared state language for empty, blocked, active, interrupted, error, and
 * completed task surfaces. Text stays caller-provided so each feature can use
 * its localized copy and recovery action.
 */
@Composable
fun AppStatePanel(
    kind: AppStateKind,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val visuals = appStateVisuals(kind)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = AppChromeDefaults.InnerCardShape,
        colors = CardDefaults.cardColors(containerColor = visuals.containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Surface(
                    shape = CircleShape,
                    color = visuals.contentColor.copy(alpha = 0.12f)
                ) {
                    Icon(
                        imageVector = visuals.icon,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(10.dp)
                            .size(24.dp),
                        tint = visuals.contentColor
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = visuals.contentColor
                    )
                }
            }
            if (!message.isNullOrBlank()) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = visuals.contentColor.copy(alpha = 0.86f)
                )
            }
            if (!actionLabel.isNullOrBlank() && onAction != null) {
                OutlinedButton(
                    onClick = onAction,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}
