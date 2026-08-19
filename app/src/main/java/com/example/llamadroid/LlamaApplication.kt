package com.example.llamadroid

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.database.sqlite.SQLiteDatabase
import androidx.work.WorkManager
import com.example.llamadroid.R
import com.example.llamadroid.data.AppContainer
import com.example.llamadroid.data.DefaultAppContainer
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SavedCommandScopes
import com.example.llamadroid.data.db.launchProfile
import com.example.llamadroid.data.db.isLegacyQuadtrixLoraAdapter
import com.example.llamadroid.data.repository.LlamaServerCardRepository
import com.example.llamadroid.data.repository.RoomGeneralSavedCommandProvider
import com.example.llamadroid.data.runtime.AgentLiteRtModelCatalog
import com.example.llamadroid.data.runtime.AgentRuntimeProfileRepositoryFactory
import com.example.llamadroid.data.runtime.AgentRuntimeProfileRuntime
import com.example.llamadroid.data.runtime.DurableLlamaServerCardCatalog
import com.example.llamadroid.data.runtime.LegacyAgentRuntimeSettings
import com.example.llamadroid.onnx.OnnxStorage
import com.example.llamadroid.service.AiRuntimeJobStore
import com.example.llamadroid.service.GenerationDiagnosticsStore
import com.example.llamadroid.service.OrganizerAlarmScheduler
import com.example.llamadroid.service.LlamaScheduledTaskScheduler
import com.example.llamadroid.service.LlamaRuntimeStateProjection
import com.example.llamadroid.service.LlamaService
import com.example.llamadroid.service.LlamaServerSessionStateStore
import com.example.llamadroid.service.UnifiedNotificationManager
import com.example.llamadroid.util.AssetPackManagerUtil
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.wear.PhoneWearGateway
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.system.exitProcess

private const val REMOVED_LLM_TRAINING_CLEANUP_PREFS = "removed_feature_cleanup"
private const val REMOVED_LLM_TRAINING_CLEANUP_DONE = "trainer_cleanup_done_v54"
private const val REMOVED_LLM_TRAINING_WORKER_CLASS = "com.example.llamadroid.service.TrainerRunWorker"
private const val WORK_MANAGER_DB_NAME = "androidx.work.workdb"

