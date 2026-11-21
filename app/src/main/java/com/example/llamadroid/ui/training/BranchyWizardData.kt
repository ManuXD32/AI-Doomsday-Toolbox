package com.example.llamadroid.ui.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.branchy.BranchyClusterRun
import com.example.llamadroid.branchy.BranchyDevice
import com.example.llamadroid.branchy.BranchyPlan
import com.example.llamadroid.branchy.BranchyPlanInput
import com.example.llamadroid.branchy.BranchyRecipe
import com.example.llamadroid.branchy.BranchyRecipes
import com.example.llamadroid.branchy.BranchyRunConfig
import com.example.llamadroid.branchy.BranchyRunEdit
import com.example.llamadroid.branchy.ClusterSelection
import com.example.llamadroid.branchy.MixEntry
import com.example.llamadroid.branchy.RunStage
import com.example.llamadroid.branchy.TrainingGuards
import com.example.llamadroid.branchy.cluster.BranchyClusterDeviceEntity
import com.example.llamadroid.training.CuratedDatasets
import com.example.llamadroid.training.DatasetSource
import com.example.llamadroid.training.DatasetStatus
import com.example.llamadroid.training.TrainingDatasetEntity
import com.example.llamadroid.ui.components.AppEmptyState
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppSectionTitle
import com.example.llamadroid.util.FormatUtils
import java.util.Locale

enum class BranchyWizardStep { DATA, MODEL, DEVICES, TRAIN }

/** Result of the Auto plan card. */
sealed interface BranchyPlanUi {
    data object Idle : BranchyPlanUi
    data object Running : BranchyPlanUi
    data class Ready(val plan: BranchyPlan) : BranchyPlanUi
    data class Failed(val message: String) : BranchyPlanUi
}

/** Why Start was blocked; the screen jumps to the step that fixes it. */
enum class BranchyStartIssue { NO_DATA, NO_PLAN, NOT_FIT, STALE_PLAN }

/** Estimated size of the selected data in tokens; [approximate] is true when any part comes from file size. */
data class DataEstimate(val tokens: Double, val approximate: Boolean)

/** Values typed for one helper in the Devices step; blank = use the advertised value (or the default link speed). */
data class ClusterDeviceEdit(val ramMb: String = "", val gflops: String = "", val linkMbps: String = "")

/** Everything the four wizard steps edit. Changing a planning input resets the plan (see [planningKey]). */
data class BranchyWizardState(
    val step: BranchyWizardStep = BranchyWizardStep.DATA,
    /** Selected dataset id -> relative sampling weight. */
    val weights: Map<Long, Float> = emptyMap(),
    val vocab: Int = 16384,
    val targetParams: Double = 30e6,
    /** Custom target in millions of parameters (text); null = use [targetParams]. */
    val customTargetMillions: String? = null,
    val context: Int = 1024,
    val customContext: String? = null,
    val weightMode: String = "q8",
    val mtp: Boolean = false,
    val branches: Int = 4,
    val allowStorageOffload: Boolean = false,
    val plan: BranchyPlanUi = BranchyPlanUi.Idle,
    val overrides: Map<String, String> = emptyMap(),
    val runName: String = "",
    val guards: TrainingGuards = TrainingGuards(),
    val autoResume: Boolean = false,
    /** 0 = default for this phone. */
    val threads: Int = 0,
    /** Helper devices (ids of `branchy_cluster_device`) that train with this phone; empty = single device. */
    val clusterDeviceIds: List<String> = emptyList(),
    /** Planner mode with helpers: `auto`, `branch_parallel` (memory) or `data_parallel` (speed). */
    val clusterMode: String = "auto",
    val deviceEdits: Map<String, ClusterDeviceEdit> = emptyMap(),
    /** Passes over the selected data (text, so the field can be edited freely); see [effectiveEpochs]. */
    val epochs: String = "1",
    /** The plan is still shown but a field it depends on (device values, epochs) changed since it was computed. */
    val planStale: Boolean = false,
    /** Recipe the wizard was prefilled from; while set, DATA and MODEL show its values read-only. */
    val recipeId: String? = null,
    /** The recipe's stages with curated ids resolved to library ids; empty for custom runs. */
    val recipeStages: List<RunStage> = emptyList(),
    /** Threads per helper id from the slider or the benchmark; absent = the helper keeps its own setting. */
    val helperThreads: Map<String, Int> = emptyMap()
) {
    fun effectiveTarget(): Double =
        customTargetMillions?.trim()?.toDoubleOrNull()?.takeIf { it in 1.0..20_000.0 }?.let { it * 1e6 } ?: targetParams

    fun effectiveContext(): Int =
        customContext?.trim()?.toIntOrNull()?.takeIf { it in 128..65_536 } ?: context

    /** Epochs from the field; blank or out of 0.1..100 falls back to 1. */
    fun effectiveEpochs(): Double = epochs.trim().toDoubleOrNull()?.takeIf { it in 0.1..100.0 } ?: 1.0

    /** Inputs whose change throws the plan (and its overrides) away. */
    fun resetKey(): List<Any> = listOf(
        vocab, effectiveTarget(), effectiveContext(), weightMode, mtp, branches, allowStorageOffload, threads,
        clusterDeviceIds, clusterMode, recipeStages.isNotEmpty()
    )

    /** Every input the planner depends on. Hard-key changes reset the plan; the rest only mark it stale. */
    fun planningKey(): List<Any> = resetKey() + listOf(deviceEdits, effectiveEpochs(), weights.keys)
}

