package com.example.llamadroid.harness.runtime

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class HarnessRuntimeControllerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun setProcessUid() {
        // Production identity checks use Android Process.myUid(); keep the fake supervisor's
        // persisted UID deterministic under the Robolectric shadow.
        ShadowProcess.setUid(42)
    }

    @Test
    fun `start is ready once and graceful timeout remains independently force stoppable`() = runBlocking {
        val fixture = Fixture(temporaryFolder.newFolder("runtime"))
        val controller = fixture.controller(gracefulResult = false)

        val started = controller.start(HarnessStartRequest(preferredPort = 39001))
        assertEquals(HarnessRuntimeState.RUNNING, started.record.state)
        assertNotNull(started.endpoint)
        assertEquals(702, started.record.nodePid)
        assertEquals(89L, started.record.nodeStartTicks)
        val repeated = controller.start()
        assertEquals(HarnessRuntimeState.RUNNING, repeated.record.state)
        assertEquals(started.record.generation, repeated.record.generation)
        assertEquals(1, fixture.launchCalls.get())

        val timedOut = controller.stop()
        assertEquals(HarnessRuntimeState.STOP_REQUESTED, timedOut.record.state)
        assertTrue(timedOut.timedOut)
        assertEquals(1, fixture.gracefulCalls.get())
        assertEquals(0, fixture.forceCalls.get())

        val forced = controller.forceStop()
        assertEquals(HarnessRuntimeState.STOPPED, forced.record.state)
        assertEquals(1, fixture.forceCalls.get())
        assertEquals(listOf(HarnessStopMode.GRACEFUL, HarnessStopMode.FORCE), fixture.stopModes)
    }

    @Test
    fun `readiness failure force cleans and persists failure without shell fallback`() = runBlocking {
        val fixture = Fixture(temporaryFolder.newFolder("failure"))
        fixture.readinessFailure = true
        val controller = fixture.controller()

        val error = runCatching { controller.start() }.exceptionOrNull()

        assertTrue(error is HarnessRuntimeException)
        assertEquals("HARNESS_NOT_READY", fixture.store.row?.errorCode)
        assertEquals(HarnessRuntimeState.FAILED, fixture.store.row?.state)
        assertEquals(1, fixture.forceCalls.get())
        assertTrue(fixture.launchCommand.all { it != "/bin/sh" && it != "-c" })
    }

    @Test
    fun `startup records phases and classifies an exited process without retaining output`() = runBlocking {
        val fixture = Fixture(temporaryFolder.newFolder("startup-diagnostics"))
        fixture.readinessFailure = true
        fixture.processExitedOnLaunch = true
        fixture.process.processDiagnostics = HarnessProcessDiagnostics(
            stderrCode = "HARNESS_NATIVE_MODULE_UNAVAILABLE",
            exitCode = 126
        )

        val error = runCatching { fixture.controller().start() }.exceptionOrNull()

        assertEquals("HARNESS_NATIVE_MODULE_UNAVAILABLE", fixture.store.row?.errorCode)
        assertTrue(error is HarnessRuntimeException)
        assertEquals("HARNESS_NATIVE_MODULE_UNAVAILABLE", (error as HarnessRuntimeException).code)
        assertTrue(fixture.logs.any { it.event == "phase" && it.phase == "environment_preparing" })
        assertTrue(fixture.logs.any { it.event == "phase" && it.phase == "readiness_waiting" })
        val exited = fixture.logs.single { it.event == "process_exited" }
        assertEquals("HARNESS_NATIVE_MODULE_UNAVAILABLE", exited.errorCode)
        assertEquals(126, exited.exitCode)
        assertTrue(fixture.logs.none { event -> event.errorCode?.contains("native module text") == true })
    }

    @Test
    fun `broker exit before handle creation retains exit metadata`() = runBlocking {
        val fixture = Fixture(temporaryFolder.newFolder("broker-before-handle"))
        fixture.launchError = HarnessRuntimeException(
            "HARNESS_BROKER_OWNER_UNAVAILABLE", "Owner identity unavailable",
            processDiagnostics = HarnessProcessDiagnostics(
                stderrCode = "HARNESS_BROKER_OWNER_UNAVAILABLE", exitCode = 143
            ),
        )

        runCatching { fixture.controller().start() }

        assertEquals("HARNESS_BROKER_OWNER_UNAVAILABLE", fixture.store.row?.errorCode)
        val failure = fixture.logs.single { it.event == "start_failed" }
        assertEquals("process_launching", failure.phase)
        assertEquals(143, failure.exitCode)
        assertEquals(143, fixture.logs.single { it.event == "process_exited" }.exitCode)
    }

    @Test
    fun `nested coded startup cause reaches the persisted error code`() = runBlocking {
        val fixture = Fixture(temporaryFolder.newFolder("nested-startup-error"))
        fixture.launchError = IllegalStateException(
            "launcher wrapper",
            HarnessRuntimeException("HARNESS_ARCHITECTURE_MISMATCH", "classified startup error")
        )

        val error = runCatching { fixture.controller().start() }.exceptionOrNull()

        assertEquals("HARNESS_ARCHITECTURE_MISMATCH", fixture.store.row?.errorCode)
        assertEquals("HARNESS_ARCHITECTURE_MISMATCH", (error as HarnessRuntimeException).code)
    }

    @Test
    fun `readiness stops promptly when the launched process exits`() = runBlocking {
        val fixture = Fixture(temporaryFolder.newFolder("early-process-exit"))
        fixture.suspendReadiness = true
        fixture.processExitedOnLaunch = true
        fixture.process.processDiagnostics = HarnessProcessDiagnostics()
        fixture.process.processDiagnosticsAfterExit = HarnessProcessDiagnostics(exitCode = 137)
        val startedAt = System.nanoTime()

        val error = runCatching {
            fixture.controller().start(HarnessStartRequest(readinessTimeoutMs = 60_000L))
        }.exceptionOrNull()

        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        assertEquals("HARNESS_PROCESS_KILLED", (error as HarnessRuntimeException).code)
        assertTrue("early process exit took ${elapsedMs}ms", elapsedMs < 2_000L)
        assertTrue(fixture.process.diagnosticsAfterExitCalls >= 1)
    }

    @Test
    fun `force stop is safe to repeat after the runtime is stopped`() = runBlocking {
        val fixture = Fixture(temporaryFolder.newFolder("repeat"))
        val controller = fixture.controller()
        controller.start()
        assertEquals(HarnessRuntimeState.STOPPED, controller.forceStop().record.state)
        assertEquals(HarnessRuntimeState.STOPPED, controller.forceStop().record.state)
        assertEquals(1, fixture.forceCalls.get())
    }

    @Test
    fun `force stop stays incomplete while the loopback listener is still open`() = runBlocking {
        val fixture = Fixture(temporaryFolder.newFolder("listener-open"))
        fixture.listenerOpen = true
        val controller = fixture.controller()
        controller.start()

        val pending = controller.forceStop()

        assertEquals(HarnessRuntimeState.FORCE_STOPPING, pending.record.state)
        assertTrue(pending.timedOut)
        fixture.listenerOpen = false
        assertEquals(HarnessRuntimeState.STOPPED, controller.forceStop().record.state)
    }

    @Test
    fun `force stop reconciles a persisted stopped row when its port is still open`() = runBlocking {
        val fixture = Fixture(temporaryFolder.newFolder("stopped-listener"))
        fixture.listenerOpen = true
        fixture.store.row = HarnessRuntimeRecord(
            environmentId = HarnessRuntimeRecord.DEFAULT_ENVIRONMENT_ID,
            state = HarnessRuntimeState.STOPPED,
            generation = "generation-1",
            port = 39000,
            endedAt = 1_700_000_000_000L
        )
        val controller = fixture.controller()

        val pending = controller.forceStop()

        assertEquals(HarnessRuntimeState.FORCE_STOPPING, pending.record.state)
        assertTrue(pending.timedOut)
        fixture.listenerOpen = false
        assertEquals(HarnessRuntimeState.STOPPED, controller.forceStop().record.state)
    }

    @Test
    fun `stop cancels readiness while start is still in progress`() = runBlocking {
        val fixture = Fixture(temporaryFolder.newFolder("cancel-start"))
        val readinessStarted = CompletableDeferred<Unit>()
        val readinessGate = CompletableDeferred<Unit>()
        fixture.readinessStarted = readinessStarted
        fixture.readinessGate = readinessGate
        val controller = fixture.controller()

        val start = async { runCatching { controller.start() } }
        readinessStarted.await()

        val stopped = controller.stop()

        assertEquals(HarnessRuntimeState.STOPPED, stopped.record.state)
        assertEquals(HarnessRuntimeState.STOPPED, fixture.store.row?.state)
        assertEquals(1, fixture.gracefulCalls.get())
        start.join()
        assertTrue(start.isCancelled)
    }

    @Test
    fun `start does not replace a generation while graceful stop still owns it`(): Unit = runBlocking {
        val fixture = Fixture(temporaryFolder.newFolder("generation-stop"))
        fixture.suspendGraceful = true
        val controller = fixture.controller()

        val first = controller.start()
        val stopping = async { controller.stop() }
        fixture.gracefulStarted.await()

        val duringStop = controller.start()
        assertEquals(HarnessRuntimeState.STOP_REQUESTED, duringStop.record.state)
        assertEquals(1, fixture.launchCalls.get())

        fixture.gracefulGate.complete(Unit)
        assertEquals(HarnessRuntimeState.STOPPED, stopping.await().record.state)

        val second = controller.start()
        assertEquals(HarnessRuntimeState.RUNNING, second.record.state)
        assertNotEquals(first.record.generation, second.record.generation)
        assertEquals(2, fixture.launchCalls.get())
        controller.forceStop()
    }

    @Test
    fun `stop waits for cancelled launch preparation before teardown hook`() = runBlocking {
        val fixture = Fixture(temporaryFolder.newFolder("prepare-stop-race"))
        fixture.suspendPrepare = true
        val controller = fixture.controller()
        val start = async { runCatching { controller.start() } }
        fixture.prepareStarted.await()

        val stopped = controller.stop()

        assertEquals(HarnessRuntimeState.STOPPED, stopped.record.state)
        assertTrue(fixture.prepareFinished.isCompleted)
        assertTrue(fixture.prepareFinishedAtHook)
        start.join()
        assertTrue(start.isCancelled)
    }

    @Test
    fun `missing persisted owner identity never reports a live generation stopped`() = runBlocking {
        val fixture = Fixture(temporaryFolder.newFolder("missing-identity"))
        fixture.store.row = HarnessRuntimeRecord(
            environmentId = HarnessRuntimeRecord.DEFAULT_ENVIRONMENT_ID,
            state = HarnessRuntimeState.RUNNING,
            generation = "generation-missing-identity",
            port = 39000
        )
        val controller = fixture.controller()

        val stopped = controller.stop()
        assertEquals(HarnessRuntimeState.STOP_REQUESTED, stopped.record.state)
        assertEquals("HARNESS_OWNER_IDENTITY_MISSING", stopped.record.errorCode)

        val forced = controller.forceStop()
        assertEquals(HarnessRuntimeState.FORCE_STOPPING, forced.record.state)
        assertEquals("HARNESS_OWNER_IDENTITY_MISSING", forced.record.errorCode)
        assertTrue(forced.timedOut)
    }

    @Test
    fun `recovery adopts validated ledger identity across launch persistence gap`() = runBlocking {
        val fixture = Fixture(temporaryFolder.newFolder("ledger-recovery"))
        fixture.store.row = HarnessRuntimeRecord(
            environmentId = HarnessRuntimeRecord.DEFAULT_ENVIRONMENT_ID,
            state = HarnessRuntimeState.RUNNING,
            generation = "generation-ledger",
            port = 39000
        )
        fixture.recoveredIdentity = HarnessProcessIdentity(
            brokerPid = 701,
            brokerStartTimeTicks = 88,
            brokerUid = 42,
            processGroupId = 701,
            ownerMarker = "generation-ledger"
        )
        val controller = fixture.controller()

        val recovered = controller.recover()

        assertEquals(HarnessRuntimeState.INTERRUPTED, recovered?.record?.state)
        assertEquals("HARNESS_PROCESS_RECOVERED_FROM_LEDGER", recovered?.record?.errorCode)
        assertFalse(recovered?.timedOut == true)
        assertEquals(1, fixture.forceCalls.get())
    }

    @Test
    fun `recovery without identity or ledger stays explicitly incomplete`() = runBlocking {
        val fixture = Fixture(temporaryFolder.newFolder("ledger-missing"))
        fixture.store.row = HarnessRuntimeRecord(
            environmentId = HarnessRuntimeRecord.DEFAULT_ENVIRONMENT_ID,
            state = HarnessRuntimeState.STARTING,
            generation = "generation-no-ledger",
            port = 39000
        )
        val controller = fixture.controller()

        val recovered = controller.recover()

        assertEquals(HarnessRuntimeState.INTERRUPTED, recovered?.record?.state)
        assertEquals("HARNESS_OWNER_IDENTITY_MISSING", recovered?.record?.errorCode)
        assertTrue(recovered?.timedOut == true)
        assertEquals(0, fixture.forceCalls.get())
    }

    @Test
    fun `listener cancellation is propagated instead of treated as an open listener`() = runBlocking {
        val fixture = Fixture(temporaryFolder.newFolder("listener-cancel"))
        val controller = fixture.controller()
        controller.start()
        fixture.cancelListenerProbe = true

        val error = runCatching { controller.stop() }.exceptionOrNull()

        assertTrue(error is kotlinx.coroutines.CancellationException)
        assertEquals(HarnessRuntimeState.RUNNING, fixture.store.row?.state)
    }

    @Test
    fun `force stop owns a frozen readiness startup independently`() = runBlocking {
        val fixture = Fixture(temporaryFolder.newFolder("frozen-start"))
        val readinessStarted = CompletableDeferred<Unit>()
        val readinessGate = CompletableDeferred<Unit>()
        fixture.readinessStarted = readinessStarted
        fixture.readinessGate = readinessGate
        val controller = fixture.controller()

        val start = async { runCatching { controller.start() } }
        readinessStarted.await()

        val forced = controller.forceStop()

        assertEquals(HarnessRuntimeState.STOPPED, forced.record.state)
        assertEquals(1, fixture.forceCalls.get())
        start.join()
        assertTrue(start.isCancelled)
    }

    @Test
    fun `force cleanup finishes before a suspended force hook`() = runBlocking {
        val fixture = Fixture(temporaryFolder.newFolder("suspended-force-hook"))
        fixture.suspendForceHook = true
        val controller = fixture.controller()
        controller.start()

        val forced = async { controller.forceStop() }

        withTimeout(1_000L) { fixture.forceCleanupFinished.await() }
        assertFalse(fixture.process.alive)
        withTimeout(1_500L) { fixture.forceHookStarted.await() }

        val result = forced.await()
        assertEquals(HarnessRuntimeState.FORCE_STOPPING, result.record.state)
        assertEquals("HARNESS_STOP_HOOK_TIMEOUT", result.record.errorCode)
        assertTrue(result.timedOut)
        assertEquals(1, fixture.forceCalls.get())
    }

    @Test
    fun `force startup cancellation cleans the broker before a suspended hook`() = runBlocking {
        val fixture = Fixture(temporaryFolder.newFolder("suspended-cancel-hook"))
        fixture.suspendForceHook = true
        val readinessStarted = CompletableDeferred<Unit>()
        val readinessGate = CompletableDeferred<Unit>()
        fixture.readinessStarted = readinessStarted
        fixture.readinessGate = readinessGate
        val controller = fixture.controller()

        val start = async { runCatching { controller.start() } }
        readinessStarted.await()

        val forced = async { controller.forceStop() }
        withTimeout(1_000L) { fixture.forceCleanupFinished.await() }
        assertFalse(fixture.process.alive)
        withTimeout(1_500L) { fixture.forceHookStarted.await() }

        val result = forced.await()
        assertEquals(HarnessRuntimeState.FORCE_STOPPING, result.record.state)
        assertEquals("HARNESS_STOP_HOOK_TIMEOUT", result.record.errorCode)
        assertTrue(result.timedOut)
        assertEquals(1, fixture.forceCalls.get())
        start.join()
        assertTrue(start.isCancelled)
    }

    private class Fixture(private val root: File) {
        val store = MemoryStore()
        val gracefulCalls = AtomicInteger()
        val forceCalls = AtomicInteger()
        val launchCalls = AtomicInteger()
        val process = FakeProcessHandle()
        val forceCleanupFinished = CompletableDeferred<Unit>()
        val forceHookStarted = CompletableDeferred<Unit>()
        val gracefulStarted = CompletableDeferred<Unit>()
        val gracefulGate = CompletableDeferred<Unit>()
        val prepareStarted = CompletableDeferred<Unit>()
        val prepareFinished = CompletableDeferred<Unit>()
        val stopModes = mutableListOf<HarnessStopMode>()
        val logs = mutableListOf<HarnessRuntimeLogEvent>()
        var readinessFailure = false
        var suspendReadiness = false
        var listenerOpen = false
        var suspendForceHook = false
        var suspendGraceful = false
        var suspendPrepare = false
        var cancelListenerProbe = false
        var prepareFinishedAtHook = false
        var readinessStarted: CompletableDeferred<Unit>? = null
        var readinessGate: CompletableDeferred<Unit>? = null
        var launchCommand: List<String> = emptyList()
        var recoveredIdentity: HarnessProcessIdentity? = null
        var processExitedOnLaunch = false
        var launchError: Throwable? = null

        fun controller(gracefulResult: Boolean = true): HarnessRuntimeController {
            val paths = environmentPaths(root)
            val payload = payload()
            val supervisor = FakeSupervisor(
                process,
                gracefulResult,
                gracefulCalls,
                forceCalls,
                forceCleanupFinished,
                gracefulStarted,
                gracefulGate,
                { suspendGraceful },
                { recoveredIdentity }
            )
            return HarnessRuntimeController(
                store = store,
                environmentProvider = object : HarnessEnvironmentProvider {
                    override suspend fun prepare(environmentId: String) = paths
                },
                payloadProvider = object : HarnessPayloadProvider {
                    override suspend fun prepare(paths: HarnessEnvironmentPaths) = payload
                },
                binaries = HarnessNativeBinaries(
                    executable(root, "broker"),
                    executable(root, "proot"),
                    executable(root, "loader")
                ),
                processLauncher = object : HarnessProcessLauncher {
                    override fun launch(spec: HarnessLaunchSpec): HarnessProcessHandle {
                        launchError?.let { throw it }
                        launchCalls.incrementAndGet()
                        launchCommand = spec.payload.command
                        process.alive = !processExitedOnLaunch
                        return process
                    }
                },
                readinessProbe = object : HarnessReadinessProbe {
                    override suspend fun awaitReady(
                        origin: String,
                        webToken: String,
                        readinessPath: String,
                        timeoutMs: Long
                    ): HarnessEndpoint {
                        if (readinessFailure) throw HarnessRuntimeException("HARNESS_NOT_READY", "test readiness failure")
                        if (suspendReadiness) awaitCancellation()
                        readinessStarted?.complete(Unit)
                        readinessGate?.await()
                        return HarnessEndpoint(origin, "dsh_session=test")
                    }
                },
                processSupervisor = supervisor,
                hooks = object : HarnessRuntimeHooks {
                    override suspend fun prepareLaunch(request: HarnessHookRequest): HarnessLaunchPreparation {
                        if (suspendPrepare) {
                            prepareStarted.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                prepareFinished.complete(Unit)
                            }
                        }
                        return HarnessLaunchPreparation(webToken = "test-web-token")
                    }

                    override suspend fun beforeStop(request: HarnessStopRequest) {
                        prepareFinishedAtHook = prepareFinished.isCompleted
                        stopModes += request.mode
                        if (request.mode == HarnessStopMode.FORCE && suspendForceHook) {
                            forceHookStarted.complete(Unit)
                            awaitCancellation()
                        }
                    }
                },
                portAllocator = HarnessPortAllocator { preferredPort -> preferredPort ?: 39000 },
                now = { 1_700_000_000_000L },
                logger = HarnessRuntimeMetadataLogger { logs += it },
                listenerProbe = {
                    if (cancelListenerProbe) throw kotlinx.coroutines.CancellationException("listener probe cancelled")
                    listenerOpen
                }
            )
        }

        private fun payload() = HarnessPayload(
            version = "0.1.6-alpha.2",
            commit = "ddefc45fbc7f8e46dd73185e68295696d1297887",
            archiveAsset = "harness/payload.tar.xz",
            archiveSha256 = "a".repeat(64),
            command = listOf("/opt/adt-harness/bin/dsh", "serve"),
            healthPath = "/rpc/health"
        )
    }

    private class MemoryStore : HarnessRuntimeStore {
        var row: HarnessRuntimeRecord? = null

        override suspend fun current(): HarnessRuntimeRecord? = row

        override suspend fun beginStart(record: HarnessRuntimeRecord): Boolean {
            if (row?.state in setOf(
                    HarnessRuntimeState.STARTING,
                    HarnessRuntimeState.RUNNING,
                    HarnessRuntimeState.STOP_REQUESTED,
                    HarnessRuntimeState.FORCE_STOPPING
                )
            ) return false
            row = record
            return true
        }

        override suspend fun compareAndSet(
            expectedStates: Set<HarnessRuntimeState>,
            record: HarnessRuntimeRecord
        ): Boolean {
            if (row?.state !in expectedStates || row?.generation != record.generation) return false
            row = record
            return true
        }

        override suspend fun replace(record: HarnessRuntimeRecord) {
            row = record
        }
    }

    private class FakeProcessHandle : HarnessProcessHandle {
        override val brokerPid: Int = 700
        var alive = true
        var processDiagnostics = HarnessProcessDiagnostics()
        var processDiagnosticsAfterExit: HarnessProcessDiagnostics? = null
        var diagnosticsAfterExitCalls = 0
        override fun isAlive() = alive
        override fun requestGracefulStop() = Unit
        override fun requestForceStop() { alive = false }
        override suspend fun awaitExit(timeoutMs: Long) = !alive
        override fun diagnostics() = processDiagnostics
        override suspend fun diagnosticsAfterExit(timeoutMs: Long): HarnessProcessDiagnostics {
            diagnosticsAfterExitCalls += 1
            return processDiagnosticsAfterExit ?: processDiagnostics
        }
    }

    private class FakeSupervisor(
        private val process: FakeProcessHandle,
        private val gracefulResult: Boolean,
        private val gracefulCalls: AtomicInteger,
        private val forceCalls: AtomicInteger,
        private val forceCleanupFinished: CompletableDeferred<Unit>,
        private val gracefulStarted: CompletableDeferred<Unit>,
        private val gracefulGate: CompletableDeferred<Unit>,
        private val suspendGraceful: () -> Boolean,
        private val recoveredIdentityProvider: () -> HarnessProcessIdentity?
    ) : HarnessProcessSupervisor {
        override suspend fun capture(handle: HarnessProcessHandle, spec: HarnessLaunchSpec) =
            HarnessProcessIdentity(
                brokerPid = 700,
                brokerStartTimeTicks = 77,
                brokerUid = 42,
                processGroupId = 701,
                childPid = 701,
                childStartTimeTicks = 88,
                ownerMarker = spec.ownerMarker,
                nodePid = 702,
                nodeStartTimeTicks = 89
            )

        override suspend fun recoverIdentity(ownerMarker: String, expectedUid: Int): HarnessProcessIdentity? =
            recoveredIdentityProvider()?.takeIf {
                it.ownerMarker == ownerMarker && it.brokerUid == expectedUid
            }

        override suspend fun gracefulStop(
            handle: HarnessProcessHandle?,
            identity: HarnessProcessIdentity,
            deadlineMs: Long
        ): Boolean {
            gracefulCalls.incrementAndGet()
            if (suspendGraceful()) {
                gracefulStarted.complete(Unit)
                gracefulGate.await()
            }
            if (gracefulResult) process.alive = false
            return gracefulResult
        }

        override suspend fun forceStop(handle: HarnessProcessHandle?, identity: HarnessProcessIdentity): Boolean {
            forceCalls.incrementAndGet()
            process.alive = false
            forceCleanupFinished.complete(Unit)
            return true
        }

        override fun isOwnerAlive(identity: HarnessProcessIdentity) = process.alive
    }

    private companion object {
        fun environmentPaths(root: File): HarnessEnvironmentPaths {
            val rootfs = File(root, "rootfs").apply { mkdirs() }
            return HarnessEnvironmentPaths(
                environmentId = HarnessRuntimeRecord.DEFAULT_ENVIRONMENT_ID,
                rootfs = rootfs,
                projectsHost = File(root, "projects").apply { mkdirs() },
                dshHomeHost = File(root, "home").apply { mkdirs() },
                tempHost = File(root, "tmp").apply { mkdirs() },
                runHost = File(root, "run").apply { mkdirs() },
                resolverFile = File(root, "resolv.conf").apply { writeText("nameserver 127.0.0.1\n") }
            )
        }

        fun executable(root: File, name: String): File = File(root, name).apply {
            writeText("test")
            setExecutable(true)
        }
    }
}

class HarnessRuntimeSecurityTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `payload archive rejects entries outside opt adt harness`() {
        val root = temporaryFolder.newFolder("archive")
        val archive = File(root, "payload.tar")
        TarArchiveOutputStream(FileOutputStream(archive)).use { output ->
            val entry = TarArchiveEntry("../../escape")
            entry.size = 1
            output.putArchiveEntry(entry)
            output.write(1)
            output.closeArchiveEntry()
            output.finish()
        }

        val error = runCatching {
            HarnessSecureArchiveExtractor.extract(archive, File(root, "out"), "/opt/adt-harness")
        }.exceptionOrNull()

        assertNotNull(error)
        assertFalse(File(root.parentFile, "escape").exists())
    }

    @Test
    fun `payload archive extracts only the pinned install prefix`() {
        val root = temporaryFolder.newFolder("archive-valid")
        val archive = File(root, "payload.tar")
        TarArchiveOutputStream(FileOutputStream(archive)).use { output ->
            val entry = TarArchiveEntry("opt/adt-harness/bin/dsh")
            entry.mode = 0b001001001
            entry.size = 3
            output.putArchiveEntry(entry)
            output.write("dsh".toByteArray())
            output.closeArchiveEntry()
            output.finish()
        }

        HarnessSecureArchiveExtractor.extract(archive, File(root, "out"), "/opt/adt-harness")

        assertEquals("dsh", File(root, "out/opt/adt-harness/bin/dsh").readText())
    }
}
