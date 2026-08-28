package com.example.llamadroid.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream

private const val MAX_PROCESS_OUTPUT_LINE_LENGTH = 16 * 1024

private val ansiControlSequenceRegex =
    Regex("""\u001B\[[0-?]*[ -/]*[@-~]""")

internal suspend fun consumeBoundedProcessOutput(
    input: InputStream,
    rawLogOutput: OutputStream? = null,
    onLogLine: suspend (String) -> Unit,
    onProgress: suspend (String) -> Unit
) = withContext(Dispatchers.IO) {
    InputStreamReader(input, Charsets.UTF_8).use { reader ->
        val readBuffer = CharArray(4096)
        val currentLine = StringBuilder()

        while (true) {
            val count = reader.read(readBuffer)
            if (count < 0) break

            rawLogOutput?.let { output ->
                val rawChunk = String(readBuffer, 0, count).toByteArray(Charsets.UTF_8)
                synchronized(output) {
                    output.write(rawChunk)
                    output.flush()
                }
            }

            for (index in 0 until count) {
                when (val character = readBuffer[index]) {
                    '\r' -> {
                        currentLine.emitSanitized()?.let { onProgress(it) }
                        currentLine.setLength(0)
                    }

                    '\n' -> {
                        currentLine.emitSanitized()?.let { onLogLine(it) }
                        currentLine.setLength(0)
                    }

                    else -> {
                        if (currentLine.length < MAX_PROCESS_OUTPUT_LINE_LENGTH) {
                            currentLine.append(character)
                        }
                    }
                }
            }
        }

        currentLine.emitSanitized()?.let { onLogLine(it) }
    }
}

internal fun sanitizeProcessOutputText(text: String): String =
    ansiControlSequenceRegex
        .replace(text, "")
        .trim()
        .take(MAX_PROCESS_OUTPUT_LINE_LENGTH)

private fun StringBuilder.emitSanitized(): String? =
    sanitizeProcessOutputText(toString()).takeIf { it.isNotEmpty() }
