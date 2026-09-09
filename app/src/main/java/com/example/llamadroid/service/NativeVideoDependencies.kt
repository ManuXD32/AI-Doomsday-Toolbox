package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.util.NativeBackendKind
import com.example.llamadroid.util.NativeEngineFamily
import com.example.llamadroid.util.NativeFeatureModuleManager
import com.example.llamadroid.util.NativeModuleCatalog
import com.example.llamadroid.util.NativeModuleDelivery
import com.example.llamadroid.util.NativeModuleLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class NativeVideoDependencyAvailability(val llmAvailable: Boolean, val mediaAvailable: Boolean)

/** Uses the canonical native-module installer; missing media still permits Android frame decoding. */
object NativeVideoDependencies {
    private val installLock = Mutex()

    suspend fun ensure(context: Context, requireLlm: Boolean): NativeVideoDependencyAvailability =
        withContext(Dispatchers.IO) {
            installLock.withLock {
                val appContext = context.applicationContext
                val binaries = BinaryRepository(appContext)
                suspend fun hasLlm() = binaries.getExecutable()?.isFile == true
                fun hasMedia() = binaries.getFFmpegBinary()?.isFile == true &&
                    binaries.getFFprobeBinary()?.isFile == true
                var llmReady = !requireLlm || hasLlm()
                var mediaReady = hasMedia()
                if (!llmReady || !mediaReady) {
                    val manager = NativeFeatureModuleManager(appContext)
                    try {
                        // Request separately through the existing manager, whose Play
                        // sessions are per module. Never start overlapping split installs.
                        val families = buildList {
                            if (!llmReady) add(NativeEngineFamily.LLM)
                            if (!mediaReady) add(NativeEngineFamily.MEDIA)
                        }
                        for (family in families) {
                            val definition = NativeModuleCatalog.definitions
                                .filter { it.family == family && it.backend == NativeBackendKind.CPU &&
                                    it.mayBeAutoProvisioned && it.isCompatible() }
                                .maxByOrNull { it.tier?.ordinal ?: -1 } ?: continue
                            val current = manager.states.value.firstOrNull {
                                it.definition.moduleName == definition.moduleName
                            } ?: continue
                            if (current.delivery != NativeModuleDelivery.PLAY_MANAGED ||
                                current.lifecycle == NativeModuleLifecycle.REMOVAL_REQUESTED) continue
                            manager.requestInstall(definition.moduleName)
                            withTimeoutOrNull(5L * 60L * 1_000L) {
                                manager.states.first { states ->
                                    states.any { state ->
                                        state.definition.moduleName == definition.moduleName &&
                                            state.lifecycle in setOf(
                                                NativeModuleLifecycle.INSTALLED,
                                                NativeModuleLifecycle.FAILED,
                                                NativeModuleLifecycle.REQUIRES_CONFIRMATION
                                            )
                                    }
                                }
                            }
                        }
                        llmReady = !requireLlm || hasLlm()
                        mediaReady = hasMedia()
                    } finally {
                        manager.close()
                    }
                }
                check(llmReady) { appContext.getString(R.string.video_native_llm_required) }
                NativeVideoDependencyAvailability(llmReady, mediaReady)
            }
        }
}
