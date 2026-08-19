package com.example.llamadroid.data.repository

import android.content.Context
import com.example.llamadroid.data.dao.LlamaServerCardDao
import com.example.llamadroid.data.db.SavedCommand
import com.example.llamadroid.data.db.SavedCommandScopes
import com.example.llamadroid.data.db.launchProfile
import com.example.llamadroid.data.model.LlamaServerCardEntity
import com.example.llamadroid.service.LlamaServerLaunchProfile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/** A saved command lookup deliberately abstracted for the central Room integration. */
fun interface GeneralSavedCommandProvider {
    suspend fun getGeneralCommand(id: Long): SavedCommand?
}

class RoomGeneralSavedCommandProvider(
    private val dao: com.example.llamadroid.data.db.SavedCommandDao
) : GeneralSavedCommandProvider {
    override suspend fun getGeneralCommand(id: Long): SavedCommand? = dao.getGeneralCommandById(id)
}

/** Card plus its live-linked GENERAL preset state. */
data class LlamaServerCardSnapshot(
    val card: LlamaServerCardEntity,
    val preset: SavedCommand?,
    val missingPreset: Boolean = preset == null
) {
    val sessionId: String get() = card.sessionId
    val port: Int get() = card.port

    /** Resolve the profile at start time; never cache a stale copy in the card. */
    fun resolveProfile(): LlamaServerLaunchProfile? = preset
        ?.takeIf { it.scope == SavedCommandScopes.GENERAL }
        ?.launchProfileForCardPort(card.port)
}

/**
 * Repository for server cards.  The Room-backed constructor is the production integration point;
 * the Preferences-backed constructor keeps the dashboard usable while an app database upgrade is
 * being coordinated and is also useful for previews/tests.
 */
class LlamaServerCardRepository(
    private val cardDao: LlamaServerCardDao,
    private val generalSavedCommandProvider: GeneralSavedCommandProvider
) {
    val cards: Flow<List<LlamaServerCardEntity>> = cardDao.observeCards()

    fun observeCardsWithPresets(): Flow<List<LlamaServerCardSnapshot>> = flow {
        cards.collect { entities ->
            emit(entities.map { card ->
                LlamaServerCardSnapshot(card, generalSavedCommandProvider.getGeneralCommand(card.savedCommandId))
            })
        }
    }

    suspend fun getCard(id: Long): LlamaServerCardEntity? = cardDao.getCard(id)

    suspend fun save(card: LlamaServerCardEntity): Long {
        require(card.name.trim().isNotBlank()) { "Server card name is required." }
        require(card.savedCommandId > 0L) { "A GENERAL saved command is required." }
        require(card.port in LlamaServerCardEntity.MIN_PORT..LlamaServerCardEntity.MAX_PORT) {
            "Server card port is outside the valid range."
        }
        return if (card.id == 0L) cardDao.insertCard(card.copy(name = card.name.trim()))
        else {
            cardDao.updateCard(card.copy(name = card.name.trim(), updatedAt = System.currentTimeMillis()))
            card.id
        }
    }

    suspend fun delete(card: LlamaServerCardEntity) = cardDao.deleteCard(card)

    suspend fun updatePort(id: Long, port: Int) {
        require(port in LlamaServerCardEntity.MIN_PORT..LlamaServerCardEntity.MAX_PORT)
        cardDao.updatePort(id, port)
    }

    suspend fun updatePreset(id: Long, savedCommandId: Long, presetNameSnapshot: String = "") {
        require(savedCommandId > 0L) { "A GENERAL saved command is required." }
        cardDao.updateSavedCommand(id, savedCommandId, presetNameSnapshot.trim())
    }
}

/** Map a saved GENERAL command to a card launch, overriding only the card port. */
fun SavedCommand.launchProfileForCardPort(port: Int): LlamaServerLaunchProfile =
    launchProfile().copy(serverPort = port)

/**
 * Small durable fallback used by the dashboard until the central Room database adds
 * [LlamaServerCardEntity]. It stores only card metadata; launch profiles remain in SavedCommand.
 */
class PreferencesLlamaServerCardRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val type = object : TypeToken<List<LlamaServerCardEntity>>() {}.type
    private val _cards = MutableStateFlow(loadCards())
    val cards: kotlinx.coroutines.flow.StateFlow<List<LlamaServerCardEntity>> = _cards.asStateFlow()

    suspend fun save(card: LlamaServerCardEntity): LlamaServerCardEntity {
        require(card.name.trim().isNotBlank()) { "Server card name is required." }
        require(card.savedCommandId > 0L) { "A GENERAL saved command is required." }
        require(card.port in LlamaServerCardEntity.MIN_PORT..LlamaServerCardEntity.MAX_PORT)
        val normalized = card.copy(
            id = if (card.id == 0L) nextId() else card.id,
            name = card.name.trim(),
            updatedAt = System.currentTimeMillis()
        )
        write((_cards.value.filterNot { it.id == normalized.id } + normalized).sortedByDescending { it.updatedAt })
        return normalized
    }

    suspend fun delete(cardId: Long) {
        write(_cards.value.filterNot { it.id == cardId })
    }

    suspend fun updatePort(cardId: Long, port: Int) {
        val card = _cards.value.firstOrNull { it.id == cardId } ?: return
        save(card.copy(port = port))
    }

    private fun nextId(): Long = (_cards.value.maxOfOrNull { it.id } ?: 0L) + 1L

    private fun loadCards(): List<LlamaServerCardEntity> = runCatching {
        gson.fromJson<List<LlamaServerCardEntity>>(prefs.getString(KEY_CARDS, null), type).orEmpty()
    }.getOrDefault(emptyList())

    private fun write(cards: List<LlamaServerCardEntity>) {
        _cards.value = cards
        prefs.edit().putString(KEY_CARDS, gson.toJson(cards, type)).apply()
    }

    private companion object {
        const val PREFS_NAME = "llama_server_cards_fallback"
        const val KEY_CARDS = "cards"
    }
}
