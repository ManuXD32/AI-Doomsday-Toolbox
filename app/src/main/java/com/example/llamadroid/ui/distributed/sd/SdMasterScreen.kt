package com.example.llamadroid.ui.distributed.sd

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.service.SdDistributedService
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.AppTaskActionFooter
import com.example.llamadroid.ui.navigation.Screen
import kotlinx.coroutines.launch

/** Which workflow a main-device screen configures; the other workflow's settings stay hidden. */
enum class SdMasterMode { IMAGES, VIDEO }

/**
 * Main-device screen of the media farm: three steps (Model, Devices, Run) over one lazy list, with a
 * fixed Back / Next / Start footer. State lives in [SdMasterViewModel]; Start is never disabled, it
 * explains what is missing and jumps to the step that fixes it.
 */
@Composable
fun SdMasterScreen(navController: NavController, mode: SdMasterMode = SdMasterMode.IMAGES) {
    val vm: SdMasterViewModel = viewModel()
    // Idempotent and synchronous: the mode must be known before the first load picks the initial step.
    remember(vm, mode) { vm.setMode(mode); mode }
    val state by vm.state.collectAsState()
    val reuseWarning by vm.reuseWarning.collectAsState()
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

    val db = remember(context) { AppDatabase.getDatabase(context) }
    val modelTypes = remember {
        ModelType.entries.filter { it.name.startsWith("SD_") } +
            listOf(ModelType.LLM, ModelType.VISION_PROJECTOR, ModelType.MMPROJ)
    }
    val installedFlow = remember(db) { db.modelDao().getModelsByTypes(modelTypes) }
    val installedModels by installedFlow.collectAsState(initial = emptyList())
    val env = remember(installedModels, navController) {
        SdMasterEnv(
            installedModels = installedModels,
            onOpenModelManager = { navController.navigate(Screen.SDModels.route) },
            onOpenHelperGuide = { navController.navigate(Screen.SdDistributedWorker.route) },
            onOpenBinarySettings = { navController.navigate("settings_imagegen") }
        )
    }
    val launcher = LocalSdMasterLauncher.current ?: remember(context) { ServiceSdMasterLauncher(context) }
    val status = rememberSdMasterRunStatus(state)

    var startMessage by remember { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(false) }
    // Any edit may have fixed what blocked Start.
    LaunchedEffect(state.settings, state.videoOptions) { startMessage = null }
    LaunchedEffect(startMessage) {
        startMessage?.let { SdDistributedService.appendLog(resources.getString(R.string.sd_dist_log_start_blocked, it)) }
    }
    LaunchedEffect(status.error) {
        status.error?.let { SdDistributedService.appendLog(resources.getString(R.string.sd_dist_log_run_failed, it)) }
    }
    LaunchedEffect(Unit) { vm.consumeVideoReuseHandoff() }

    val inputPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            copyDistributedInputImageToCache(context, uri, "sd_dist_image_input").fold(
                onSuccess = { path -> vm.update { it.copy(imageInputPath = path) } },
                onFailure = {
                    startMessage = it.message ?: resources.getString(R.string.sd_dist_input_image_import_failed)
                }
            )
        }
    }
    val hooks = SdMasterRunHooks(
        status = status,
        navController = navController,
        onPickInputImage = { inputPicker.launch(arrayOf("image/*")) }
    )

    val onStart: () -> Unit = {
        if (!starting) {
            starting = true
            startMessage = null
            vm.dismissReuseWarning()
            scope.launch {
                try {
                    when (val outcome = startSdMasterRun(context, vm, installedModels, launcher)) {
                        SdMasterStartOutcome.Started -> Unit
                        is SdMasterStartOutcome.Blocked -> {
                            startMessage = outcome.message
                            vm.goTo(outcome.step)
                        }
                    }
                } finally {
                    starting = false
                }
            }
        }
    }
    val goBack: () -> Unit = {
        when (state.step) {
            SdMasterStep.MODEL -> navController.popBackStack()
            SdMasterStep.DEVICES -> vm.goTo(SdMasterStep.MODEL)
            SdMasterStep.RUN -> vm.goTo(SdMasterStep.DEVICES)
        }
    }
    BackHandler(enabled = state.step != SdMasterStep.MODEL) { goBack() }

    val listState = rememberLazyListState()
    LaunchedEffect(state.step) { listState.scrollToItem(0) }

    AppScreenScaffold(
        title = stringResource(
            if (state.mode == SdMasterMode.VIDEO) R.string.sd_r4_video_title else R.string.sd_r4_images_title
        ),
        onBack = { navController.popBackStack() },
        bottomBar = {
            AppTaskActionFooter {
                val message = startMessage
                if (message != null && !status.running) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("sd_master_start_message")
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = goBack,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("sd_master_back")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_back), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    when {
                        state.step != SdMasterStep.RUN -> Button(
                            onClick = {
                                vm.goTo(if (state.step == SdMasterStep.MODEL) SdMasterStep.DEVICES else SdMasterStep.RUN)
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("sd_master_next")
                        ) {
                            Text(stringResource(R.string.sd_r8_w08_next), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                        }
                        status.running -> OutlinedButton(
                            onClick = { cancelSdMasterRun(state, launcher) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("sd_master_stop")
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        else -> Button(
                            onClick = onStart,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("sd_master_start")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.sd_dist_start_work), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SdMasterStepHeader(current = state.step, onGoTo = vm::goTo)
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
                reuseWarning?.let { warning ->
                    item(key = "reuse_warning") { WarningBand(text = warning) }
                }
                if (!state.loaded) {
                    item(key = "loading") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.sd_r8_w08_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    when (state.step) {
                        SdMasterStep.MODEL -> {
                            item(key = "model_how_it_works") { SdHowItWorks(SdHowToScreen.MASTER) }
                            sdMasterModelStepItems(state, vm, env)
                        }
                        SdMasterStep.DEVICES -> sdMasterDevicesStepItems(state, vm, env)
                        SdMasterStep.RUN -> sdMasterRunStepItems(state, vm, hooks)
                    }
                }
            }
        }
    }
}

private fun SdMasterStep.labelRes(): Int = when (this) {
    SdMasterStep.MODEL -> R.string.sd_r8_w08_step_model
    SdMasterStep.DEVICES -> R.string.sd_r8_w08_step_devices
    SdMasterStep.RUN -> R.string.sd_r8_w08_step_run
}

/** Tappable Model · Devices · Run header: the current step is highlighted, earlier ones are ticked. */
@Composable
internal fun SdMasterStepHeader(current: SdMasterStep, onGoTo: (SdMasterStep) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppChromeDefaults.ScreenPadding, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SdMasterStep.entries.forEach { step ->
            val isCurrent = step == current
            val done = step.ordinal < current.ordinal
            val label = stringResource(step.labelRes())
            val doneDescription = stringResource(R.string.sd_r8_w08_step_done_cd, label)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("sd_master_step_${step.name}")
                    .clickable(role = Role.Tab) { onGoTo(step) }
                    .semantics { if (done) stateDescription = doneDescription },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (isCurrent || done) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(26.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        if (done) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        } else {
                            Text(
                                text = (step.ordinal + 1).toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isCurrent) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
