package com.example.llamadroid.ui.training

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.branchy.BranchyRecipes
import com.example.llamadroid.branchy.BranchyRunEntity
import com.example.llamadroid.branchy.BranchyRunStatus
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.AppSectionTitle
import com.example.llamadroid.ui.components.AppTaskActionFooter
import com.example.llamadroid.ui.distributed.branchy.BranchyHelperViewModel
import com.example.llamadroid.ui.distributed.branchy.BranchyWorkerPanel
import com.example.llamadroid.ui.navigation.Screen

/**
 * Branchy trainer: a runs list with recipe cards (home), the Data / Model / Devices / Train wizard for custom runs,
 * and the live view of one run. All state lives in [BranchyTrainerViewModel]; training runs in
 * `BranchyTrainingService`.
 */
@Composable
fun BranchyTrainerScreen(navController: NavController) {
    val vm: BranchyTrainerViewModel = viewModel()
    val view by vm.view.collectAsState()
    val context = LocalContext.current
    val resources = LocalResources.current

    LaunchedEffect(vm) {
        vm.notices.collect { notice ->
            val text = if (notice.arg != null) resources.getString(notice.res, notice.arg) else resources.getString(notice.res)
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }

    when (val v = view) {
        BranchyView.Home -> BranchyHome(navController, vm)
        BranchyView.Wizard -> BranchyWizard(navController, vm)
        is BranchyView.Live -> BranchyLive(vm, v.runId, navController)
    }
}

@Composable
private fun BranchyHome(navController: NavController, vm: BranchyTrainerViewModel) {
    val runs by vm.runs.collectAsState()
    val live by vm.live.collectAsState()
    val datasets by vm.datasets.collectAsState()
    val recipeProgress by vm.recipe.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val workerVm: BranchyHelperViewModel = viewModel()

    AppScreenScaffold(
        title = stringResource(R.string.training_hub_branchy_title),
        subtitle = stringResource(R.string.branchy_ui_subtitle),
        onBack = { navController.popBackStack() }
    ) {
        Column(Modifier.fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = {
                    Text(stringResource(R.string.branchy_r4_tab_train), maxLines = 1, overflow = TextOverflow.Ellipsis)
                })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = {
                    Text(stringResource(R.string.branchy_r4_tab_worker), maxLines = 1, overflow = TextOverflow.Ellipsis)
                })
            }
            if (tab == 0) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(
                        start = AppChromeDefaults.ScreenPadding,
                        end = AppChromeDefaults.ScreenPadding,
                        top = 8.dp,
                        bottom = AppChromeDefaults.ScreenPadding
                    ),
                    verticalArrangement = Arrangement.spacedBy(AppChromeDefaults.SectionSpacing)
                ) {
                    item(key = "new_run") {
                        Button(
                            onClick = vm::openWizard,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.branchy_ui_new_run), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    item(key = "runs_title") {
                        AppSectionTitle(
                            title = stringResource(R.string.branchy_ui_runs_title),
                            supporting = stringResource(R.string.branchy_ui_runs_hint)
                        )
                    }
                    branchyRunsItems(
                        runs = runs, live = live, onOpen = vm::openRun, onPause = vm::pauseRun,
                        onResume = vm::resumeRun, onDelete = vm::deleteRun, onFreeSpace = vm::freeSpace
                    )
                    item(key = "recipes_title") {
                        AppSectionTitle(
                            title = stringResource(R.string.branchy_ui_recipes_title),
                            supporting = stringResource(R.string.branchy_ui_recipes_hint)
                        )
                    }
                    items(BranchyRecipes.all.size, key = { "recipe_${BranchyRecipes.all[it].id}" }) { index ->
                        val recipe = BranchyRecipes.all[index]
                        val stageNames = recipe.stages.map { stringResource(it.nameRes) }
                        BranchyRecipeCard(
                            recipe = recipe,
                            datasetRows = datasets,
                            progress = recipeProgress?.takeIf { it.recipeId == recipe.id },
                            onStart = { vm.startRecipe(recipe, stageNames) },
                            onCancel = vm::cancelRecipe
                        )
                    }
                }
            } else {
                BranchyWorkerPanel(workerVm, Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

@Composable
private fun BranchyWizard(navController: NavController, vm: BranchyTrainerViewModel) {
    val state by vm.wizard.collectAsState()
    val datasets by vm.datasets.collectAsState()
    val helpers by vm.helperDevices.collectAsState()
    val helperStatus by vm.helperStatus.collectAsState()
    val bench by vm.bench.collectAsState()
    var issue by remember { mutableStateOf<BranchyStartIssue?>(null) }
    // Any edit may have fixed what blocked Start.
    LaunchedEffect(state.weights, state.plan, state.vocab, state.planStale) { issue = null }

    val goBack: () -> Unit = {
        when (state.step) {
            BranchyWizardStep.DATA -> vm.goHome()
            BranchyWizardStep.MODEL -> vm.goToStep(BranchyWizardStep.DATA)
            BranchyWizardStep.DEVICES -> vm.goToStep(BranchyWizardStep.MODEL)
            BranchyWizardStep.TRAIN -> vm.goToStep(BranchyWizardStep.DEVICES)
        }
    }
    BackHandler { goBack() }

    val listState = rememberLazyListState()
    LaunchedEffect(state.step) { listState.scrollToItem(0) }
    // Browse for helpers only while the Devices step is open.
    DisposableEffect(state.step == BranchyWizardStep.DEVICES) {
        if (state.step == BranchyWizardStep.DEVICES) vm.startDiscovery()
        onDispose { vm.stopDiscovery() }
    }
    // Heartbeat-probe the saved helpers right away and every 10 s while the Devices step is visible.
    DisposableEffect(state.step == BranchyWizardStep.DEVICES) {
        if (state.step == BranchyWizardStep.DEVICES) vm.startHelperProbing()
        onDispose { vm.stopHelperProbing() }
    }
    val nextBlock = BranchyWizardHelpers.nextBlock(state)
    val dataEstimate = remember(state.weights.keys, datasets) {
        BranchyWizardHelpers.estimatedDataTokens(state.weights.keys, datasets)
    }

    AppScreenScaffold(
        title = stringResource(R.string.branchy_ui_wizard_title),
        onBack = vm::goHome,
        bottomBar = {
            AppTaskActionFooter {
                issue?.let {
                    Text(
                        text = stringResource(issueRes(it)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = goBack, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_back), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (state.step != BranchyWizardStep.TRAIN) {
                        Button(
                            onClick = {
                                vm.goToStep(
                                    when (state.step) {
                                        BranchyWizardStep.DATA -> BranchyWizardStep.MODEL
                                        BranchyWizardStep.MODEL -> BranchyWizardStep.DEVICES
                                        else -> BranchyWizardStep.TRAIN
                                    }
                                )
                            },
                            enabled = nextBlock == null,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        ) {
                            Text(stringResource(R.string.action_next), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                        }
                    } else {
                        Button(
                            onClick = {
                                val blocked = vm.startCustomRun()
                                issue = blocked
                                when (blocked) {
                                    BranchyStartIssue.NO_DATA -> vm.goToStep(BranchyWizardStep.DATA)
                                    BranchyStartIssue.NO_PLAN, BranchyStartIssue.NOT_FIT ->
                                        vm.goToStep(BranchyWizardStep.MODEL)
                                    BranchyStartIssue.STALE_PLAN -> vm.goToStep(BranchyWizardStep.DEVICES)
                                    null -> Unit
                                }
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_start), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (nextBlock != null) {
                    Text(
                        text = stringResource(issueRes(nextBlock)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            BranchyWizardStepHeader(current = state.step, onGoTo = vm::goToStep)
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = AppChromeDefaults.ScreenPadding,
                    end = AppChromeDefaults.ScreenPadding,
                    top = 8.dp,
                    bottom = AppChromeDefaults.ScreenPadding
                ),
                verticalArrangement = Arrangement.spacedBy(AppChromeDefaults.SectionSpacing)
            ) {
                when (state.step) {
                    BranchyWizardStep.DATA -> if (state.recipeId != null) branchyRecipeDataItems(
                        state = state,
                        datasets = datasets,
                        onCustomize = vm::customizeRecipe
                    ) else branchyDataStepItems(
                        state = state,
                        datasets = datasets,
                        onToggle = vm::toggleDataset,
                        onWeight = vm::setWeight,
                        onVocab = { v -> vm.updateWizard { it.copy(vocab = v) } },
                        onOpenLibrary = { navController.navigate(Screen.DatasetLibrary.route) }
                    )
                    BranchyWizardStep.MODEL -> branchyModelStepItems(
                        state, vm::updateWizard, vm::computePlan, dataEstimate, vm::customizeRecipe
                    )
                    BranchyWizardStep.DEVICES -> branchyDevicesStepItems(
                        state = state,
                        helpers = helpers,
                        helperStatus = helperStatus,
                        edit = vm::updateWizard,
                        onToggle = vm::toggleHelper,
                        onEditDevice = vm::editHelper,
                        onAdd = vm::addHelperManually,
                        onRemove = vm::removeHelper,
                        onComputePlan = vm::computePlan,
                        cores = vm.cores,
                        bench = bench,
                        onBenchmark = vm::runBenchmark,
                        onCancelBenchmark = vm::cancelBenchmark,
                        onPreferredInterface = vm::setHelperInterface,
                        onHelperThreads = vm::setHelperThreads,
                        onBenchHelper = vm::benchHelper
                    )
                    BranchyWizardStep.TRAIN -> branchyTrainStepItems(state, vm::updateWizard)
                }
            }
        }
    }
}

private fun issueRes(issue: BranchyStartIssue): Int = when (issue) {
    BranchyStartIssue.NO_DATA -> R.string.branchy_ui_issue_no_data
    BranchyStartIssue.NO_PLAN -> R.string.branchy_ui_issue_no_plan
    BranchyStartIssue.NOT_FIT -> R.string.branchy_ui_issue_no_fit
    BranchyStartIssue.STALE_PLAN -> R.string.branchy_r5_next_plan_stale
}

@Composable
private fun BranchyLive(vm: BranchyTrainerViewModel, runId: Long, navController: NavController) {
    val runFlow = remember(vm, runId) { vm.liveRun(runId) }
    val run: BranchyRunEntity? by runFlow.collectAsState(initial = null)
    val live by vm.live.collectAsState()
    var editing by rememberSaveable(runId) { mutableStateOf(false) }
    // A new session per open: the editor ViewModel is keyed by it, so a finished (Saved) edit never closes the next one.
    var editSession by rememberSaveable(runId) { mutableIntStateOf(0) }
    BackHandler(enabled = !editing) { vm.goHome() }

    // The device editor replaces the live view; it is only offered for a paused run the service does not own.
    if (editing) {
        BranchyRunEditor(runId = runId, session = editSession, run = run, live = live, onClose = { editing = false })
    } else {
        val canEdit = run?.status == BranchyRunStatus.PAUSED.value && !live.isActive
        AppScreenScaffold(
            title = run?.name ?: stringResource(R.string.training_hub_branchy_title),
            onBack = vm::goHome,
            bottomBar = {
                AppTaskActionFooter {
                    BranchyLiveControls(
                        run = run,
                        live = live,
                        onPause = { vm.pauseRun(runId) },
                        onResume = { vm.resumeRun(runId) },
                        onStop = { vm.stopRun(runId) },
                        onEditDevices = if (canEdit) ({ editSession += 1; editing = true }) else null
                    )
                }
            }
        ) {
            BranchyLiveView(
                run = run,
                live = live,
                modifier = Modifier.fillMaxSize(),
                exportSection = run?.let { r -> { BranchyExportSection(r, navController) } }
            )
        }
    }
}
