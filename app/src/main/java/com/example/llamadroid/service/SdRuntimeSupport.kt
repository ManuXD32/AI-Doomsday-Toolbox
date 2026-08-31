package com.example.llamadroid.service

import android.content.Context
import android.os.SystemClock
import com.example.llamadroid.R
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.util.AccelerationWorkload
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.DeviceAcceleration
import com.example.llamadroid.sd.SdPipelineValidationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

internal object SdGenerationProcessLock {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}

internal class SdToolGenerationRunner(
    private val context: Context
) {
    suspend fun generateTxt2Img(
        config: SDConfig,
        onProgress: (SdProgressSnapshot) -> Unit = {},
        onStatus: (String) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        require(config.mode == SDMode.TXT2IMG) { "SD tool generation supports txt2img only." }
        SdGenerationProcessLock.withLock {
            val outputPath = runGenerationWithFallback(config, onProgress, onStatus)
            val outputFile = File(outputPath)
            if (!outputFile.exists() || outputFile.length() <= 0L) {
                throw IllegalStateException(context.getString(R.string.imagegen_error_output_missing))
            }
            outputFile
        }
    }

    private suspend fun runGenerationWithFallback(
        config: SDConfig,
        onProgress: (SdProgressSnapshot) -> Unit,
        onStatus: (String) -> Unit
    ): String {
        val appContext = context.applicationContext
        val binaryRepo = BinaryRepository(appContext)
        val sdBinary = binaryRepo.getSdBinary()
            ?: throw IllegalStateException(context.getString(R.string.video_gen_error_sd_binary_missing))
        if (!sdBinary.exists()) {
            throw IllegalStateException(context.getString(R.string.video_gen_error_sd_binary_missing))
        }

        return runCatching {
            runGenerationWithBinary(config, binaryRepo, sdBinary, onProgress, onStatus)
        }.getOrElse { error ->
            val cpuBinary = binaryRepo.getCpuSdBinary()
            if (shouldRetrySdGenerationOnCpu(sdBinary, cpuBinary, config.distributedRuntime, error)) {
                DebugLog.log("[SdToolGenerationRunner] Accelerator SD binary failed, retrying CPU fallback: ${error.message}")
                runGenerationWithBinary(config, binaryRepo, requireNotNull(cpuBinary), onProgress, onStatus)
            } else {
                throw error
            }
        }
    }

    private suspend fun runGenerationWithBinary(
        config: SDConfig,
        binaryRepo: BinaryRepository,
        sdBinary: File,
        onProgress: (SdProgressSnapshot) -> Unit,
        onStatus: (String) -> Unit
    ): String {
        DeviceAcceleration.reportActiveBinary(AccelerationWorkload.STABLE_DIFFUSION, sdBinary)

        val binaryCapabilities = probeSdBinaryCapabilities(context, sdBinary, binaryRepo)
        val args = mutableListOf(sdBinary.absolutePath)
        val effectiveConfig = config.copy(
            maxVramCpuGiB = effectiveSdMaxVramCpuGiBForBinary(sdBinary, config.maxVramCpuGiB)
        )
        try {
            val pipeline = resolveSdPipelineForLaunch(context, effectiveConfig)
            args.addAll(buildSdCommandArgs(effectiveConfig, pipeline, binaryCapabilities))
        } catch (e: SdIpAdapterConfigurationException) {
            throw SdConfigurationException(sdIpAdapterErrorMessage(context, e))
        } catch (e: SdMissingComponentsException) {
            throw IllegalStateException(context.getString(R.string.imagegen_error_missing_required_components, e.roles.joinToString(", ") { it.name }))
        } catch (e: SdUnsupportedFlagsException) {
            throw IllegalStateException(context.getString(R.string.imagegen_error_binary_missing_flags, e.flags.joinToString(", ")))
        } catch (e: SdUnsupportedModesException) {
            throw IllegalStateException(context.getString(R.string.imagegen_error_binary_missing_modes, e.modes.joinToString(", ")))
        } catch (e: SdPipelineValidationException) {
            throw IllegalStateException(
                sdPipelineIssueMessage(context, e.pipeline.blockingIssues.firstOrNull())
            )
        }

        DebugLog.log("[SdToolGenerationRunner] Running command: ${args.joinToString(" ")}")
        val pb = ProcessBuilder(args)
            .redirectErrorStream(true)
            .directory(sdBinary.parentFile)

        val libDir = File(context.filesDir, "lib").apply { mkdirs() }
        setupSdLibrarySymlinks(sdBinary.parentFile, libDir, sdBinary.absolutePath)
        pb.environment()["LD_LIBRARY_PATH"] = sdProcessLibraryPath(binaryRepo, sdBinary, libDir)

        val progressTracker = SdProgressTracker(
            totalStepsHint = config.steps.coerceAtLeast(1),
            startedAtMs = SystemClock.elapsedRealtime()
        )
        val nativeOutput = SdNativeOutputBuffer()
        val process = pb.start()
        process.inputStream.bufferedReader().use { reader ->
            var line = reader.readLine()
            while (line != null) {
                nativeOutput.add(line)
                DebugLog.log("SD tool: $line")
                onStatus(line)
                progressTracker.update(line, SystemClock.elapsedRealtime())?.let(onProgress)
                line = reader.readLine()
            }
        }

        val exitCode = process.waitFor()
        DebugLog.log("[SdToolGenerationRunner] Process exited with code $exitCode")
        if (exitCode != 0) {
            val failureReport = SdNativeFailureClassifier.classify(
                exitCode = exitCode,
                recentOutput = nativeOutput.snapshot(),
                stage = progressTracker.currentSnapshot()?.phase,
                acceleratorBinary = DeviceAcceleration.isAcceleratorBinary(sdBinary)
            )
            if (failureReport.category == SdFailureCategory.ACCELERATOR_FAILURE) {
                val detail = "Stable Diffusion accelerator ${sdBinary.name} failed with exit code $exitCode."
                DebugLog.log("[SdToolGenerationRunner] $detail")
                DeviceAcceleration.reportRuntimeFailure(AccelerationWorkload.STABLE_DIFFUSION, detail)
            }
            throw SdNativeFailureException(
                report = failureReport,
                message = sdNativeFailureMessage(context, failureReport)
            )
        }

        return config.outputPath
    }
}

