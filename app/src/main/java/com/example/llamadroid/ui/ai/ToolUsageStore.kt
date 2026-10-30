package com.example.llamadroid.ui.ai

import android.content.Context
import androidx.core.content.edit

/**
 * Remembers when each catalog tool was last opened so Home can suggest tools that were never
 * tried. The root navigation listener calls [recordRoute] on every destination change.
 */
object ToolUsageStore {
    private const val PREFS = "tool_usage"

    /** Stores the current time for the tool that owns [route]; unknown routes are ignored. */
    fun recordRoute(context: Context, route: String?) {
        val tool = ToolCatalog.toolForRoute(route) ?: return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putLong(tool.id, System.currentTimeMillis())
        }
    }

    /** Tool id to last-opened epoch millis. */
    fun lastOpened(context: Context): Map<String, Long> =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
            .mapNotNull { (key, value) -> (value as? Long)?.let { key to it } }
            .toMap()
}

/** Pure selection logic for the Home "Not tried yet" row. */
object ToolDiscovery {
    /**
     * Picks up to [limit] grid tools that were never opened. The order is a shuffle seeded by
     * [daySeed] (for example the epoch day), so the picks are stable during a day and rotate daily.
     */
    fun notTried(
        candidates: List<AIToolDefinition>,
        openedToolIds: Set<String>,
        daySeed: Long,
        excludeIds: Set<String> = emptySet(),
        limit: Int = 3
    ): List<AIToolDefinition> = candidates
        .filter { it.visibility == ToolVisibility.GRID && it.id !in openedToolIds && it.id !in excludeIds }
        .sortedBy { it.id }
        .shuffled(java.util.Random(daySeed))
        .take(limit)
}
