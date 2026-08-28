package com.example.llamadroid.service

import java.io.File
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

private val WHISPER_HELP_FLAG_REGEX =
    Regex("""(?<![A-Za-z0-9_-])(--[A-Za-z0-9][A-Za-z0-9_-]*|-[A-Za-z])(?![A-Za-z0-9_-])""")

data class WhisperBinaryCapabilities(
    val supportedFlags: Set<String>,
    val allowAll: Boolean = false
) {
    fun supports(flag: String): Boolean = allowAll || supportedFlags.contains(flag)

    companion object {
        val ALLOW_ALL = WhisperBinaryCapabilities(emptySet(), allowAll = true)
    }
}

class WhisperUnsupportedFlagsException(
    val flags: List<String>
) : IllegalStateException(
    "Unsupported whisper.cpp flags: ${flags.joinToString(", ")}"
)

data class WhisperInvocationRequest(
    val binaryPath: String,
    val modelPath: String,
    val audioPath: String,
    val language: String,
    val threads: Int,
    val translate: Boolean,
    val outputFormats: Set<WhisperOutputFormat>,
    val outputBasePath: String,
    val purpose: WhisperInvocationPurpose,
    val vad: WhisperVadConfig
)

fun parseWhisperBinaryCapabilities(helpText: String): WhisperBinaryCapabilities =
    WhisperBinaryCapabilities(
        WHISPER_HELP_FLAG_REGEX.findAll(helpText).map { it.value }.toSet()
    )

object WhisperBinaryCapabilityCache {
    private val cache = mutableMapOf<String, WhisperBinaryCapabilities>()

    @Synchronized
    fun clear() {
        cache.clear()
    }

    fun capabilitiesFor(
        binary: File,
        workingDirectory: File,
        environment: Map<String, String>
    ): WhisperBinaryCapabilities {
        val key = listOf(
            binary.absolutePath,
            binary.length().toString(),
            binary.lastModified().toString()
        ).joinToString("|")
        synchronized(this) {
            cache[key]?.let { return it }
        }

        val processBuilder = ProcessBuilder(binary.absolutePath, "--help")
            .directory(workingDirectory)
            .redirectErrorStream(true)
        processBuilder.environment().putAll(environment)
        val process = processBuilder.start()
        val output = StringBuilder()
        val readerThread = Thread(
            {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> output.appendLine(line) }
                }
            },
            "whisper-capability-reader"
        ).apply { isDaemon = true; start() }
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            readerThread.join(1_000)
            throw IllegalStateException("Timed out while reading whisper.cpp capabilities")
        }
        readerThread.join()
        val parsed = parseWhisperBinaryCapabilities(output.toString())
        if (parsed.supportedFlags.isEmpty()) {
            throw IllegalStateException("whisper.cpp did not expose a readable help surface")
        }
        synchronized(this) {
            cache[key] = parsed
        }
        return parsed
    }
}

fun whisperCpuEnvironment(
    libraryPath: String,
    homeDirectory: File,
    temporaryDirectory: File
): Map<String, String> = mapOf(
    "LD_LIBRARY_PATH" to libraryPath,
    "GGML_BACKEND_PATH" to "/dev/null",
    "HOME" to homeDirectory.absolutePath,
    "TMPDIR" to temporaryDirectory.absolutePath
)

fun buildWhisperInvocationArgs(
    request: WhisperInvocationRequest,
    binaryCapabilities: WhisperBinaryCapabilities? = null
): List<String> {
    require(request.binaryPath.isNotBlank()) { "Missing whisper.cpp binary path" }
    require(request.modelPath.isNotBlank()) { "Missing Whisper model path" }
    require(request.audioPath.isNotBlank()) { "Missing Whisper audio path" }
    require(request.outputBasePath.isNotBlank()) { "Missing Whisper output path" }
    require(request.threads > 0) { "Whisper thread count must be positive" }

    val formats = request.outputFormats.ifEmpty { setOf(WhisperOutputFormat.TXT) }
    val effectiveVad = request.vad.forPurpose(request.purpose)
    val missingFlags = mutableSetOf<String>()

    fun requireFlag(flag: String) {
        val capabilities = binaryCapabilities ?: return
        if (!capabilities.supports(flag)) {
            missingFlags += flag
        }
    }

    val args = mutableListOf<String>()
    args += request.binaryPath
    args += listOf("-m", request.modelPath)
    args += listOf("-f", request.audioPath)
    args += listOf("-l", request.language.trim().ifBlank { "auto" })
    args += listOf("-t", request.threads.toString())
    if (request.translate) args += "-tr"

    // Android remains CPU-only until a separately packaged and benchmarked Whisper GPU backend exists.
    args += "--no-gpu"

    formats.sortedBy { it.ordinal }.forEach { args += it.cliFlag }
    args += listOf("-of", request.outputBasePath)

    if (effectiveVad.enabled) {
        val vadModelPath = effectiveVad.modelPath
            ?: throw WhisperVadUnavailableException(
                "Voice activity detection is enabled, but no Whisper VAD model path is available."
            )
        listOf(
            "--vad",
            "--vad-model",
            "--vad-threshold",
            "--vad-min-speech-duration-ms",
            "--vad-min-silence-duration-ms",
            "--vad-speech-pad-ms",
            "--vad-samples-overlap"
        ).forEach(::requireFlag)
        if (effectiveVad.maxSpeechDurationSeconds != null) {
            requireFlag("--vad-max-speech-duration-s")
        }

        args += "--vad"
        args += listOf("--vad-model", vadModelPath)
        args += listOf("--vad-threshold", effectiveVad.threshold.toCliNumber())
        args += listOf(
            "--vad-min-speech-duration-ms",
            effectiveVad.minSpeechDurationMs.toString()
        )
        args += listOf(
            "--vad-min-silence-duration-ms",
            effectiveVad.minSilenceDurationMs.toString()
        )
        effectiveVad.maxSpeechDurationSeconds?.let { seconds ->
            args += listOf("--vad-max-speech-duration-s", seconds.toCliNumber())
        }
        args += listOf("--vad-speech-pad-ms", effectiveVad.speechPaddingMs.toString())
        args += listOf("--vad-samples-overlap", effectiveVad.samplesOverlap.toCliNumber())
    }

    if (missingFlags.isNotEmpty()) {
        throw WhisperUnsupportedFlagsException(missingFlags.toList().sorted())
    }
    return args
}

private fun Float.toCliNumber(): String =
    BigDecimal(toString()).stripTrailingZeros().toPlainString()
