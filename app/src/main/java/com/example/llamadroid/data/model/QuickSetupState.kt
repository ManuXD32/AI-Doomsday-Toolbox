package com.example.llamadroid.data.model

import android.content.Context
import androidx.core.content.edit
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.service.LlamaServerLaunchProfile
import com.example.llamadroid.service.LlamaSpeculativeMode

/** Durable (SharedPreferences) markers for the starter bundle's automatic server creation. */
object QuickSetupState {
    private const val PREFS = "quick_setup_state"
    private const val KEY_REQUESTED = "starter_requested"
    private const val KEY_SERVER_HANDLED = "starter_server_handled"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markRequested(context: Context) {
        prefs(context).edit { putBoolean(KEY_REQUESTED, true) }
    }

    fun isRequested(context: Context): Boolean = prefs(context).getBoolean(KEY_REQUESTED, false)

    fun markServerHandled(context: Context) {
        prefs(context).edit { putBoolean(KEY_SERVER_HANDLED, true) }
    }

    fun isServerHandled(context: Context): Boolean = prefs(context).getBoolean(KEY_SERVER_HANDLED, false)

    /** True while the starter download was requested but the server has not been handled yet. */
    fun isServerPending(context: Context): Boolean = isRequested(context) && !isServerHandled(context)
}

/** Pure decision/profile logic for the starter bundle's managed server (JVM-testable). */
object QuickSetupServerPlanner {
    enum class Decision { SKIP_NOT_REQUESTED, SKIP_ALREADY_HANDLED, WAIT_FOR_FILES, SKIP_EXISTING_SERVER, CREATE }

    /**
     * Idempotent gate. [existingEasyCard] means a managed Easy card already targets the Gemma
     * model (created earlier by the user or a previous run); it is never duplicated or rewritten.
     */
    fun decide(
        requested: Boolean,
        alreadyHandled: Boolean,
        gemmaFilesReady: Boolean,
        existingEasyCard: Boolean
    ): Decision = when {
        !requested -> Decision.SKIP_NOT_REQUESTED
        alreadyHandled -> Decision.SKIP_ALREADY_HANDLED
        !gemmaFilesReady -> Decision.WAIT_FOR_FILES
        existingEasyCard -> Decision.SKIP_EXISTING_SERVER
        else -> Decision.CREATE
    }

    /** All three Gemma files must be present as verified installed rows of their exact types. */
    fun gemmaFilesReady(main: ModelEntity?, mmproj: ModelEntity?, mtp: ModelEntity?): Boolean =
        listOf(
            QuickSetupBundleCatalog.gemmaMain to main,
            QuickSetupBundleCatalog.gemmaMmproj to mmproj,
            QuickSetupBundleCatalog.gemmaMtp to mtp
        ).all { (file, model) ->
            model != null && file.matchesVerifiedInstalledModel(file.installedFilename(""), model)
        }

    /** Easy-chat phone defaults plus vision projector and MTP speculative decoding. */
    fun starterProfile(base: LlamaServerLaunchProfile, mmprojPath: String, draftPath: String): LlamaServerLaunchProfile =
        base.copy(
            mmprojPath = mmprojPath,
            visionEnabled = true,
            parallel = 1,
            speculativeEnabled = true,
            speculativeMode = LlamaSpeculativeMode.DRAFT_MTP.flagValue,
            draftModelPath = draftPath,
            mtpUseDraftModel = true,
            mtpDraftMax = 3,
            mtpDraftMin = 0,
            mtpDraftPMin = 0f,
            draftThreads = base.threads,
            draftThreadsBatch = base.threads
        )
}
