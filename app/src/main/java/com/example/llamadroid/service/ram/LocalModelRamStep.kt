package com.example.llamadroid.service.ram

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.llamadroid.data.HttpEndpointUrlSupport
import com.example.llamadroid.service.LlamaServerLaunchProfile
import com.example.llamadroid.service.LlamaServerLauncher
import com.example.llamadroid.service.LlamaServerSessionOwnerStore
import com.example.llamadroid.service.LlamaServerSessionStateStore
import com.example.llamadroid.service.LlamaServerSessionStatus
import com.example.llamadroid.service.LlamaServerUsageStore
import com.example.llamadroid.service.ManagedLlamaServerHttp
import com.example.llamadroid.service.managedLlamaConnectHost
import com.example.llamadroid.util.NativeProcessCleanup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** The model does not fit even after stopping every idle managed model. */
class LocalModelRamRefusal(val neededBytes: Long, val availableBytes: Long) :
    RuntimeException("Not enough free memory: need $neededBytes B, have $availableBytes B")

/**
 * RAM step of a managed start: when the new model does not fit, stops idle managed models
 * (least recently active first, only enough) and re-checks once. Only `card:` sessions are
 * candidates, so ephemeral helpers (title/vision) and legacy servers are never evicted.
 */
internal class LocalModelRamStep(
    context: Context,
    private val states: LlamaServerSessionStateStore,
    private val owners: LlamaServerSessionOwnerStore
) {
    private val app = context.applicationContext
    private val gate = LlamaRamGate(app)

    /**
     * Throws [LocalModelRamRefusal] when the model cannot fit (and [allowLowRam] is false).
     * Any other failure of the measurement is logged and ignored: never block a start on a bug.
     */
    suspend fun ensureRoom(
        profile: LlamaServerLaunchProfile,
        ownSessionId: String,
        allowLowRam: Boolean,
        onFreeing: () -> Unit
    ) {
        try {
            val estimate = gate.estimate(profile)
            if (estimate.totalBytes <= 0L) return // unknown model size: nothing to reason about
            val needed = estimate.totalBytes + LlamaRamEstimator.SAFETY_MARGIN_BYTES
            val snap = gate.snapshot(excludePort = profile.serverPort)
            var plan = LocalModelStartPolicy.plan(needed, snap.availBytes, snap.lowMemory, emptyList())
            if (plan is LocalModelStartPlan.Fits) return
            plan = LocalModelStartPolicy.plan(needed, snap.availBytes, snap.lowMemory, candidates(ownSessionId))
            when (plan) {
                is LocalModelStartPlan.Fits -> return
                is LocalModelStartPlan.Refuse -> if (!allowLowRam) throw LocalModelRamRefusal(plan.neededBytes, plan.availableAfterStopsBytes)
                is LocalModelStartPlan.StopThenStart -> {
                    onFreeing()
                    val stopped = plan.stop.filter { stopIfStillIdle(it.sessionId) }
                    waitStopped(stopped.map { it.sessionId })
                    delay(SETTLE_MS)
                    val after = gate.snapshot(excludePort = profile.serverPort)
                    val second = LocalModelStartPolicy.plan(needed, after.availBytes, after.lowMemory, emptyList())
                    if (second is LocalModelStartPlan.Refuse && !allowLowRam) {
                        throw LocalModelRamRefusal(second.neededBytes, second.availableAfterStopsBytes)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LocalModelRamRefusal) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "RAM check failed; starting anyway", e)
        }
    }

    private fun candidates(ownSessionId: String): List<LocalModelCandidate> {
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        return states.readAll().mapNotNull { snapshot ->
            val id = snapshot.sessionId
            if (id == ownSessionId || !id.startsWith(CARD_PREFIX) || snapshot.status != LlamaServerSessionStatus.RUNNING) return@mapNotNull null
            val owner = owners.get(id) ?: return@mapNotNull null
            if (!NativeProcessCleanup.recordedLlamaOwnerIsAliveSync(owner.pid, owner.processStartTimeTicks, owner.port)) return@mapNotNull null
            val resident = LlamaRamGate.residentBytesOfPid(owner.pid)
            if (resident <= 0L) return@mapNotNull null
            val usage = LlamaServerUsageStore(app, id).read()
            LocalModelCandidate(
                sessionId = id,
                residentBytes = resident,
                idle = isIdle(id, owner),
                lastActivityElapsedMs = estimateLastActivityElapsedMs(
                    snapshot.idleActivityAtElapsedMs, usage.lastActivityMs, snapshot.updatedAt, nowWall, nowElapsed)
            )
        }
    }

    /** Unknown (no usable /slots answer, unreadable receipts) counts as NOT idle. */
    private fun isIdle(sessionId: String, owner: com.example.llamadroid.service.LlamaServerSessionOwner): Boolean {
        val usage = LlamaServerUsageStore(app, sessionId).read()
        if (usage.active || !usage.readable) return false
        val profile = LlamaServerLaunchProfile.decode(owner.launchProfileJson) ?: return false
        val url = HttpEndpointUrlSupport.fromHostPort(managedLlamaConnectHost(profile.host), owner.port) ?: return false
        val observation = ManagedLlamaServerHttp.idleObservation(url) ?: return false
        return !observation.busy
    }

    /**
     * Re-checks idleness under the usage gate and waits for the stop inside it, so a caller that
     * wants this server meanwhile waits and then prepares the stopped server instead of losing it.
     */
    private suspend fun stopIfStillIdle(sessionId: String): Boolean =
        LlamaServerUsageStore(app, sessionId).guardIdleStop {
            val owner = owners.get(sessionId)
            if (owner != null && isIdle(sessionId, owner) && LlamaServerLauncher.stopSession(app, sessionId).isSuccess) {
                waitStopped(listOf(sessionId))
                true
            } else false
        }

    private suspend fun waitStopped(sessionIds: List<String>) {
        if (sessionIds.isEmpty()) return
        val deadline = SystemClock.elapsedRealtime() + STOP_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val all = states.readAll().associateBy { it.sessionId }
            val done = sessionIds.all { id ->
                val status = all[id]?.status
                status == null || status == LlamaServerSessionStatus.STOPPED || status == LlamaServerSessionStatus.ERROR
            }
            if (done) return
            delay(POLL_MS)
        }
    }

    private companion object {
        const val TAG = "LocalModelRamStep"
        const val CARD_PREFIX = "card:"
        const val STOP_WAIT_MS = 15_000L
        const val POLL_MS = 300L
        const val SETTLE_MS = 500L
    }
}
