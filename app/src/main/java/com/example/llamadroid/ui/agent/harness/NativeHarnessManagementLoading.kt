package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

enum class HarnessManagementArea { SETTINGS, EXTENSIONS, SESSIONS }

data class HarnessManagementLoadUi(
    val isLoading: Boolean = false,
    val loaded: Boolean = false,
    val errorCode: String? = null,
)

internal class HarnessManagementContext(val area: HarnessManagementArea) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<HarnessManagementContext>
}

/** Coalesces manager reads independently of the serialized mutation queue. */
internal class NativeHarnessManagementLoader(
    private val scope: CoroutineScope,
    private val client: () -> HarnessClient?,
    private val state: () -> NativeHarnessUiState,
    private val update: ((NativeHarnessUiState) -> NativeHarnessUiState) -> Unit,
    private val load: suspend (HarnessManagementArea) -> Unit,
) {
    private val jobs = mutableMapOf<HarnessManagementArea, Job>()
    private val owners = mutableMapOf<HarnessManagementArea, HarnessClient>()
    private val epochs = mutableMapOf<HarnessManagementArea, Long>()

    @Synchronized
    fun request(area: HarnessManagementArea, force: Boolean = false) {
        val captured = client() ?: return
        if (!force && owners[area] === captured && (jobs[area]?.isActive == true ||
                state().managementLoads[area]?.loaded == true)) return
        val epoch = (epochs[area] ?: 0L) + 1L
        epochs[area] = epoch
        jobs.remove(area)?.cancel()
        owners[area] = captured
        update { it.copy(managementLoads = it.managementLoads + (area to HarnessManagementLoadUi(isLoading = true))) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                withContext(HarnessManagementContext(area)) { withTimeout(30_000) { load(area) } }
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                failed(area, captured, epoch, "TIMEOUT")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed(area, captured, epoch, "HARNESS_ERROR")
            } finally {
                if (isCurrent(area, captured, epoch)) update { current ->
                    val previous = current.managementLoads[area] ?: HarnessManagementLoadUi()
                    current.copy(managementLoads = current.managementLoads +
                        (area to previous.copy(isLoading = false, loaded = previous.errorCode == null)))
                }
            }
        }
        jobs[area] = job
        job.start()
    }

    private fun failed(area: HarnessManagementArea, captured: HarnessClient, epoch: Long, code: String) {
        if (isCurrent(area, captured, epoch)) update { current ->
            current.copy(managementLoads = current.managementLoads +
                (area to HarnessManagementLoadUi(errorCode = code)))
        }
    }

    @Synchronized
    private fun isCurrent(area: HarnessManagementArea, captured: HarnessClient, epoch: Long) =
        client() === captured && epochs[area] == epoch

    /**
     * Marks manager snapshots stale without starting a read.  The selected
     * destination owns the next read through [request], so repeated native or
     * WebView return callbacks only cancel the old work and do not create a
     * refresh storm.
     *
     * Keeping the owner is intentional: a request for the same client can
     * start again after invalidation, while a request for a newly published
     * client still replaces the old owner as usual.
     */
    @Synchronized
    fun invalidate(vararg areas: HarnessManagementArea) {
        val targets = if (areas.isEmpty()) owners.keys.toList() else areas.toList()
        targets.forEach { area ->
            val activeOwner = owners[area]
            val activeJob = jobs[area]
            // A read already in flight is the cheapest coalescing point. It
            // will publish a complete snapshot; repeated callbacks must not
            // cancel and restart that same request.
            if (activeJob?.isActive == true && activeOwner === client()) return@forEach
            jobs.remove(area)?.cancel()
            epochs[area] = (epochs[area] ?: 0L) + 1L
            if (owners.containsKey(area)) {
                update { current ->
                    val previous = current.managementLoads[area] ?: HarnessManagementLoadUi()
                    current.copy(managementLoads = current.managementLoads +
                        (area to previous.copy(isLoading = false, loaded = false, errorCode = null)))
                }
            }
        }
    }
}

/** Text input is synchronous and never queues behind network operations. */
internal fun NativeHarnessUiState.withImmediateEdit(action: NativeHarnessUiAction): NativeHarnessUiState? = when (action) {
    NativeHarnessUiAction.DismissNotice -> copy(notice = null)
    is NativeHarnessUiAction.UpdateComposer -> copy(composerText = action.text)
    is NativeHarnessUiAction.SetComposerMode -> copy(composerMode = action.mode)
    is NativeHarnessUiAction.UpdateCommandLine -> copy(commandLine = action.line)
    is NativeHarnessUiAction.UpdateTrajectorySearch -> copy(extensions = extensions.copy(
        trajectory = extensions.trajectory.copy(searchQuery = action.query)))
    else -> null
}
