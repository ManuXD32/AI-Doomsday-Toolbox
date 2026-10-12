package com.example.llamadroid.ui.ai.llama.servers

import com.example.llamadroid.data.repository.UnifiedServer
import com.example.llamadroid.data.repository.UnifiedServerKind
import com.example.llamadroid.service.LlamaKvOffloadMode
import com.example.llamadroid.service.LlamaLoadMode
import com.example.llamadroid.service.LlamaLoraSpec
import com.example.llamadroid.service.LlamaServerLaunchProfile
import com.example.llamadroid.service.LlamaSpeculativeMode

/**
 * Pure state and rules behind the local-model half of the server editor. Numbers stay as text so
 * the fields can be edited freely; [validateLocalForm] and [applyLocalForm] interpret them.
 */
data class LocalServerForm(
    val name: String = "",
    val modelPath: String = "",
    val mmprojPath: String? = null,
    val visionEnabled: Boolean = false,
    /** Blank means "pick a free port" for a new server. */
    val portText: String = "",
    val contextText: String = "8192",
    val threadsText: String = "4",
    /** One of [KV_QUANT_OPTIONS] or [KV_QUANT_CUSTOM] when K and V differ (kept untouched). */
    val kvQuant: String = KV_F16,
    val draftModelPath: String? = null,
    val allowWearStart: Boolean = false,
    val batchText: String = "512",
    val physicalBatchText: String = "",
    val threadsBatchText: String = "",
    val parallelText: String = "",
    val cacheRamText: String = "",
    val sleepIdleText: String = "",
    val loadMode: String = LlamaLoadMode.MMAP.value,
    val kvOffloadMode: String = LlamaKvOffloadMode.AUTO.value,
    val flashAttention: Boolean = false,
    val videoEnabled: Boolean = false,
    val loras: List<LlamaLoraSpec> = emptyList(),
    val customFlags: String = "",
    val commandTemplate: String = ""
) {
    companion object {
        const val KV_F16 = "f16"
        const val KV_Q8 = "q8_0"
        const val KV_Q4 = "q4_0"
        const val KV_QUANT_CUSTOM = "custom"
        val KV_QUANT_OPTIONS = listOf(KV_F16, KV_Q8, KV_Q4)

        const val CONTEXT_MIN = 128
        const val CONTEXT_MAX = 131_072
        const val THREADS_MAX = 64
        const val BATCH_MAX = 65_536

        /** Derive the form from a saved launch profile (edit mode, preset copy, legacy upgrade). */
        fun fromProfile(
            profile: LlamaServerLaunchProfile,
            name: String,
            port: Int?,
            allowWearStart: Boolean
        ): LocalServerForm = LocalServerForm(
            name = name,
            modelPath = profile.modelPath,
            mmprojPath = profile.mmprojPath,
            visionEnabled = profile.visionEnabled,
            portText = port?.toString().orEmpty(),
            contextText = profile.contextSize.toString(),
            threadsText = profile.threads.toString(),
            kvQuant = kvQuantOf(profile),
            draftModelPath = profile.draftModelPath,
            allowWearStart = allowWearStart,
            batchText = profile.batchSize.toString(),
            physicalBatchText = profile.physicalBatchSize?.toString().orEmpty(),
            threadsBatchText = profile.threadsBatch?.toString().orEmpty(),
            parallelText = profile.parallel?.toString().orEmpty(),
            cacheRamText = profile.cacheRam?.toString().orEmpty(),
            sleepIdleText = profile.sleepIdleSeconds?.toString().orEmpty(),
            loadMode = profile.resolvedLoadMode().value,
            kvOffloadMode = profile.kvOffloadMode,
            flashAttention = profile.flashAttention,
            videoEnabled = profile.videoEnabled,
            loras = profile.resolvedLoras(),
            customFlags = profile.customFlags.orEmpty(),
            commandTemplate = profile.commandTemplate.orEmpty()
        )

        fun kvQuantOf(profile: LlamaServerLaunchProfile): String = when {
            !profile.kvCacheEnabled -> KV_F16
            profile.kvCacheTypeK == profile.kvCacheTypeV && profile.kvCacheTypeK in KV_QUANT_OPTIONS ->
                profile.kvCacheTypeK
            else -> KV_QUANT_CUSTOM
        }
    }
}

