package com.example.llamadroid.util

import android.content.Context
import android.util.Log
import com.google.android.play.core.splitinstall.SplitInstallManager
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class NativeModuleLifecycle {
    NOT_INSTALLED,
    QUEUED,
    REQUIRES_CONFIRMATION,
    DOWNLOADING,
    INSTALLING,
    INSTALLED,
    REMOVAL_REQUESTED,
    FAILED
}

data class NativeModuleState(
    val definition: NativeModuleDefinition,
    val lifecycle: NativeModuleLifecycle,
    val compatible: Boolean,
    val complete: Boolean,
    val bytesDownloaded: Long? = null,
    val totalBytes: Long? = null,
    val lastError: String? = null,
    val delivery: NativeModuleDelivery
) {
    val usable: Boolean
        get() = lifecycle == NativeModuleLifecycle.INSTALLED && compatible && complete
}

/**
 * Owns Play split sessions and the durable "removal requested" marker.  The
 * marker prevents new work from selecting a split while Play removes it later.
 */
class NativeFeatureModuleManager(private val context: Context) {
    companion object {
        private const val TAG = "NativeFeatureModules"
        private const val PREFS = "native_feature_modules"
        private const val PENDING_REMOVAL_PREFIX = "pending_removal_"
    }

    private val delivery = NativeModuleCatalog.deliveryFor(context)
    private val splitManager: SplitInstallManager? = when (delivery) {
        NativeModuleDelivery.PLAY_MANAGED -> SplitInstallManagerFactory.create(context)
        NativeModuleDelivery.EMBEDDED,
        NativeModuleDelivery.SIDELOADED -> null
    }
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutableStates = MutableStateFlow(emptyMap<String, NativeModuleState>())
    private val visibleStates = MutableStateFlow<List<NativeModuleState>>(emptyList())
    val states: StateFlow<List<NativeModuleState>> = visibleStates.asStateFlow()
    private val listener = SplitInstallStateUpdatedListener(::onSplitUpdate)

    init {
        splitManager?.registerListener(listener)
        refresh()
    }

    fun close() {
        splitManager?.unregisterListener(listener)
    }

    fun refresh() {
        val installed = splitManager?.installedModules.orEmpty()
        val fresh = NativeModuleCatalog.definitions.associate { definition ->
            val embedded = delivery != NativeModuleDelivery.PLAY_MANAGED &&
                NativeModuleFileValidator.hasCompletePayload(context, definition)
            val isInstalled = if (delivery == NativeModuleDelivery.PLAY_MANAGED) {
                definition.moduleName in installed
            } else {
                embedded
            }
            val pending = prefs.getBoolean(PENDING_REMOVAL_PREFIX + definition.moduleName, false)
            if (pending && !isInstalled) {
                prefs.edit().remove(PENDING_REMOVAL_PREFIX + definition.moduleName).apply()
            }
            definition.moduleName to NativeModuleState(
                definition = definition,
                lifecycle = when {
                    pending && isInstalled -> NativeModuleLifecycle.REMOVAL_REQUESTED
                    isInstalled -> NativeModuleLifecycle.INSTALLED
                    else -> NativeModuleLifecycle.NOT_INSTALLED
                },
                compatible = definition.isCompatible(),
                complete = !isInstalled || NativeModuleFileValidator.hasCompletePayload(context, definition),
                delivery = delivery
            )
        }
        publish(fresh)
    }

    fun requestInstall(moduleName: String) {
        val definition = NativeModuleCatalog.require(moduleName)
        val current = mutableStates.value[moduleName]
        if (!definition.isCompatible()) {
            update(moduleName, current?.copy(
                lifecycle = NativeModuleLifecycle.FAILED,
                compatible = false,
                lastError = "This native binary is not compatible with this device."
            ) ?: return)
            return
        }
        val manager = splitManager
        if (manager == null) {
            update(moduleName, current?.copy(
                lifecycle = NativeModuleLifecycle.FAILED,
                lastError = "This binary is included in this package or requires Google Play."
            ) ?: return)
            return
        }
        if (moduleName in manager.installedModules) {
            refresh()
            return
        }
        update(moduleName, (current ?: return).copy(lifecycle = NativeModuleLifecycle.QUEUED, lastError = null))
        manager.startInstall(SplitInstallRequest.newBuilder().addModule(moduleName).build())
            .addOnFailureListener { error ->
                Log.e(TAG, "Unable to install $moduleName", error)
                update(moduleName, (mutableStates.value[moduleName] ?: return@addOnFailureListener).copy(
                    lifecycle = NativeModuleLifecycle.FAILED,
                    lastError = error.message
                ))
            }
    }

