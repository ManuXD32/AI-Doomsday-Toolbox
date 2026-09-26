package com.example.llamadroid.tama.world.memory

import android.content.Context
import com.example.llamadroid.tama.db.TamaDatabase

enum class AdventureMemoryPolicy { NEVER, ASK, AUTO_MAJOR }

/** Per-pet preference; synthetic episodes have no route into this repository. */
class AdventureMemoryPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tama_world_memories", Context.MODE_PRIVATE)

    fun policy(petId: String): AdventureMemoryPolicy = runCatching {
        AdventureMemoryPolicy.valueOf(prefs.getString(petId, AdventureMemoryPolicy.AUTO_MAJOR.name)!!)
    }.getOrDefault(AdventureMemoryPolicy.AUTO_MAJOR)

    fun setPolicy(petId: String, policy: AdventureMemoryPolicy) {
        prefs.edit().putString(petId, policy.name).apply()
    }

    suspend fun approve(database: TamaDatabase, petId: String, episodeId: String) {
        val dao = database.worldDao()
        val episode = dao.episode(episodeId) ?: return
        require(episode.petId == petId) { "episode_ownership_mismatch" }
        if (episode.memoryStatus in setOf("CLOSED", "PENDING")) {
            dao.saveEpisodes(listOf(episode.copy(memoryStatus = "APPROVED")))
        }
    }
}
