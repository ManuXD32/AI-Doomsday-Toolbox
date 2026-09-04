package com.example.llamadroid.util

import android.content.Context
import android.os.PowerManager
import com.example.llamadroid.util.DebugLog

/**
 * Centralized wake lock manager for the app.
 * Provides a singleton wake lock that can be acquired/released by any component.
 * Useful for long-running operations like model downloads, ZIM processing, etc.
 */
object WakeLockManager {
    private const val APP_WAKE_LOCK_TIMEOUT_MS = 24 * 60 * 60 * 1_000L
    private var wakeLock: PowerManager.WakeLock? = null
    private val wakeOwners = OwnerLockState()
    private val lock = Any()
    
    /**
     * Acquire the app-wide wake lock.
     * Uses reference counting - lock is only released when all acquires are matched with releases.
     * 
     * @param context Application context
     * @param tag Identifier for debugging which component acquired the lock
     */
    fun acquire(context: Context, tag: String = "Unknown") {
        synchronized(lock) {
            if (wakeLock == null) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "LlamaDroid:AppWakeLock"
                )
            }
            
            val transition = wakeOwners.acquire(tag)
            if (transition.totalCount == 1) {
                // A safety timeout prevents a process-lifetime leak if an owner fails to release.
                // Normal callers still release as soon as their work completes.
                wakeLock?.acquire(APP_WAKE_LOCK_TIMEOUT_MS)
                DebugLog.log("[WakeLock] Acquired by $tag (refCount=${transition.totalCount}, owners=${transition.ownerSummary})")
            } else {
                DebugLog.log("[WakeLock] Ref increased by $tag (refCount=${transition.totalCount}, owners=${transition.ownerSummary})")
            }
        }
    }
    
    /**
     * Release the app-wide wake lock.
     * Only actually releases when reference count reaches 0.
     * 
     * @param tag Identifier for debugging which component released the lock
     */
    fun release(tag: String = "Unknown") {
        synchronized(lock) {
            val transition = wakeOwners.release(tag)
            if (!transition.ownerHadReference) {
                DebugLog.log("[WakeLock] Ignored unowned release by $tag (refCount=${transition.totalCount}, owners=${transition.ownerSummary})")
                return
            }

            DebugLog.log("[WakeLock] Released by $tag (refCount=${transition.totalCount}, owners=${transition.ownerSummary})")

            if (transition.totalCount == 0 && wakeLock?.isHeld == true) {
                wakeLock?.release()
                DebugLog.log("[WakeLock] Actually released, no more refs")
            }
        }
    }
    
    /**
     * Force release the wake lock regardless of reference count.
     * Use only in emergency situations like app termination.
     */
    fun forceRelease() {
        synchronized(lock) {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                DebugLog.log("[WakeLock] Force released")
            }
            wakeOwners.clear()
        }
    }
    
    /**
     * Check if wake lock is currently held
     */
    fun isHeld(): Boolean = wakeLock?.isHeld == true
    
    // ============================================================================================
    // WifiLock Management
    // ============================================================================================
    
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private val wifiOwners = OwnerLockState()
    private val wifiLockSync = Any()
    
    /**
     * Acquire a high-performance WifiLock to prevent radio from sleeping.
     * Essential for distributed inference where low latency is required.
     */
    fun acquireWifiLock(context: Context, tag: String = "Unknown") {
        synchronized(wifiLockSync) {
            if (wifiLock == null) {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager

                // Keep the chosen mode explicit in one place instead of branching to identical code paths.
                @Suppress("DEPRECATION")
                wifiLock = wifiManager.createWifiLock(
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "LlamaDroid:AppWifiLock"
                )
                wifiLock?.setReferenceCounted(false)
            }
            
            val transition = wifiOwners.acquire(tag)
            if (transition.totalCount == 1) {
                wifiLock?.acquire()
                DebugLog.log("[WifiLock] Acquired by $tag (refCount=${transition.totalCount}, owners=${transition.ownerSummary})")
            } else {
                DebugLog.log("[WifiLock] Ref increased by $tag (refCount=${transition.totalCount}, owners=${transition.ownerSummary})")
            }
        }
    }
    
    /**
     * Release the WifiLock.
     */
    fun releaseWifiLock(tag: String = "Unknown") {
        synchronized(wifiLockSync) {
            val transition = wifiOwners.release(tag)
            if (!transition.ownerHadReference) {
                DebugLog.log("[WifiLock] Ignored unowned release by $tag (refCount=${transition.totalCount}, owners=${transition.ownerSummary})")
                return
            }

            DebugLog.log("[WifiLock] Released by $tag (refCount=${transition.totalCount}, owners=${transition.ownerSummary})")

            if (transition.totalCount == 0 && wifiLock?.isHeld == true) {
                wifiLock?.release()
                DebugLog.log("[WifiLock] Actually released, no more refs")
            }
        }
    }

    fun isWifiHeld(): Boolean = wifiLock?.isHeld == true
}

internal class OwnerLockState {
    private val owners = linkedMapOf<String, Int>()

    fun acquire(owner: String): OwnerLockTransition {
        val normalized = owner.ifBlank { "Unknown" }
        owners[normalized] = (owners[normalized] ?: 0) + 1
        return snapshot(ownerHadReference = true)
    }

    fun release(owner: String): OwnerLockTransition {
        val normalized = owner.ifBlank { "Unknown" }
        val count = owners[normalized] ?: 0
        if (count <= 0) {
            return snapshot(ownerHadReference = false)
        }
        if (count == 1) {
            owners.remove(normalized)
        } else {
            owners[normalized] = count - 1
        }
        return snapshot(ownerHadReference = true)
    }

    fun clear() {
        owners.clear()
    }

    fun totalCount(): Int = owners.values.sum()

    private fun snapshot(ownerHadReference: Boolean): OwnerLockTransition =
        OwnerLockTransition(
            totalCount = totalCount(),
            ownerSummary = owners.entries.joinToString(",") { "${it.key}:${it.value}" }.ifBlank { "none" },
            ownerHadReference = ownerHadReference
        )
}

internal data class OwnerLockTransition(
    val totalCount: Int,
    val ownerSummary: String,
    val ownerHadReference: Boolean
)