/** Reasons a local-server form cannot be saved yet; each maps to one inline message. */
enum class LocalFormError {
    MODEL_NOT_SELECTED,
    MODEL_FILE_MISSING,
    MMPROJ_FILE_MISSING,
    DRAFT_FILE_MISSING,
    PORT_INVALID,
    PORT_IN_USE,
    CONTEXT_INVALID,
    THREADS_INVALID,
    BATCH_INVALID,
    FLAGS_INVALID
}

private fun optionalPositive(text: String, max: Int): Boolean =
    text.isBlank() || text.trim().toIntOrNull()?.let { it in 1..max } == true

/**
 * @param usedPorts ports of the other local servers (the edited server's own port excluded)
 * @param fileExists readable-file check, injected so tests need no filesystem
 * @param argumentsValid shell-style splitter check for custom flags and the command template
 */
fun validateLocalForm(
    form: LocalServerForm,
    usedPorts: Set<Int>,
    fileExists: (String) -> Boolean,
    argumentsValid: (flags: String, template: String) -> Boolean
): Set<LocalFormError> {
    val errors = linkedSetOf<LocalFormError>()
    if (form.modelPath.isBlank()) errors += LocalFormError.MODEL_NOT_SELECTED
    else if (!fileExists(form.modelPath)) errors += LocalFormError.MODEL_FILE_MISSING
    if (form.visionEnabled && form.mmprojPath?.let(fileExists) != true) errors += LocalFormError.MMPROJ_FILE_MISSING
    if (!form.draftModelPath.isNullOrBlank() && !fileExists(form.draftModelPath)) {
        errors += LocalFormError.DRAFT_FILE_MISSING
    }
    if (form.portText.isNotBlank()) {
        val port = form.portText.trim().toIntOrNull()
        if (port == null || port !in 1..65535) errors += LocalFormError.PORT_INVALID
        else if (port in usedPorts) errors += LocalFormError.PORT_IN_USE
    }
    if (form.contextText.trim().toIntOrNull()?.let {
            it in LocalServerForm.CONTEXT_MIN..LocalServerForm.CONTEXT_MAX
        } != true) errors += LocalFormError.CONTEXT_INVALID
    if (form.threadsText.trim().toIntOrNull()?.let { it in 1..LocalServerForm.THREADS_MAX } != true) {
        errors += LocalFormError.THREADS_INVALID
    }
    val batchOk = form.batchText.trim().toIntOrNull()?.let { it in 1..LocalServerForm.BATCH_MAX } == true &&
        optionalPositive(form.physicalBatchText, LocalServerForm.BATCH_MAX) &&
        optionalPositive(form.threadsBatchText, LocalServerForm.THREADS_MAX) &&
        optionalPositive(form.parallelText, 64) &&
        optionalPositive(form.cacheRamText, Int.MAX_VALUE) &&
        (form.sleepIdleText.isBlank() || form.sleepIdleText.trim().toIntOrNull()?.let { it >= 0 } == true)
    if (!batchOk) errors += LocalFormError.BATCH_INVALID
    if (!argumentsValid(form.customFlags, form.commandTemplate)) errors += LocalFormError.FLAGS_INVALID
    return errors
}

/** Name suggested from a model file, e.g. "Qwen3-4B-Q4_K_M.gguf" becomes "Qwen3-4B-Q4_K_M". */
fun defaultServerName(modelPath: String): String =
    modelPath.substringAfterLast('/').removeSuffix(".gguf").removeSuffix(".GGUF").trim()

/** The port to persist, or null when the form asks for automatic allocation. */
fun LocalServerForm.requestedPort(): Int? = portText.trim().toIntOrNull()

/**
 * Overlay the form on [base]. Fields the form does not expose (n-gram tuning, MTP, video policy
 * mirrors, binary selection...) are preserved from [base]. Call only after [validateLocalForm]
 * returned no errors.
 */
