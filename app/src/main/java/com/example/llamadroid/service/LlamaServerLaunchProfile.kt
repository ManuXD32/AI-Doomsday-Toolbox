package com.example.llamadroid.service

import androidx.annotation.Keep
import com.example.llamadroid.data.SettingsRepository
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * A durable, app-managed llama.cpp launch snapshot owned by one native-chat
 * server entry.  It intentionally does not alter global LLM settings when a
 * chat starts that server.
 */
@Keep
data class LlamaServerLaunchProfile(
    val schemaVersion: Int = SCHEMA_VERSION,
    val modelPath: String = "",
    val mmprojPath: String? = null,
    val visionEnabled: Boolean = false,
    val loraPath: String? = null,
    /** Ordered, uncapped LoRA stack; duplicates are retained intentionally. */
    val loras: List<LlamaLoraSpec> = emptyList(),
    /** Network binding is part of the launch command, not a UI-only preference. */
    val host: String = "127.0.0.1",
    val serverPort: Int = 8080,
    val threads: Int = 4,
    val batchSize: Int = 512,
    val physicalBatchSize: Int? = null,
    /** Optional CPU threads used for batch and prompt processing. */
    val threadsBatch: Int? = null,
    val contextSize: Int = 8192,
    val temperature: Float = 0.7f,
    val kvCacheEnabled: Boolean = false,
    val kvCacheTypeK: String = "f16",
    val kvCacheTypeV: String = "f16",
    val kvCacheReuse: Int = 0,
    val kvOffloadMode: String = LlamaKvOffloadMode.AUTO.value,
    /** Canonical llama.cpp model loading mode. */
    val loadMode: String = LlamaLoadMode.MMAP.value,
    /** Deprecated compatibility mirror for profiles written before --load-mode. */
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
            threadsBatch = threadsBatch,
            port = serverPort,
            temperature = temperature,
            host = host,
            mmprojPath = mmprojPath.takeIf { visionEnabled },
            loadMode = resolvedLoadMode().value,
            loraPath = loraPath,
            loras = resolvedLoras(),
            kvCacheEnabled = kvCacheEnabled,
            kvCacheTypeK = kvCacheTypeK,
            kvCacheTypeV = kvCacheTypeV,
            kvCacheReuse = kvCacheReuse,
            kvOffloadMode = kvOffloadMode,
            noMmap = resolvedLoadMode() == LlamaLoadMode.NONE,
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

    /** Resolve the legacy boolean for profiles constructed by older callers. */
    fun resolvedLoadMode(): LlamaLoadMode =
        if (noMmap) LlamaLoadMode.NONE else LlamaLoadMode.fromValue(loadMode)

    /** The new stack wins when present; otherwise retain the historical single path. */
    fun resolvedLoras(): List<LlamaLoraSpec> =
        loras.takeIf { it.isNotEmpty() }
            ?: loraPath?.trim()?.takeIf { it.isNotBlank() }?.let { listOf(LlamaLoraSpec(it)) }
            ?: emptyList()

    companion object {
        const val SCHEMA_VERSION: Int = 4
        private val gson = Gson()

        fun capture(settings: SettingsRepository): LlamaServerLaunchProfile = LlamaServerLaunchProfile(
            modelPath = settings.selectedModelPath.value.orEmpty(),
            mmprojPath = settings.selectedMmprojPath.value,
            visionEnabled = settings.enableVision.value,
            loraPath = settings.selectedLlmLoras.value.firstOrNull()?.path,
            loras = settings.selectedLlmLoras.value,
            host = if (settings.remoteAccess.value) "0.0.0.0" else "127.0.0.1",
            serverPort = settings.serverPort.value,
            threads = settings.threads.value,
            batchSize = settings.serverBatchSize.value,
            physicalBatchSize = settings.serverPhysicalBatchSize.value,
            threadsBatch = settings.serverThreadsBatch.value,
            contextSize = settings.contextSize.value,
            temperature = settings.temperature.value,
            kvCacheEnabled = settings.serverKvCacheEnabled.value,
            kvCacheTypeK = settings.serverKvCacheTypeK.value,
            kvCacheTypeV = settings.serverKvCacheTypeV.value,
            kvCacheReuse = settings.serverKvCacheReuse.value,
            kvOffloadMode = settings.llamaKvOffloadMode.value,
            loadMode = settings.llamaLoadMode.value.value,
            noMmap = settings.llamaLoadMode.value == LlamaLoadMode.NONE,
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

        fun encode(profile: LlamaServerLaunchProfile): String {
            val migrated = migrateLegacyLlamaManagedSettings(
                args = ProcessController().splitCommandLine(profile.customFlags.orEmpty()),
                configuredLoadMode = profile.resolvedLoadMode(),
                selectedLoras = profile.resolvedLoras()
            )
            val canonical = profile.copy(
                schemaVersion = SCHEMA_VERSION,
                loadMode = migrated.loadMode.value,
                noMmap = migrated.loadMode == LlamaLoadMode.NONE,
                loraPath = migrated.loras.firstOrNull()?.path,
                loras = migrated.loras,
                customFlags = ProcessController().buildCommandString(migrated.filteredArgs)
                    .takeIf { it.isNotBlank() }
            )
            return gson.toJson(canonical)
        }

        /**
         * Decode through a JSON-tree migration. Gson does not reliably apply Kotlin
         * constructor defaults when it bypasses a data class constructor, so the
         * default tree is merged before deserialisation. A missing `loras` member
         * means "migrate legacy loraPath" while an explicit `[]` means "no LoRA".
         */
        fun decode(value: String?): LlamaServerLaunchProfile? = value
            ?.takeIf { it.isNotBlank() }
            ?.let { encoded ->
                runCatching {
                    val root = JsonParser.parseString(encoded)
                        .takeIf { it.isJsonObject }
                        ?.asJsonObject
                        ?: return@runCatching null
                    gson.fromJson(migrateJson(root), LlamaServerLaunchProfile::class.java)
                }.getOrNull()
            }

        /** Exposed to focused unit tests without requiring an Android context. */
        internal fun migrateJson(root: JsonObject): JsonObject {
            val defaults = gson.toJsonTree(LlamaServerLaunchProfile()).asJsonObject
            root.entrySet().forEach { (key, element) -> defaults.add(key, element.deepCopy()) }

            defaults.addProperty("schemaVersion", SCHEMA_VERSION)

            val rawLoadMode = root.get("loadMode")
                ?.takeUnless { it.isJsonNull }
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
            val loadMode = if (rawLoadMode == null) {
                val legacyNoMmap = root.get("noMmap")
                    ?.takeUnless { it.isJsonNull }
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                    ?.asBoolean
                    ?: false
                if (legacyNoMmap) LlamaLoadMode.NONE.value else LlamaLoadMode.MMAP.value
            } else {
                LlamaLoadMode.fromValue(rawLoadMode).value
            }
            defaults.addProperty("loadMode", loadMode)
            // Keep the old field as a mirror for readers that still inspect it.
            defaults.addProperty("noMmap", loadMode == LlamaLoadMode.NONE.value)

            if (root.has("loras")) {
                val rawLoras = root.get("loras")
                when {
                    rawLoras == null || rawLoras.isJsonNull -> {
                        defaults.add("loras", JsonArray())
                        defaults.add("loraPath", JsonNull.INSTANCE)
                    }
                    !rawLoras.isJsonArray -> {
                        defaults.add("loras", JsonArray())
                        defaults.add("loraPath", JsonNull.INSTANCE)
                    }
                    else -> {
                        val normalizedLoras = JsonArray()
                        rawLoras.asJsonArray.forEach { rawLora ->
                            if (rawLora.isJsonObject) {
                                val candidate = rawLora.asJsonObject
                                val path = candidate.get("path")
                                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                                    ?.asString
                                    ?.trim()
                                    .orEmpty()
                                val strength = runCatching {
                                    candidate.get("strength")
                                        ?.takeUnless { it.isJsonNull }
                                        ?.asFloat
                                        ?: 1f
                                }.getOrNull()
                                if (path.isNotBlank() && strength?.isFinite() == true) {
                                    normalizedLoras.add(JsonObject().apply {
                                        addProperty("path", path)
                                        addProperty("strength", strength)
                                    })
                                }
                            }
                        }
                        defaults.add("loras", normalizedLoras)
                        // An explicit empty stack deliberately suppresses the
                        // historical single-path field.
                        if (normalizedLoras.size() == 0) defaults.add("loraPath", JsonNull.INSTANCE)
                    }
                }
            } else {
                val legacyPath = root.get("loraPath")
                    ?.takeUnless { it.isJsonNull }
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                val migratedLoras = JsonArray()
                legacyPath?.let {
                    migratedLoras.add(JsonObject().apply {
                        addProperty("path", it)
                        addProperty("strength", 1.0f)
                    })
                }
                defaults.add("loras", migratedLoras)
            }

            val baseLoras = defaults.getAsJsonArray("loras").mapNotNull { element ->
                element.takeIf { it.isJsonObject }?.asJsonObject?.let { item ->
                    val path = item.get("path")?.asString?.trim().orEmpty()
                    val strength = runCatching { item.get("strength")?.asFloat ?: 1f }.getOrNull()
                    if (path.isNotBlank() && strength?.isFinite() == true) {
                        LlamaLoraSpec(path, strength)
                    } else null
                }
            }
            val customFlags = defaults.get("customFlags")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                .orEmpty()
            val migrated = migrateLegacyLlamaManagedSettings(
                args = ProcessController().splitCommandLine(customFlags),
                configuredLoadMode = LlamaLoadMode.fromValue(defaults.get("loadMode")?.asString),
                selectedLoras = baseLoras
            )
            defaults.addProperty("loadMode", migrated.loadMode.value)
            defaults.addProperty("noMmap", migrated.loadMode == LlamaLoadMode.NONE)
            defaults.add("loras", gson.toJsonTree(migrated.loras))
            migrated.loras.firstOrNull()?.path?.let {
                defaults.addProperty("loraPath", it)
            } ?: defaults.add("loraPath", JsonNull.INSTANCE)
            val filteredCustomFlags = ProcessController().buildCommandString(migrated.filteredArgs)
            if (filteredCustomFlags.isBlank()) {
                defaults.add("customFlags", JsonNull.INSTANCE)
            } else {
                defaults.addProperty("customFlags", filteredCustomFlags)
            }
            return defaults
        }

        /** Restores every preference which changes the generated llama-server command. */
        fun restore(profile: LlamaServerLaunchProfile, settings: SettingsRepository) {
            settings.setSelectedModelPath(profile.modelPath)
            settings.setSelectedMmprojPath(profile.mmprojPath)
            settings.setEnableVision(profile.visionEnabled)
            settings.setSelectedLlmLoras(profile.resolvedLoras())
            settings.setRemoteAccess(profile.host == "0.0.0.0")
            settings.setServerPort(profile.serverPort)
            settings.setThreads(profile.threads)
            settings.setServerBatchSize(profile.batchSize)
            settings.setServerPhysicalBatchSize(profile.physicalBatchSize)
            settings.setServerThreadsBatch(profile.threadsBatch)
            settings.setContextSize(profile.contextSize)
            settings.setTemperature(profile.temperature)
            settings.setServerKvCacheEnabled(profile.kvCacheEnabled)
            settings.setServerKvCacheTypeK(profile.kvCacheTypeK)
            settings.setServerKvCacheTypeV(profile.kvCacheTypeV)
            settings.setServerKvCacheReuse(profile.kvCacheReuse)
            settings.setLlamaKvOffloadMode(profile.kvOffloadMode)
            settings.setLlamaLoadMode(profile.resolvedLoadMode())
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
