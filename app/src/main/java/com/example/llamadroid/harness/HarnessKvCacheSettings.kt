package com.example.llamadroid.harness

import androidx.core.content.edit
import android.content.Context
import com.example.llamadroid.service.NativeChatKvCacheStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global switch and disk budget for the DeepSeek Harness KV caches written by
 * `NativeChatKvCacheCoordinator`. Kept in its own preferences file so it does not grow
 * `SettingsRepository`. Never stores session or prompt data.
 */
class HarnessKvCacheSettings private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val mutableEnabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, true))
    private val mutableBudgetBytes = MutableStateFlow(
        preferences.getLong(KEY_BUDGET_BYTES, DEFAULT_BUDGET_BYTES).takeIf { it > 0 } ?: DEFAULT_BUDGET_BYTES
    )

    /** Whether harness requests on managed llama cards keep their KV cache on disk. */
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()

    /** Maximum bytes the harness caches may use before the oldest are evicted. */
    val budgetBytes: StateFlow<Long> = mutableBudgetBytes.asStateFlow()

    fun setEnabled(value: Boolean) {
        preferences.edit { putBoolean(KEY_ENABLED, value) }
        mutableEnabled.value = value
    }

    fun setBudgetBytes(value: Long) {
        val bytes = value.coerceAtLeast(MIN_BUDGET_BYTES)
        preferences.edit { putLong(KEY_BUDGET_BYTES, bytes) }
        mutableBudgetBytes.value = bytes
    }

    /** Deletes every harness cache of [runtimeId]. */
    fun clearRuntime(runtimeId: String) {
        NativeChatKvCacheStore(appContext).deleteHarnessRuntime(runtimeId)
    }

    /** Disk bytes used by the harness caches of [runtimeId]. */
    fun sizeForRuntime(runtimeId: String): Long =
        NativeChatKvCacheStore(appContext).harnessRuntimeBytes(runtimeId)

    companion object {
        const val PREFERENCES_NAME = "harness_kv_cache_settings"
        const val KEY_ENABLED = "enabled"
        const val KEY_BUDGET_BYTES = "budget_bytes"
        const val DEFAULT_BUDGET_BYTES = 4L * 1024 * 1024 * 1024
        const val MIN_BUDGET_BYTES = 256L * 1024 * 1024

        @Volatile private var instance: HarnessKvCacheSettings? = null

        fun get(context: Context): HarnessKvCacheSettings =
            instance ?: synchronized(this) {
                instance ?: HarnessKvCacheSettings(context).also { instance = it }
            }

        fun clearRuntime(context: Context, runtimeId: String) = get(context).clearRuntime(runtimeId)

        fun sizeForRuntime(context: Context, runtimeId: String): Long =
            get(context).sizeForRuntime(runtimeId)
    }
}