internal fun sdProcessLibraryPath(
    binaryRepo: BinaryRepository,
    sdBinary: File,
    libDir: File
): String = buildList {
    add(libDir.absolutePath)
    add(binaryRepo.getLibraryDir())
    if (DeviceAcceleration.isAcceleratorBinary(sdBinary)) {
        DeviceAcceleration.acceleratorLibrarySearchDirs()
            .map { it.absolutePath }
            .forEach(::add)
    }
}.flatMap { it.split(':') }
    .filter { it.isNotBlank() }
    .distinct()
    .joinToString(":")

internal fun shouldRetrySdGenerationOnCpu(
    sdBinary: File,
    cpuBinary: File?,
    distributedRuntime: SdDistributedRuntimeConfig,
    error: Throwable
): Boolean {
    if (error is SdConfigurationException) return false
    if (error is SdNativeFailureException &&
        error.report.category != SdFailureCategory.ACCELERATOR_FAILURE
    ) {
        return false
    }
    if (!DeviceAcceleration.isAcceleratorBinary(sdBinary) ||
        cpuBinary == null ||
        !cpuBinary.exists() ||
        cpuBinary.absolutePath == sdBinary.absolutePath
    ) {
        return false
    }

    if (!distributedRuntime.enabled) {
        return true
    }

    val message = error.message.orEmpty()
    return listOf("--rpc-servers", "--backend", "--split-mode", "--params-backend", "--auto-fit", "--max-vram")
        .any { flag -> message.contains(flag) }
}

private object SdBinaryCapabilityCache {
    var binaryPath: String? = null
    var capabilities: SdBinaryCapabilities? = null
}