    /** Request deferred removal.  Callers must stop active work and select a fallback first. */
    fun requestRemoval(moduleName: String, onResult: (Result<Unit>) -> Unit = {}) {
        val manager = splitManager
        val current = mutableStates.value[moduleName] ?: return onResult(Result.failure(IllegalArgumentException("Unknown module")))
        if (manager == null) {
            return onResult(Result.failure(IllegalStateException("Embedded native binaries cannot be removed separately.")))
        }
        if (current.lifecycle != NativeModuleLifecycle.INSTALLED) {
            return onResult(Result.failure(IllegalStateException("This native binary is not installed.")))
        }
        manager.deferredUninstall(listOf(moduleName))
            .addOnSuccessListener {
                prefs.edit().putBoolean(PENDING_REMOVAL_PREFIX + moduleName, true).apply()
                update(moduleName, current.copy(lifecycle = NativeModuleLifecycle.REMOVAL_REQUESTED))
                onResult(Result.success(Unit))
            }
            .addOnFailureListener { error -> onResult(Result.failure(error)) }
    }

    private fun onSplitUpdate(state: com.google.android.play.core.splitinstall.SplitInstallSessionState) {
        state.moduleNames().forEach { moduleName ->
            val current = mutableStates.value[moduleName] ?: return@forEach
            val lifecycle = when (state.status()) {
                SplitInstallSessionStatus.PENDING -> NativeModuleLifecycle.QUEUED
                SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION -> NativeModuleLifecycle.REQUIRES_CONFIRMATION
                SplitInstallSessionStatus.DOWNLOADING -> NativeModuleLifecycle.DOWNLOADING
                SplitInstallSessionStatus.DOWNLOADED,
                SplitInstallSessionStatus.INSTALLING -> NativeModuleLifecycle.INSTALLING
                SplitInstallSessionStatus.INSTALLED -> NativeModuleLifecycle.INSTALLED
                SplitInstallSessionStatus.FAILED,
                SplitInstallSessionStatus.CANCELED,
                SplitInstallSessionStatus.CANCELING -> NativeModuleLifecycle.FAILED
                else -> current.lifecycle
            }
            update(moduleName, current.copy(
                lifecycle = lifecycle,
                bytesDownloaded = state.bytesDownloaded().takeIf { it >= 0L },
                totalBytes = state.totalBytesToDownload().takeIf { it > 0L },
                lastError = state.errorCode().takeIf { lifecycle == NativeModuleLifecycle.FAILED }?.toString()
            ))
            if (lifecycle == NativeModuleLifecycle.INSTALLED) refresh()
        }
    }

    private fun update(moduleName: String, state: NativeModuleState) = publish(mutableStates.value + (moduleName to state))

    private fun publish(next: Map<String, NativeModuleState>) {
        mutableStates.value = next
        visibleStates.value = NativeModuleCatalog.definitions.mapNotNull { definition ->
            next[definition.moduleName]
        }
    }
}

/** Finds each module's payload without relying on any other module's libraries. */
object NativeModuleFileValidator {
    fun hasCompletePayload(context: Context, definition: NativeModuleDefinition): Boolean {
        val directories = linkedSetOf<File>()
        File(context.applicationInfo.nativeLibraryDir).takeIf { it.isDirectory }?.let(directories::add)
        context.applicationInfo.splitSourceDirs?.forEach { splitPath ->
            File(splitPath).parentFile?.let { parent ->
                listOf("arm64-v8a", "arm64").map { File(parent, "lib/$it") }
                    .filter { it.isDirectory }
                    .forEach(directories::add)
            }
        }
        return definition.expectedFiles.all { fileName -> directories.any { File(it, fileName).isFile } }
    }
}
