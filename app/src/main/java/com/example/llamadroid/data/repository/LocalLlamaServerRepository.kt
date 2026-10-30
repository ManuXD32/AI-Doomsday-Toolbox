package com.example.llamadroid.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.savedCommandFromLaunchProfile
import com.example.llamadroid.data.model.LlamaServerCardEntity
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.service.LlamaServerLaunchProfile
import com.example.llamadroid.service.LlamaServerLauncher
import com.example.llamadroid.service.LlamaService
import com.example.llamadroid.service.ManagedLlamaServerCoordinator
import com.example.llamadroid.service.ManagedLlamaServerException
import com.example.llamadroid.service.ServerState
import com.example.llamadroid.service.managedLlamaConnectHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Ids of the three linked records that make up one local managed server. */
data class LocalServerIds(val cardId: Long, val serverRowId: Long, val presetId: Long)

/** How an edit of a card's profile must treat the preset the card points at. */
enum class PresetEditPlan { UPDATE_IN_PLACE, COPY_ON_WRITE }

/** Presets shared with another card are copied on write so editing one card never changes another. */
internal fun presetEditPlan(otherCardsUsingPreset: Int): PresetEditPlan =
    if (otherCardsUsingPreset > 0) PresetEditPlan.COPY_ON_WRITE else PresetEditPlan.UPDATE_IN_PLACE

/** A preset is removed with its card only when no other card still points at it. */
internal fun shouldDeletePresetWithCard(otherCardsUsingPreset: Int): Boolean = otherCardsUsingPreset == 0

/**
 * Owns the 1:1 card + connection row + own GENERAL preset triple for local llama-servers.
 * Every creation path (Easy chat, Quick Setup, the card UI, upgrading a legacy row) goes through here.
 */