enum class OverrideKind { INT, DOUBLE, CHOICE }

data class OverrideField(val key: String, val kind: OverrideKind, val choices: List<String> = emptyList())

object BranchyWizardHelpers {
    val sizePresets: List<Double> = listOf(30e6, 120e6, 350e6, 1e9, 3e9, 9e9)
    val contextPresets: List<Int> = listOf(256, 512, 1024, 2048, 4096, 8192, 16384, 32768)
    val vocabOptions: List<Int> = listOf(8192, 16384, 32768)
    val branchOptions: List<Int> = listOf(1, 2, 4, 8)
    val weightModes: List<String> = listOf("f32", "q8", "q4")
    val clusterModes: List<String> = listOf("auto", "branch_parallel", "data_parallel")

    val overrideFields: List<OverrideField> = listOf(
        OverrideField("train.microbatch", OverrideKind.INT),
        OverrideField("train.grad_accum", OverrideKind.INT),
        OverrideField("train.recompute", OverrideKind.CHOICE, listOf("none", "branches", "all")),
        OverrideField("train.optimizer", OverrideKind.CHOICE, listOf("adamw", "adamw8", "factored")),
        OverrideField("train.grad_mode", OverrideKind.CHOICE, listOf("f32", "fused")),
        OverrideField("train.lr", OverrideKind.DOUBLE),
        OverrideField("train.steps", OverrideKind.INT),
        OverrideField("train.checkpoint_every", OverrideKind.INT)
    )

    /** "30M", "1.0B"; model sizes are language-neutral. */
    fun formatParams(params: Double): String = when {
        params >= 1e9 -> String.format(Locale.US, "%.1fB", params / 1e9)
        params >= 1e6 -> String.format(Locale.US, "%.0fM", params / 1e6)
        params >= 1e3 -> String.format(Locale.US, "%.0fK", params / 1e3)
        else -> String.format(Locale.US, "%.0f", params)
    }

    /** "850 MB" below 1 GiB, "2.3 GB" above. */
    fun formatMb(mb: Double): String =
        if (mb >= 1024.0) String.format(Locale.US, "%.1f GB", mb / 1024.0) else String.format(Locale.US, "%.0f MB", mb)

    /** "1.2B", "300M" token counts. */
    fun formatTokens(tokens: Double): String = formatParams(tokens)

    fun formatVocab(vocab: Int): String = "${vocab / 1024}k"

    /** About 10 tokens per parameter, never below 200M. Matches the recipes (30M -> 300M, 120M -> 1.2B). */
    fun defaultTokensTarget(params: Double): Double = maxOf(2e8, params * 10.0)