class LlamaApplication : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        instance = this  // Safe: Application lives for entire app lifecycle
        container = DefaultAppContainer(this)
        UnifiedNotificationManager.init(this)
        DebugLog.init(this)
        GenerationDiagnosticsStore.init(this)
        if (!isMainProcess()) {
            installCrashBreadcrumbHandler()
            return
        }
        LlamaRuntimeStateProjection.registerMainProcess(this, LlamaService.mutableStateForProjection(), LlamaService.mutableServerLogsForProjection())
        PhoneWearGateway.start(this)
        installCrashBreadcrumbHandler()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runRemovedLlmTrainingCleanupOnce()
            pruneLegacyPortableModelRows()
            normalizeLegacyQuadtrixLoraRows()
            installAgentRuntimeProfiles()
            val staleJobs = AiRuntimeJobStore.markStaleActiveJobsTerminal(this@LlamaApplication)
            runCatching {
                GenerationDiagnosticsStore.recordBreadcrumb(
                    source = "llama_application",
                    event = "startup_runtime_prune",
                    details = "stalePruned=${staleJobs.size}"
                )
            }
            runCatching { OrganizerAlarmScheduler.rescheduleAll(this@LlamaApplication) }
            runCatching { LlamaScheduledTaskScheduler.rescheduleAll(this@LlamaApplication) }
        }
        
        // Request native libs installation immediately (Simulate Fast-Follow)
        // REMOVED: Managed by MainActivity failsafe to avoid double-prompting and race conditions
        // com.example.llamadroid.util.DynamicFeatureManager.installAllFeatures(this)
    }
    
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(updateLocale(base))
        com.google.android.play.core.splitcompat.SplitCompat.install(this)
    }
    
    companion object {
        /**
         * Application instance for global access.
         * Safe because Application lives for entire app lifecycle.
         * Use this instead of storing Activity references.
         */
        lateinit var instance: LlamaApplication
            private set
        
        fun updateLocale(context: Context): Context {
            val prefs = context.getSharedPreferences("llamadroid_settings", Context.MODE_PRIVATE)
            val languageCode = prefs.getString("selected_language", "system") ?: "system"
            
            val locale = when (languageCode) {
                "system" -> Locale.getDefault()
                "en" -> Locale.ENGLISH
                "es" -> Locale("es")
                else -> Locale(languageCode)
            }
            
            Locale.setDefault(locale)
            val config = Configuration(context.resources.configuration)
            config.setLocale(locale)
            
            return context.createConfigurationContext(config)
        }
    }

    private fun isMainProcess(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return true
        return runCatching { android.app.Application.getProcessName() == applicationInfo.processName }.getOrDefault(true)
    }

    private fun installCrashBreadcrumbHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                GenerationDiagnosticsStore.recordBreadcrumb(
                    source = "app_crash",
                    mode = null,
                    event = "uncaught_exception",
                    phase = thread.name,
                    details = "${throwable.javaClass.name}: ${throwable.message ?: "no message"}\n" +
                        throwable.stackTraceToString().take(2048)
                )
            }
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            }
        }
    }

    private fun runRemovedLlmTrainingCleanupOnce() {
        val prefs = getSharedPreferences(REMOVED_LLM_TRAINING_CLEANUP_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(REMOVED_LLM_TRAINING_CLEANUP_DONE, false)) return

        listOf(
            ::cancelRemovedLlmTrainingWork,
            ::deleteRemovedLlmTrainingRuntimeFiles
        ).forEach { cleanupStep ->
            runCatching { cleanupStep() }
                .onFailure { error ->
                    DebugLog.log("[StartupCleanup] Removed trainer cleanup step failed: ${error.message}")
                }
        }

        prefs.edit()
            .putBoolean(REMOVED_LLM_TRAINING_CLEANUP_DONE, true)
            .apply()
    }

    private fun cancelRemovedLlmTrainingWork() {
        val workManager = WorkManager.getInstance(this)
        workManager.cancelAllWorkByTag(REMOVED_LLM_TRAINING_WORKER_CLASS)
        removedLlmTrainingWorkIdsFromWorkDb().forEach { workId ->
            runCatching { workManager.cancelWorkById(UUID.fromString(workId)) }
        }
    }

    private fun removedLlmTrainingWorkIdsFromWorkDb(): List<String> {
        val workDb = getDatabasePath(WORK_MANAGER_DB_NAME)
        if (!workDb.exists()) return emptyList()

        return runCatching {
            SQLiteDatabase.openDatabase(workDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery(
                    """
                    SELECT DISTINCT workspec.id
                    FROM workspec
                    LEFT JOIN worktag ON worktag.work_spec_id = workspec.id
                    WHERE workspec.worker_class_name = ?
                       OR worktag.tag = ?
                       OR worktag.tag LIKE 'trainer:%'
                    """.trimIndent(),
                    arrayOf(REMOVED_LLM_TRAINING_WORKER_CLASS, REMOVED_LLM_TRAINING_WORKER_CLASS)
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(cursor.getString(0))
                        }
                    }
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun deleteRemovedLlmTrainingRuntimeFiles() {
        File(filesDir, "trainer").deleteRecursively()
        listOf(
            File(filesDir, "bin"),
            File(filesDir, "binaries"),
            AssetPackManagerUtil.getBinariesDir(this)
        )
            .distinctBy { it.absolutePath }
            .forEach { dir ->
                dir.listFiles { file ->
                    file.isFile &&
                        file.name.startsWith("libllm-trainer_") &&
                        file.name.endsWith(".so")
                }?.forEach { file ->
                    file.delete()
                }
            }
    }

    private suspend fun pruneLegacyPortableModelRows() {
        val db = AppDatabase.getDatabase(this)
        val onnxRoot = OnnxStorage.managedModelsRoot(this)
        val onnxTypes = listOf(
            ModelType.ONNX_IMAGE_GEN,
            ModelType.ONNX_TTS,
            ModelType.ONNX_BACKGROUND_REMOVAL,
            ModelType.ONNX_IMAGE_UPSCALER
        )
        var removedOnnx = 0
        db.modelDao().getModelsByTypesSync(onnxTypes).forEach { model ->
            if (!isWithinRoot(File(model.path), onnxRoot)) {
                db.modelDao().deleteModel(model)
                removedOnnx += 1
            }
        }
        val liteRtRoot = File(applicationContext.noBackupFilesDir, "litert_models")
        var removedLiteRt = 0
        db.liteRtModelDao().observeAll().first().forEach { model ->
            if (!isWithinRoot(File(model.path), liteRtRoot)) {
                db.liteRtModelDao().deleteById(model.id)
                removedLiteRt += 1
            }
        }
        if (removedOnnx > 0 || removedLiteRt > 0) {
            DebugLog.log(
                "[StartupCleanup] Removed $removedOnnx legacy ONNX row(s) and $removedLiteRt legacy LiteRT row(s); re-import required."
            )
        }
    }

    private suspend fun normalizeLegacyQuadtrixLoraRows() {
        val db = AppDatabase.getDatabase(this)
        db.modelDao()
            .getModelsByTypesSync(listOf(ModelType.QUADTRIX))
            .filter { it.isLegacyQuadtrixLoraAdapter() }
            .forEach { model ->
                db.modelDao().insertModel(model.copy(type = ModelType.LORA))
            }
    }

    private suspend fun installAgentRuntimeProfiles() {
        val db = AppDatabase.getDatabase(this)
        val settings = SettingsRepository(this)
        val cardRepository = LlamaServerCardRepository(
            db.llamaServerCardDao(),
            RoomGeneralSavedCommandProvider(db.savedCommandDao())
        )
        val managedServers = DurableLlamaServerCardCatalog(
            cards = cardRepository.cards,
            stateStore = LlamaServerSessionStateStore(this),
            modelNames = combine(
                cardRepository.cards,
                db.savedCommandDao().getCommandsByScope(SavedCommandScopes.GENERAL)
            ) { cards, commands ->
                    val modelByPreset = commands.associate { command ->
                        command.id to command.launchProfile().modelPath
                            .substringAfterLast('/')
                            .takeIf(String::isNotBlank)
                    }
                    cards.associate { card ->
                        card.id to modelByPreset[card.savedCommandId]
                    }
            }
        )
        val repository = AgentRuntimeProfileRepositoryFactory.create(
            context = this,
            dao = db.agentRuntimeProfileDao(),
            endpointDao = db.agentRuntimeEndpointConfigDao(),
            managedServerCatalog = managedServers
        )
        val liteRtCatalog = object : AgentLiteRtModelCatalog {
            override suspend fun containsModel(id: Long): Boolean =
                db.liteRtModelDao().getById(id) != null
        }
        val customAgents = db.customAgentDao().getAllAgents().first()
        repository.migrateOnce(
            customAgentNames = customAgents.map { it.name },
            legacy = LegacyAgentRuntimeSettings(
                globalBackend = settings.agentBackend.value,
                globalModel = settings.agentOrchestratorModel.value,
                llamaServerUrl = settings.llamaServerUrl.value,
                llamaServerModelLabel = settings.agentLlamaServerModelLabel.value,
                liteRtModelId = settings.agentLiteRtModelId.value.takeIf { it > 0L },
                roleModels = mapOf(
                    "ORCHESTRATOR" to settings.agentOrchestratorModel.value,
                    "CODEBASE_SCOUT" to settings.agentCodebaseScoutModel.value,
                    "RESEARCHER" to settings.agentResearcherModel.value,
                    "PLANNER" to settings.agentPlannerModel.value,
                    "CODER" to settings.agentCoderModel.value,
                    "REVIEWER" to settings.agentReviewerModel.value,
                    "EXECUTOR" to settings.agentExecutorModel.value,
                    "SUMMARIZER" to settings.agentSummarizerModel.value,
                    "VISUAL_TESTER" to settings.agentVisualTesterModel.value
                ),
                customModels = customAgents.associate { it.name to it.model }
            )
        )
        AgentRuntimeProfileRuntime.install(repository, liteRtCatalog)
    }

    private fun isWithinRoot(file: File, root: File): Boolean {
        val filePath = runCatching { file.canonicalFile.absolutePath }.getOrDefault(file.absolutePath)
        val rootPath = runCatching { root.canonicalFile.absolutePath }.getOrDefault(root.absolutePath)
        return filePath == rootPath || filePath.startsWith("$rootPath${File.separator}")
    }
}
