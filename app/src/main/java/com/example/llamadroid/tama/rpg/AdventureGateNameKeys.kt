package com.example.llamadroid.tama.rpg

import com.example.llamadroid.R
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/**
 * Stable string keys for the string resources that battle snapshots reference.
 *
 * R.string ids change between builds, so persisting them (battle JSON, transfer bundles)
 * would point at unrelated or missing strings after an update. Snapshots store these keys
 * (monster, skill, enemy-action and story ids) and resolve them back to the current build's
 * resource id on load. Legacy integer values and unknown keys decode to "no resource" so
 * callers fall back to generic text instead of crashing.
 */
object AdventureGateNameKeys {
    private const val SKELETON_HELPER_ATTACK_KEY = "x:skeleton_helper_attack"
    private const val NIGHT_ARENA_STORY_KEY = "story:night_arena"

    private val keyToRes: Map<String, Int> by lazy {
        buildMap {
            AdventureGateCatalog.monsters.forEach { put("m:${it.id}", it.nameRes) }
            AdventureGateCatalog.skills.forEach { put("s:${it.id}", it.nameRes) }
            AdventureGateCatalog.enemyActions.forEach { put("e:${it.id}", it.nameRes) }
            put(SKELETON_HELPER_ATTACK_KEY, R.string.adventure_gate_skill_skeleton_helper_attack)
            put(NIGHT_ARENA_STORY_KEY, R.string.night_arena_level_intro)
            AdventureGateCatalog.worlds.forEach { world ->
                world.phases.forEach { phase ->
                    put("story:${world.id}:${phase.phaseNumber}", phase.storyRes)
                    phase.bossRevealRes?.let { put("reveal:${world.id}:${phase.phaseNumber}", it) }
                }
            }
        }
    }

    // First key wins when two entries share one resource id (the string is identical anyway).
    private val resToKey: Map<Int, String> by lazy {
        buildMap { keyToRes.forEach { (key, res) -> putIfAbsent(res, key) } }
    }

    fun keyFor(res: Int): String? = resToKey[res]

    fun resFor(key: String): Int? = keyToRes[key]

    /** Legacy saves persisted the night-arena story as a raw resource id; only that story was ever stored. */
    internal val legacyStoryRes: Int get() = R.string.night_arena_level_intro
}

/** Nullable name/reveal resource reference persisted as a stable key; legacy ints decode to null. */
object AdventureGateNullableResKeySerializer : KSerializer<Int?> {
    private val delegate = String.serializer().nullable
    override val descriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: Int?) {
        delegate.serialize(encoder, value?.let(AdventureGateNameKeys::keyFor))
    }

    override fun deserialize(decoder: Decoder): Int? {
        val raw = decodeKey(decoder) ?: return null
        return AdventureGateNameKeys.resFor(raw)
    }
}

/** Required story resource reference; unknown keys decode to 0 and legacy ints to the night-arena intro. */
object AdventureGateStoryResKeySerializer : KSerializer<Int> {
    private val delegate = String.serializer().nullable
    override val descriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: Int) {
        delegate.serialize(encoder, AdventureGateNameKeys.keyFor(value))
    }

    override fun deserialize(decoder: Decoder): Int {
        if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            val primitive = element as? JsonPrimitive ?: return 0
            if (primitive.isString) return AdventureGateNameKeys.resFor(primitive.content) ?: 0
            return if (primitive.content == "null") 0 else AdventureGateNameKeys.legacyStoryRes
        }
        return decodeKey(decoder)?.let(AdventureGateNameKeys::resFor) ?: 0
    }
}

@OptIn(ExperimentalSerializationApi::class)
private fun decodeKey(decoder: Decoder): String? {
    if (decoder is JsonDecoder) {
        val primitive = decoder.decodeJsonElement() as? JsonPrimitive ?: return null
        return primitive.takeIf { it.isString }?.content
    }
    return if (decoder.decodeNotNullMark()) decoder.decodeString() else decoder.decodeNull()
}