    /** Run-length cap: epochs x data tokens, never above the default target; no data estimate or epochs <= 0 = default. */
    fun tokensTarget(params: Double, epochs: Double, dataTokens: Double): Double {
        val base = defaultTokensTarget(params)
        return if (epochs > 0.0 && dataTokens > 0.0) minOf(base, epochs * dataTokens) else base
    }

    /** Total token target of the recipe stages, as the recipe defines it (not capped by the data estimate). */
    fun recipeTokens(state: BranchyWizardState): Double = state.recipeStages.sumOf { it.tokensTarget }

    /** Selected datasets' tokens: the prepared count when known, else file size / 4 (then [DataEstimate.approximate]). */
    fun estimatedDataTokens(
        selected: Collection<Long>,
        rows: List<TrainingDatasetEntity>,
        prepared: Map<Long, Long> = emptyMap()
    ): DataEstimate {
        var total = 0.0
        var approximate = false
        for (row in rows) {
            if (row.id !in selected) continue
            val known = prepared[row.id]?.takeIf { it > 0L }
            if (known != null) total += known else { total += row.sizeBytes / 4.0; approximate = true }
        }
        return DataEstimate(total, approximate)
    }

    /** Why the Next button of [state]'s step is disabled, or null. DEVICES never blocks. */
    fun nextBlock(state: BranchyWizardState): BranchyStartIssue? = when (state.step) {
        BranchyWizardStep.DATA -> if (state.weights.isEmpty()) BranchyStartIssue.NO_DATA else null
        BranchyWizardStep.MODEL -> planIssue(state)
        BranchyWizardStep.DEVICES, BranchyWizardStep.TRAIN -> null
    }

    private fun planIssue(state: BranchyWizardState): BranchyStartIssue? = when {
        state.plan !is BranchyPlanUi.Ready -> BranchyStartIssue.NO_PLAN
        state.planStale -> BranchyStartIssue.STALE_PLAN
        !state.plan.plan.fits -> BranchyStartIssue.NOT_FIT
        else -> null
    }

    /** ETA above this gets a warning on the plan card. */
    const val LONG_RUN_S = 24 * 3600.0

    fun defaultThreads(cores: Int): Int = (cores / 2).coerceAtLeast(1)

    fun effectiveThreads(state: BranchyWizardState, cores: Int): Int =
        if (state.threads > 0) state.threads.coerceAtMost(cores.coerceAtLeast(1)) else defaultThreads(cores)

    fun defaultRunName(params: Double): String = "Branchy ${formatParams(params)}"

    /** Selected datasets as sampling weights summing to 1; all-zero weights become equal shares. */
    fun normalizedMix(weights: Map<Long, Float>): List<MixEntry> {
        if (weights.isEmpty()) return emptyList()
        val positive = weights.filterValues { it > 0f }
        val source = if (positive.isEmpty()) weights.mapValues { 1f } else positive
        val total = source.values.sumOf { it.toDouble() }
        return source.map { (id, w) -> MixEntry(id, w / total) }
    }

    fun weightShare(weights: Map<Long, Float>, id: Long): Float {
        val total = weights.values.sum()
        val w = weights[id] ?: return 0f
        return if (total <= 0f) 0f else w / total
    }

    private fun positiveLong(text: String): Long? = text.trim().toLongOrNull()?.takeIf { it > 0L }

