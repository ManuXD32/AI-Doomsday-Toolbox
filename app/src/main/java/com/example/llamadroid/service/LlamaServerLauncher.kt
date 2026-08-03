package com.example.llamadroid.service

import android.content.Context
import android.content.Intent
import com.example.llamadroid.data.LlamaOcrSettingsSnapshot
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.ui.ai.AiJobStartupDiagnostics
import com.example.llamadroid.util.DebugLog

object LlamaServerLauncher {
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
        appContext.startForegroundService(intent)
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

    fun startForOcr(context: Context, settings: LlamaOcrSettingsSnapshot): Result<Unit> = runCatching {
        val appContext = context.applicationContext
        val modelPath = requireNotNull(settings.modelPath?.takeIf { it.isNotBlank() }) {
            appContext.getString(com.example.llamadroid.R.string.pdf_ocr_llama_error_missing_model)
        }
        val mmprojPath = requireNotNull(settings.mmprojPath?.takeIf { it.isNotBlank() }) {
            appContext.getString(com.example.llamadroid.R.string.pdf_ocr_llama_error_missing_mmproj)
        }
        DebugLog.log("LlamaServerLauncher: Starting OCR llama-server on port ${settings.port} with preset ${settings.promptPreset.label}")
        AiJobStartupDiagnostics.record(appContext, "llama_ocr_server_start", "pre_launch_state")
        val intent = Intent(appContext, LlamaService::class.java).apply {
            action = LlamaService.ACTION_START
            putOcrSettings(settings, modelPath, mmprojPath)
        }
        appContext.startForegroundService(intent)
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
        appContext.startForegroundService(Intent(appContext, LlamaService::class.java).apply {
            action = LlamaService.ACTION_RECONFIGURE
            putOcrSettings(settings, modelPath, mmprojPath)
        })
    }

    fun reconfigureGeneral(context: Context, modelPath: String): Result<Unit> = runCatching {
        val appContext = context.applicationContext
        val settingsRepo = SettingsRepository(appContext)
        appContext.startForegroundService(Intent(appContext, LlamaService::class.java).apply {
            action = LlamaService.ACTION_RECONFIGURE
            putExtra(LlamaService.EXTRA_MODEL_PATH, modelPath)
            putExtra(LlamaService.EXTRA_SETTINGS_PROFILE, LlamaService.SETTINGS_PROFILE_GENERAL)
            putExtra(LlamaService.EXTRA_HOST, if (settingsRepo.remoteAccess.value) "0.0.0.0" else "127.0.0.1")
            putExtra(LlamaService.EXTRA_PORT, settingsRepo.serverPort.value)
            putExtra(LlamaService.EXTRA_FLASH_ATTENTION, settingsRepo.flashAttentionEnabled.value)
            putExtra(LlamaService.EXTRA_CUSTOM_FLAGS, settingsRepo.customFlags.value)
            putExtra(LlamaService.EXTRA_COMMAND_TEMPLATE, settingsRepo.customCommandTemplate.value)
        })
    }

    fun stop(context: Context): Result<Unit> = runCatching {
        context.applicationContext.startService(Intent(context.applicationContext, LlamaService::class.java).apply {
            action = LlamaService.ACTION_STOP
        })
    }
}