internal suspend fun probeSdBinaryCapabilities(
    context: Context,
    sdBinary: File,
    binaryRepo: BinaryRepository
): SdBinaryCapabilities? = withContext(Dispatchers.IO) {
    if (SdBinaryCapabilityCache.binaryPath == sdBinary.absolutePath && SdBinaryCapabilityCache.capabilities != null) {
        return@withContext SdBinaryCapabilityCache.capabilities
    }

    val libDir = File(context.filesDir, "lib").apply { mkdirs() }
    setupSdLibrarySymlinks(sdBinary.parentFile, libDir, sdBinary.absolutePath)
    val envPath = sdProcessLibraryPath(binaryRepo, sdBinary, libDir)

    val helpCapabilities = listOf("--help", "-h").mapNotNull { flag ->
        runCatching {
            val process = ProcessBuilder(sdBinary.absolutePath, flag)
                .redirectErrorStream(true)
                .directory(sdBinary.parentFile)
                .apply {
                    environment()["LD_LIBRARY_PATH"] = envPath
                }
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output.takeIf { it.isNotBlank() }?.let(::parseSdBinaryCapabilities)
        }.getOrNull()
    }
    val capabilities = helpCapabilities.maxByOrNull {
        it.supportedFlags.size + it.supportedModes.size
    } ?: return@withContext null

    capabilities.also {
        SdBinaryCapabilityCache.binaryPath = sdBinary.absolutePath
        SdBinaryCapabilityCache.capabilities = capabilities
    }
}

internal fun inferSdRuntimeTierSuffix(binaryName: String): String = when {
    binaryName.contains("_snapdragon_vulkan") -> "_snapdragon_vulkan"
    binaryName.contains("_snapdragon_opencl") -> "_snapdragon_opencl"
    binaryName.contains("_i8mm") -> "_i8mm"
    binaryName.contains("_armv9") -> "_armv9"
    binaryName.contains("_dotprod") -> "_dotprod"
    binaryName.contains("_baseline") -> "_baseline"
    else -> ""
}

internal fun setupSdLibrarySymlinks(sourceDir: File?, targetDir: File, binaryPath: String) {
    if (sourceDir == null) return

    val binaryName = File(binaryPath).name
    val tier = inferSdRuntimeTierSuffix(binaryName)

    DebugLog.log("StableDiffusion: Inferred tier '$tier' from $binaryName")

    val librariesToLink = listOf(
        "libmtmd.so" to listOf("libmtmd${tier}.so", "libmtmd.so"),
        "libmtmd.so.0" to listOf("libmtmd${tier}.so", "libmtmd.so"),
        "libllama.so" to listOf("libllama.so", "libllama.so.0.so"),
        "libllama.so.0" to listOf("libllama.so.0", "libllama.so", "libllama.so.0.so"),
        "libggml.so" to listOf("libggml.so", "libggml.so.0.so"),
        "libggml.so.0" to listOf("libggml.so.0", "libggml.so", "libggml.so.0.so"),
        "libggml-cpu.so" to listOf("libggml-cpu.so", "libggml-cpu.so.0.so"),
        "libggml-cpu.so.0" to listOf("libggml-cpu.so.0", "libggml-cpu.so", "libggml-cpu.so.0.so"),
        "libggml-base.so" to listOf("libggml-base.so", "libggml-base.so.0.so"),
        "libggml-base.so.0" to listOf("libggml-base.so.0", "libggml-base.so", "libggml-base.so.0.so")
    )

    for ((linkName, sourceCandidates) in librariesToLink) {
        val sourceFile = sourceCandidates
            .map { candidateName -> File(sourceDir, candidateName) }
            .firstOrNull { it.exists() }

        val linkFile = File(targetDir, linkName)
        if (sourceFile != null) {
            try {
                if (linkFile.exists()) {
                    linkFile.delete()
                }
                val result = Runtime.getRuntime()
                    .exec(arrayOf("ln", "-sf", sourceFile.absolutePath, linkFile.absolutePath))
                    .waitFor()
                if (result != 0 || !linkFile.exists()) {
                    sourceFile.copyTo(linkFile, overwrite = true)
                }
            } catch (e: Exception) {
                DebugLog.log("StableDiffusion: Error creating link/copy for $linkName: ${e.message}")
                try {
                    sourceFile.copyTo(linkFile, overwrite = true)
                } catch (_: Exception) {
                }
            }
        }
    }
}
