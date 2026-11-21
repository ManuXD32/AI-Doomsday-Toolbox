package com.example.llamadroid.ui.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.branchy.BranchyRecipe
import com.example.llamadroid.training.CuratedDatasets
import com.example.llamadroid.training.TrainingDatasetEntity
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.util.FormatUtils
import java.util.Locale

/**
 * One recipe ("Mini assistant 30M / 120M"): what it trains on, the datasets it will download, an honest
 * time/quality note, and Start. Start adds the missing curated datasets through the shared dataset library.
 */
@Composable
fun BranchyRecipeCard(
    recipe: BranchyRecipe,
    datasetRows: List<TrainingDatasetEntity>,
    progress: RecipeProgress?,
    onStart: () -> Unit,
    onCancel: () -> Unit
) {
    val entries = recipe.datasetIds().mapNotNull { CuratedDatasets.byId(it) }
    val present = BranchyWizardHelpers.findCuratedRows(recipe.datasetIds(), datasetRows)
    val missing = entries.filter { !present.containsKey(it.id) }
    val missingBytes = missing.sumOf { it.totalBytes }
    val inProgress = progress != null && progress.phase != RecipePhase.ERROR

    AppSectionCard {
        Text(
            text = stringResource(recipe.titleRes),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(text = stringResource(recipe.descRes), style = MaterialTheme.typography.bodySmall)
        Text(
            text = stringResource(R.string.branchy_ui_recipe_tokens, BranchyWizardHelpers.formatTokens(recipe.totalTokens)),
            style = MaterialTheme.typography.labelLarge
        )
        Text(text = stringResource(R.string.branchy_ui_recipe_datasets), style = MaterialTheme.typography.titleSmall)
        entries.forEach { entry ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (present.containsKey(entry.id)) stringResource(R.string.branchy_ui_recipe_in_library)
                    else FormatUtils.formatFileSize(entry.totalBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        if (entries.any { it.nonCommercial }) {
            Text(
                text = stringResource(R.string.branchy_ui_recipe_noncommercial),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Text(
            text = if (missing.isEmpty()) stringResource(R.string.branchy_ui_recipe_all_present)
            else pluralStringResource(
                R.plurals.branchy_ui_recipe_missing, missing.size, missing.size, FormatUtils.formatFileSize(missingBytes)
            ),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = stringResource(R.string.branchy_ui_recipe_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (progress != null) {
            when (progress.phase) {
                RecipePhase.ADDING -> {
                    Text(stringResource(R.string.branchy_ui_recipe_adding), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                RecipePhase.DOWNLOADING -> {
                    Text(
                        stringResource(
                            R.string.branchy_ui_recipe_downloading, String.format(Locale.US, "%.0f", progress.fraction * 100f)
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                }
                RecipePhase.PLANNING -> {
                    Text(stringResource(R.string.branchy_ui_recipe_planning), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                RecipePhase.ERROR -> {
                    val res = progress.errorRes
                    if (res != null) {
                        Text(
                            text = if (progress.errorArg != null) stringResource(res, progress.errorArg) else stringResource(res),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
        if (inProgress) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_start), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
