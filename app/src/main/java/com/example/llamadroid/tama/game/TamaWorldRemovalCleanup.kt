package com.example.llamadroid.tama.game

import android.content.Context
import androidx.core.content.edit
import com.example.llamadroid.util.DebugLog
import java.io.File

/** One-time sweep of files and preferences left behind by the removed simulated Tama world. */
object TamaWorldRemovalCleanup {
    private const val PREFS = "removed_feature_cleanup"
    private const val DONE_KEY = "tama_world_cleanup_done_v1"

    fun runOnce(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(DONE_KEY, false)) return
        listOf<() -> Unit>(
            { context.deleteSharedPreferences("tama_world_memories") },
            { context.deleteSharedPreferences("tama_synthetic_training") },
            { File(context.noBackupFilesDir, "tama-world-training").deleteRecursively() }
        ).forEach { step ->
            runCatching { step() }.onFailure { error ->
                DebugLog.log("[StartupCleanup] Tama world cleanup step failed: ${error.message}")
            }
        }
        prefs.edit { putBoolean(DONE_KEY, true) }
    }
}
