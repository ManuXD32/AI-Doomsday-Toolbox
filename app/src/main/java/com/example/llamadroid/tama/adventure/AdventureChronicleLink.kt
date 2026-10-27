package com.example.llamadroid.tama.adventure

import android.content.Context
import androidx.core.content.edit
import com.example.llamadroid.tama.rpg.AdventureGateCatalog
import com.example.llamadroid.tama.rpg.AdventureGateStory
import com.example.llamadroid.tama.rpg.AdventureGateWorldProgress
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * Links the LLM Dream Chronicle to the Adventure Gate (Task A5). Everything here is pure so it is unit tested:
 * which Gate world the Chaos Realm visits, which monsters cameo, and how Chronicle grants resolve into a Gate
 * supply, a Nexum lore fragment or a lantern spark.
 */
object AdventureChronicleLink {
    const val FALLBACK_COINS = 25
    const val CAMEO_COUNT = 3
    const val SPARK = "spark"
    private const val SUPPLY_PREFIX = "supply:"
    private const val COINS_PREFIX = "coins:"
    private const val FRAGMENT_PREFIX = "fragment:"

    // ---- Chaos Realm visits ----

    /** World ids the pet has unlocked in campaign order: world 0, then each world whose predecessor's final boss fell. */
    fun unlockedWorldIds(rows: List<AdventureGateWorldProgress>): List<String> {
        val worlds = AdventureGateCatalog.worlds
        val result = ArrayList<String>()
        for ((index, world) in worlds.withIndex()) {
            if (index > 0 && rows.firstOrNull { it.worldId == worlds[index - 1].id }?.finalBossCleared != true) break
            result += world.id
        }
        return result
    }

    private fun seedOf(text: String, salt: Int = 0): Long = text.hashCode().toLong() * 31L + salt

    /** The Gate world a Chaos Realm run visits, seeded by the session id so the pick is stable for the whole run. */
    fun pickChaosWorld(sessionId: String, unlockedWorldIds: List<String>): String? {
        if (unlockedWorldIds.isEmpty()) return null
        return unlockedWorldIds[Random(seedOf(sessionId)).nextInt(unlockedWorldIds.size)]
    }

    /** Ordinary (non-boss, non-elite) monsters of [worldId], in a session-seeded order, limited to [count]. */
    fun cameoMonsterIds(worldId: String, sessionId: String, count: Int = CAMEO_COUNT): List<String> {
        val world = AdventureGateCatalog.worlds.firstOrNull { it.id == worldId } ?: return emptyList()
        val candidates = world.phases.flatMap { phase -> phase.waveMonsterIds.flatten() }
            .distinct()
            .filter { id ->
                val monster = AdventureGateCatalog.monsters.firstOrNull { it.id == id }
                monster != null && !monster.isBoss && monster.eliteOfId == null
            }
        return candidates.shuffled(Random(seedOf(sessionId, 7))).take(count)
    }

    /** The prompt block injected into Chaos Realm planner and scene prompts. [cameoNames] are already localized. */
    fun chaosBlock(world: ResolvedWorld, cameoNames: List<String>): String = buildString {
        appendLine("GATE WORLD VISIT: in this dream the hero drifts through ${world.name}, a realm of the Adventure Gate it already knows. Blend it into the free-form setting.")
        appendLine("Mood: ${world.mood}")
        if (world.landmarks.isNotEmpty()) appendLine("Landmarks: ${world.landmarks.joinToString("; ")}")
        appendLine("Inhabitants: ${world.inhabitants}")
        if (cameoNames.isNotEmpty()) {
            appendLine("Familiar creatures that may cameo, friendly or mischievous and never graphic: ${cameoNames.joinToString(", ")}")
        }
        append("Use these as seasoning, not as the whole story.")
    }

    // ---- Grants ----

    fun gateFragmentId(worldId: String, index: Int): String = "$worldId#$index"

