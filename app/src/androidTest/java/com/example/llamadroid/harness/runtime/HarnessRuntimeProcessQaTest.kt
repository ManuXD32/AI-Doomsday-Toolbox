package com.example.llamadroid.harness.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.BuildConfig
import com.example.llamadroid.data.proot.AgentProotNativeBinaryProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Exercises the native broker's real subreaper and generation ledger on the x86_64 carrier.
 * The detached child clears the owner marker, so cleanup can only find it through the broker's
 * persisted PID/start-time ledger after it has been reparented.
 */
@RunWith(AndroidJUnit4::class)
class HarnessRuntimeProcessQaTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun qaForceStopsMarkerlessReparentedDescendant() = runBlocking {
        assumeTrue("This test requires the explicit x86_64 Harness QA build", BuildConfig.HARNESS_QA_X86)
        assumeTrue("The carrier must expose x86_64 native binaries", android.os.Build.SUPPORTED_ABIS.firstOrNull() == "x86_64")

        val root = File(
            context.cacheDir,
            "adt-harness-broker-qa-${UUID.randomUUID()}"
        ).canonicalFile.apply { mkdirs() }
        val run = File(root, "run").apply { mkdirs() }
        val rootfs = File(root, "rootfs").apply { mkdirs() }
        val projects = File(root, "projects").apply { mkdirs() }
        val dshHome = File(root, "dsh-home").apply { mkdirs() }
        val temp = File(root, "tmp").apply { mkdirs() }
        val resolver = File(root, "resolv.conf").apply { writeText("nameserver 127.0.0.1\n") }
        val childPidFile = File(run, "harness-child.pid")
        val ledgerFile = File(run, "harness-processes.ledger")
        val detachedPidFile = File(run, "detached.pid")
        val marker = "qa-detached-${UUID.randomUUID()}"
        val broker = AgentProotNativeBinaryProvider.requireBroker(context)
        var process: Process? = null
        var handle: DirectProcessHandle? = null
        var identity: HarnessProcessIdentity? = null
        var detachedSnapshot: ProcSnapshot? = null
        val supervisor = AndroidHarnessProcessSupervisor(ledgerFile = ledgerFile)
        try {
            val paths = HarnessEnvironmentPaths(
                environmentId = HarnessRuntimeRecord.DEFAULT_ENVIRONMENT_ID,
                rootfs = rootfs,
                projectsHost = projects,
                dshHomeHost = dshHome,
                tempHost = temp,
                runHost = run,
                resolverFile = resolver
            )
            val payload = HarnessPayload(
                version = "0.1.6-alpha.2",
                commit = "ddefc45fbc7f8e46dd73185e68295696d1297887",
                archiveAsset = "harness/payload.tar.xz",
                archiveSha256 = "a".repeat(64),
                command = listOf("/opt/adt-harness/bin/dsh", "serve"),
                healthPath = "/rpc/health"
            )
            val spec = HarnessLaunchSpec(
                payload = payload,
                paths = paths,
                port = 39_111,
                ownerMarker = marker,
                preparation = HarnessLaunchPreparation(webToken = "qa-token"),
                brokerPath = broker,
                prootPath = broker,
                loaderPath = null
            )
            val script = """
                unset ADT_HARNESS_OWNER_MARKER
                /system/bin/toybox setsid /system/bin/sh -c 'trap "" TERM; while :; do /system/bin/sleep 1; done' >/dev/null 2>&1 &
                echo ${'$'}! > ${shellQuote(detachedPidFile.absolutePath)}
                while :; do /system/bin/sleep 1; done
            """.trimIndent()
            val command = listOf(
                broker.absolutePath,
                "--max-processes", "64",
                "--max-open-files", "256",
                "--max-file-bytes", "1048576",
                "--term-grace-ms", "1000",
                "--no-force-on-timeout",
                "--term-child-only",
                "--child-pid-file", childPidFile.absolutePath,
                "--process-ledger-file", ledgerFile.absolutePath,
                "--",
                "/system/bin/sh", "-c", script
            )
            process = ProcessBuilder(command)
                .directory(projects)
                .redirectErrorStream(true)
                .apply {
                    environment()["ADT_HARNESS_OWNER_MARKER"] = marker
                    environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
                }
                .start()
            val launchedProcess = requireNotNull(process)
            val launchedHandle = DirectProcessHandle(launchedProcess, findBrokerPid(childPidFile))
            handle = launchedHandle
            identity = supervisor.capture(launchedHandle, spec)
            val captured = requireNotNull(identity)
            val detached = withTimeout(3_000L) {
                var candidate: ProcSnapshot? = null
                while (candidate == null) {
                    candidate = detachedPidFile.takeIf { it.isFile }
                        ?.readText()
                        ?.trim()
                        ?.toIntOrNull()
                        ?.takeIf { it > 0 }
                        ?.let(::readSnapshot)
                        ?.takeIf { it.state != 'Z' }
                    if (candidate == null) kotlinx.coroutines.delay(25L)
                }
                requireNotNull(candidate)
            }
            detachedSnapshot = detached
            assertTrue("The captured owner was not alive", supervisor.isOwnerAlive(captured))

            // TERM ends the main shell while --no-force-on-timeout deliberately leaves the
            // markerless child for the explicit Force operation. Reparenting is asserted only
            // after the shell has exited; before graceful stop its parent is the shell, not the broker.
            assertFalse(supervisor.gracefulStop(handle, captured, 1_000L))
            withTimeout(3_000L) {
                while (readParentPid(detached) != captured.brokerPid ||
                    !ledgerContains(ledgerFile, detached)
                ) {
                    if (!sameProcess(detached)) {
                        throw AssertionError(
                            "Detached PID was replaced or exited before generation-ledger adoption"
                        )
                    }
                    kotlinx.coroutines.delay(25L)
                }
            }
            assertTrue("Detached child was not reparented to the broker", readParentPid(detached) == captured.brokerPid)
            assertTrue("The broker ledger lost the detached child identity", ledgerContains(ledgerFile, detached))
            assertTrue(supervisor.forceStop(handle, captured))
            assertTrue(
                "Markerless detached child survived Force or its PID was not revalidated",
                awaitProcessGone(detached, 3_000L)
            )
            assertFalse("The broker supervisor still reports owned processes", supervisor.isOwnerAlive(captured))
        } finally {
            val captured = identity
            val detached = detachedSnapshot
            // Always attempt owner-scoped Force when the broker identity was captured, even when
            // the detached-child observation failed. Without the detached snapshot we cannot
            // prove descendant cleanup, so the fixture remains visible for diagnosis.
            val ownerGone = if (captured != null) {
                try {
                    supervisor.forceStop(handle, captured) && !supervisor.isOwnerAlive(captured)
                } catch (_: Throwable) {
                    false
                }
            } else {
                false
            }
            val detachedGone = if (detached != null) {
                try {
                    awaitProcessGone(detached, 3_000L)
                } catch (_: Throwable) {
                    false
                }
            } else {
                false
            }
            val cleanupVerified = ownerGone && detachedGone
            val brokerExited = process?.let {
                !it.isAlive || it.waitFor(1_000L, TimeUnit.MILLISECONDS)
            } ?: true
            if (cleanupVerified && brokerExited) {
                check(root.deleteRecursively()) { "Unable to delete Harness broker QA fixture ${root.absolutePath}" }
            } else {
                System.err.println(
                    "Harness broker QA fixture retained at ${root.absolutePath}; " +
                        "owner cleanup was not proven"
                )
                throw AssertionError("Harness broker cleanup was incomplete; fixture was retained")
            }
        }
    }

    private class DirectProcessHandle(
        private val process: Process,
        override val brokerPid: Int
    ) : HarnessProcessHandle {
        override fun isAlive(): Boolean = process.isAlive
        override fun requestGracefulStop() = Unit
        override fun requestForceStop() = Unit
        override suspend fun awaitExit(timeoutMs: Long): Boolean =
            process.waitFor(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
    }

    private fun findBrokerPid(childPidFile: File): Int {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < deadline) {
            val childPid = childPidFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
            if (childPid != null) {
                readParentPid(childPid)?.let { return it }
            }
            Thread.sleep(25L)
        }
        throw AssertionError("Native broker did not publish a child PID")
    }

    private data class ProcSnapshot(
        val pid: Int,
        val parentPid: Int,
        val startTimeTicks: Long,
        val uid: Int,
        val state: Char
    )

    private fun readSnapshot(pid: Int): ProcSnapshot? = runCatching {
        val directory = File("/proc/$pid")
        val stat = File(directory, "stat").readText()
        val fields = stat.substringAfterLast(") ", missingDelimiterValue = "")
            .split(Regex("\\s+"))
        val state = fields.getOrNull(0)?.firstOrNull() ?: return@runCatching null
        val parentPid = fields.getOrNull(1)?.toIntOrNull() ?: return@runCatching null
        val startTimeTicks = fields.getOrNull(19)?.toLongOrNull() ?: return@runCatching null
        val uid = File(directory, "status").readLines()
            .firstOrNull { it.startsWith("Uid:") }
            ?.removePrefix("Uid:")
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
            ?.toIntOrNull()
            ?: return@runCatching null
        ProcSnapshot(pid, parentPid, startTimeTicks, uid, state)
    }.getOrNull()

    private fun readParentPid(pid: Int): Int? = readSnapshot(pid)?.parentPid

    private fun readParentPid(expected: ProcSnapshot): Int? = readSnapshot(expected.pid)
        ?.takeIf {
            it.state != 'Z' &&
                it.startTimeTicks == expected.startTimeTicks &&
                it.uid == expected.uid
        }
        ?.parentPid

    private fun sameProcess(expected: ProcSnapshot): Boolean = readSnapshot(expected.pid)?.let { current ->
        current.state != 'Z' &&
            current.startTimeTicks == expected.startTimeTicks &&
            current.uid == expected.uid
    } == true

    private suspend fun awaitProcessGone(expected: ProcSnapshot, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (sameProcess(expected)) kotlinx.coroutines.delay(25L)
            true
        } ?: false

    private fun ledgerContains(file: File, expected: ProcSnapshot): Boolean = runCatching {
        file.takeIf { it.isFile }?.inputStream()?.use { input ->
            val bytes = ByteArray(64 * 1024)
            var total = 0
            while (total < bytes.size) {
                val count = input.read(bytes, total, bytes.size - total)
                if (count <= 0) break
                total += count
            }
            String(bytes, 0, total, StandardCharsets.UTF_8).lineSequence().any { line ->
                val fields = line.split(' ')
                fields.size == 4 && fields[0] == "process" &&
                    fields[1].toIntOrNull() == expected.pid &&
                    fields[2].toLongOrNull() == expected.startTimeTicks &&
                    fields[3].toIntOrNull() == expected.uid
            }
        } ?: false
    }.getOrDefault(false)

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
