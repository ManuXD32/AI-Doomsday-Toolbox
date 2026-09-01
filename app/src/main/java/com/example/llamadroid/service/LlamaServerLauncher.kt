package com.example.llamadroid.service

import android.content.Context
import android.content.Intent
import com.example.llamadroid.data.LlamaOcrSettingsSnapshot
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.ui.ai.AiJobStartupDiagnostics
import com.example.llamadroid.util.DebugLog

object LlamaServerLauncher {
    internal fun ocrReservedSessionId(): String =
        com.example.llamadroid.data.model.LlamaServerSessionIds.OCR

    private val ownedServiceActions = setOf(
        LlamaService.ACTION_START,
        LlamaService.ACTION_RECONFIGURE,
        LlamaService.ACTION_SWITCH_MODEL,
        LlamaService.ACTION_STOP,
        LlamaService.ACTION_RECOVER
    )

    private val ownedSessionActions = setOf(
        LlamaServerSessionService.ACTION_START,
        LlamaServerSessionService.ACTION_STOP,
        LlamaServerSessionService.ACTION_CLEAR_LOGS,
        LlamaServerSessionService.ACTION_REMOVE
    )

    /** Pure action gate kept separate so service-boundary policy can be unit-tested. */
    internal fun isOwnedServiceAction(action: String?): Boolean = action in ownedServiceActions

    internal fun isOwnedSessionAction(action: String?): Boolean = action in ownedSessionActions

    /**
     * Sends only an explicit, app-local command to [LlamaService]. This keeps the launcher from
     * becoming a generic intent forwarder while preserving one diagnostic path for every local
     * start, reconfigure, switch, stop, and recorded-owner recovery request.
     */
    fun dispatchOwnedCommand(context: Context, intent: Intent): Result<Unit> = runCatching {
        val appContext = context.applicationContext
        val action = requireNotNull(intent.action) { "Missing llama-server service action" }
        require(isOwnedServiceAction(action)) { "Unsupported llama-server service action: $action" }
        val component = intent.component
        require(
            component?.packageName == appContext.packageName &&
                component?.className == LlamaService::class.java.name
        ) { "Command must explicitly target the local LlamaService" }

        // OCR temporarily owns every local llama runtime.  Commands sent through this launcher
        // are the only regular entry point that can target the legacy service, so reject any
        // unrelated start/reconfigure/stop while the lease is active.  Recovery uses the same
        // persisted token as the keyed session commands below.
        if (action != LlamaService.ACTION_RECOVER &&
            LlamaOcrExclusiveLeaseStore.rejectsLegacyCommand(
                appContext,
                intent.getStringExtra(LlamaOcrExclusiveLeaseStore.TOKEN_EXTRA)
            )
        ) {
            throw IllegalStateException("A GGUF OCR runtime currently owns the local llama server.")
        }

        AiJobStartupDiagnostics.record(
            appContext,
            "llama_server_owned_command",
            "dispatch_requested",
            "action=$action"
        )
        val ownedIntent = Intent(intent).setPackage(appContext.packageName)
        if (action == LlamaService.ACTION_STOP) {
            appContext.startService(ownedIntent)
        } else {
            appContext.startForegroundService(ownedIntent)
        }
        AiJobStartupDiagnostics.record(
            appContext,
            "llama_server_owned_command",
            "dispatch_sent",
            "action=$action"
        )
        DebugLog.log("LlamaServerLauncher: dispatched owned action=$action")
    }.onFailure { error ->
        AiJobStartupDiagnostics.record(
            context.applicationContext,
            "llama_server_owned_command",
            "dispatch_rejected",
            "error=${error.javaClass.simpleName}: ${error.message.orEmpty()}"
        )
        DebugLog.log("LlamaServerLauncher: rejected owned command: ${error.message}")
    }

