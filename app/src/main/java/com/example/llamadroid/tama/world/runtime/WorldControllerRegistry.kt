package com.example.llamadroid.tama.world.runtime

import android.content.Context
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.game.FarmRepository
import com.example.llamadroid.tama.game.TamaGameEngine
import java.util.IdentityHashMap

/** Phone UI and Wear gateway engine instances must never own competing world snapshots. */
object WorldControllerRegistry {
    private data class Entry(val controller: TamaWorldController, val engines: MutableSet<TamaGameEngine>)
    private val controllers = IdentityHashMap<TamaDatabase, Entry>()

    @Synchronized
    fun get(context: Context, database: TamaDatabase, farm: FarmRepository, engine: TamaGameEngine): TamaWorldController {
        val entry = controllers.getOrPut(database) {
            Entry(TamaWorldController(context.applicationContext, database, farm, engine), linkedSetOf())
        }
        entry.engines.add(engine)
        entry.controller.bindEngine(entry.engines.first())
        return entry.controller
    }

    @Synchronized
    fun ownsClock(engine: TamaGameEngine): Boolean = controllers.values.any { it.engines.firstOrNull() === engine }

    @Synchronized
    fun release(engine: TamaGameEngine) {
        val iterator = controllers.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            entry.engines.remove(engine)
            val next = entry.engines.firstOrNull()
            if (next == null) iterator.remove() else entry.controller.bindEngine(next)
        }
    }
}
