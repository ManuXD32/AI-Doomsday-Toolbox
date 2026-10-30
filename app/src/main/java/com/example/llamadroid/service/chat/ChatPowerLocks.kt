package com.example.llamadroid.service.chat

import android.content.Context
import com.example.llamadroid.util.WakeLockManager

/** Wake + Wi-Fi locks held while at least one chat session exists (reference counted). */
internal class ChatPowerLocks(context: Context, private val tag: String) {
    private val app = context.applicationContext
    private val guard = Any()
    private var holders = 0

    fun acquire() {
        synchronized(guard) {
            holders++
            if (holders == 1) {
                WakeLockManager.acquire(app, tag)
                WakeLockManager.acquireWifiLock(app, tag)
            }
        }
    }

    fun release() {
        synchronized(guard) {
            if (holders == 0) return
            holders--
            if (holders == 0) {
                WakeLockManager.release(tag)
                WakeLockManager.releaseWifiLock(tag)
            }
        }
    }

    fun releaseAll() {
        synchronized(guard) {
            if (holders == 0) return
            holders = 0
            WakeLockManager.release(tag)
            WakeLockManager.releaseWifiLock(tag)
        }
    }
}
