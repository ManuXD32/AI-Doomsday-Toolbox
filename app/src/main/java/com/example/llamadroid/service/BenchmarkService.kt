package com.example.llamadroid.service

import android.annotation.SuppressLint
import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.BenchmarkResult
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.WakeLockManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Benchmark service that runs llama-bench to measure tokens/second with different thread counts.
 * Uses the actual llama-bench binary to get real performance measurements.
 * Results are saved to database for persistence.
 * 
 * Progress output format:
 * | llama-bench: benchmark 1/2: prompt run 1/5 |
 * | llama-bench: benchmark 2/2: generation run 3/5 |
 * 
 * Table format (8 columns):
 * | model | size | params | backend | threads | mmap | test | t/s |
 */
class BenchmarkService(context: Context) {
    private val context = context.applicationContext
    
    companion object {
        // Global state for running status (persists across navigation)
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
        
        private val _progressText = MutableStateFlow("")
        val progressText: StateFlow<String> = _progressText.asStateFlow()
        
        private val _progress = MutableStateFlow(0f)
        val progress: StateFlow<Float> = _progress.asStateFlow()

        private val _runningModelPath = MutableStateFlow<String?>(null)
        val runningModelPath: StateFlow<String?> = _runningModelPath.asStateFlow()

        private val _currentRunStartedAt = MutableStateFlow<Long?>(null)
        val currentRunStartedAt: StateFlow<Long?> = _currentRunStartedAt.asStateFlow()
        
        // Service-level scope that survives navigation
        private val serviceScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )
        
        // Current benchmark job
        private var benchmarkJob: kotlinx.coroutines.Job? = null
        
        // Singleton instance for the current benchmark
        // The singleton survives navigation and BenchmarkService stores applicationContext only.
        @SuppressLint("StaticFieldLeak")
        private var instance: BenchmarkService? = null
        
