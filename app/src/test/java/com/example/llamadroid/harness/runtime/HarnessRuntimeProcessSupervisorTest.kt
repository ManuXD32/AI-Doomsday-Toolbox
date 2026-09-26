package com.example.llamadroid.harness.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HarnessRuntimeProcessSupervisorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `owner validation rejects recycled broker PID`() {
        val proc = temporaryFolder.newFolder("proc")
        writeProcess(proc, 100, uid = 42, parent = 1, group = 100, start = 777, command = "libproot_broker.so", marker = "generation-1")
        // This fixture checks only broker identity. A marker-bearing detached child is a valid
        // owner even when the broker PID is recycled and is covered by the regression below.
        writeProcess(proc, 101, uid = 42, parent = 100, group = 101, start = 778, command = "libproot.so", marker = null)
        val signals = mutableListOf<Pair<Int, Int>>()
        val supervisor = AndroidHarnessProcessSupervisor(
            procRoot = proc,
            currentUid = 42,
            currentPid = 1,
            signal = { pid, signal -> signals += pid to signal },
            sleep = {}
        )
        val owner = HarnessProcessIdentity(100, 777, 42, 101, 101, 778, "generation-1")
        val recycled = owner.copy(brokerStartTimeTicks = 778)

        assertTrue(supervisor.isOwnerAlive(owner))
        assertFalse(supervisor.isOwnerAlive(recycled))
        assertTrue(signals.isEmpty())
    }

    @Test
    fun `force cleanup removes detached marker descendant but leaves recycled broker`() =
        kotlinx.coroutines.runBlocking {
            val proc = temporaryFolder.newFolder("proc-recycled-detached-marker")
            // PID 100 is unrelated and has reused the broker number. PID 101 survived after the
            // broker exited and retains the generation marker, without a final ledger entry.
            writeProcess(proc, 100, uid = 42, parent = 1, group = 100, start = 999, command = "other", marker = null)
            writeProcess(proc, 101, uid = 42, parent = 1, group = 101, start = 778, command = "node", marker = "generation-1")
            val signals = mutableListOf<Pair<Int, Int>>()
            val supervisor = AndroidHarnessProcessSupervisor(
                procRoot = proc,
                currentUid = 42,
                currentPid = 1,
                signal = { pid, signal ->
                    signals += pid to signal
                    if (signal == android.system.OsConstants.SIGKILL) {
                        File(proc, pid.toString()).deleteRecursively()
                    }
                },
                sleep = {}
            )
            val owner = HarnessProcessIdentity(100, 777, 42, 101, 101, 778, "generation-1")

            assertTrue(supervisor.isOwnerAlive(owner))
            assertTrue(supervisor.forceStop(null, owner))

            assertTrue(signals.any { it.first == 101 })
            assertTrue(signals.none { it.first == 100 })
            assertTrue(File(proc, "100").exists())
            assertFalse(File(proc, "101").exists())
        }

    @Test
    fun `force cleanup ignores recycled broker and unrelated same uid child`() = kotlinx.coroutines.runBlocking {
        val proc = temporaryFolder.newFolder("proc-recycled-tree")
        // PID 100 is now an unrelated process with a child of the same app UID. Its changed
        // start-time and owner marker must prevent tree traversal from reaching PID 102.
        writeProcess(proc, 100, uid = 42, parent = 1, group = 100, start = 999, command = "other", marker = null)
        writeProcess(proc, 102, uid = 42, parent = 100, group = 102, start = 1000, command = "other-child", marker = null)
        val signals = mutableListOf<Pair<Int, Int>>()
        val supervisor = AndroidHarnessProcessSupervisor(
            procRoot = proc,
            currentUid = 42,
            currentPid = 1,
            signal = { pid, signal -> signals += pid to signal },
            sleep = {}
        )
        val owner = HarnessProcessIdentity(100, 777, 42, 101, 101, 778, "generation-1")

        assertTrue(supervisor.forceStop(null, owner))
        assertTrue(signals.isEmpty())
    }

    @Test
    fun `force cleanup rejects recycled child start time before signaling`() = kotlinx.coroutines.runBlocking {
        val proc = temporaryFolder.newFolder("proc-recycled-child")
        writeProcess(proc, 100, uid = 42, parent = 1, group = 100, start = 777, command = "libproot_broker.so", marker = "generation-1")
        writeProcess(proc, 101, uid = 42, parent = 100, group = 101, start = 999, command = "node", marker = "generation-1")
        val signals = mutableListOf<Pair<Int, Int>>()
        val supervisor = AndroidHarnessProcessSupervisor(
            procRoot = proc,
            currentUid = 42,
            currentPid = 1,
            signal = { pid, signal -> signals += pid to signal },
            sleep = {}
        )
        val owner = HarnessProcessIdentity(100, 777, 42, 101, 101, 778, "generation-1")

        supervisor.forceStop(null, owner)

        assertTrue(signals.any { it.first == 100 })
        assertTrue(signals.none { it.first == 101 })
    }

    @Test
    fun `force cleanup includes owner marked detached descendants`() = kotlinx.coroutines.runBlocking {
        val proc = temporaryFolder.newFolder("proc-detached")
        writeProcess(proc, 100, uid = 42, parent = 1, group = 100, start = 777, command = "libproot_broker.so", marker = "generation-1")
        writeProcess(proc, 101, uid = 42, parent = 1, group = 101, start = 778, command = "node", marker = "generation-1")
        writeProcess(proc, 1, uid = 42, parent = 0, group = 1, start = 700, command = "app", marker = "generation-1")
        val signals = mutableListOf<Pair<Int, Int>>()
        val supervisor = AndroidHarnessProcessSupervisor(
            procRoot = proc,
            currentUid = 42,
            currentPid = 1,
            signal = { pid, signal -> signals += pid to signal },
            sleep = {}
        )
        val owner = HarnessProcessIdentity(100, 777, 42, 101, 101, 778, "generation-1")

        supervisor.forceStop(null, owner)

        assertTrue(signals.any { it.first == 100 })
        assertTrue(signals.any { it.first == 101 })
        assertTrue("The Android supervisor must never signal itself", signals.none { it.first == 1 })
    }

    @Test
    fun `force cleanup adopts env-cleared attached descendant but preserves unrelated same uid`() =
        kotlinx.coroutines.runBlocking {
            val proc = temporaryFolder.newFolder("proc-env-cleared")
            writeProcess(proc, 100, uid = 42, parent = 1, group = 100, start = 777, command = "libproot_broker.so", marker = "generation-1")
            writeProcess(proc, 101, uid = 42, parent = 100, group = 101, start = 778, command = "proot", marker = "generation-1")
            // This child deliberately has env -i semantics. Its verified ancestry is the only
            // ownership evidence available while the broker is still present.
            writeProcess(proc, 102, uid = 42, parent = 101, group = 102, start = 779, command = "node", marker = null)
            writeProcess(proc, 103, uid = 42, parent = 1, group = 103, start = 780, command = "unrelated", marker = null)
            val signals = mutableListOf<Pair<Int, Int>>()
            val supervisor = AndroidHarnessProcessSupervisor(
                procRoot = proc,
                currentUid = 42,
                currentPid = 1,
                signal = { pid, signal -> signals += pid to signal },
                sleep = {}
            )
            val owner = HarnessProcessIdentity(100, 777, 42, 101, 101, 778, "generation-1")

            supervisor.forceStop(null, owner)

            assertTrue(signals.any { it.first == 102 })
            assertTrue(signals.any { it.first == 101 })
            assertTrue(signals.any { it.first == 100 })
            assertTrue(signals.none { it.first == 103 })
        }

    @Test
    fun `force cleanup uses generation ledger for markerless reparented descendants`() = kotlinx.coroutines.runBlocking {
        val proc = temporaryFolder.newFolder("proc-ledger")
        val ledger = File(proc, "harness-processes.ledger")
        // The broker and child have already exited. PID 101 is now reparented to init and has
        // been launched with env -i, so only the generation-scoped ledger proves ownership.
        writeProcess(proc, 101, uid = 42, parent = 1, group = 101, start = 778, command = "node", marker = null)
        ledger.writeText(
            "adt-harness-process-ledger-v1\n" +
                "marker=generation-1\n" +
                "process 101 778 42\n"
        )
        val signals = mutableListOf<Pair<Int, Int>>()
        val supervisor = AndroidHarnessProcessSupervisor(
            procRoot = proc,
            currentUid = 42,
            currentPid = 1,
            signal = { pid, signal -> signals += pid to signal },
            sleep = {},
            ledgerFile = ledger
        )
        val owner = HarnessProcessIdentity(100, 777, 42, 101, 101, 778, "generation-1")

        supervisor.forceStop(null, owner)

        assertTrue(signals.any { it.first == 101 })
    }

    @Test
    fun `recovery reconstructs exact owner from a generation ledger tuple`() = kotlinx.coroutines.runBlocking {
        val proc = temporaryFolder.newFolder("proc-ledger-recovery")
        val ledger = File(proc, "harness-processes.ledger")
        // The broker is gone, so this reparented markerless PRoot/Node tuple is the only durable
        // owner evidence left after the launch-to-Room persistence gap.
        writeProcess(proc, 101, uid = 42, parent = 1, group = 101, start = 778, command = "node", marker = null)
        ledger.writeText(
            "adt-harness-process-ledger-v1\n" +
                "marker=generation-1\n" +
                "process 101 778 42\n"
        )
        val supervisor = AndroidHarnessProcessSupervisor(
            procRoot = proc,
            currentUid = 42,
            currentPid = 1,
            signal = { _, _ -> },
            sleep = {},
            ledgerFile = ledger
        )

        val recovered = supervisor.recoverIdentity("generation-1", expectedUid = 42)

        assertEquals(101, recovered?.brokerPid)
        assertEquals(778L, recovered?.brokerStartTimeTicks)
        assertEquals(42, recovered?.brokerUid)
        assertEquals(101, recovered?.processGroupId)
        assertEquals("generation-1", recovered?.ownerMarker)
        assertEquals(101, recovered?.nodePid)
        assertEquals(778L, recovered?.nodeStartTimeTicks)
    }

    @Test
    fun `recovery rejects a recycled or wrong uid ledger tuple`() = kotlinx.coroutines.runBlocking {
        val proc = temporaryFolder.newFolder("proc-ledger-recovery-recycled")
        val ledger = File(proc, "harness-processes.ledger")
        writeProcess(proc, 101, uid = 43, parent = 1, group = 101, start = 999, command = "node", marker = null)
        ledger.writeText(
            "adt-harness-process-ledger-v1\n" +
                "marker=generation-1\n" +
                "process 101 778 42\n"
        )
        val supervisor = AndroidHarnessProcessSupervisor(
            procRoot = proc,
            currentUid = 42,
            currentPid = 1,
            sleep = {},
            ledgerFile = ledger
        )

        assertEquals(null, supervisor.recoverIdentity("generation-1", expectedUid = 42))
    }

    @Test
    fun `force cleanup ignores recycled PID from generation ledger`() = kotlinx.coroutines.runBlocking {
        val proc = temporaryFolder.newFolder("proc-ledger-recycled")
        val ledger = File(proc, "harness-processes.ledger")
        writeProcess(proc, 101, uid = 42, parent = 1, group = 101, start = 999, command = "other", marker = null)
        ledger.writeText(
            "adt-harness-process-ledger-v1\n" +
                "marker=generation-1\n" +
                "process 101 778 42\n"
        )
        val signals = mutableListOf<Pair<Int, Int>>()
        val supervisor = AndroidHarnessProcessSupervisor(
            procRoot = proc,
            currentUid = 42,
            currentPid = 1,
            signal = { pid, signal -> signals += pid to signal },
            sleep = {},
            ledgerFile = ledger
        )
        val owner = HarnessProcessIdentity(100, 777, 42, 101, 101, 778, "generation-1")

        supervisor.forceStop(null, owner)

        assertTrue(signals.isEmpty())
    }

    @Test
    fun `force cleanup ignores an oversized ledger instead of loading it unbounded`() =
        kotlinx.coroutines.runBlocking {
            val proc = temporaryFolder.newFolder("proc-ledger-oversized")
            val ledger = File(proc, "harness-processes.ledger")
            writeProcess(proc, 101, uid = 42, parent = 1, group = 101, start = 778, command = "node", marker = null)
            ledger.writeText(
                "adt-harness-process-ledger-v1\nmarker=generation-1\n" +
                    "process 101 778 42\n" + "x".repeat(70 * 1024)
            )
            val signals = mutableListOf<Pair<Int, Int>>()
            val supervisor = AndroidHarnessProcessSupervisor(
                procRoot = proc,
                currentUid = 42,
                currentPid = 1,
                signal = { pid, signal -> signals += pid to signal },
                sleep = {},
                ledgerFile = ledger
            )

            supervisor.forceStop(
                null,
                HarnessProcessIdentity(100, 777, 42, 101, 101, 778, "generation-1")
            )

            assertTrue(signals.isEmpty())
        }

    @Test
    fun `graceful stop targets Node before broker and never signals the PRoot group`() =
        kotlinx.coroutines.runBlocking {
            val proc = temporaryFolder.newFolder("proc-graceful-node")
            writeProcess(proc, 100, uid = 42, parent = 1, group = 100, start = 777, command = "libproot_broker.so", marker = "generation-1")
            writeProcess(proc, 101, uid = 42, parent = 100, group = 101, start = 778, command = "proot", marker = "generation-1")
            writeProcess(proc, 102, uid = 42, parent = 101, group = 102, start = 779, command = "node", marker = "generation-1")
            val signals = mutableListOf<Pair<Int, Int>>()
            val supervisor = AndroidHarnessProcessSupervisor(
                procRoot = proc,
                currentUid = 42,
                currentPid = 1,
                signal = { pid, signal ->
                    signals += pid to signal
                    if (signal == android.system.OsConstants.SIGTERM) {
                        File(proc, pid.toString()).deleteRecursively()
                    }
                },
                sleep = {}
            )
            val owner = HarnessProcessIdentity(
                brokerPid = 100,
                brokerStartTimeTicks = 777,
                brokerUid = 42,
                processGroupId = 101,
                childPid = 101,
                childStartTimeTicks = 778,
                ownerMarker = "generation-1",
                nodePid = 102,
                nodeStartTimeTicks = 779
            )

            val stopped = supervisor.gracefulStop(
                handle = object : HarnessProcessHandle {
                    override val brokerPid = 100
                    override fun isAlive() = false
                    override fun requestGracefulStop() = Unit
                    override fun requestForceStop() = Unit
                    override suspend fun awaitExit(timeoutMs: Long) = true
                },
                identity = owner,
                deadlineMs = 1_000L
            )

            assertTrue(stopped)
            assertEquals(listOf(102, 100, 101), signals.map { it.first })
        }

    @Test
    fun `owner observation distinguishes a vanished Node from a live broker`() {
        val proc = temporaryFolder.newFolder("proc-observation")
        writeProcess(proc, 100, uid = 42, parent = 1, group = 100, start = 777, command = "libproot_broker.so", marker = "generation-1")
        writeProcess(proc, 101, uid = 42, parent = 100, group = 101, start = 778, command = "proot", marker = "generation-1")
        writeProcess(proc, 102, uid = 42, parent = 101, group = 102, start = 779, command = "node", marker = "generation-1")
        val supervisor = AndroidHarnessProcessSupervisor(
            procRoot = proc,
            currentUid = 42,
            currentPid = 1,
            signal = { _, _ -> },
            sleep = {}
        )
        val owner = HarnessProcessIdentity(
            brokerPid = 100,
            brokerStartTimeTicks = 777,
            brokerUid = 42,
            processGroupId = 101,
            childPid = 101,
            childStartTimeTicks = 778,
            ownerMarker = "generation-1",
            nodePid = 102,
            nodeStartTimeTicks = 779,
        )

        val live = supervisor.observe(owner)
        assertTrue(live.brokerAlive)
        assertEquals(true, live.childAlive)
        assertEquals(true, live.nodeAlive)
        assertFalse(live.knownOwnerLost)

        File(proc, "102").deleteRecursively()
        val nodeGone = supervisor.observe(owner)
        assertTrue(nodeGone.brokerAlive)
        assertEquals(true, nodeGone.childAlive)
        assertEquals(false, nodeGone.nodeAlive)
        assertTrue(nodeGone.knownOwnerLost)
    }

    @Test
    fun `launch command keeps kill on exit and direct pinned payload`() {
        val root = temporaryFolder.newFolder("command")
        val rootfs = File(root, "rootfs").apply { mkdirs() }
        val projects = File(root, "projects").apply { mkdirs() }
        val home = File(root, "home").apply { mkdirs() }
        val temp = File(root, "tmp").apply { mkdirs() }
        val run = File(root, "run").apply { mkdirs() }
        val resolver = File(root, "resolv.conf").apply { writeText("nameserver 127.0.0.1\n") }
        fun executable(name: String) = File(root, name).apply {
            writeText("binary")
            setExecutable(true)
        }
        val paths = HarnessEnvironmentPaths(
            environmentId = HarnessRuntimeRecord.DEFAULT_ENVIRONMENT_ID,
            rootfs = rootfs,
            projectsHost = projects,
            dshHomeHost = home,
            tempHost = temp,
            runHost = run,
            resolverFile = resolver
        )
        val spec = HarnessLaunchSpec(
            payload = HarnessPayload(
                version = "0.1.6-alpha.2",
                commit = "ddefc45fbc7f8e46dd73185e68295696d1297887",
                archiveAsset = "harness/payload.tar.xz",
                archiveSha256 = "a".repeat(64),
                command = listOf("/opt/adt-harness/bin/dsh", "--profile", "web"),
                healthPath = "/"
            ),
            paths = paths,
            port = 39002,
            ownerMarker = "generation-1",
            preparation = HarnessLaunchPreparation("token"),
            brokerPath = executable("broker"),
            prootPath = executable("proot"),
            loaderPath = executable("loader")
        )

        val command = buildHarnessLaunchCommand(
            spec = spec,
            childPidFile = File(run, "child.pid"),
            processLedgerFile = File(run, "processes.ledger"),
            procRoot = File(root, "proc").apply { mkdirs() },
            devices = emptyList()
        )

        assertTrue(command.contains("--kill-on-exit"))
        assertTrue(command.contains("--no-force-on-timeout"))
        assertTrue(command.contains("--term-child-only"))
        assertTrue(command.contains("--child-pid-file"))
        assertTrue(command.contains("--process-ledger-file"))
        assertTrue(command.contains("/opt/adt-harness/bin/dsh"))
        assertEquals(
            listOf(
                "/opt/adt-harness/bin/dsh",
                "--profile", "web",
                "--patch", "/opt/adt-harness/node_modules/@manuxd32/adt-dsh-bridge/cordis.patch.yml",
                "--host", "127.0.0.1", "--port", "39002", "--no-open"
            ),
            command.takeLast(10)
        )
        val workingDirectoryIndex = command.indexOf("-w")
        assertTrue(workingDirectoryIndex >= 0)
        assertEquals(HarnessRuntimePaths.DEFAULT_PROJECT_GUEST, command[workingDirectoryIndex + 1])
        assertFalse(command.contains("/bin/sh"))
        assertFalse(command.contains("-c"))
    }

    private fun writeProcess(
        procRoot: File,
        pid: Int,
        uid: Int,
        parent: Int,
        group: Int,
        start: Long,
        command: String,
        marker: String?
    ) {
        val directory = File(procRoot, pid.toString()).apply { mkdirs() }
        val fields = MutableList(20) { "0" }
        fields[0] = "S"
        fields[1] = parent.toString()
        fields[2] = group.toString()
        fields[19] = start.toString()
        File(directory, "stat").writeText("$pid ($command) ${fields.joinToString(" ")}\n")
        File(directory, "status").writeText("Name:\ttest\nUid:\t$uid\t$uid\t$uid\t$uid\n")
        File(directory, "cmdline").writeText(command)
        File(directory, "environ").writeBytes(
            marker?.let { "ADT_HARNESS_OWNER_MARKER=$it\u0000" }?.toByteArray() ?: ByteArray(0)
        )
    }
}
