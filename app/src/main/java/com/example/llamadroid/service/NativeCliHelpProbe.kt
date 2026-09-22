package com.example.llamadroid.service

import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Stable identity for a native executable.  A path alone is insufficient because the native
 * payload can be replaced in place by a refreshed split or custom package.
 */
internal data class NativeCliBinaryIdentity(
    val absolutePath: String,
    val length: Long,
    val lastModified: Long
) {
    companion object {
        fun from(binary: File): NativeCliBinaryIdentity = NativeCliBinaryIdentity(
            absolutePath = binary.absolutePath,
            length = binary.length(),
            lastModified = binary.lastModified()
        )
    }
}

/**
 * Bounded, shared help probing for native command line tools.
 *
 * `--help` is preferred because current upstream tools document it.  Older custom payloads that
 * reject it fall back to `-h`.  The raw surfaces are cached by binary identity so Whisper and
 * Stable Diffusion callers do not repeatedly start the same executable.
 */
internal object NativeCliHelpProbe {
    private const val DEFAULT_TIMEOUT_MS = 10_000L
    private const val FORCE_TIMEOUT_MS = 2_000L
    private const val READER_JOIN_MS = 2_000L
    // llama-server's help includes optional speculative/delegated-server sections near the end.
    // Keep the probe bounded, but large enough that those app-visible flags are not silently
    // hidden by the cache.
    private const val DEFAULT_MAX_OUTPUT_CHARS = 256 * 1024

    private val cache = mutableMapOf<NativeCliBinaryIdentity, List<String>>()

    @Synchronized
    fun clear() {
        cache.clear()
    }

    @Synchronized
    fun clear(binary: File) {
        val path = binary.absolutePath
        cache.keys.removeAll { it.absolutePath == path }
    }

    fun probe(
        binary: File,
        workingDirectory: File,
        environment: Map<String, String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS
    ): List<String> {
        require(timeoutMs > 0L) { "Native help probe timeout must be positive" }
        require(maxOutputChars > 0) { "Native help probe output limit must be positive" }

        val identity = NativeCliBinaryIdentity.from(binary)
        synchronized(this) {
            cache[identity]?.let { return it }
        }

        val longHelp = runHelp(
            binary = binary,
            flag = "--help",
            workingDirectory = workingDirectory,
            environment = environment,
            timeoutMs = timeoutMs,
            forceTimeoutMs = FORCE_TIMEOUT_MS,
            readerJoinMs = READER_JOIN_MS,
            maxOutputChars = maxOutputChars
        )
        // Current upstream tools print complete help for --help.  A custom/older payload may
        // reject it and only implement -h, so retain the fallback when the first surface does
        // not resemble help.  Avoiding the second process matters on Android and removes the
        // duplicate SD probe that used to dominate startup.
        val surfaces = if (longHelp != null && looksLikeHelpSurface(longHelp)) {
            listOf(longHelp)
        } else {
            listOfNotNull(
                longHelp,
                runHelp(
                    binary = binary,
                    flag = "-h",
                    workingDirectory = workingDirectory,
                    environment = environment,
                    timeoutMs = timeoutMs,
                    forceTimeoutMs = FORCE_TIMEOUT_MS,
                    readerJoinMs = READER_JOIN_MS,
                    maxOutputChars = maxOutputChars
                )
            )
        }
        if (surfaces.isNotEmpty()) {
            synchronized(this) {
                // A replacement during probing must not store the old surface under the new
                // identity.  Leave both identities uncached; the next call probes the new file.
                val finalIdentity = NativeCliBinaryIdentity.from(binary)
                if (finalIdentity == identity) {
                    cache[identity] = surfaces
                }
            }
        }
        return surfaces
    }

    private fun looksLikeHelpSurface(surface: String): Boolean {
        val normalized = surface.lowercase(Locale.ROOT)
        if (listOf("unknown option", "unrecognized option", "invalid option")
                .any { normalized.contains(it) }
        ) {
            return false
        }
        if (Regex("\\busage\\s*:").containsMatchIn(normalized) ||
            Regex("\\b(options?|flags?|arguments?)\\s*:").containsMatchIn(normalized)
        ) {
            return true
        }
        // Small custom wrappers often omit headings but still enumerate two or more options.
        return Regex("(?:^|\\s)--[a-zA-Z][a-zA-Z0-9-]*").findAll(surface).count() >= 2
    }

    private fun runHelp(
        binary: File,
        flag: String,
        workingDirectory: File,
        environment: Map<String, String>,
        timeoutMs: Long,
        forceTimeoutMs: Long,
        readerJoinMs: Long,
        maxOutputChars: Int
    ): String? {
        val process = runCatching {
            ProcessBuilder(binary.absolutePath, flag)
                .directory(workingDirectory)
                .redirectErrorStream(true)
                .apply { environment().putAll(environment) }
                .start()
        }.getOrNull() ?: return null

        val output = StringBuilder()
        val reader = Thread({
            runCatching {
                process.inputStream.bufferedReader().use { stream ->
                    val buffer = CharArray(4 * 1024)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        synchronized(output) {
                            val remaining = maxOutputChars - output.length
                            if (remaining > 0) {
                                output.append(buffer, 0, minOf(read, remaining))
                            }
                        }
                    }
                }
            }
        }, "native-cli-help-reader").apply {
            isDaemon = true
            start()
        }

        try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroy()
                if (!process.waitFor(forceTimeoutMs, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(forceTimeoutMs, TimeUnit.MILLISECONDS)
                }
            }
            reader.join(readerJoinMs)
            return synchronized(output) {
                output.toString().takeIf { it.isNotBlank() }
            }
        } finally {
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            if (process.isAlive) runCatching { process.destroyForcibly() }
            if (reader.isAlive) reader.interrupt()
        }
    }
}
