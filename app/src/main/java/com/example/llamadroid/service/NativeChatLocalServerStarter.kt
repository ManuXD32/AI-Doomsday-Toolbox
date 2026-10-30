package com.example.llamadroid.service

import android.content.Context
import android.content.Intent
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File

data class NativeChatServerStartRequest(
    val chatId: Long? = null,
    val imagePath: String? = null,
    val videoPath: String? = null,
    val userInitiated: Boolean = true
)

/** [code] is a stable machine-readable reason; the message is already localized. */
class NativeChatServerStartException(val code: String, message: String) : IllegalStateException(message)

/** Outcome of [decideLocalServerAction]. */
sealed class LocalServerAction {
    object Ready : LocalServerAction()
    object Wait : LocalServerAction()
    object Start : LocalServerAction()
    /** Another healthy llama-server owns [otherPort]; it must never be killed silently. */
    data class Blocked(val otherPort: Int) : LocalServerAction()
}

/**
 * Pure decision table for an unlinked loopback llama-server row.
 *
 * @param rowHealthy `/health` of the row's own endpoint answered.
 * @param statePortHealthy `/health` of the port reported by a `Running` state answered
 *   (only consulted when that port differs from [rowPort]).
 * @param staleRunningExpired the grace period for a `Running` state on [rowPort] whose
 *   `/health` fails has elapsed, so the process should be replaced.
 */
internal fun decideLocalServerAction(
    rowHealthy: Boolean,
    state: ServerState,
    rowPort: Int,
    statePortHealthy: Boolean = false,
    staleRunningExpired: Boolean = false
): LocalServerAction = when {
    rowHealthy -> LocalServerAction.Ready
    state is ServerState.Starting || state is ServerState.Loading -> LocalServerAction.Wait
    state is ServerState.Running && state.port != rowPort ->
        if (statePortHealthy) LocalServerAction.Blocked(state.port) else LocalServerAction.Start
    state is ServerState.Running ->
        if (staleRunningExpired) LocalServerAction.Start else LocalServerAction.Wait
    else -> LocalServerAction.Start
}

/**
 * Single owner of "make this native chat server answer /health". Managed card rows are
 * delegated to [ManagedLlamaServerCoordinator]; legacy loopback llama-server rows are started
 * through [LlamaServerLauncher] with preflight checks and fail-fast error reporting.
 */