    /** Fragments the Gate journal already shows (one per cleared boss, see WorldStoryUnlocks.fragmentCount). */
    fun gateUnlockedFragmentIds(rows: List<AdventureGateWorldProgress>): Set<String> {
        val result = LinkedHashSet<String>()
        val byWorld = rows.associateBy { it.worldId }
        for (worldId in AdventureGateStory.worldIds) {
            val count = AdventureGateStory.worldUnlocks(worldId, byWorld[worldId], emptySet()).fragmentCount
            for (i in 0 until count) result += gateFragmentId(worldId, i)
        }
        return result
    }

    /** Picks a Nexum lore fragment id ("world#index") not in [taken]; null when every fragment is already unlocked. */
    fun pickFragment(sessionId: String, taken: Set<String>): String? {
        val lore = AdventureLore.loreFor("en")
        val all = lore.worlds.flatMap { world -> world.journalFragments.indices.map { gateFragmentId(world.worldId, it) } }
        val free = all.filter { it !in taken }
        if (free.isEmpty()) return null
        return free[Random(seedOf(sessionId, 13)).nextInt(free.size)]
    }

    /** Picks a Gate supply (HP/mana potion) the pet could already find; null only if the catalog has none. */
    fun pickSupply(sessionId: String, unlockedWorldCount: Int): String? {
        val pool = AdventureGateCatalog.rewardSupplyPoolForWorld((unlockedWorldCount - 1).coerceAtLeast(0))
        if (pool.isEmpty()) return null
        return pool[Random(seedOf(sessionId, 3)).nextInt(pool.size)].id
    }

    /**
     * Resolves the raw grants of a finished run into concrete rewards. Unresolvable ones (no supply, no fragment
     * left) become a small coin payout so the player is never left empty-handed. Pure and deterministic.
     */
    fun resolveGrants(
        grants: List<ChronicleGrant>,
        sessionId: String,
        rows: List<AdventureGateWorldProgress>,
        alreadyHeldFragments: Set<String>
    ): List<ChronicleGrant> {
        val taken = LinkedHashSet<String>(gateUnlockedFragmentIds(rows) + alreadyHeldFragments)
        val worldCount = unlockedWorldIds(rows).size
        return grants.mapIndexed { index, grant ->
            if (grant.resolved != null) return@mapIndexed grant
            val resolved = when (grant.rewardId) {
                "gate_item" -> pickSupply("$sessionId:$index", worldCount)?.let { SUPPLY_PREFIX + it }
                "memory_fragment" -> pickFragment("$sessionId:$index", taken)?.also { taken += it }?.let { FRAGMENT_PREFIX + it }
                "lantern_spark" -> SPARK
                else -> null
            } ?: (COINS_PREFIX + FALLBACK_COINS)
            grant.copy(resolved = resolved)
        }
    }

    fun supplyIdOf(grant: ChronicleGrant): String? = grant.resolved?.takeIf { it.startsWith(SUPPLY_PREFIX) }?.removePrefix(SUPPLY_PREFIX)
    fun fragmentIdOf(grant: ChronicleGrant): String? = grant.resolved?.takeIf { it.startsWith(FRAGMENT_PREFIX) }?.removePrefix(FRAGMENT_PREFIX)
    fun fallbackCoinsOf(grant: ChronicleGrant): Int =
        grant.resolved?.takeIf { it.startsWith(COINS_PREFIX) }?.removePrefix(COINS_PREFIX)?.toIntOrNull() ?: 0
    fun isSpark(grant: ChronicleGrant): Boolean = grant.resolved == SPARK

    /** Fragment id back to its world name and text in [language]; null for unknown ids. */
    fun fragmentText(language: String, fragmentId: String): Pair<String, String>? {
        val worldId = fragmentId.substringBefore('#')
        val index = fragmentId.substringAfter('#', "").toIntOrNull() ?: return null
        val world = AdventureLore.loreFor(language).world(worldId) ?: return null
        val text = world.journalFragments.getOrNull(index) ?: return null
        return world.name to text
    }

