package com.example.llamadroid.service

import java.util.concurrent.ConcurrentHashMap

/**
 * One foreground lease per running Harness runtime. Releasing runtime A must keep the foreground
 * role while runtime B is still retained, so the service tracks ids instead of a single flag.
 */
internal class HarnessRetainLeases {
    private val ids = ConcurrentHashMap.newKeySet<String>()

    /** Returns true when this call created the lease (false when it was already retained). */
    fun acquire(runtimeId: String): Boolean = ids.add(runtimeId)

    /** Returns true when a lease existed and was removed. */
    fun release(runtimeId: String): Boolean = ids.remove(runtimeId)

    fun holds(runtimeId: String): Boolean = ids.contains(runtimeId)
    fun isEmpty(): Boolean = ids.isEmpty()
    fun isNotEmpty(): Boolean = ids.isNotEmpty()
    fun snapshot(): Set<String> = ids.toSet()
}
