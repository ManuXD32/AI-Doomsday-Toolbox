package com.example.llamadroid.service

import java.io.File

private val LLAMA_HELP_FLAG_REGEX =
    Regex(
        """(?<![A-Za-z0-9_-])(?:--[A-Za-z][A-Za-z0-9_-]*|-[A-Za-z][A-Za-z0-9_-]*)(?![A-Za-z0-9_-])"""
    )

/** Flags advertised by one llama-server executable. */
data class LlamaBinaryCapabilities(
    val supportedFlags: Set<String>,
    val allowAll: Boolean = false
) {
    fun supports(flag: String): Boolean = allowAll || supportedFlags.contains(flag)

    /**
     * Prefer the short spelling used by the app's historical command line, while accepting a
     * long-only custom build when help output proves that spelling is available.
     */
    fun preferredFlag(longFlag: String, shortFlag: String): String = when {
        allowAll || supports(shortFlag) -> shortFlag
        supports(longFlag) -> longFlag
        else -> shortFlag
    }

    companion object {
        val ALLOW_ALL = LlamaBinaryCapabilities(emptySet(), allowAll = true)
    }
}

fun parseLlamaBinaryCapabilities(helpText: String): LlamaBinaryCapabilities =
    LlamaBinaryCapabilities(
        supportedFlags = LLAMA_HELP_FLAG_REGEX.findAll(helpText).map { it.value }.toSet()
    )

/**
 * Shares the bounded native help surface with the other packaged CLI tools. A failed probe is
 * deliberately recoverable: imported binaries have historically been allowed to run with the
 * app's legacy command spelling when they do not implement a conventional help command.
 */
object LlamaBinaryCapabilityCache {
    fun clear() = NativeCliHelpProbe.clear()

    fun clear(binary: File) = NativeCliHelpProbe.clear(binary)

    fun capabilitiesFor(
        binary: File,
        workingDirectory: File,
        environment: Map<String, String>
    ): LlamaBinaryCapabilities {
        val surfaces = NativeCliHelpProbe.probe(
            binary = binary,
            workingDirectory = workingDirectory,
            environment = environment,
            timeoutMs = LLAMA_HELP_TIMEOUT_MS,
            maxOutputChars = LLAMA_HELP_OUTPUT_CHARS
        )
        if (surfaces.isEmpty()) {
            throw IllegalStateException("llama-server did not expose a readable help surface")
        }
        return surfaces.map(::parseLlamaBinaryCapabilities)
            .maxByOrNull { it.supportedFlags.size }
            ?.takeIf { it.supportedFlags.isNotEmpty() }
            ?: throw IllegalStateException("llama-server did not expose a readable help surface")
    }

    fun capabilitiesOrNull(
        binary: File,
        workingDirectory: File,
        environment: Map<String, String>
    ): LlamaBinaryCapabilities? = runCatching {
        capabilitiesFor(binary, workingDirectory, environment)
    }.getOrNull()
}

private const val LLAMA_HELP_TIMEOUT_MS = 10_000L
private const val LLAMA_HELP_OUTPUT_CHARS = 256 * 1024
