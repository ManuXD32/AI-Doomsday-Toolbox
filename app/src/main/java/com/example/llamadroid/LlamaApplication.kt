package com.example.llamadroid

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.database.sqlite.SQLiteDatabase
import androidx.work.WorkManager
import com.example.llamadroid.R
import com.example.llamadroid.data.AppContainer
import com.example.llamadroid.data.DefaultAppContainer
import com.example.llamadroid.service.AiRuntimeJobStore
import com.example.llamadroid.service.GenerationDiagnosticsStore
import com.example.llamadroid.service.OrganizerAlarmScheduler
import com.example.llamadroid.service.LlamaScheduledTaskScheduler
import com.example.llamadroid.service.UnifiedNotificationManager
import com.example.llamadroid.util.AssetPackManagerUtil
import com.example.llamadroid.util.DebugLog
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        installCrashBreadcrumbHandler()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runRemovedLlmTrainingCleanupOnce()
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
}
