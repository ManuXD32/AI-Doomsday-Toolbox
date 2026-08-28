package com.example.llamadroid.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Utilities for robust process management.
 * Ensures processes are properly terminated even if they ignore SIGTERM.
 */
object ProcessUtils {
    
    /**
     * Gracefully stop a process with timeout and forced kill fallback.
     * 
     * @param process The process to stop
     * @param gracePeriodMs Time to wait for graceful shutdown (SIGTERM)
     * @param forcePeriodMs Time to wait after SIGKILL before giving up
     * @return true if process terminated, false if still running
     */
    suspend fun stopProcess(
        process: Process?,
        gracePeriodMs: Long = 5000,
        forcePeriodMs: Long = 3000
    ): Boolean = withContext(Dispatchers.IO) {
        if (process == null) return@withContext true
        if (!process.isAlive) return@withContext true
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        
        DebugLog.log("[ProcessUtils] Sending SIGTERM...")
        
        // Step 1: Try graceful shutdown (SIGTERM)
        process.destroy()
        
        val gracefulTerminated = try {
            process.waitFor(gracePeriodMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            false
        }
        
        if (gracefulTerminated) {
            DebugLog.log("[ProcessUtils] Process terminated gracefully")
            return@withContext true
        }
        
        // Step 2: Force kill (SIGKILL)
        DebugLog.log("[ProcessUtils] Grace period expired, sending SIGKILL...")
        process.destroyForcibly()
        
        val forcedTerminated = try {
            process.waitFor(forcePeriodMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            false
        }
        
        if (forcedTerminated) {
            DebugLog.log("[ProcessUtils] Process terminated forcibly")
        } else {
            DebugLog.log("[ProcessUtils] WARNING: Process still running after SIGKILL!")
        }
        
        return@withContext forcedTerminated
    }
    
    /**
     * Synchronous version for non-coroutine contexts.
     * Blocks the current thread until process terminates.
     */
    fun stopProcessSync(
        process: Process?,
        gracePeriodMs: Long = 5000,
        forcePeriodMs: Long = 3000
    ): Boolean {
        if (process == null) return true
        if (!process.isAlive) return true
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        
        DebugLog.log("[ProcessUtils] Sending SIGTERM (sync)...")
        
        // Step 1: Try graceful shutdown
        process.destroy()
        
        val gracefulTerminated = try {
            process.waitFor(gracePeriodMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            false
        }
        
        if (gracefulTerminated) {
            DebugLog.log("[ProcessUtils] Process terminated gracefully")
            return true
        }
        
        // Step 2: Force kill
        DebugLog.log("[ProcessUtils] Grace period expired, sending SIGKILL...")
        process.destroyForcibly()
        
        val forcedTerminated = try {
            process.waitFor(forcePeriodMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            false
        }
        
        if (forcedTerminated) {
            DebugLog.log("[ProcessUtils] Process terminated forcibly")
        } else {
            DebugLog.log("[ProcessUtils] WARNING: Process still running after SIGKILL!")
        }
        
        return forcedTerminated
    }
    
    /**
     * Check if a process is truly dead.
     */
    fun isTerminated(process: Process?): Boolean {
        return process == null || !process.isAlive
    }
}