fun applyLocalForm(base: LlamaServerLaunchProfile, form: LocalServerForm): LlamaServerLaunchProfile {
    val kv = form.kvQuant
    val quantized = kv != LocalServerForm.KV_F16
    val keepKv = kv == LocalServerForm.KV_QUANT_CUSTOM
    val loraList = form.loras.filter { it.path.isNotBlank() }
    val loadMode = LlamaLoadMode.fromValue(form.loadMode)
    val draft = form.draftModelPath?.takeIf { it.isNotBlank() }
    val mmproj = form.mmprojPath?.takeIf { it.isNotBlank() }
    return base.copy(
        modelPath = form.modelPath,
        mmprojPath = mmproj,
        visionEnabled = form.visionEnabled && mmproj != null,
        videoEnabled = form.videoEnabled && mmproj != null,
        serverPort = form.requestedPort() ?: base.serverPort,
        contextSize = form.contextText.trim().toInt(),
        threads = form.threadsText.trim().toInt(),
        batchSize = form.batchText.trim().toInt(),
        physicalBatchSize = form.physicalBatchText.trim().toIntOrNull(),
        threadsBatch = form.threadsBatchText.trim().toIntOrNull(),
        parallel = form.parallelText.trim().toIntOrNull(),
        cacheRam = form.cacheRamText.trim().toIntOrNull(),
        sleepIdleSeconds = form.sleepIdleText.trim().toIntOrNull(),
        kvCacheEnabled = if (keepKv) base.kvCacheEnabled else quantized,
        kvCacheTypeK = if (keepKv) base.kvCacheTypeK else kv,
        kvCacheTypeV = if (keepKv) base.kvCacheTypeV else kv,
        kvOffloadMode = form.kvOffloadMode,
        loadMode = loadMode.value,
        noMmap = loadMode == LlamaLoadMode.NONE,
        flashAttention = form.flashAttention,
        loras = loraList,
        loraPath = loraList.firstOrNull()?.path,
        draftModelPath = draft,
        speculativeEnabled = draft != null,
        speculativeMode = if (draft != null) {
            base.speculativeMode ?: LlamaSpeculativeMode.DRAFT_SIMPLE.flagValue
        } else {
            base.speculativeMode
        },
        customFlags = form.customFlags.trim().takeIf { it.isNotEmpty() },
        commandTemplate = form.commandTemplate.trim().takeIf { it.isNotEmpty() }
    )
}

/** Ports already taken by other local servers, so the editor can flag collisions inline. */
fun usedLocalPorts(servers: List<UnifiedServer>, editing: UnifiedServer?): Set<Int> =
    servers.asSequence()
        .filter { it.kind != UnifiedServerKind.REMOTE && it.port > 0 }
        .filter { s -> editing == null || (s.cardId != editing.cardId || s.rowId != editing.rowId) }
        .map { it.port }
        .toSet()

/** Reasons a remote-server form cannot be saved yet. */
enum class RemoteFormError { NAME_BLANK, HOST_BLANK, PORT_INVALID, MODEL_BLANK, LITERT_MODEL_MISSING }

/** Remote validation mirrors the old dialog's canSave rules plus a port range check. */
fun validateRemoteForm(
    engine: String,
    name: String,
    host: String,
    portText: String,
    modelName: String,
    liteRtModelSelected: Boolean
): Set<RemoteFormError> {
    val errors = linkedSetOf<RemoteFormError>()
    if (name.isBlank()) errors += RemoteFormError.NAME_BLANK
    if (engine == com.example.llamadroid.data.model.LlamaServerEntity.ENGINE_LITERT_LM) {
        if (!liteRtModelSelected) errors += RemoteFormError.LITERT_MODEL_MISSING
        return errors
    }
    if (host.isBlank()) errors += RemoteFormError.HOST_BLANK
    if (portText.trim().toIntOrNull()?.let { it in 1..65535 } != true) errors += RemoteFormError.PORT_INVALID
    val needsModel = engine != com.example.llamadroid.data.model.LlamaServerEntity.ENGINE_LLAMA_SERVER
    if (needsModel && modelName.isBlank()) errors += RemoteFormError.MODEL_BLANK
    return errors
}