    private fun positiveDouble(text: String): Double? =
        text.trim().toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() }

    /** RAM (MiB) typed per helper; blank or invalid entries are left out. */
    fun typedRam(state: BranchyWizardState): Map<String, Long> =
        state.deviceEdits.mapNotNull { (id, e) -> positiveLong(e.ramMb)?.let { id to it } }.toMap()

    fun typedGflops(state: BranchyWizardState): Map<String, Double> =
        state.deviceEdits.mapNotNull { (id, e) -> positiveDouble(e.gflops)?.let { id to it } }.toMap()

    fun typedLinks(state: BranchyWizardState): Map<String, Double> =
        state.deviceEdits.mapNotNull { (id, e) -> positiveDouble(e.linkMbps)?.let { id to it } }.toMap()

    /**
     * New runs with helpers let the planner use at least one branch per device (this phone plus each helper) and
     * never fewer than 4 (the user's own larger choice wins); a single device keeps the chosen count.
     */
    fun preferBranches(chosen: Int, helperCount: Int): Int =
        if (helperCount == 0) chosen else maxOf(4, chosen, 1 + helperCount)

    /** Planner input; with [helpers] it lists this phone first, then every helper, and carries the cluster mode. */
    fun buildPlanInput(
        state: BranchyWizardState,
        device: BranchyDevice,
        helpers: List<BranchyClusterDeviceEntity> = emptyList(),
        dataTokens: Double = 0.0
    ): BranchyPlanInput {
        val target = state.effectiveTarget()
        return BranchyPlanInput(
            targetParams = target,
            context = state.effectiveContext(),
            weightMode = state.weightMode,
            mtp = state.mtp,
            vocab = state.vocab,
            tokensTarget = if (state.recipeStages.isNotEmpty()) recipeTokens(state)
            else tokensTarget(target, state.effectiveEpochs(), dataTokens),
            allowOptimizerOffload = false,
            allowStorageOffload = state.allowStorageOffload,
            preferBranches = preferBranches(state.branches, helpers.size),
            mode = if (helpers.isEmpty()) "auto" else state.clusterMode,
            devices = if (helpers.isEmpty()) listOf(device) else BranchyClusterRun.planDevices(
                device, helpers, typedRam(state), typedGflops(state), typedLinks(state)
            )
        )
    }

    fun planValue(plan: BranchyPlan, key: String): String = when (key) {
        "train.microbatch" -> plan.train.microbatch.toString()
        "train.grad_accum" -> plan.train.gradAccum.toString()
        "train.recompute" -> plan.train.recompute
        "train.optimizer" -> plan.train.optimizer
        "train.grad_mode" -> plan.train.gradMode
        "train.lr" -> plan.train.lr.toString()
        "train.steps" -> plan.train.steps.toString()
        "train.checkpoint_every" -> plan.train.checkpointEvery.toString()
        else -> ""
    }

    private fun isValid(field: OverrideField, text: String): Boolean = when (field.kind) {
        OverrideKind.INT -> (text.toLongOrNull() ?: 0L) > 0L
        OverrideKind.DOUBLE -> (text.toDoubleOrNull() ?: 0.0).let { it > 0.0 && it.isFinite() }
        OverrideKind.CHOICE -> text in field.choices
    }

    /** Keeps only valid overrides that differ from the plan's own value, ready for `BranchyRunConfig.overrides`. */
    fun cleanOverrides(raw: Map<String, String>, plan: BranchyPlan?): Map<String, String> {
        if (plan == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (field in overrideFields) {
            val text = raw[field.key]?.trim().orEmpty()
            if (text.isEmpty() || !isValid(field, text) || text == planValue(plan, field.key)) continue
            out[field.key] = text
        }
        return out
    }

    /** Checkpoint-frequency field: typed value (blank = planner default). Sets or clears the override. */
    fun withCheckpointEvery(overrides: Map<String, String>, text: String): Map<String, String> {
        val digits = text.filter { it.isDigit() }.take(7)
        // Keep the raw digits while typing (a blank field removes the override); validity is checked by cleanOverrides.
        return if (digits.isEmpty()) overrides - BranchyRunEdit.CHECKPOINT_KEY
        else overrides + (BranchyRunEdit.CHECKPOINT_KEY to digits)
    }

    fun validateStart(state: BranchyWizardState): BranchyStartIssue? =
        if (state.weights.isEmpty()) BranchyStartIssue.NO_DATA else planIssue(state)

    fun customConfig(
        state: BranchyWizardState,
        device: BranchyDevice,
        threads: Int,
        helpers: List<BranchyClusterDeviceEntity> = emptyList(),
        dataTokens: Double = 0.0
    ): BranchyRunConfig? {
        val plan = (state.plan as? BranchyPlanUi.Ready)?.plan ?: return null
        val stages = state.recipeStages
        return BranchyRunConfig(
            mix = if (stages.isNotEmpty()) stages.first().mix else normalizedMix(state.weights),
            vocab = state.vocab,
            planInput = buildPlanInput(state, device, helpers, dataTokens),
            plan = plan,
            guards = state.guards,
            autoResume = state.autoResume,
            allowStorageOffload = state.allowStorageOffload,
            overrides = cleanOverrides(state.overrides, plan),
            threads = threads,
            helperThreads = state.helperThreads.filterKeys { id -> helpers.any { it.id == id } }.filterValues { it > 0 },
            cluster = if (helpers.isEmpty()) null else ClusterSelection(helpers.map { it.id }, state.clusterMode),
            epochs = state.effectiveEpochs(),
            recipeId = if (stages.isNotEmpty()) state.recipeId else null,
            stages = stages,
            currentStage = 0
        )
    }

    /** The row that already holds a curated dataset (same title, not failed); a ready row wins. */
    fun findCuratedRows(ids: List<String>, rows: List<TrainingDatasetEntity>): Map<String, TrainingDatasetEntity> {
        val out = LinkedHashMap<String, TrainingDatasetEntity>()
        for (id in ids) {
            val title = CuratedDatasets.byId(id)?.title ?: continue
            val candidates = rows.filter {
                it.source == DatasetSource.CURATED.value && it.name == title && it.status != DatasetStatus.FAILED.value
            }
            val best = candidates.firstOrNull { it.status == DatasetStatus.READY.value } ?: candidates.firstOrNull()
            if (best != null) out[id] = best
        }
        return out
    }

    /** Recipe stages with curated ids resolved to Room ids, or null when one id is unresolved. */
    fun recipeStages(recipe: BranchyRecipe, idMap: Map<String, Long>, stageNames: List<String>): List<RunStage>? =
        recipe.stages.mapIndexed { index, stage ->
            val mix = stage.mix.map { (curated, weight) ->
                MixEntry(idMap[curated] ?: return null, weight)
            }
            RunStage(stageNames.getOrElse(index) { "" }, mix, stage.tokensTarget, stage.lrScale)
        }

    /**
     * Wizard state prefilled from [recipe]: model size, context and vocab come from the recipe, every dataset of its
     * stages is selected (the real per-stage mix lives in [stages]). Helper selection and typed device values of
     * [previous] are kept, as for a custom run.
     */
    fun recipeWizardState(
        recipe: BranchyRecipe, stages: List<RunStage>, runName: String, previous: BranchyWizardState
    ): BranchyWizardState = BranchyWizardState(
        weights = stages.flatMap { it.mix }.map { it.datasetId }.distinct().associateWith { 1f },
        vocab = recipe.vocab,
        targetParams = recipe.targetParams,
        context = recipe.defaultContext,
        runName = runName,
        clusterDeviceIds = previous.clusterDeviceIds,
        deviceEdits = previous.deviceEdits,
        recipeId = recipe.id,
        recipeStages = stages
    )

    /** Leaves the recipe: its values stay as the starting point of an ordinary custom run. */
    fun customizeRecipe(state: BranchyWizardState): BranchyWizardState =
        state.copy(recipeId = null, recipeStages = emptyList())

    /** Overall readiness (0..1) of [ids]: ready = 1, downloading = mean file progress, anything else 0. */
    fun downloadFraction(ids: Collection<Long>, rows: Map<Long, TrainingDatasetEntity>, progress: Map<String, Float>): Float {
        if (ids.isEmpty()) return 1f
        val sum = ids.sumOf { id ->
            val row = rows[id]
            when (row?.status) {
                DatasetStatus.READY.value -> 1.0
                DatasetStatus.DOWNLOADING.value -> (DatasetUiHelpers.downloadProgress(progress, id) ?: 0f).toDouble()
                else -> 0.0
            }
        }
        return (sum / ids.size).toFloat().coerceIn(0f, 1f)
    }

    /** Fraction of a run's steps done, 0 when the total is unknown. */
    fun runFraction(step: Long, total: Long): Float =
        if (total <= 0L) 0f else (step.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

// region Step 1: data

/** Read-only recipe summary (stages, datasets, tokens) with a Customize path; shown instead of the dataset picker. */
internal fun LazyListScope.branchyRecipeDataItems(
    state: BranchyWizardState,
    datasets: List<TrainingDatasetEntity>,
    onCustomize: () -> Unit
) {
    val recipe = BranchyRecipes.byId(state.recipeId)
    item(key = "recipe_title") {
        AppSectionTitle(
            title = stringResource(recipe?.titleRes ?: R.string.branchy_ui_data_title),
            supporting = stringResource(R.string.branchy_r6_recipe_data_hint)
        )
    }
    items(state.recipeStages.size, key = { "recipe_stage_$it" }) { index ->
        val stage = state.recipeStages[index]
        AppSectionCard {
            Text(
                text = stage.name.ifBlank { stringResource(R.string.branchy_r6_recipe_stage, index + 1) },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.branchy_r6_recipe_stage_tokens, BranchyWizardHelpers.formatTokens(stage.tokensTarget)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            stage.mix.forEach { entry ->
                val name = datasets.firstOrNull { it.id == entry.datasetId }?.name ?: "#${entry.datasetId}"
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = String.format(Locale.US, "%.0f%%", entry.weight * 100.0),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
            }
        }
    }
    item(key = "recipe_data_customize") { RecipeCustomizeCard(onCustomize) }
}

/** Explains that the values come from the recipe and offers the switch to a custom run. */
@Composable
internal fun RecipeCustomizeCard(onCustomize: () -> Unit) {
    AppSectionCard {
        Text(
            text = stringResource(R.string.branchy_r6_recipe_locked),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = onCustomize, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.branchy_r6_recipe_customize), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal fun LazyListScope.branchyDataStepItems(
    state: BranchyWizardState,
    datasets: List<TrainingDatasetEntity>,
    onToggle: (Long, Boolean) -> Unit,
    onWeight: (Long, Float) -> Unit,
    onVocab: (Int) -> Unit,
    onOpenLibrary: () -> Unit
) {
    val ready = datasets.filter { it.status == DatasetStatus.READY.value }
    item(key = "data_title") {
        AppSectionTitle(
            title = stringResource(R.string.branchy_ui_data_title),
            supporting = stringResource(R.string.branchy_ui_data_hint)
        )
    }
    if (ready.isEmpty()) {
        item(key = "data_empty") {
            AppEmptyState(
                title = stringResource(R.string.branchy_ui_data_empty_title),
                subtitle = stringResource(R.string.branchy_ui_data_empty_body)
            )
        }
    }
    items(ready.size, key = { "dataset_${ready[it].id}" }) { index ->
        val row = ready[index]
        val selected = state.weights.containsKey(row.id)
        AppSectionCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(checked = selected, onCheckedChange = { onToggle(row.id, it) })
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${row.format} · ${FormatUtils.formatFileSize(row.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (selected) {
                    val share = BranchyWizardHelpers.weightShare(state.weights, row.id)
                    Text(
                        text = String.format(Locale.US, "%.0f%%", share * 100f),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (selected) {
                Text(
                    text = stringResource(R.string.branchy_ui_data_weight),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = state.weights[row.id] ?: 1f,
                    onValueChange = { onWeight(row.id, it) },
                    valueRange = 0.05f..1f
                )
            }
        }
    }
    item(key = "data_library") {
        OutlinedButton(onClick = onOpenLibrary, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.branchy_ui_data_open_library),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    item(key = "data_vocab") {
        AppSectionCard {
            AppSectionTitle(
                title = stringResource(R.string.branchy_ui_vocab_title),
                supporting = stringResource(R.string.branchy_ui_vocab_hint)
            )
            ChipRow {
                BranchyWizardHelpers.vocabOptions.forEach { v ->
                    FilterChip(
                        selected = state.vocab == v,
                        onClick = { onVocab(v) },
                        label = { Text(BranchyWizardHelpers.formatVocab(v)) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
        }
    }
}

// endregion
