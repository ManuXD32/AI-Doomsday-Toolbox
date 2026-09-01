package com.example.llamadroid.service

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import java.util.UUID

/**
 * Phases persisted by the OCR coordinator.  The lease is deliberately small and contains only
 * runtime metadata and launch profiles.  It never contains a prompt, OCR output, image bytes or
 * any other document content.
 */
internal enum class LlamaOcrLeasePhase {
    CAPTURED,
    PAUSING,
    OCR_STARTING,
    OCR_RUNNING,
    STOPPING_OCR,
    RESTORING,
    RESTORE_FAILED
}

internal enum class LlamaOcrCapturedRuntimeKind {
    CARD,
    LEGACY,
    RESERVED
}

internal data class LlamaOcrCapturedRuntime(
    val sessionId: String,
    val kind: LlamaOcrCapturedRuntimeKind,
    val port: Int?,
    /** Encoded [LlamaServerLaunchProfile], retained so restoration is independent of mutable UI. */
    val launchProfileJson: String,
    val status: LlamaServerSessionStatus? = null,
    val ownerPid: Int? = null,
    val ownerStartTimeTicks: Long? = null
) {
    fun launchProfile(): LlamaServerLaunchProfile? =
        LlamaServerLaunchProfile.decode(launchProfileJson)
}

internal data class LlamaOcrExclusiveLease(
    val schemaVersion: Int = SCHEMA_VERSION,
    val token: String,
    val phase: LlamaOcrLeasePhase,
    val createdAt: Long,
    val updatedAt: Long,
    val ocrSessionId: String = com.example.llamadroid.data.model.LlamaServerSessionIds.OCR,
    val ocrPort: Int,
    val ocrProfileJson: String,
    val capturedRuntimes: List<LlamaOcrCapturedRuntime> = emptyList(),
    /** Sessions whose restore completed before a possible main-process restart. */
    val restoredSessionIds: Set<String> = emptySet()
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/**
 * Cross-process lease store used by the main process, the keyed session service and launchers.
 * Writes use a same-directory temporary file followed by rename so a service process never sees
 * a partially encoded lease after the app process is killed.
 */
internal object LlamaOcrExclusiveLeaseStore {
    const val TOKEN_EXTRA = "com.example.llamadroid.extra.LLAMA_OCR_LEASE_TOKEN"
    private const val FILE_NAME = "llama_ocr_exclusive_lease.json"
    private val gson = Gson()
    private val lock = Any()

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)

    fun read(context: Context): LlamaOcrExclusiveLease? = synchronized(lock) {
        runCatching {
            val target = file(context)
            if (!target.isFile) return@runCatching null
            gson.fromJson(target.readText(Charsets.UTF_8), LlamaOcrExclusiveLease::class.java)
                ?.takeIf { it.token.isNotBlank() && it.ocrSessionId == com.example.llamadroid.data.model.LlamaServerSessionIds.OCR }
        }.getOrNull()
    }

    fun create(
        context: Context,
        ocrProfile: LlamaServerLaunchProfile,
        capturedRuntimes: List<LlamaOcrCapturedRuntime>,
        now: Long = System.currentTimeMillis(),
        token: String = UUID.randomUUID().toString()
    ): LlamaOcrExclusiveLease = synchronized(lock) {
        check(read(context) == null) { "Another GGUF OCR runtime lease is active." }
        val lease = LlamaOcrExclusiveLease(
            token = token,
            phase = LlamaOcrLeasePhase.CAPTURED,
            createdAt = now,
            updatedAt = now,
            ocrPort = ocrProfile.serverPort,
            ocrProfileJson = LlamaServerLaunchProfile.encode(ocrProfile),
            capturedRuntimes = capturedRuntimes
        )
        writeLocked(context, lease)
        lease
    }

    fun updatePhase(
        context: Context,
        lease: LlamaOcrExclusiveLease,
        phase: LlamaOcrLeasePhase,
        now: Long = System.currentTimeMillis()
    ): LlamaOcrExclusiveLease = synchronized(lock) {
        val current = read(context)
        check(current == null || current.token == lease.token) { "Another GGUF OCR runtime lease is active." }
        lease.copy(phase = phase, updatedAt = now).also { writeLocked(context, it) }
    }

    fun write(context: Context, lease: LlamaOcrExclusiveLease) = synchronized(lock) {
        val current = read(context)
        check(current == null || current.token == lease.token) { "Another GGUF OCR runtime lease is active." }
        writeLocked(context, lease)
    }

    fun markRestored(
        context: Context,
        lease: LlamaOcrExclusiveLease,
        sessionId: String,
        now: Long = System.currentTimeMillis()
    ): LlamaOcrExclusiveLease = synchronized(lock) {
        val current = read(context)
        check(current == null || current.token == lease.token) { "Another GGUF OCR runtime lease is active." }
        lease.copy(
            updatedAt = now,
            restoredSessionIds = lease.restoredSessionIds + sessionId
        ).also { writeLocked(context, it) }
    }

    fun clear(context: Context, token: String? = null) = synchronized(lock) {
        val target = file(context)
        val current = read(context)
        if (token == null || current == null || current.token == token) {
            target.delete()
        }
    }

    fun currentToken(context: Context): String? = read(context)?.token

    /**
     * Returns true when a local session command must be rejected while OCR owns the runtime.
     * Restoration and OCR commands carry the persisted token and are therefore allowed.
     */
    fun rejectsSessionCommand(
        context: Context,
        sessionId: String,
        suppliedToken: String?
    ): Boolean {
        val lease = read(context) ?: return false
        return sessionId.isBlank() || suppliedToken != lease.token
    }

    fun rejectsLegacyCommand(context: Context, suppliedToken: String?): Boolean {
        val lease = read(context) ?: return false
        return suppliedToken != lease.token
    }

    private fun writeLocked(context: Context, lease: LlamaOcrExclusiveLease) {
        val target = file(context)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(gson.toJson(lease), Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.writeText(gson.toJson(lease), Charsets.UTF_8)
            temp.delete()
        }
    }
}
