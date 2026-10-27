package com.example.llamadroid.tama.game

import android.content.Context
import androidx.core.content.edit
import com.example.llamadroid.tama.adventure.AdventureChronicleStore
import com.example.llamadroid.tama.adventure.ChronicleLog
import com.example.llamadroid.tama.ui.AdventureGateStoryStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json

/**
 * Pet-scoped state that lives in SharedPreferences instead of Room. A transfer must carry all of it, so each
 * provider exports/imports one slice for a single pet id and the bundle stores the result under [key] in
 * `TamaTransferBundle.extras`. Unknown keys and malformed values are ignored on import.
 *
 * Deliberately NOT carried: the shimeji overlay settings and daily presence credit (`tama_shimeji`) are
 * per-device (overlay permission, position, enabled flag), and notification delivery signatures
 * (`tama_notification_state`) are per-device dedupe state; copying either would suppress or duplicate
 * notifications on the receiving phone.
 */
interface TamaPetPrefsExtra {
    val key: String
    fun export(petId: String): JsonElement?
    fun import(petId: String, element: JsonElement)
}

object TamaPetPrefsExtras {
    const val GATE_STORY_SEEN = "adventure_gate_story_seen"
    const val CHRONICLE_LOG = "adventure_chronicle_log"
    const val FARM_WORK_BUDGET = "farm_pet_work_budget"
    const val SLEEPY_FAIRY_LAST_SHOWN = "sleepy_fairy_last_shown"

    // Preference file names owned by PetFarmWork and TamaGameEngine (kept private there).
    private const val FARM_WORK_PREFS = "tama_farm_pet_work"
    private const val SLEEPY_FAIRY_PREFS = "tama_sleepy_fairy"

    fun providers(context: Context): List<TamaPetPrefsExtra> {
        val app = context.applicationContext
        return listOf(
            GateStoryExtra(AdventureGateStoryStore(app)),
            ChronicleExtra(AdventureChronicleStore(app)),
            SinglePrefExtra(FARM_WORK_BUDGET, app, FARM_WORK_PREFS, asLong = false) { petId -> petId },
            SinglePrefExtra(SLEEPY_FAIRY_LAST_SHOWN, app, SLEEPY_FAIRY_PREFS, asLong = true) { petId -> "last_shown_$petId" }
        )
    }

    /** Collects every provider's slice for [petId]; failing or empty providers are skipped. */
    fun exportAll(providers: List<TamaPetPrefsExtra>, petId: String): Map<String, JsonElement> {
        val result = LinkedHashMap<String, JsonElement>()
        providers.forEach { provider ->
            runCatching { provider.export(petId) }.getOrNull()?.let { result[provider.key] = it }
        }
        return result
    }

    /** Restores the known slices of [extras] for [petId]; unknown keys and bad values are ignored. */
    fun importAll(providers: List<TamaPetPrefsExtra>, extras: Map<String, JsonElement>, petId: String) {
        if (extras.isEmpty()) return
        providers.forEach { provider ->
            val element = extras[provider.key] ?: return@forEach
            runCatching { provider.import(petId, element) }
        }
    }

    private class GateStoryExtra(private val store: AdventureGateStoryStore) : TamaPetPrefsExtra {
        override val key = GATE_STORY_SEEN
        override fun export(petId: String): JsonElement? =
            store.load(petId).takeIf { it.isNotEmpty() }?.sorted()?.let { ids -> JsonArray(ids.map(::JsonPrimitive)) }

        override fun import(petId: String, element: JsonElement) {
            store.save(petId, element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet())
        }
    }

    private class ChronicleExtra(private val store: AdventureChronicleStore) : TamaPetPrefsExtra {
        override val key = CHRONICLE_LOG
        override fun export(petId: String): JsonElement? {
            val log = store.load(petId)
            return if (log.entries.isEmpty()) null else Json.parseToJsonElement(ChronicleLog.encode(log))
        }

        override fun import(petId: String, element: JsonElement) {
            store.replace(petId, ChronicleLog.decode(element.toString()))
        }
    }

    /** One per-pet preference (a "date:points" string or a Long timestamp) in its own prefs file. */
    private class SinglePrefExtra(
        override val key: String,
        context: Context,
        prefsName: String,
        private val asLong: Boolean,
        private val prefKey: (String) -> String
    ) : TamaPetPrefsExtra {
        private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

        override fun export(petId: String): JsonElement? =
            prefs.all[prefKey(petId)]?.toString()?.let(::JsonPrimitive)

        override fun import(petId: String, element: JsonElement) {
            val raw = element.jsonPrimitive.contentOrNull ?: return
            if (asLong) {
                val value = raw.toLongOrNull() ?: return
                prefs.edit { putLong(prefKey(petId), value) }
            } else {
                prefs.edit { putString(prefKey(petId), raw) }
            }
        }
    }
}
