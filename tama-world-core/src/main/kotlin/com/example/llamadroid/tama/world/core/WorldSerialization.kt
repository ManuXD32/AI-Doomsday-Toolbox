package com.example.llamadroid.tama.world.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/** Shared persistence format for Room adapters, transfer bundles, and tests. */
@OptIn(ExperimentalSerializationApi::class)
object WorldStateCodec {
    val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
        classDiscriminator = "kind"
    }

    fun encode(state: WorldState): String = json.encodeToString(state)

    fun decode(value: String): WorldState = json.decodeFromString(value)

    fun roundTrip(state: WorldState): WorldState = decode(encode(state))
}
