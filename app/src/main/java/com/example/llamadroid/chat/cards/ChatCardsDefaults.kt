package com.example.llamadroid.chat.cards

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.llamadroid.service.NativeChatToolConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import org.json.JSONObject

/**
 * App-wide "interactive cards on by default for new chats" preference, in its own prefs file
 * (SettingsRepository is over 2k lines). Only seeds NEW chats; each chat keeps its own
 * [NativeChatToolConfig.KEY_CARDS_ENABLED] value afterwards.
 */
object ChatCardsDefaults {
    private const val PREFS = "chat_cards_defaults"
    private const val KEY_DEFAULT_ON = "default_on_for_new_chats"

    private const val KEY_DISABLED_TYPES = "disabled_card_types"

    /** Every type the user's stored disabled set was written with (so a type added later gets its default). */
    private const val KEY_KNOWN_TYPES = "known_card_types"

    /** Types that start switched off until the user decides (costs prompt tokens / needs a capable model). */
    val DEFAULT_DISABLED: Set<ChatCardType> = setOf(ChatCardType.OMNI)

    private val state = MutableStateFlow<Boolean?>(null)
    private val disabledState = MutableStateFlow<Set<ChatCardType>?>(null)

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isDefaultOn(context: Context): Boolean = prefs(context).getBoolean(KEY_DEFAULT_ON, false)

    fun setDefaultOn(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(KEY_DEFAULT_ON, value) }
        state.value = value
    }

    /** Live value for the settings switch; changes made through [setDefaultOn] are pushed to collectors. */
    fun observe(context: Context): Flow<Boolean> {
        if (state.value == null) state.value = isDefaultOn(context)
        return state.filterNotNull()
    }

    /**
     * Pure resolution of the disabled set: the stored one, plus every [DEFAULT_DISABLED] type the user has never
     * seen ([known] = types present when the set was last saved; null or missing = none). Unknown keys are ignored.
     */
    fun resolveDisabled(stored: Set<String>?, known: Set<String>?): Set<ChatCardType> {
        val knownTypes = known.orEmpty().mapNotNull(ChatCardType::fromKey).toSet()
        return stored.orEmpty().mapNotNull(ChatCardType::fromKey).toSet() + (DEFAULT_DISABLED - knownTypes)
    }

    /** Card types switched off for every chat: the user's choice, with new default-off types (omni) off until chosen. */
    fun disabledTypes(context: Context): Set<ChatCardType> {
        val p = prefs(context)
        return resolveDisabled(p.getStringSet(KEY_DISABLED_TYPES, null), p.getStringSet(KEY_KNOWN_TYPES, null))
    }

    fun setDisabledTypes(context: Context, disabled: Set<ChatCardType>) {
        prefs(context).edit {
            putStringSet(KEY_DISABLED_TYPES, disabled.map { it.key }.toSet())
            putStringSet(KEY_KNOWN_TYPES, ChatCardType.entries.map { it.key }.toSet())
        }
        disabledState.value = disabled
    }

    /** Live disabled set for the gallery and the token figure; changes made through [setDisabledTypes] are pushed. */
    fun observeDisabledTypes(context: Context): Flow<Set<ChatCardType>> {
        if (disabledState.value == null) disabledState.value = disabledTypes(context)
        return disabledState.filterNotNull()
    }

    /**
     * Enabled types for prompt building, which has no Context: the cached value, else the stored one read through
     * the application instance. Falls back to every type when no application exists (plain JVM unit tests).
     */
    fun currentEnabledTypes(): Set<ChatCardType> {
        val disabled = disabledState.value
            ?: runCatching { disabledTypes(com.example.llamadroid.LlamaApplication.instance) }.getOrNull()
                ?.also { disabledState.value = it }
            ?: emptySet()
        return ChatCardType.entries.toSet() - disabled
    }

    /**
     * Seeds [defaultOn] into a NEW chat's params unless they already carry an explicit
     * `chat_cards_enabled` value (a server's defaults, Easy mode, a profile). Off adds nothing,
     * because absent means off.
     */
    fun applyToNewChatParams(apiParams: String?, defaultOn: Boolean): String? {
        if (!defaultOn) return apiParams
        val json = if (apiParams.isNullOrBlank()) JSONObject()
        else runCatching { JSONObject(apiParams) }.getOrNull() ?: return apiParams
        // The global default wins over a stored `false`: server defaults always serialize the key, so a
        // `false` there cannot be told apart from "never chosen". The chat's own switch can turn it off again.
        if (json.optBoolean(NativeChatToolConfig.KEY_CARDS_ENABLED, false)) return apiParams
        json.put(NativeChatToolConfig.KEY_CARDS_ENABLED, true)
        return json.toString()
    }

    fun applyToNewChatParams(context: Context, apiParams: String?): String? =
        applyToNewChatParams(apiParams, isDefaultOn(context))
}