    private fun Intent.putOcrSettings(settings: LlamaOcrSettingsSnapshot, modelPath: String, mmprojPath: String) {
        val flags = composeLlamaOcrFlags(
            recommendedFlags = settings.promptPreset.recommendedFlags,
            customFlags = settings.customFlags
        )
        putExtra(LlamaService.EXTRA_MODEL_PATH, modelPath)
        putExtra(LlamaService.EXTRA_MMPROJ_PATH, mmprojPath)
        putExtra(LlamaService.EXTRA_ALLOW_SETTINGS_MMPROJ, false)
        putExtra(LlamaService.EXTRA_SETTINGS_PROFILE, LlamaService.SETTINGS_PROFILE_OCR)
        putExtra(LlamaService.EXTRA_HOST, "127.0.0.1")
        putExtra(LlamaService.EXTRA_PORT, settings.port)
        putExtra(LlamaService.EXTRA_CONTEXT_SIZE, settings.contextSize)
        putExtra(LlamaService.EXTRA_BATCH_SIZE, 512)
        putExtra(LlamaService.EXTRA_PHYSICAL_BATCH_SIZE, 512)
        putExtra(LlamaService.EXTRA_TEMPERATURE, 0.0f)
        putExtra(LlamaService.EXTRA_KV_CACHE_ENABLED, false)
        putExtra(LlamaService.EXTRA_KV_CACHE_REUSE, 0)
        putExtra(LlamaService.EXTRA_FLASH_ATTENTION, settings.flashAttention)
        putExtra(LlamaService.EXTRA_PARALLEL, settings.parallel)
        if (settings.cacheRam > 0) putExtra(LlamaService.EXTRA_CACHE_RAM, settings.cacheRam)
        if (flags.isNotBlank()) putExtra(LlamaService.EXTRA_CUSTOM_FLAGS, flags)
        settings.commandTemplate?.takeIf { it.isNotBlank() }?.let {
            putExtra(LlamaService.EXTRA_COMMAND_TEMPLATE, it)
        }
    }

    internal fun composeLlamaOcrFlags(
        recommendedFlags: String,
        customFlags: String?
    ): String {
        val combined = when {
            customFlags.isNullOrBlank() -> recommendedFlags
            customFlags.trim() == recommendedFlags.trim() -> recommendedFlags
            else -> "$recommendedFlags ${customFlags.trim()}"
        }
        val tokens = combined.split(Regex("\\s+")).filter { it.isNotBlank() }
        val result = mutableListOf<String>()
        val seenPairs = mutableSetOf<Pair<String, String?>>()
        var index = 0
        while (index < tokens.size) {
            val flag = tokens[index]
            val value = tokens.getOrNull(index + 1)?.takeUnless { it.startsWith("-") }
            val consumed = if (value != null) 2 else 1
            // Flash attention has a dedicated captured setting and must not be emitted again.
            if (flag !in setOf("--flash-attn", "-fa") && !isUnsafeForLlamaOcr(flag)) {
                val key = flag to value
                if (seenPairs.add(key)) {
                    result += flag
                    if (value != null) result += value
                }
            }
            index += consumed
        }
        return result.joinToString(" ")
    }

    private fun isUnsafeForLlamaOcr(flag: String): Boolean {
        val normalized = flag.substringBefore('=')
        return normalized in setOf(
            "--spec-type",
            "--spec-draft-model",
            "-md",
            "--spec-draft-n-max",
            "--spec-draft-n-min",
            "--spec-draft-p-min",
            "--spec-draft-threads",
            "--spec-draft-threads-batch",
            "--spec-ngram-mod-n-min",
            "--spec-ngram-mod-n-max",
            "--spec-ngram-mod-n-match",
            "--spec-ngram-simple-size-n",
            "--spec-ngram-simple-size-m",
            "--spec-ngram-simple-min-hits",
            "--spec-ngram-map-k-size-n",
            "--spec-ngram-map-k-size-m",
            "--spec-ngram-map-k-min-hits",
            "--spec-ngram-map-k4v-size-n",
            "--spec-ngram-map-k4v-size-m",
            "--spec-ngram-map-k4v-min-hits",
            "--draft-device",
            "--device-draft",
            "--gpu-layers-draft",
            "--tools"
        )
    }

