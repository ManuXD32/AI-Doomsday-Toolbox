package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class NativeCliHelpProbeTest {
    @Test
    fun `help surfaces are bounded and cached by executable identity`() {
        val root = createTempDirectory(prefix = "native-help-probe").toFile()
        val binary = File(root, "probe.sh")
        val invocations = File(root, "invocations")
        binary.writeText(
            """
            #!/bin/sh
            printf x >> '${invocations.absolutePath}'
            if [ "${'$'}1" = "--help" ]; then
              printf '%s' '--model FILE --threads N'
            else
              printf '%s' '-m FILE -t N'
            fi
            i=0
            while [ "${'$'}i" -lt 1000 ]; do
              printf x
              i=${'$'}((i + 1))
            done
            printf '\n'
            """.trimIndent()
        )
        check(binary.setExecutable(true))
        NativeCliHelpProbe.clear()
        try {
            val first = NativeCliHelpProbe.probe(
                binary = binary,
                workingDirectory = root,
                environment = emptyMap(),
                timeoutMs = 1_000L,
                maxOutputChars = 256
            )
            assertEquals("complete --help avoids the legacy -h duplicate", 1, first.size)
            assertTrue(first.all { it.length <= 256 })
            assertTrue(first.single().contains("--model"))
            assertEquals(1, invocations.length())

            val second = NativeCliHelpProbe.probe(
                binary = binary,
                workingDirectory = root,
                environment = emptyMap(),
                timeoutMs = 1_000L,
                maxOutputChars = 256
            )
            assertEquals(first, second)
            assertEquals("cache hit must not start the executable again", 1, invocations.length())

            binary.appendText("\n")
            val refreshed = NativeCliHelpProbe.probe(
                binary = binary,
                workingDirectory = root,
                environment = emptyMap(),
                timeoutMs = 1_000L,
                maxOutputChars = 256
            )
            assertEquals(1, refreshed.size)
            assertEquals("identity change must invalidate the old surface", 2, invocations.length())
        } finally {
            NativeCliHelpProbe.clear()
            root.deleteRecursively()
        }
    }

    @Test
    fun `falls back to short help when long help is rejected`() {
        val root = createTempDirectory(prefix = "native-help-fallback").toFile()
        val binary = File(root, "probe.sh")
        val invocations = File(root, "invocations")
        binary.writeText(
            """
            #!/bin/sh
            printf x >> '${invocations.absolutePath}'
            if [ "${'$'}1" = "--help" ]; then
              printf '%s' 'unknown option --help'
              exit 2
            fi
            printf '%s\n' 'Usage: probe --model FILE --threads N'
            """.trimIndent()
        )
        check(binary.setExecutable(true))
        NativeCliHelpProbe.clear()
        try {
            val surfaces = NativeCliHelpProbe.probe(
                binary = binary,
                workingDirectory = root,
                environment = emptyMap(),
                timeoutMs = 1_000L,
                maxOutputChars = 256
            )
            assertEquals(2, surfaces.size)
            assertTrue(surfaces.last().contains("Usage:"))
            assertEquals(2, invocations.length())
        } finally {
            NativeCliHelpProbe.clear()
            root.deleteRecursively()
        }
    }

    @Test
    fun `does not cache old surface under identity observed after replacement`() {
        val root = createTempDirectory(prefix = "native-help-replacement").toFile()
        val binary = File(root, "probe.sh")
        val ready = File(root, "ready")
        val invocations = File(root, "invocations")
        binary.writeText(
            """
            #!/bin/sh
            printf x >> '${invocations.absolutePath}'
            touch '${ready.absolutePath}'
            printf '%s\n' 'Usage: probe --model FILE --threads N'
            sleep 1
            """.trimIndent()
        )
        check(binary.setExecutable(true))
        NativeCliHelpProbe.clear()
        try {
            val replacer = Thread {
                repeat(100) {
                    if (ready.exists()) {
                        // Mutate while the child is still sleeping so final identity differs from
                        // the identity captured immediately before --help was launched.
                        binary.appendText("\n")
                        return@Thread
                    }
                    Thread.sleep(10)
                }
            }.apply { start() }
            val first = NativeCliHelpProbe.probe(
                binary = binary,
                workingDirectory = root,
                environment = emptyMap(),
                timeoutMs = 2_000L,
                maxOutputChars = 256
            )
            replacer.join()
            assertEquals(1, first.size)

            NativeCliHelpProbe.probe(
                binary = binary,
                workingDirectory = root,
                environment = emptyMap(),
                timeoutMs = 2_000L,
                maxOutputChars = 256
            )
            assertEquals(
                "a replaced executable must be probed again instead of receiving stale cached help",
                2,
                invocations.length()
            )
        } finally {
            NativeCliHelpProbe.clear()
            root.deleteRecursively()
        }
    }
}
