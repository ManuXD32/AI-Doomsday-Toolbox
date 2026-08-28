package com.example.llamadroid.service

import androidx.annotation.Keep
import com.example.llamadroid.data.SettingsRepository
import com.google.gson.Gson

/**
 * A durable, app-managed llama.cpp launch snapshot owned by one native-chat
 * server entry.  It intentionally does not alter global LLM settings when a
 * chat starts that server.
 */
@Keep
data class LlamaServerLaunchProfile(
    val schemaVersion: Int = 3,
    val modelPath: String = "",
    val mmprojPath: String? = null,
    val visionEnabled: Boolean = false,
    val loraPath: String? = null,
    /** Network binding is part of the launch command, not a UI-only preference. */
    val host: String = "127.0.0.1",
    val serverPort: Int = 8080,
    val threads: Int = 4,
    val batchSize: Int = 512,
    val physicalBatchSize: Int? = null,
    val contextSize: Int = 8192,
    val temperature: Float = 0.7f,
    val kvCacheEnabled: Boolean = false,
    val kvCacheTypeK: String = "f16",
    val kvCacheTypeV: String = "f16",
    val kvCacheReuse: Int = 0,
    val kvOffloadMode: String = LlamaKvOffloadMode.AUTO.value,
    val noMmap: Boolean = false,
    val parallel: Int? = null,
    val cacheRam: Int? = null,
    val contextCheckpoints: Int? = null,
    val checkpointMinStep: Int? = null,
    val cachePrompt: Boolean = true,
    val cacheIdleSlots: Boolean = true,
    val kvUnifiedMode: String = LlamaKvUnifiedMode.AUTO.value,
    val swaFull: Boolean = false,
    val sleepIdleSeconds: Int? = 1800,
    val customFlags: String? = null,
    val flashAttention: Boolean = false,
    /** OpenCL-only placement policy for this launch profile. */
    val openClCpuTargetGpuDraft: Boolean = false,
    /**
     * The llama-server binary requested by this profile. Null is reserved for profiles written
     * before binary selection became part of the snapshot and falls back to the global setting.
     */
    val nativeBinarySelection: String? = null,
    val nativeToolsEnabled: Boolean = false,
    val commandTemplate: String? = null,
    val speculativeEnabled: Boolean = false,
    val speculativeMode: String? = null,
    val draftModelPath: String? = null,
    val draftMax: Int = 3,
    val draftMin: Int = 0,
    val draftPMin: Float = 0f,
    val draftThreads: Int = 4,
    val draftThreadsBatch: Int = 4,
    val mtpDraftMax: Int = 3,
    val mtpDraftMin: Int = 0,
    val mtpDraftPMin: Float = 0f,
    val mtpUseDraftModel: Boolean = false,
    val draftDeviceMode: String = LlamaDraftDeviceMode.AUTO.value,
    val ngramModNMatch: Int = 24,
    val ngramModNMin: Int = 48,
    val ngramModNMax: Int = 64,
    val ngramSimpleSizeN: Int = 12,
    val ngramSimpleSizeM: Int = 48,
    val ngramSimpleMinHits: Int = 1,
    val ngramMapKSizeN: Int = 12,
    val ngramMapKSizeM: Int = 48,
    val ngramMapKMinHits: Int = 1,
    val ngramMapK4VSizeN: Int = 12,
    val ngramMapK4VSizeM: Int = 48,
    val ngramMapK4VMinHits: Int = 1
) {
    fun hasModel(): Boolean = modelPath.isNotBlank()

    fun summary(): String = "${modelPath.substringAfterLast('/')} · $contextSize ctx · ${threads} threads"

    /**
     * The launcher-shaped projection used by saved-command tests and previews.
     * Keeping it here makes the snapshot-to-command contract explicit and prevents
     * individual callers from selectively mapping settings.
     */
    fun toLlamaConfig(): LlamaConfig {
        val mode = speculativeMode?.let(LlamaSpeculativeMode::fromFlagValue)
            ?.takeIf { speculativeEnabled }
        val configuredDraftModel = if (
            mode == LlamaSpeculativeMode.DRAFT_MTP && !mtpUseDraftModel
        ) {
            null
        } else {
            draftModelPath
        }
        return LlamaConfig(
            modelPath = modelPath,
            contextSize = contextSize,
            threads = threads,
            batchSize = batchSize,
            physicalBatchSize = physicalBatchSize,
            port = serverPort,
            temperature = temperature,
            host = host,
            mmprojPath = mmprojPath.takeIf { visionEnabled },
            loraPath = loraPath,
            kvCacheEnabled = kvCacheEnabled,
            kvCacheTypeK = kvCacheTypeK,
            kvCacheTypeV = kvCacheTypeV,
            kvCacheReuse = kvCacheReuse,
            kvOffloadMode = kvOffloadMode,
            noMmap = noMmap,
            speculativeMode = mode,
            draftModelPath = configuredDraftModel,
            draftMax = draftMax,
            draftMin = draftMin,
            draftPMin = draftPMin,
            draftThreads = draftThreads,
            draftThreadsBatch = draftThreadsBatch,
            draftDeviceMode = draftDeviceMode,
            mtpDraftMax = mtpDraftMax,
            mtpDraftMin = mtpDraftMin,
            mtpDraftPMin = mtpDraftPMin,
            ngramModNMatch = ngramModNMatch,
            ngramModNMin = ngramModNMin,
            ngramModNMax = ngramModNMax,
            ngramSimpleSizeN = ngramSimpleSizeN,
            ngramSimpleSizeM = ngramSimpleSizeM,
            ngramSimpleMinHits = ngramSimpleMinHits,
            ngramMapKSizeN = ngramMapKSizeN,
            ngramMapKSizeM = ngramMapKSizeM,
            ngramMapKMinHits = ngramMapKMinHits,
            ngramMapK4VSizeN = ngramMapK4VSizeN,
            ngramMapK4VSizeM = ngramMapK4VSizeM,
            ngramMapK4VMinHits = ngramMapK4VMinHits,
            nativeToolsEnabled = nativeToolsEnabled,
            parallel = parallel,
            cacheRam = cacheRam,
            contextCheckpoints = contextCheckpoints,
            checkpointMinStep = checkpointMinStep,
            cachePrompt = cachePrompt,
            cacheIdleSlots = cacheIdleSlots,
            kvUnifiedMode = kvUnifiedMode,
            swaFull = swaFull,
            sleepIdleSeconds = sleepIdleSeconds,
            customFlags = customFlags,
            flashAttention = flashAttention,
            openClCpuTargetGpuDraft = openClCpuTargetGpuDraft
        )
    }

    companion object {
        const val SCHEMA_VERSION: Int = 3
        private val gson = Gson()

        fun capture(settings: SettingsRepository): LlamaServerLaunchProfile = LlamaServerLaunchProfile(
            modelPath = settings.selectedModelPath.value.orEmpty(),
            mmprojPath = settings.selectedMmprojPath.value,
            visionEnabled = settings.enableVision.value,
            loraPath = settings.selectedLlmLoraPath.value,
            host = if (settings.remoteAccess.value) "0.0.0.0" else "127.0.0.1",
            serverPort = settings.serverPort.value,
            threads = settings.threads.value,
            batchSize = settings.serverBatchSize.value,
            physicalBatchSize = settings.serverPhysicalBatchSize.value,
            contextSize = settings.contextSize.value,
            temperature = settings.temperature.value,
            kvCacheEnabled = settings.serverKvCacheEnabled.value,
            kvCacheTypeK = settings.serverKvCacheTypeK.value,
            kvCacheTypeV = settings.serverKvCacheTypeV.value,
            kvCacheReuse = settings.serverKvCacheReuse.value,
            kvOffloadMode = settings.llamaKvOffloadMode.value,
            noMmap = settings.lowMemoryMode.value,
            parallel = settings.serverParallel.value,
            cacheRam = settings.serverCacheRam.value,
            contextCheckpoints = settings.serverContextCheckpoints.value,
            checkpointMinStep = settings.serverCheckpointMinStep.value,
            cachePrompt = settings.serverCachePrompt.value,
            cacheIdleSlots = settings.serverCacheIdleSlots.value,
            kvUnifiedMode = settings.serverKvUnifiedMode.value,
            swaFull = settings.serverSwaFull.value,
            sleepIdleSeconds = settings.serverSleepIdleSeconds.value.takeIf { it >= 0 },
            customFlags = settings.customFlags.value.takeIf { it.isNotBlank() },
            flashAttention = settings.flashAttentionEnabled.value,
            openClCpuTargetGpuDraft = settings.llamaOpenClCpuTargetGpuDraft.value,
            nativeBinarySelection = settings.llmNativeBinarySelection.value,
            nativeToolsEnabled = settings.llamaNativeToolsEnabled.value,
            commandTemplate = settings.customCommandTemplate.value.takeIf { it.isNotBlank() },
            speculativeEnabled = settings.speculativeEnabled.value,
            speculativeMode = settings.speculativeMode.value.flagValue,
            draftModelPath = settings.draftModelPath.value,
            draftMax = settings.draftMaxTokens.value,
            draftMin = settings.draftMinTokens.value,
            draftPMin = settings.draftPMin.value,
            draftThreads = settings.draftThreads.value,
            draftThreadsBatch = settings.draftThreadsBatch.value,
            mtpDraftMax = settings.mtpDraftMaxTokens.value,
            mtpDraftMin = settings.mtpDraftMinTokens.value,
            mtpDraftPMin = settings.mtpDraftPMin.value,
            mtpUseDraftModel = settings.mtpUseDraftModel.value,
            draftDeviceMode = settings.llamaDraftDeviceMode.value,
            ngramModNMatch = settings.ngramModNMatch.value,
            ngramModNMin = settings.ngramModNMin.value,
            ngramModNMax = settings.ngramModNMax.value,
            ngramSimpleSizeN = settings.ngramSimpleSizeN.value,
            ngramSimpleSizeM = settings.ngramSimpleSizeM.value,
            ngramSimpleMinHits = settings.ngramSimpleMinHits.value,
            ngramMapKSizeN = settings.ngramMapKSizeN.value,
            ngramMapKSizeM = settings.ngramMapKSizeM.value,
            ngramMapKMinHits = settings.ngramMapKMinHits.value,
            ngramMapK4VSizeN = settings.ngramMapK4VSizeN.value,
            ngramMapK4VSizeM = settings.ngramMapK4VSizeM.value,
            ngramMapK4VMinHits = settings.ngramMapK4VMinHits.value
        )

        fun encode(profile: LlamaServerLaunchProfile): String = gson.toJson(profile)

        fun decode(value: String?): LlamaServerLaunchProfile? = value
            ?.takeIf { it.isNotBlank() }
            ?.let { encoded -> runCatching { gson.fromJson(encoded, LlamaServerLaunchProfile::class.java) }.getOrNull() }

        /** Restores every preference which changes the generated llama-server command. */
        fun restore(profile: LlamaServerLaunchProfile, settings: SettingsRepository) {
            settings.setSelectedModelPath(profile.modelPath)
            settings.setSelectedMmprojPath(profile.mmprojPath)
            settings.setEnableVision(profile.visionEnabled)
            settings.setSelectedLlmLoraPath(profile.loraPath)
            settings.setRemoteAccess(profile.host == "0.0.0.0")
            settings.setServerPort(profile.serverPort)
            settings.setThreads(profile.threads)
            settings.setServerBatchSize(profile.batchSize)
            settings.setServerPhysicalBatchSize(profile.physicalBatchSize)
            settings.setContextSize(profile.contextSize)
            settings.setTemperature(profile.temperature)
            settings.setServerKvCacheEnabled(profile.kvCacheEnabled)
            settings.setServerKvCacheTypeK(profile.kvCacheTypeK)
            settings.setServerKvCacheTypeV(profile.kvCacheTypeV)
            settings.setServerKvCacheReuse(profile.kvCacheReuse)
            settings.setLlamaKvOffloadMode(profile.kvOffloadMode)
            settings.setLowMemoryMode(profile.noMmap)
            settings.setServerParallel(profile.parallel)
            settings.setServerCacheRam(profile.cacheRam)
            settings.setServerContextCheckpoints(profile.contextCheckpoints)
            settings.setServerCheckpointMinStep(profile.checkpointMinStep)
            settings.setServerCachePrompt(profile.cachePrompt)
            settings.setServerCacheIdleSlots(profile.cacheIdleSlots)
            settings.setServerKvUnifiedMode(profile.kvUnifiedMode)
            settings.setServerSwaFull(profile.swaFull)
            settings.setServerSleepIdleSeconds(profile.sleepIdleSeconds)
            settings.setCustomFlags(profile.customFlags.orEmpty())
            settings.setFlashAttentionEnabled(profile.flashAttention)
            settings.setLlamaOpenClCpuTargetGpuDraft(profile.openClCpuTargetGpuDraft)
            profile.nativeBinarySelection
                ?.takeIf { it.isNotBlank() }
                ?.let(settings::setLlmNativeBinarySelection)
            settings.setLlamaNativeToolsEnabled(profile.nativeToolsEnabled)
            settings.setCustomCommandTemplate(profile.commandTemplate.orEmpty())
            settings.setSpeculativeEnabled(profile.speculativeEnabled)
            settings.setSpeculativeMode(LlamaSpeculativeMode.fromFlagValue(profile.speculativeMode))
            settings.setDraftModelPath(profile.draftModelPath)
            settings.setDraftMaxTokens(profile.draftMax)
            settings.setDraftMinTokens(profile.draftMin)
            settings.setDraftPMin(profile.draftPMin)
            settings.setDraftThreads(profile.draftThreads)
            settings.setDraftThreadsBatch(profile.draftThreadsBatch)
            settings.setMtpDraftMaxTokens(profile.mtpDraftMax)
            settings.setMtpDraftMinTokens(profile.mtpDraftMin)
            settings.setMtpDraftPMin(profile.mtpDraftPMin)
            settings.setMtpUseDraftModel(profile.mtpUseDraftModel)
            settings.setLlamaDraftDeviceMode(profile.draftDeviceMode)
            settings.setNgramModNMatch(profile.ngramModNMatch)
            settings.setNgramModNMin(profile.ngramModNMin)
            settings.setNgramModNMax(profile.ngramModNMax)
            settings.setNgramSimpleSizeN(profile.ngramSimpleSizeN)
            settings.setNgramSimpleSizeM(profile.ngramSimpleSizeM)
            settings.setNgramSimpleMinHits(profile.ngramSimpleMinHits)
            settings.setNgramMapKSizeN(profile.ngramMapKSizeN)
            settings.setNgramMapKSizeM(profile.ngramMapKSizeM)
            settings.setNgramMapKMinHits(profile.ngramMapKMinHits)
            settings.setNgramMapK4VSizeN(profile.ngramMapK4VSizeN)
            settings.setNgramMapK4VSizeM(profile.ngramMapK4VSizeM)
            settings.setNgramMapK4VMinHits(profile.ngramMapK4VMinHits)
        }
    }
}