    // ---- Lantern sparks ----

    /** Short lines, one per spark, shown once the Regent is beaten ("relit corners of the Nexum"). */
    val relitCorners: List<L10n> = listOf(
        L10n("A paper lantern glows again above the Sproutvale doorway.", "Un farolillo de papel vuelve a brillar sobre la Puerta Brotevalle."),
        L10n("The clockwork stairs hum a warm tune once more.", "Las escaleras de engranajes vuelven a tararear una melodía cálida."),
        L10n("A reading nook in the Moonmoss library lights up, cushion and all.", "Un rincón de lectura de la Biblioteca Musgolunar se enciende, cojín incluido."),
        L10n("The frozen toybox sparkles; one tiny drawer slides open by itself.", "El juguetero helado centellea; un cajoncito se abre solo."),
        L10n("Soft light runs along the Nexum's crossroads like a held breath let go.", "Una luz suave recorre la encrucijada del Nexo como un aliento contenido que por fin se suelta."),
        L10n("A window in the Starfall Citadel remembers its sunrise.", "Una ventana de la Ciudadela Estrellada recuerda su amanecer."),
        L10n("Somewhere far off, a small bell answers Lumo's glow.", "En algún lugar lejano, una campanita responde a la luz de Lumo.")
    )

    fun relitCornerLines(language: String, sparks: Int): List<String> =
        relitCorners.take(sparks.coerceAtLeast(0)).map { it.get(language) }
}

/** One finished LLM run as shown in the Journal's Dream Chronicle section. */
@Serializable
data class ChronicleLogEntry(
    val sessionId: String,
    val dungeon: String,
    val title: String,
    val finishedAt: Long,
    val outcome: AdventureOutcome,
    val coins: Int = 0,
    val xp: Int = 0,
    val bond: Int = 0,
    val grants: List<ChronicleGrant> = emptyList()
)

/** Per-pet history of finished runs. Sparks and fragments are derived from the entries, so upserts are idempotent. */
@Serializable
data class ChronicleLog(val entries: List<ChronicleLogEntry> = emptyList()) {
    val sparks: Int get() = entries.sumOf { entry -> entry.grants.count(AdventureChronicleLink::isSpark) }

    val fragmentIds: Set<String>
        get() = entries.flatMap { entry -> entry.grants.mapNotNull(AdventureChronicleLink::fragmentIdOf) }.toSet()

    /** Replaces an existing entry of the same session, so granting twice never doubles anything. */
    fun upsert(entry: ChronicleLogEntry): ChronicleLog =
        copy(entries = (entries.filterNot { it.sessionId == entry.sessionId } + entry).sortedBy { it.finishedAt }.takeLast(MAX_ENTRIES))

    companion object {
        const val MAX_ENTRIES = 200
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(raw: String?): ChronicleLog =
            raw?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString<ChronicleLog>(it) }.getOrNull() } ?: ChronicleLog()

        fun encode(log: ChronicleLog): String = json.encodeToString(log)
    }
}

/** SharedPreferences home of the [ChronicleLog] (survives "new adventure", which deletes the finished session). */
class AdventureChronicleStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("adventure_chronicle_log", Context.MODE_PRIVATE)

    fun load(petId: String): ChronicleLog = ChronicleLog.decode(prefs.getString("log_$petId", null))

    fun upsert(petId: String, entry: ChronicleLogEntry) {
        val updated = load(petId).upsert(entry)
        prefs.edit { putString("log_$petId", ChronicleLog.encode(updated)) }
    }

    /** Overwrites the stored log of [petId] (used when a transfer restores the pet's chronicle). */
    fun replace(petId: String, log: ChronicleLog) {
        prefs.edit { putString("log_$petId", ChronicleLog.encode(log)) }
    }
}