class LocalLlamaServerRepository(
    context: Context,
    private val database: AppDatabase = AppDatabase.getDatabase(context)
) {
    private val context = context.applicationContext
    private val cardDao get() = database.llamaServerCardDao()
    private val serverDao get() = database.llamaServerDao()
    private val commandDao get() = database.savedCommandDao()

    /**
     * Create preset, card and linked row in one transaction. When [port] is null a free port is
     * allocated under [ManagedLlamaServerCoordinator.creationLock]; callers already holding that
     * lock must pass an explicit port (the mutex is not reentrant).
     */
    suspend fun createLocalServer(
        name: String,
        profile: LlamaServerLaunchProfile,
        port: Int? = null,
        allowWearStart: Boolean = false,
        easyModelId: String? = null,
        defaultApiParams: String? = null
    ): LocalServerIds = withContext(Dispatchers.IO) {
        if (port != null) insertTriple(name, profile, port, allowWearStart, easyModelId, defaultApiParams)
        else ManagedLlamaServerCoordinator.creationLock.withLock {
            val allocated = ManagedLlamaServerCoordinator(context, database).allocatePort()
            insertTriple(name, profile, allocated, allowWearStart, easyModelId, defaultApiParams)
        }
    }

    private suspend fun insertTriple(
        name: String,
        profile: LlamaServerLaunchProfile,
        port: Int,
        allowWearStart: Boolean,
        easyModelId: String?,
        defaultApiParams: String?
    ): LocalServerIds = database.withTransaction {
        val displayName = name.trim().ifBlank { profile.modelPath.substringAfterLast('/').removeSuffix(".gguf") }
            .ifBlank { "llama-server" }
        val finalProfile = profile.copy(serverPort = port)
        val presetId = commandDao.insertCommand(savedCommandFromLaunchProfile(displayName, finalProfile))
        val draft = LlamaServerCardEntity(
            name = displayName, savedCommandId = presetId, presetNameSnapshot = displayName,
            port = port, easyModelId = easyModelId
        )
        val cardId = cardDao.insertCard(draft)
        if (allowWearStart) cardDao.setWearStartCard(cardId)
        val card = draft.copy(id = cardId)
        val rowId = serverDao.insertServer(
            linkedRowFor(card, card.launchProfileForPreset(finalProfile), defaultApiParams))
        LocalServerIds(cardId, rowId, presetId)
    }

    /** Return the card's linked row, creating it for older cards that have none. */
    suspend fun ensureLinkedRow(cardId: Long, launched: LlamaServerLaunchProfile? = null): LlamaServerEntity? =
        database.withTransaction {
            serverDao.getManagedServer(cardId)?.let { return@withTransaction it }
            val card = cardDao.getCard(cardId) ?: return@withTransaction null
            val profile = launched
                ?: commandDao.getGeneralCommandById(card.savedCommandId)?.launchProfileForCard(card)
                ?: throw ManagedLlamaServerException(
                    "MANAGED_PRESET_MISSING", context.getString(R.string.managed_llama_preset_missing))
            val row = linkedRowFor(card, profile)
            row.copy(id = serverDao.insertServer(row))
        }

    /**
     * Replace a managed server's launch profile. A preset shared with another card is copied first
     * (copy-on-write); an unshared preset is updated in place. The linked row's metadata follows.
     */
    suspend fun updateLocalServerProfile(
        cardId: Long,
        profile: LlamaServerLaunchProfile,
        name: String? = null,
        port: Int? = null,
        allowWearStart: Boolean? = null,
        defaultApiParams: String? = null
    ): Unit = withContext(Dispatchers.IO) {
        database.withTransaction {
            val card = cardDao.getCard(cardId) ?: throw ManagedLlamaServerException(
                "MANAGED_CARD_MISSING", context.getString(R.string.managed_llama_card_missing))
            val newName = name?.trim()?.takeIf(String::isNotBlank) ?: card.name
            val newPort = port ?: card.port
            val finalProfile = profile.copy(serverPort = newPort)
            val existing = commandDao.getGeneralCommandById(card.savedCommandId)
            val plan = if (existing == null) PresetEditPlan.COPY_ON_WRITE
            else presetEditPlan(cardDao.countOtherCardsUsingPreset(existing.id, card.id))
            val presetId = when (plan) {
                PresetEditPlan.COPY_ON_WRITE ->
                    commandDao.insertCommand(savedCommandFromLaunchProfile(newName, finalProfile))
                PresetEditPlan.UPDATE_IN_PLACE -> {
                    // Keep user-chosen preset names; only follow the card name when they were identical.
                    val presetName = if (existing!!.name == card.name) newName else existing.name
                    commandDao.insertCommand(savedCommandFromLaunchProfile(presetName, finalProfile, id = existing.id))
                }
            }
            val updated = card.copy(
                name = newName, port = newPort, savedCommandId = presetId,
                presetNameSnapshot = commandDao.getGeneralCommandById(presetId)?.name ?: newName,
                updatedAt = System.currentTimeMillis()
            )
            cardDao.updateCard(updated)
            if (allowWearStart != null) {
                if (allowWearStart) cardDao.setWearStartCard(cardId)
                else if (card.allowWearStart) cardDao.setWearStartCard(null)
            }
            val effective = updated.launchProfileForPreset(finalProfile)
            val row = serverDao.getManagedServer(cardId)
            if (row == null) serverDao.insertServer(linkedRowFor(updated, effective, defaultApiParams))
            else serverDao.updateServer(row.copy(
                name = newName, host = managedLlamaConnectHost(effective.host), port = newPort,
                modelName = effective.modelPath.substringAfterLast('/'),
                supportsVision = effective.visionEnabled, supportsVideo = effective.videoEnabled,
                // Saving persists the normalized legacy ceiling; other rows keep their tool defaults.
                defaultApiParams = defaultApiParams ?: persistedManagedToolDefaults(row.defaultApiParams)))
            Unit
        }
    }

    /** Remove the card, its linked row and its own preset; clear chat bindings; stop the session. */
    suspend fun deleteLocalServer(cardId: Long): Unit = withContext(Dispatchers.IO) {
        val card = cardDao.getCard(cardId) ?: return@withContext
        database.withTransaction {
            serverDao.getManagedServer(cardId)?.let { row ->
                database.llamaChatDao().clearChatServerBinding(row.id)
                serverDao.deleteServerById(row.id)
            }
            cardDao.deleteCardById(cardId)
            if (shouldDeletePresetWithCard(cardDao.countOtherCardsUsingPreset(card.savedCommandId, cardId))) {
                commandDao.getGeneralCommandById(card.savedCommandId)?.let { commandDao.deleteCommand(it) }
            }
        }
        // Card first, then the session and its logs (same order as the card UI).
        LlamaServerLauncher.removeSession(context, card.sessionId)
        Unit
    }

    /**
     * Upgrade a loopback llama-server connection row to a managed server. The row is kept (chats
     * stay bound to it) and linked to a new card and preset. Returns null when the row is not
     * convertible.
     */
    suspend fun convertLegacyRowToManaged(rowId: Long): LocalServerIds? = withContext(Dispatchers.IO) {
        val row = serverDao.getServerById(rowId) ?: return@withContext null
        if (row.managedServerCardId != null || !row.isLlamaServerEngine()) return@withContext null
        val profile = (LlamaServerLaunchProfile.decode(row.localLaunchProfileJson)
            ?: LlamaServerLaunchProfile.capture(SettingsRepository(context)))
            .copy(serverPort = row.port)
        val legacy = LlamaService.state.value
        if (legacy !is ServerState.Stopped && legacyPort(legacy) in listOf(null, row.port)) {
            LlamaServerLauncher.stopLegacy(context)
        }
        database.withTransaction {
            val presetId = commandDao.insertCommand(savedCommandFromLaunchProfile(row.name, profile))
            val cardId = cardDao.insertCard(LlamaServerCardEntity(
                name = row.name, savedCommandId = presetId, presetNameSnapshot = row.name, port = row.port))
            serverDao.linkManagedCard(row.id, cardId)
            LocalServerIds(cardId, row.id, presetId)
        }
    }

    private fun legacyPort(state: ServerState): Int? = (state as? ServerState.Running)?.port

    private fun linkedRowFor(
        card: LlamaServerCardEntity,
        profile: LlamaServerLaunchProfile,
        defaultApiParams: String? = null
    ) = LlamaServerEntity(
        name = card.name,
        host = managedLlamaConnectHost(profile.host),
        port = profile.serverPort,
        modelName = profile.modelPath.substringAfterLast('/'),
        supportsVision = profile.visionEnabled,
        supportsVideo = profile.videoEnabled,
        managedServerCardId = card.id,
        defaultApiParams = defaultApiParams ?: managedDefaultToolsJson()
    )

    /** Apply the card's easy/manual lifecycle rules to a freshly written profile. */
    private fun LlamaServerCardEntity.launchProfileForPreset(profile: LlamaServerLaunchProfile): LlamaServerLaunchProfile =
        savedCommandFromLaunchProfile(name, profile).launchProfileForCard(this)
}