    fun start(context: Context, modelPath: String? = null): Result<Unit> = runCatching {
        val appContext = context.applicationContext
        val settingsRepo = SettingsRepository(appContext)
        DebugLog.log("LlamaServerLauncher: Starting server...")
        AiJobStartupDiagnostics.record(appContext, "llama_server_start", "pre_launch_state")
        val serverHost = if (settingsRepo.remoteAccess.value) "0.0.0.0" else "127.0.0.1"
        val intent = Intent(appContext, LlamaService::class.java).apply {
            action = LlamaService.ACTION_START
            putExtra(LlamaService.EXTRA_MODEL_PATH, modelPath ?: "")
            putExtra(LlamaService.EXTRA_SETTINGS_PROFILE, LlamaService.SETTINGS_PROFILE_GENERAL)
            putExtra(LlamaService.EXTRA_HOST, serverHost)
            putExtra(LlamaService.EXTRA_PORT, settingsRepo.serverPort.value)

            if (settingsRepo.speculativeEnabled.value) {
                val speculativeMode = settingsRepo.speculativeMode.value
                val shouldPassDraftModel =
                    speculativeMode.requiresDraftModel ||
                        (speculativeMode == LlamaSpeculativeMode.DRAFT_MTP && settingsRepo.mtpUseDraftModel.value)
                if (shouldPassDraftModel) {
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
        dispatchOwnedCommand(appContext, intent).getOrThrow()
        AiJobStartupDiagnostics.record(appContext, "llama_server_start", "post_launch_state")
        DebugLog.log("LlamaServerLauncher: start intent sent")
    }.onFailure { error ->
        AiJobStartupDiagnostics.record(
            context.applicationContext,
            "llama_server_start",
            "launch_failed",
            "error=${error.javaClass.simpleName}: ${error.message.orEmpty()}"
        )
        DebugLog.log("LlamaServerLauncher: start FAILED: ${error.message}")
    }

    /**
     * Build the complete keyed launch profile for OCR without touching SettingsRepository.  This
     * pure mapping is intentionally kept public-to-the-module so it can be unit tested without an
     * Android service or a live native process.
     */
    internal fun buildLlamaOcrLaunchProfile(settings: LlamaOcrSettingsSnapshot): LlamaServerLaunchProfile =
        LlamaServerLaunchProfile(
            modelPath = settings.modelPath.orEmpty().trim(),
            mmprojPath = settings.mmprojPath?.trim()?.takeIf { it.isNotBlank() },
            visionEnabled = !settings.mmprojPath.isNullOrBlank(),
            host = "127.0.0.1",
            serverPort = settings.port,
            threads = 4,
            batchSize = 512,
            physicalBatchSize = 512,
            contextSize = settings.contextSize,
            temperature = 0f,
            kvCacheEnabled = false,
            kvCacheReuse = 0,
            kvOffloadMode = com.example.llamadroid.data.SettingsRepository.LLAMA_KV_OFFLOAD_CPU,
            parallel = settings.parallel.coerceAtLeast(1),
            cacheRam = settings.cacheRam.takeIf { it > 0 },
            cachePrompt = true,
            cacheIdleSlots = true,
            customFlags = composeLlamaOcrFlags(
                recommendedFlags = settings.promptPreset.recommendedFlags,
                customFlags = settings.customFlags
            ).takeIf { it.isNotBlank() },
            flashAttention = settings.flashAttention,
            commandTemplate = settings.commandTemplate?.trim()?.takeIf { it.isNotBlank() },
            speculativeEnabled = false,
            draftModelPath = null,
            nativeToolsEnabled = false
        )

    fun startForOcr(
        context: Context,
        settings: LlamaOcrSettingsSnapshot,
        leaseToken: String? = null
    ): Result<Unit> = runCatching {
        val appContext = context.applicationContext
        requireNotNull(settings.modelPath?.takeIf { it.isNotBlank() }) {
            appContext.getString(com.example.llamadroid.R.string.pdf_ocr_llama_error_missing_model)
        }
        requireNotNull(settings.mmprojPath?.takeIf { it.isNotBlank() }) {
            appContext.getString(com.example.llamadroid.R.string.pdf_ocr_llama_error_missing_mmproj)
        }
        val profile = buildLlamaOcrLaunchProfile(settings)
        DebugLog.log("LlamaServerLauncher: Starting OCR session ${ocrReservedSessionId()} on port ${settings.port} with preset ${settings.promptPreset.label}")
        AiJobStartupDiagnostics.record(appContext, "llama_ocr_server_start", "pre_launch_state")
        startReservedSession(
            context = appContext,
            sessionId = ocrReservedSessionId(),
            profile = profile,
            portOverride = settings.port,
            leaseToken = leaseToken ?: LlamaOcrExclusiveLeaseStore.currentToken(appContext)
        ).getOrThrow()
        AiJobStartupDiagnostics.record(appContext, "llama_ocr_server_start", "post_launch_state")
    }.onFailure { error ->
        AiJobStartupDiagnostics.record(
            context.applicationContext,
            "llama_ocr_server_start",
            "launch_failed",
            "error=${error.javaClass.simpleName}: ${error.message.orEmpty()}"
        )
        DebugLog.log("LlamaServerLauncher: OCR start FAILED: ${error.message}")
    }

    fun reconfigureForOcr(context: Context, settings: LlamaOcrSettingsSnapshot): Result<Unit> = runCatching {
        val appContext = context.applicationContext
        val modelPath = requireNotNull(settings.modelPath?.takeIf { it.isNotBlank() })
        val mmprojPath = requireNotNull(settings.mmprojPath?.takeIf { it.isNotBlank() })
        dispatchOwnedCommand(appContext, Intent(appContext, LlamaService::class.java).apply {
            action = LlamaService.ACTION_RECONFIGURE
            putOcrSettings(settings, modelPath, mmprojPath)
        }).getOrThrow()
    }

    fun reconfigureGeneral(context: Context, modelPath: String): Result<Unit> = runCatching {
        val appContext = context.applicationContext
        val settingsRepo = SettingsRepository(appContext)
        dispatchOwnedCommand(appContext, Intent(appContext, LlamaService::class.java).apply {
            action = LlamaService.ACTION_RECONFIGURE
            putExtra(LlamaService.EXTRA_MODEL_PATH, modelPath)
            putExtra(LlamaService.EXTRA_SETTINGS_PROFILE, LlamaService.SETTINGS_PROFILE_GENERAL)
            putExtra(LlamaService.EXTRA_HOST, if (settingsRepo.remoteAccess.value) "0.0.0.0" else "127.0.0.1")
            putExtra(LlamaService.EXTRA_PORT, settingsRepo.serverPort.value)
            putExtra(LlamaService.EXTRA_FLASH_ATTENTION, settingsRepo.flashAttentionEnabled.value)
            putExtra(LlamaService.EXTRA_CUSTOM_FLAGS, settingsRepo.customFlags.value)
            putExtra(LlamaService.EXTRA_COMMAND_TEMPLATE, settingsRepo.customCommandTemplate.value)
        }).getOrThrow()
    }

    /**
     * Local chat has a saved per-server profile, so it cannot use [start]'s global settings
     * shortcut. Keep its command dispatch here nevertheless, so every local launch shares the
     * same diagnostics and service lifecycle entry point.
     */
    fun startLocalChat(context: Context, startIntent: Intent): Result<Unit> = runCatching {
        val appContext = context.applicationContext
        AiJobStartupDiagnostics.record(appContext, "llama_local_chat_start", "pre_launch_state")
        dispatchOwnedCommand(appContext, startIntent).getOrThrow()
        AiJobStartupDiagnostics.record(appContext, "llama_local_chat_start", "post_launch_state")
    }.onFailure { error ->
        AiJobStartupDiagnostics.record(
            context.applicationContext,
            "llama_local_chat_start",
            "launch_failed",
            "error=${error.javaClass.simpleName}: ${error.message.orEmpty()}"
        )
    }

    /** Start one independent keyed session without changing global SettingsRepository values. */
    fun startSession(
        context: Context,
        sessionId: String,
        profile: LlamaServerLaunchProfile,
        portOverride: Int? = null,
        leaseToken: String? = null
    ): Result<Unit> = dispatchSessionCommand(
        context,
        Intent(context.applicationContext, LlamaServerSessionService::class.java).apply {
            action = LlamaServerSessionService.ACTION_START
            putExtra(LlamaServerSessionService.EXTRA_SESSION_ID, sessionId)
            putExtra(LlamaServerSessionService.EXTRA_PROFILE_JSON, LlamaServerLaunchProfile.encode(profile))
            portOverride?.let { putExtra(LlamaServerSessionService.EXTRA_PORT, it) }
            leaseToken?.let { putExtra(LlamaServerSessionService.EXTRA_LEASE_TOKEN, it) }
        }
    )

    /** Stop only the child owned by [sessionId]; no same-UID sweep is performed. */
    fun stopSession(context: Context, sessionId: String, leaseToken: String? = null): Result<Unit> = dispatchSessionCommand(
        context,
        Intent(context.applicationContext, LlamaServerSessionService::class.java).apply {
            action = LlamaServerSessionService.ACTION_STOP
            putExtra(LlamaServerSessionService.EXTRA_SESSION_ID, sessionId)
            leaseToken?.let { putExtra(LlamaServerSessionService.EXTRA_LEASE_TOKEN, it) }
        }
    )

    fun clearSessionLogs(context: Context, sessionId: String): Result<Unit> = dispatchSessionCommand(
        context,
        Intent(context.applicationContext, LlamaServerSessionService::class.java).apply {
            action = LlamaServerSessionService.ACTION_CLEAR_LOGS
            putExtra(LlamaServerSessionService.EXTRA_SESSION_ID, sessionId)
        }
    )

    /** Remove a card/session and its durable logs. Call only after the card itself is deleted. */
    fun removeSession(context: Context, sessionId: String): Result<Unit> = dispatchSessionCommand(
        context,
        Intent(context.applicationContext, LlamaServerSessionService::class.java).apply {
            action = LlamaServerSessionService.ACTION_REMOVE
            putExtra(LlamaServerSessionService.EXTRA_SESSION_ID, sessionId)
        }
    )

    /** Compatibility wrapper for internal OCR/master/worker launchers. */
    fun startReservedSession(
        context: Context,
        sessionId: String,
        profile: LlamaServerLaunchProfile,
        portOverride: Int? = null,
        leaseToken: String? = null
    ): Result<Unit> {
        require(com.example.llamadroid.data.model.LlamaServerSessionIds.isReserved(sessionId)) {
            "Reserved session id required for internal launcher compatibility"
        }
        return startSession(context, sessionId, profile, portOverride, leaseToken)
    }

    private fun dispatchSessionCommand(context: Context, intent: Intent): Result<Unit> = runCatching {
        val appContext = context.applicationContext
        val action = requireNotNull(intent.action) { "Missing llama session action" }
        require(isOwnedSessionAction(action)) { "Unsupported llama session action: $action" }
        val sessionId = intent.getStringExtra(LlamaServerSessionService.EXTRA_SESSION_ID).orEmpty()
        require(sessionId.isNotBlank()) { "Session id is required" }
        if (LlamaOcrExclusiveLeaseStore.rejectsSessionCommand(
                appContext,
                sessionId,
                intent.getStringExtra(LlamaOcrExclusiveLeaseStore.TOKEN_EXTRA)
            )
        ) {
            throw IllegalStateException("A GGUF OCR runtime currently owns the local llama sessions.")
        }
        val component = intent.component
        require(
            component?.packageName == appContext.packageName &&
                component?.className == LlamaServerSessionService::class.java.name
        ) { "Session command must explicitly target the local session service" }
        val ownedIntent = Intent(intent).setPackage(appContext.packageName)
        if (action == LlamaServerSessionService.ACTION_START) {
            appContext.startForegroundService(ownedIntent)
        } else {
            appContext.startService(ownedIntent)
        }
        DebugLog.log("LlamaServerLauncher: dispatched session action=$action")
    }

    fun startLegacyProfile(
        context: Context,
        profile: LlamaServerLaunchProfile,
        leaseToken: String? = null
    ): Result<Unit> = runCatching {
        require(profile.hasModel()) { "A model is required for the legacy llama-server." }
        val appContext = context.applicationContext
        dispatchOwnedCommand(
            appContext,
            Intent(appContext, LlamaService::class.java).apply {
                action = LlamaService.ACTION_START
                putExtra(LlamaService.EXTRA_MODEL_PATH, profile.modelPath)
                putExtra(LlamaService.EXTRA_MMPROJ_PATH, profile.mmprojPath)
                putExtra(LlamaService.EXTRA_ALLOW_SETTINGS_MMPROJ, false)
                putExtra(LlamaService.EXTRA_SETTINGS_PROFILE, LlamaService.SETTINGS_PROFILE_GENERAL)
                putExtra(LlamaService.EXTRA_HOST, profile.host)
                putExtra(LlamaService.EXTRA_PORT, profile.serverPort)
                putExtra(LlamaService.EXTRA_LAUNCH_PROFILE_JSON, LlamaServerLaunchProfile.encode(profile))
                leaseToken?.let { putExtra(LlamaOcrExclusiveLeaseStore.TOKEN_EXTRA, it) }
            }
        ).getOrThrow()
    }

    fun stopLegacy(context: Context, leaseToken: String? = null): Result<Unit> = runCatching {
        dispatchOwnedCommand(context.applicationContext, Intent(context.applicationContext, LlamaService::class.java).apply {
            action = LlamaService.ACTION_STOP
            leaseToken?.let { putExtra(LlamaOcrExclusiveLeaseStore.TOKEN_EXTRA, it) }
        }).getOrThrow()
    }

    fun stopForOcr(context: Context, leaseToken: String? = null): Result<Unit> =
        stopSession(
            context = context,
            sessionId = ocrReservedSessionId(),
            leaseToken = leaseToken ?: LlamaOcrExclusiveLeaseStore.currentToken(context.applicationContext)
        )

    fun stop(context: Context): Result<Unit> = stopLegacy(context)

    fun recover(context: Context): Result<Unit> =
        dispatchOwnedCommand(context.applicationContext, Intent(context.applicationContext, LlamaService::class.java).apply {
            action = LlamaService.ACTION_RECOVER
        })
}