class NativeChatLocalServerStarter(
    context: Context,
    private val settingsRepo: SettingsRepository = SettingsRepository(context.applicationContext),
    private val database: AppDatabase = AppDatabase.getDatabase(context.applicationContext),
    private val chatService: LlamaServerChatService = LlamaServerChatService()
) {
    private val context = context.applicationContext

    suspend fun ensureReady(
        server: LlamaServerEntity,
        request: NativeChatServerStartRequest = NativeChatServerStartRequest(),
        onStatus: (String) -> Unit = {}
    ) {
        server.managedServerCardId?.let { cardId ->
            try {
                ManagedLlamaServerCoordinator(context, database).prepare(cardId, onStatus)
            } catch (error: ManagedLlamaServerException) {
                throw NativeChatServerStartException(error.code, error.message.orEmpty())
            }
            return
        }
        if (!server.isLlamaServerEngine() || !isNativeChatLoopbackHost(server.host)) return
        val baseUrl = server.baseUrl()

        var staleExpired = false
        while (true) {
            currentCoroutineContext().ensureActive()
            val healthy = chatService.checkConnection(baseUrl)
            val state = LlamaService.state.value
            val statePortHealthy = !healthy && state is ServerState.Running && state.port != server.port &&
                chatService.checkConnection("http://127.0.0.1:${state.port}")
            when (val action = decideLocalServerAction(healthy, state, server.port, statePortHealthy, staleExpired)) {
                LocalServerAction.Ready -> return
                LocalServerAction.Start -> break
                is LocalServerAction.Blocked -> throw NativeChatServerStartException(
                    "blocked_other_port",
                    context.getString(
                        R.string.native_chat_server_start_blocked_other_port,
                        action.otherPort,
                        runningModelLabel()
                    )
                )
                LocalServerAction.Wait -> {
                    onStatus(context.getString(R.string.llama_client_status_waiting_local_server))
                    val staleRunning = state is ServerState.Running
                    val outcome = waitForHealth(
                        baseUrl = baseUrl,
                        maxWaitMs = if (staleRunning) STALE_RUNNING_GRACE_MS else READY_TIMEOUT_MS,
                        previousError = null,
                        failOnError = false
                    )
                    when {
                        outcome == WaitOutcome.READY -> return
                        // Replace a stale Running process after the grace period.
                        staleRunning -> staleExpired = true
                        // An Error ends the wait; the next pass decides to start again.
                        outcome == WaitOutcome.ERROR -> Unit
                        else -> throw NativeChatServerStartException(
                            "timeout",
                            context.getString(R.string.llama_client_error_local_server_timeout)
                        )
                    }
                }
            }
        }

        if (!request.userInitiated && LlamaService.hasRecentStartupFailure()) {
            throw NativeChatServerStartException(
                "recent_failure",
                context.getString(R.string.llama_client_error_local_server_not_running)
            )
        }
        startAndWait(server, request, baseUrl, onStatus)
    }

    private suspend fun startAndWait(
        server: LlamaServerEntity,
        request: NativeChatServerStartRequest,
        baseUrl: String,
        onStatus: (String) -> Unit
    ) {
        val savedProfile = LlamaServerLaunchProfile.decode(server.localLaunchProfileJson)
        val modelPath = savedProfile?.modelPath?.takeIf { it.isNotBlank() }
            ?: settingsRepo.selectedModelPath.value
            ?: throw NativeChatServerStartException(
                "no_model",
                context.getString(R.string.llama_client_error_no_selected_model)
            )
        val selectedVisionEnabled = savedProfile?.visionEnabled ?: settingsRepo.enableVision.value
        val selectedMmprojPath = savedProfile?.mmprojPath ?: settingsRepo.selectedMmprojPath.value
        val videoAutoStartProfile = if (!request.videoPath.isNullOrBlank()) {
            (savedProfile ?: LlamaServerLaunchProfile.capture(settingsRepo)).copy(
                modelPath = modelPath,
                mmprojPath = savedProfile?.mmprojPath ?: selectedMmprojPath,
                // A video turn must load the projector even when general vision chat is off.
                visionEnabled = true,
                host = nativeChatLocalHostForServer(server.host),
                serverPort = server.port
            )
        } else {
            null
        }
        // A saved profile is the exact server configuration. A video-only turn builds a
        // short-lived profile so LlamaService enables MTMD for this auto-started process.
        val profileForRequest = videoAutoStartProfile ?: savedProfile
        val profileJsonForRequest = profileForRequest?.let(LlamaServerLaunchProfile::encode)
        val shouldPassVisionProjector = profileForRequest?.let { profile ->
            (profile.visionEnabled || profile.videoEnabled) && !profile.mmprojPath.isNullOrBlank()
        } ?: shouldAttachMmprojForLocalAutoStart(
            server = server,
            imagePath = request.imagePath,
            videoPath = request.videoPath,
            visionEnabled = selectedVisionEnabled,
            selectedMmprojPath = selectedMmprojPath
        )
        val mmprojForRequest = profileForRequest?.mmprojPath ?: selectedMmprojPath
        val draftForRequest = draftModelPathNeeded(profileForRequest)
        preflightFiles(
            modelPath = modelPath,
            mmprojPath = mmprojForRequest.takeIf { shouldPassVisionProjector },
            draftModelPath = draftForRequest
        )

        val startIntent = Intent(context, LlamaService::class.java).apply {
            action = LlamaService.ACTION_START
            putExtra(LlamaService.EXTRA_MODEL_PATH, modelPath)
            putExtra(LlamaService.EXTRA_SETTINGS_PROFILE, LlamaService.SETTINGS_PROFILE_GENERAL)
            putExtra(LlamaService.EXTRA_HOST, nativeChatLocalHostForServer(server.host))
            putExtra(LlamaService.EXTRA_PORT, server.port)
            putExtra(LlamaService.EXTRA_ALLOW_SETTINGS_MMPROJ, false)
            profileJsonForRequest?.let { putExtra(LlamaService.EXTRA_LAUNCH_PROFILE_JSON, it) }
            if (shouldPassVisionProjector) {
                putExtra(LlamaService.EXTRA_MMPROJ_PATH, mmprojForRequest)
            }
            if (profileForRequest == null && settingsRepo.speculativeEnabled.value) {
                if (needsSettingsDraftModel()) {
                    putExtra(LlamaService.EXTRA_DRAFT_MODEL_PATH, settingsRepo.draftModelPath.value)
                }
                putExtra(LlamaService.EXTRA_DRAFT_MAX, settingsRepo.draftMaxTokens.value)
                putExtra(LlamaService.EXTRA_DRAFT_MIN, settingsRepo.draftMinTokens.value)
                putExtra(LlamaService.EXTRA_DRAFT_P_MIN, settingsRepo.draftPMin.value)
                putExtra(LlamaService.EXTRA_DRAFT_THREADS, settingsRepo.draftThreads.value)
                putExtra(LlamaService.EXTRA_DRAFT_THREADS_BATCH, settingsRepo.draftThreadsBatch.value)
            }
            putExtra(LlamaService.EXTRA_FLASH_ATTENTION, settingsRepo.flashAttentionEnabled.value)
            putExtra(LlamaService.EXTRA_CUSTOM_FLAGS, settingsRepo.customFlags.value)
            putExtra(LlamaService.EXTRA_COMMAND_TEMPLATE, settingsRepo.customCommandTemplate.value)
        }

        onStatus(context.getString(R.string.llama_client_status_starting_local_server))
        DebugLog.log("NativeChatLocalServerStarter: starting local llama-server at $baseUrl")
        val previousState = LlamaService.state.value
        LlamaServerLauncher.startLocalChat(context, startIntent).getOrElse { error ->
            throw NativeChatServerStartException(
                "dispatch_failed",
                context.getString(R.string.native_chat_server_start_failed_with_reason, error.message.orEmpty())
            )
        }
        if (videoAutoStartProfile != null && profileJsonForRequest != server.localLaunchProfileJson) {
            // Keep the video-enabled profile on the server row so a cold retry can rebuild it.
            database.llamaServerDao().updateServer(server.copy(localLaunchProfileJson = profileJsonForRequest))
        }

        when (waitForHealth(baseUrl, READY_TIMEOUT_MS, previousState, failOnError = true)) {
            WaitOutcome.READY -> Unit
            WaitOutcome.ERROR -> {
                val reason = (LlamaService.state.value as? ServerState.Error)?.message.orEmpty()
                throw NativeChatServerStartException(
                    "start_failed",
                    context.getString(R.string.native_chat_server_start_failed_with_reason, reason)
                )
            }
            WaitOutcome.STUCK_STOPPED -> throw NativeChatServerStartException(
                "start_failed",
                context.getString(
                    R.string.native_chat_server_start_failed_with_reason,
                    context.getString(R.string.llama_client_error_local_server_not_running)
                )
            )
            WaitOutcome.TIMEOUT -> throw NativeChatServerStartException(
                "timeout",
                context.getString(R.string.llama_client_error_local_server_timeout)
            )
        }
    }

    private enum class WaitOutcome { READY, ERROR, STUCK_STOPPED, TIMEOUT }

    /**
     * Polls /health. With [failOnError] a new `Error` (seen after the launch became active, or
     * different from [previousError]) ends the wait immediately instead of after the full timeout.
     */
    private suspend fun waitForHealth(
        baseUrl: String,
        maxWaitMs: Long,
        previousError: ServerState?,
        failOnError: Boolean
    ): WaitOutcome {
        val startedAt = System.currentTimeMillis()
        var sawActiveState = false
        while (System.currentTimeMillis() - startedAt < maxWaitMs) {
            delay(POLL_DELAY_MS)
            currentCoroutineContext().ensureActive()
            if (chatService.checkConnection(baseUrl)) return WaitOutcome.READY
            val state = LlamaService.state.value
            if (state is ServerState.Starting || state is ServerState.Loading || state is ServerState.Running) {
                sawActiveState = true
            }
            if (state is ServerState.Error) {
                // Without failOnError the caller wants to see the Error and start again.
                if (!failOnError) return WaitOutcome.ERROR
                // The main-process state is a projection that can re-emit an old Error as a new
                // object, so only an Error after this launch became active, or a different one, counts.
                if (sawActiveState || state != previousError) return WaitOutcome.ERROR
            }
            if (failOnError && !sawActiveState &&
                (state is ServerState.Stopped || state == previousError) &&
                System.currentTimeMillis() - startedAt > NEVER_STARTED_GRACE_MS
            ) {
                return WaitOutcome.STUCK_STOPPED
            }
        }
        return WaitOutcome.TIMEOUT
    }

    private fun needsSettingsDraftModel(): Boolean {
        val mode = settingsRepo.speculativeMode.value
        return mode.requiresDraftModel ||
            (mode == LlamaSpeculativeMode.DRAFT_MTP && settingsRepo.mtpUseDraftModel.value)
    }

    /** Draft model path the launch will actually load, or null when none is required. */
    private fun draftModelPathNeeded(profile: LlamaServerLaunchProfile?): String? {
        if (profile != null) {
            if (!profile.speculativeEnabled) return null
            val mode = profile.speculativeMode?.let(LlamaSpeculativeMode::fromFlagValue) ?: return null
            val needs = mode.requiresDraftModel ||
                (mode == LlamaSpeculativeMode.DRAFT_MTP && profile.mtpUseDraftModel)
            return profile.draftModelPath?.takeIf { needs && it.isNotBlank() }
        }
        if (!settingsRepo.speculativeEnabled.value || !needsSettingsDraftModel()) return null
        return settingsRepo.draftModelPath.value?.takeIf { it.isNotBlank() }
    }

    private fun preflightFiles(modelPath: String, mmprojPath: String?, draftModelPath: String?) {
        for (path in listOfNotNull(modelPath, mmprojPath, draftModelPath)) {
            val file = File(path)
            if (!file.isFile || !file.canRead()) {
                throw NativeChatServerStartException(
                    "model_file_missing",
                    context.getString(R.string.native_chat_server_start_missing_model_file, file.name.ifBlank { path })
                )
            }
        }
    }

    private fun runningModelLabel(): String =
        (DistributedService.lastRunParams.value["modelPath"] as? String)
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.native_chat_server_start_unknown_model)

    companion object {
        private const val POLL_DELAY_MS = 2_000L
        private const val READY_TIMEOUT_MS = 180_000L
        private const val STALE_RUNNING_GRACE_MS = 10_000L
        private const val NEVER_STARTED_GRACE_MS = 30_000L
    }
}
