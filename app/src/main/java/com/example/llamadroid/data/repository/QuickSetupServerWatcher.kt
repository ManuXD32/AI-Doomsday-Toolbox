package com.example.llamadroid.data.repository

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.QuickSetupBundleCatalog
import com.example.llamadroid.data.model.QuickSetupServerPlanner
import com.example.llamadroid.data.model.QuickSetupState
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Creates the managed llama.cpp server (MTP + mmproj) for the ADT starter bundle once its Gemma 4
 * files are installed. It watches the durable model table (not a screen callback) from the
 * application scope, so completion is noticed while the app is backgrounded, and re-checks on
 * every process start while the marker is pending. Idempotent via [QuickSetupState] plus the
 * Easy-card lookup. The server is created but not started; Easy chat starts it on demand.
 */
object QuickSetupServerWatcher {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null

    /** Starts the observer when a server is still pending. Safe to call repeatedly. */
    @OptIn(FlowPreview::class)
    @Synchronized
    fun ensureStarted(context: Context) {
        val app = context.applicationContext
        if (!QuickSetupState.isServerPending(app) || job?.isActive == true) return
        job = scope.launch {
            AppDatabase.getDatabase(app).modelDao().getAllModels().debounce(1_500L).collect {
                runCatching { reconcile(app) }
                    .onFailure { DebugLog.log("[QUICK_SETUP] server reconcile failed: ${it.message}") }
                if (!QuickSetupState.isServerPending(app)) throw kotlinx.coroutines.CancellationException("done")
            }
        }
    }

    suspend fun reconcile(context: Context) = mutex.withLock {
        val app = context.applicationContext
        val database = AppDatabase.getDatabase(app)
        val dao = database.modelDao()
        val main = QuickSetupBundleCatalog.gemmaMain
        val mainName = main.installedFilename("")
        val mainRow = dao.getModelByFilename(mainName)
        val ready = QuickSetupServerPlanner.gemmaFilesReady(
            mainRow,
            dao.getModelByFilename(QuickSetupBundleCatalog.gemmaMmproj.installedFilename("")),
            dao.getModelByFilename(QuickSetupBundleCatalog.gemmaMtp.installedFilename(""))
        )
        val decision = QuickSetupServerPlanner.decide(
            requested = QuickSetupState.isRequested(app),
            alreadyHandled = QuickSetupState.isServerHandled(app),
            gemmaFilesReady = ready,
            existingEasyCard = ready && database.llamaServerCardDao().getEasyCard(mainName) != null
        )
        when (decision) {
            QuickSetupServerPlanner.Decision.CREATE -> {
                val mmproj = dao.getModelByFilename(QuickSetupBundleCatalog.gemmaMmproj.installedFilename(""))!!.path
                val draft = dao.getModelByFilename(QuickSetupBundleCatalog.gemmaMtp.installedFilename(""))!!.path
                EasyLlamaChatCoordinator(app, database).createOrReuse(
                    modelId = mainName,
                    name = app.getString(R.string.quick_setup_server_name)
                ) { base -> QuickSetupServerPlanner.starterProfile(base, mmproj, draft) }
                QuickSetupState.markServerHandled(app)
                DebugLog.log("[QUICK_SETUP] managed MTP server created for $mainName")
            }
            QuickSetupServerPlanner.Decision.SKIP_EXISTING_SERVER -> QuickSetupState.markServerHandled(app)
            else -> Unit
        }
        decision
    }
}
