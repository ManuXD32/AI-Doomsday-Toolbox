package com.example.llamadroid.tama.world.core

/** One bounded index of immutable generated layout lists; no mutable simulation state is cached. */
internal class WorldSpatialIndex private constructor(private val source: WorldState) {
    private val roads = BooleanArray(source.width * source.height)
    private val buildings = arrayOfNulls<WorldStructure>(source.width * source.height)
    private val entrances = BooleanArray(source.width * source.height)

    init {
        source.roads.forEach { if (source.contains(it)) roads[index(it.x, it.y)] = true }
        source.structures.forEach { building ->
            for (y in building.yBounds) for (x in building.bounds) {
                if (x in 0 until source.width && y in 0 until source.height) buildings[index(x, y)] = building
            }
            if (source.contains(building.entrance)) entrances[index(building.entrance.x, building.entrance.y)] = true
        }
    }

    private fun index(x: Int, y: Int): Int = y * source.width + x
    fun isRoad(x: Int, y: Int): Boolean = roads[index(x, y)]
    fun isEntrance(x: Int, y: Int): Boolean = entrances[index(x, y)]
    fun structure(x: Int, y: Int): WorldStructure? = buildings[index(x, y)]
    private fun matches(state: WorldState): Boolean = source.width == state.width && source.height == state.height &&
        source.roads === state.roads && source.structures === state.structures

    companion object {
        @Volatile private var cached: WorldSpatialIndex? = null

        fun of(state: WorldState): WorldSpatialIndex {
            cached?.takeIf { it.matches(state) }?.let { return it }
            return synchronized(this) {
                cached?.takeIf { it.matches(state) } ?: WorldSpatialIndex(state).also { cached = it }
            }
        }
    }
}