        fun cancel() {
            instance?.cancelInternal()
            benchmarkJob?.cancel()
            benchmarkJob = null
            _isRunning.value = false
            instance?.context?.getString(R.string.benchmark_progress_cancelled)?.let { _progressText.value = it }
            _runningModelPath.value = null
            _currentRunStartedAt.value = null
        }
    }
    
    private val db = AppDatabase.getDatabase(context)
    private var notificationTaskId: Int? = null
    private var currentProcess: Process? = null
    
    @Volatile
    private var isCancelled = false
    
    data class SingleResult(
        val threads: Int,
        val promptTokensPerSecond: Float,
        val genTokensPerSecond: Float,
        val totalTimeMs: Long,
        val success: Boolean,
        val error: String? = null
    )

    data class QueuedModel(
        val modelPath: String,
        val modelName: String = modelPath.substringAfterLast("/")
    )
    
    /**
     * Internal cancel method.
     */
    private fun cancelInternal() {
        isCancelled = true
        currentProcess?.destroyForcibly()
        currentProcess = null
        DebugLog.log("Benchmark: Cancelled by user")
    }
    
    /**
     * Start a benchmark in the service scope (survives navigation).
     */
    fun startBenchmark(
        modelPath: String,
        minThreads: Int,
        maxThreads: Int,
        promptTokens: Int,
        genTokens: Int,
        runName: String,
        waitBetweenTestsSeconds: Int = 0
    ) {
        // Cancel any existing job
        benchmarkJob?.cancel()
        instance = this
        
        benchmarkJob = serviceScope.launch {
            runFullBenchmark(
                modelPath = modelPath,
                minThreads = minThreads,
                maxThreads = maxThreads,
                promptTokens = promptTokens,
                genTokens = genTokens,
                runName = runName,
                waitBetweenTestsSeconds = waitBetweenTestsSeconds,
                onProgress = { _, _ -> }  // Progress handled by global state
            )
        }
    }

    fun startBenchmarkQueue(
        models: List<QueuedModel>,
        minThreads: Int,
        maxThreads: Int,
        promptTokens: Int,
        genTokens: Int,
        runName: String,
        waitBetweenTestsSeconds: Int = 0
    ) {
        val queuedModels = models.distinctBy { it.modelPath }.filter { it.modelPath.isNotBlank() }
        if (queuedModels.isEmpty()) return

        benchmarkJob?.cancel()
        instance = this

        benchmarkJob = serviceScope.launch {
            isCancelled = false
            _isRunning.value = true
            _progressText.value = context.getString(R.string.benchmark_progress_starting)
            _progress.value = 0f

            try {
                for ((index, model) in queuedModels.withIndex()) {
                    if (isCancelled || !coroutineContext.isActive) break

                    val runStartedAt = System.currentTimeMillis()
                    val timestamp = queueTimestampFormatter().format(Date(runStartedAt))
                    val queueRunName = context.getString(
                        R.string.benchmark_queue_run_name_format,
                        runName.trim().ifBlank { context.getString(R.string.benchmark_untitled_run) },
                        model.modelName,
                        timestamp
                    )
                    val progressBase = index.toFloat() / queuedModels.size.toFloat()
                    val progressSpan = 1f / queuedModels.size.toFloat()

                    runFullBenchmark(
                        modelPath = model.modelPath,
                        displayModelName = model.modelName,
                        minThreads = minThreads,
                        maxThreads = maxThreads,
                        promptTokens = promptTokens,
                        genTokens = genTokens,
                        runName = queueRunName,
                        waitBetweenTestsSeconds = waitBetweenTestsSeconds,
                        runStartedAtOverride = runStartedAt,
                        resetCancel = index == 0,
                        finalizeState = false,
                        progressBase = progressBase,
                        progressSpan = progressSpan,
                        queueIndex = index + 1,
                        queueTotal = queuedModels.size,
                        onProgress = { _, _ -> }
                    )
                }

                _progressText.value = if (isCancelled) {
                    context.getString(R.string.benchmark_progress_cancelled)
                } else {
                    context.getString(R.string.benchmark_progress_complete)
                }
                _progress.value = 1f
            } finally {
                _isRunning.value = false
                _runningModelPath.value = null
                _currentRunStartedAt.value = null
                currentProcess = null
            }
        }
    }
    
    /**
     * Run benchmark for a single thread count with progress updates.
     */
    suspend fun runBenchmark(
        modelPath: String,
        threads: Int,
        promptTokens: Int = 512,
        genTokens: Int = 128,
        repetitions: Int = 3,
        onProgress: (String, Float) -> Unit = { _, _ -> }
    ): SingleResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        
        if (isCancelled || !coroutineContext.isActive) {
            return@withContext SingleResult(
                threads = threads,
                promptTokensPerSecond = 0f,
                genTokensPerSecond = 0f,
                totalTimeMs = 0,
                success = false,
                error = "Cancelled"
            )
        }
        
        try {
            val binaryRepo = BinaryRepository(context)
            
            val binaryFile = binaryRepo.getLlamaBenchBinary()
            if (binaryFile == null || !binaryFile.exists()) {
                return@withContext SingleResult(
                    threads = threads,
                    promptTokensPerSecond = 0f,
                    genTokensPerSecond = 0f,
                    totalTimeMs = 0,
                    success = false,
                    error = "llama-bench binary not found"
                )
            }
            
            val args = mutableListOf(
                binaryFile.absolutePath,
                "-m", modelPath,
                "-t", threads.toString(),
                "-p", promptTokens.toString(),
                "-n", genTokens.toString(),
                "-r", repetitions.toString(),
                "-mmp", "0",         // disable mmap
                "-ngl", "0",         // CPU only
                "--progress",        // Enable progress output
                "-o", "md"
            )
            
            DebugLog.log("Benchmark: Running with $threads threads")
            
            val pb = ProcessBuilder(args)
            pb.redirectErrorStream(true)
            pb.directory(context.filesDir)
            
            val nativeLibDir = binaryFile.parentFile
            val libDir = File(context.filesDir, "lib")
            libDir.mkdirs()
            val ldPath = "${libDir.absolutePath}:${nativeLibDir?.absolutePath ?: ""}"
            pb.environment()["LD_LIBRARY_PATH"] = ldPath
            pb.environment()["HOME"] = context.filesDir.absolutePath
            pb.environment()["GGML_BACKEND_PATH"] = ""
            
            val process = pb.start()
            currentProcess = process
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            
            var promptTokPerSec = 0f
            var genTokPerSec = 0f
            var line: String?
            
            while (reader.readLine().also { line = it } != null && !isCancelled) {
                val currentLine = line ?: continue
                DebugLog.log("Benchmark: $currentLine")

                // Parse progress: "llama-bench: benchmark 1/2: prompt run 2/5"
                val progressMatch = Regex(
                    """benchmark (\d+)/(\d+):\s*(warmup\s+)?(prompt|generation)\s+run\s*(\d+)?/?(\d+)?"""
                ).find(currentLine)

                if (progressMatch != null) {
                    val benchNum = progressMatch.groupValues[1].toIntOrNull() ?: 1
                    val benchTotal = progressMatch.groupValues[2].toIntOrNull() ?: 2
                    val isWarmup = progressMatch.groupValues[3].isNotEmpty()
                    val phase = progressMatch.groupValues[4]
                    val runNum = progressMatch.groupValues[5].toIntOrNull() ?: 0
                    val runTotal = progressMatch.groupValues[6].toIntOrNull() ?: repetitions

                    val overallProgress = if (isWarmup) {
                        ((benchNum - 1).toFloat() / benchTotal.toFloat())
                    } else {
                        ((benchNum - 1).toFloat() + (runNum.toFloat() / runTotal.toFloat())) / benchTotal.toFloat()
                    }
                    
                    val localizedPhase = when (phase) {
                        "prompt" -> context.getString(R.string.benchmark_phase_prompt)
                        "generation" -> context.getString(R.string.benchmark_phase_generation)
                        else -> phase
                    }
                    val phaseText = if (isWarmup) {
                        context.getString(R.string.benchmark_phase_warmup, localizedPhase)
                    } else {
                        context.getString(R.string.benchmark_phase_run, localizedPhase, runNum, runTotal)
                    }
                    onProgress(context.getString(R.string.benchmark_progress_thread_phase, threads, phaseText), overallProgress)
                }

                // Parse result table row
                // Format: | model | size | params | backend | threads | mmap | test | t/s |
                // Example: | llama 1B IQ3_S mix - 3.66 bpw | 619.37 MiB | 1.24 B | CPU | 1 | 0 | pp512 | 7.20 ± 0.88 |
                if (currentLine.startsWith("|") &&
                    !currentLine.contains("model") &&
                    !currentLine.contains("---") &&
                    !currentLine.contains("llama-bench:") &&
                    (currentLine.contains("pp") || currentLine.contains("tg"))) {

                    val parts = currentLine.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                    DebugLog.log("Benchmark: Parsed ${parts.size} columns: $parts")

                    // Find test and t/s columns (last two meaningful columns)
                    if (parts.size >= 2) {
                        // test is second-to-last, t/s is last
                        val testCol = parts[parts.size - 2]
                        val speedStr = parts[parts.size - 1]

                        // Parse speed (format: "7.20 ± 0.88" or just "7.20")
                        val speed = speedStr.split("±").firstOrNull()?.trim()?.toFloatOrNull()
                        
                        DebugLog.log("Benchmark: test=$testCol, speed=$speed")
                        
                        if (speed != null) {
                            when {
                                testCol.contains("pp") -> {
                                    promptTokPerSec = speed
                                    DebugLog.log("Benchmark: Set pp=$speed")
                                }
                                testCol.contains("tg") -> {
                                    genTokPerSec = speed
                                    DebugLog.log("Benchmark: Set tg=$speed")
                                }
                            }
                        }
                    }
                }
            }
            
            if (isCancelled) {
                process.destroyForcibly()
                return@withContext SingleResult(
                    threads = threads,
                    promptTokensPerSecond = 0f,
                    genTokensPerSecond = 0f,
                    totalTimeMs = System.currentTimeMillis() - startTime,
                    success = false,
                    error = "Cancelled"
                )
            }
            
            process.waitFor()
            currentProcess = null
            val totalTime = System.currentTimeMillis() - startTime
            
            DebugLog.log("Benchmark: $threads threads - pp: $promptTokPerSec, tg: $genTokPerSec")
            
            SingleResult(
                threads = threads,
                promptTokensPerSecond = promptTokPerSec,
                genTokensPerSecond = genTokPerSec,
                totalTimeMs = totalTime,
                success = genTokPerSec > 0 || promptTokPerSec > 0
            )
            
        } catch (e: Exception) {
            DebugLog.log("Benchmark error: ${e.message}")
            SingleResult(
                threads = threads,
                promptTokensPerSecond = 0f,
                genTokensPerSecond = 0f,
                totalTimeMs = System.currentTimeMillis() - startTime,
                success = false,
                error = e.message
            )
        }
    }
    
    /**
     * Run benchmarks for a range of threads and save to database.
     */
    suspend fun runFullBenchmark(
        modelPath: String,
        displayModelName: String = modelPath.substringAfterLast("/"),
        minThreads: Int,
        maxThreads: Int,
        promptTokens: Int = 512,
        genTokens: Int = 128,
        runName: String = "",
        waitBetweenTestsSeconds: Int = 0,
        runStartedAtOverride: Long? = null,
        resetCancel: Boolean = true,
        finalizeState: Boolean = true,
        progressBase: Float = 0f,
        progressSpan: Float = 1f,
        queueIndex: Int = 1,
        queueTotal: Int = 1,
        onProgress: (String, Float) -> Unit
    ): List<BenchmarkResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<BenchmarkResult>()
        val modelName = displayModelName.ifBlank { modelPath.substringAfterLast("/") }
        val safeMinThreads = minOf(minThreads, maxThreads).coerceAtLeast(1)
        val safeMaxThreads = maxOf(minThreads, maxThreads).coerceAtLeast(safeMinThreads)
        val totalTests = safeMaxThreads - safeMinThreads + 1
        val runStartedAt = runStartedAtOverride ?: System.currentTimeMillis()
        val trimmedRunName = runName.trim()
        val safeProgressSpan = progressSpan.coerceIn(0f, 1f)
        val safeWaitBetweenTestsSeconds = waitBetweenTestsSeconds.coerceAtLeast(0)

        fun scaleProgress(localProgress: Float): Float {
            return (progressBase + localProgress.coerceIn(0f, 1f) * safeProgressSpan).coerceIn(0f, 1f)
        }

        fun decorateStatus(status: String): String {
            return if (queueTotal > 1) {
                context.getString(R.string.benchmark_queue_progress_model, queueIndex, queueTotal, modelName, status)
            } else {
                status
            }
        }
        
        // Reset cancel flag and set global running state
        if (resetCancel) {
            isCancelled = false
        }
        _isRunning.value = true
        _progressText.value = decorateStatus(context.getString(R.string.benchmark_progress_starting))
        _progress.value = scaleProgress(0f)
        _runningModelPath.value = modelPath
        _currentRunStartedAt.value = runStartedAt
        
        // Acquire wakelock to prevent sleep during benchmark
        WakeLockManager.acquire(context, "Benchmark")
        
        try {
            // Start notification
            val (taskId, _) = UnifiedNotificationManager.startTaskForForeground(
                UnifiedNotificationManager.TaskType.BENCHMARK,
                context.getString(R.string.benchmark_notification_title, modelName)
            )
            notificationTaskId = taskId
        
            for (threads in safeMinThreads..safeMaxThreads) {
                if (isCancelled) {
                    DebugLog.log("Benchmark: Stopping at thread $threads (cancelled)")
                    break
                }
                
                val testNum = threads - safeMinThreads + 1
                val overallProgress = (testNum - 1).toFloat() / totalTests.toFloat()
                
                // Update global and callback progress
                val threadStatus = context.getString(R.string.benchmark_progress_thread_count, threads, testNum, totalTests)
                val decoratedThreadStatus = decorateStatus(threadStatus)
                val scaledOverallProgress = scaleProgress(overallProgress)
                _progressText.value = decoratedThreadStatus
                _progress.value = scaledOverallProgress
                onProgress(decoratedThreadStatus, scaledOverallProgress)
                UnifiedNotificationManager.updateProgress(
                    taskId,
                    overallProgress,
                    context.getString(R.string.benchmark_progress_testing_threads, threads)
                )
                
                val result = runBenchmark(
                    modelPath = modelPath,
                    threads = threads,
                    promptTokens = promptTokens,
                    genTokens = genTokens,
                    onProgress = { status, threadProgress ->
                        val combined = overallProgress + (threadProgress / totalTests)
                        val scaledCombined = scaleProgress(combined)
                        val decoratedStatus = decorateStatus(status)
                        _progressText.value = decoratedStatus
                        _progress.value = scaledCombined
                        onProgress(decoratedStatus, scaledCombined)
                        UnifiedNotificationManager.updateProgress(taskId, combined, status)
                    }
                )
                
                if (result.success) {
                    val dbResult = BenchmarkResult(
                        modelPath = modelPath,
                        modelName = modelName,
                        threads = threads,
                        promptTokensPerSecond = result.promptTokensPerSecond,
                        genTokensPerSecond = result.genTokensPerSecond,
                        promptTokens = promptTokens,
                        genTokens = genTokens,
                        runStartedAt = runStartedAt,
                        runName = trimmedRunName
                    )
                    db.benchmarkDao().insert(dbResult)
                    results.add(dbResult)
                    DebugLog.log("Benchmark: Saved result for $threads threads: pp=${result.promptTokensPerSecond}, tg=${result.genTokensPerSecond}")
                }

                if (shouldWaitBetweenThreadTests(safeWaitBetweenTestsSeconds, threads, safeMaxThreads, isCancelled)) {
                    val waitStatus = context.getString(R.string.benchmark_progress_cooling_down, safeWaitBetweenTestsSeconds)
                    val decoratedWaitStatus = decorateStatus(waitStatus)
                    _progressText.value = decoratedWaitStatus
                    _progress.value = scaleProgress(overallProgress + (1f / totalTests.toFloat()))
                    onProgress(decoratedWaitStatus, _progress.value)
                    UnifiedNotificationManager.updateProgress(
                        taskId,
                        overallProgress + (1f / totalTests.toFloat()),
                        waitStatus
                    )
                    waitForCooldown(safeWaitBetweenTestsSeconds)
                }
            }
            
            // Complete notification
            val message = if (isCancelled) {
                context.getString(R.string.benchmark_progress_cancelled)
            } else {
                context.getString(R.string.benchmark_progress_complete)
            }
            UnifiedNotificationManager.completeTask(taskId, message)
            
            results
        } finally {
            // Release wakelock and reset global state
            WakeLockManager.release("Benchmark")
            if (finalizeState) {
                _isRunning.value = false
                _runningModelPath.value = null
                _currentRunStartedAt.value = null
            }
            currentProcess = null
        }
    }

    private fun queueTimestampFormatter(): SimpleDateFormat {
        return SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }

    private suspend fun waitForCooldown(seconds: Int) {
        repeat(seconds.coerceAtLeast(0)) {
            if (isCancelled || !coroutineContext.isActive) return
            delay(1000L)
        }
    }
}

internal fun shouldWaitBetweenThreadTests(
    waitBetweenTestsSeconds: Int,
    currentThreads: Int,
    maxThreads: Int,
    isCancelled: Boolean
): Boolean {
    return waitBetweenTestsSeconds > 0 && currentThreads < maxThreads && !isCancelled
}
