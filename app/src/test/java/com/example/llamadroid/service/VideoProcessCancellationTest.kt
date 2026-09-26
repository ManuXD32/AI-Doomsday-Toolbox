package com.example.llamadroid.service

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class VideoProcessCancellationTest {
    @Test fun cancellationTerminatesOwnedDecoder() = runBlocking {
        assumeTrue(File("/bin/sh").canExecute())
        assumeTrue("java.lang.ProcessHandle is required on the host JVM", HostProcessHandle.isAvailable())
        val directory = Files.createTempDirectory("video-cancel-test").toFile()
        var ownedPid: Long? = null
        try {
            val pidFile = File(directory, "decoder.pid")
            val job = launch {
                BoundedVideoProcessRunner().run(
                    listOf("/bin/sh", "-c", "echo ${'$'}${'$'} > decoder.pid; exec sleep 30"),
                    directory, 30_000
                )
            }
            withTimeout(5_000) {
                while (!pidFile.isFile || pidFile.readText().trim().isEmpty()) delay(20)
            }
            val pid = pidFile.readText().trim().toLong().also { ownedPid = it }
            withTimeout(5_000) { job.cancelAndJoin() }
            withTimeout(5_000) {
                while (HostProcessHandle.isAlive(pid)) delay(20)
            }
            assertFalse(HostProcessHandle.isAlive(pid))
        } finally {
            ownedPid?.let(HostProcessHandle::destroyForcibly)
            directory.deleteRecursively()
        }
    }

    @Test fun outputIsBoundedAndFloodingCannotPreventTimeout() = runBlocking {
        assumeTrue(File("/bin/sh").canExecute())
        val directory = Files.createTempDirectory("video-output-test").toFile()
        try {
            val runner = BoundedVideoProcessRunner(maxOutputChars = 32)
            val result = runner.run(
                listOf("/bin/sh", "-c", "i=0; while [ ${'$'}i -lt 100 ]; do echo abcdefghijklmnop; i=${'$'}((i+1)); done"),
                directory, 5_000
            )
            assertEquals(0, result.exitCode)
            assertTrue(result.output.length <= 32)
            val failure = runCatching {
                withTimeout(5_000) {
                    runner.run(listOf("/bin/sh", "-c", "while :; do echo decoder-output; done"), directory, 200)
                }
            }.exceptionOrNull()
            assertTrue("Expected decoder timeout, got $failure", failure is VideoProcessTimeoutException)
        } finally {
            directory.deleteRecursively()
        }
    }
}

/**
 * Keeps the host-JVM process assertion available without making Android's
 * compile SDK resolve java.lang.ProcessHandle.
 */
private object HostProcessHandle {
    private data class Api(
        val of: java.lang.reflect.Method,
        val isPresent: java.lang.reflect.Method,
        val get: java.lang.reflect.Method,
        val isAlive: java.lang.reflect.Method,
        val destroyForcibly: java.lang.reflect.Method
    )

    private val api: Api? by lazy {
        runCatching {
            val handleClass = Class.forName("java.lang.ProcessHandle")
            val optionalClass = Class.forName("java.util.Optional")
            Api(
                of = handleClass.getMethod("of", Long::class.javaPrimitiveType!!),
                isPresent = optionalClass.getMethod("isPresent"),
                get = optionalClass.getMethod("get"),
                isAlive = handleClass.getMethod("isAlive"),
                destroyForcibly = handleClass.getMethod("destroyForcibly")
            )
        }.getOrNull()
    }

    fun isAvailable(): Boolean = api != null

    fun isAlive(pid: Long): Boolean {
        val methods = api ?: return false
        val handle = handle(pid, methods) ?: return false
        return runCatching { methods.isAlive.invoke(handle) as? Boolean ?: false }.getOrDefault(false)
    }

    fun destroyForcibly(pid: Long) {
        val methods = api ?: return
        handle(pid, methods)?.let { runCatching { methods.destroyForcibly.invoke(it) } }
    }

    private fun handle(pid: Long, methods: Api): Any? {
        val optional = runCatching { methods.of.invoke(null, pid) }.getOrNull() ?: return null
        val present = runCatching { methods.isPresent.invoke(optional) as? Boolean ?: false }.getOrDefault(false)
        return if (present) runCatching { methods.get.invoke(optional) }.getOrNull() else null
    }
}
