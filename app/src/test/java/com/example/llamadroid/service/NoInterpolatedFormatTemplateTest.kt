package com.example.llamadroid.service

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards against splicing runtime text into a `.format()` template (e.g. model output with `100%;`). */
class NoInterpolatedFormatTemplateTest {
    private val pattern = Regex(
        "\"(?:[^\"\\\\\\n]|\\\\.)*\\$(?:\\{|[A-Za-z_])(?:[^\"\\\\\\n]|\\\\.)*\"\\s*\\.format\\("
    )

    private fun mainSourceRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            listOf("app/src/main/java", "src/main/java").forEach { rel ->
                val f = File(dir, rel)
                if (f.isDirectory) return f
            }
            dir = dir.parentFile
        }
        error("main source root not found")
    }

    @Test
    fun noStringTemplateWithInterpolationIsFormatted() {
        val offenders = mainSourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    if (pattern.containsMatchIn(line)) "${file.name}:${i + 1}: ${line.trim()}" else null
                }.asSequence()
            }.toList()
        assertTrue("Interpolated format templates:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
