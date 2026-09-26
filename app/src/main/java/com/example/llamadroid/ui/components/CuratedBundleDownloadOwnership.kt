package com.example.llamadroid.ui.components

import android.content.Context

/**
 * Keeps explicit bundle requests attached to a shared download task.
 *
 * Curated audio bundles deliberately reuse one physical task for shared
 * components. The task must only be cancelled after every bundle that asked
 * for it has released its ownership. SharedPreferences keeps that small bit
 * of intent across navigation and process recreation without adding database
 * schema or storing model contents.
 */
internal object CuratedBundleDownloadOwnership {
    private const val PREFERENCES = "curated_bundle_download_ownership"
    private const val KEY_PREFIX = "task:"

    private val lock = Any()
    private var loadedPreferences: android.content.SharedPreferences? = null
    private val ownersByTask = linkedMapOf<String, MutableSet<String>>()

    private fun preferences(context: Context): android.content.SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun ensureLoaded(context: Context) {
        val preferences = preferences(context)
        if (loadedPreferences === preferences) return
        ownersByTask.clear()
        preferences.all.forEach { (key, value) ->
            if (!key.startsWith(KEY_PREFIX)) return@forEach
            val taskId = key.removePrefix(KEY_PREFIX)
            val owners = (value as? Set<*>)
                ?.filterIsInstance<String>()
                ?.filter { it.isNotBlank() }
                ?.toMutableSet()
                ?: mutableSetOf()
            if (taskId.isNotBlank() && owners.isNotEmpty()) {
                ownersByTask[taskId] = owners
            }
        }
        loadedPreferences = preferences
    }

    private fun persist(
        context: Context,
        taskId: String,
        owners: Set<String>
    ) {
        val editor = preferences(context).edit()
        if (owners.isEmpty()) {
            editor.remove(KEY_PREFIX + taskId)
        } else {
            editor.putStringSet(KEY_PREFIX + taskId, owners.toSet())
        }
        // The service may begin immediately after the click. Commit the tiny
        // ownership record before that worker can observe cancellation.
        editor.commit()
    }

    fun addOwner(context: Context, taskId: String, bundleId: String) {
        if (taskId.isBlank() || bundleId.isBlank()) return
        synchronized(lock) {
            ensureLoaded(context)
            val owners = ownersByTask.getOrPut(taskId) { linkedSetOf() }
            if (owners.add(bundleId)) persist(context, taskId, owners)
        }
    }

    fun owners(context: Context, taskId: String): Set<String> = synchronized(lock) {
        ensureLoaded(context)
        ownersByTask[taskId].orEmpty().toSet()
    }

    /**
     * Releases one bundle's request and returns true only when it was the last
     * known owner. Unknown shared tasks intentionally return false so a stale
     * UI cannot cancel a download that may still be needed elsewhere.
     */
    fun releaseOwner(context: Context, taskId: String, bundleId: String): Boolean =
        synchronized(lock) {
            ensureLoaded(context)
            val owners = ownersByTask[taskId] ?: return@synchronized false
            if (!owners.remove(bundleId)) return@synchronized false
            if (owners.isEmpty()) ownersByTask.remove(taskId)
            persist(context, taskId, owners)
            owners.isEmpty()
        }

    fun clear(context: Context, taskId: String) {
        if (taskId.isBlank()) return
        synchronized(lock) {
            ensureLoaded(context)
            if (ownersByTask.remove(taskId) != null) persist(context, taskId, emptySet())
        }
    }
}
