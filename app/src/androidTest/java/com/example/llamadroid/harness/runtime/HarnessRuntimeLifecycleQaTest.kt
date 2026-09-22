package com.example.llamadroid.harness.runtime

import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.BuildConfig
import com.example.llamadroid.data.proot.AgentProotNativeBinaryProvider
import com.example.llamadroid.harness.HarnessAndroidBridge
import com.example.llamadroid.harness.HarnessCredentialStore
import com.example.llamadroid.harness.client.HarnessAuthResult
import com.example.llamadroid.harness.client.HarnessRpcResult
import com.example.llamadroid.harness.client.OkHttpHarnessClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/**
 * Runs the real pinned DSH Web payload through the Android controller on the x86_64 QA carrier.
 * Unit tests cover synthetic ownership races; these tests prove the signed carrier reaches the
 * authenticated RPC readiness path and that lifecycle cancellation owns the real process.
 */
@RunWith(AndroidJUnit4::class)
class HarnessRuntimeLifecycleQaTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun qaPinnedHarnessStartsRepeatedlyAndForceStops(): Unit = runBlocking {
        assumeQaCarrier()
        val fixture = QaRuntimeFixture(context)
        try {
            val first = try {
                fixture.controller.start(HarnessStartRequest(readinessTimeoutMs = 60_000L))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // The controller deliberately keeps process output out of durable diagnostics.
                // Surface only its reviewed metadata fields here so a carrier startup failure
                // identifies the phase/code without retaining payload or terminal content.
                throw AssertionError(fixture.sanitizedStartupFailure())
            }
            assertEquals(HarnessRuntimeState.RUNNING, first.record.state)
            assertNotNull(first.endpoint)
            assertNotNull("Real DSH Node identity was not captured", first.record.nodePid)

            val repeated = fixture.controller.start()
            assertEquals(HarnessRuntimeState.RUNNING, repeated.record.state)
            assertEquals(first.record.generation, repeated.record.generation)
            assertEquals("Repeated Start changed the broker PID", first.record.brokerPid, repeated.record.brokerPid)
            assertEquals(
                "Repeated Start changed the broker start-time identity",
                first.record.processStartTicks,
                repeated.record.processStartTicks
            )
            assertEquals("Repeated Start changed the Node PID", first.record.nodePid, repeated.record.nodePid)
            assertEquals(
                "Repeated Start changed the Node start-time identity",
                first.record.nodeStartTicks,
                repeated.record.nodeStartTicks
            )
            assertEquals("Repeated Start changed the listener port", first.record.port, repeated.record.port)

            // Freeze the actual Node event loop: Force cannot rely on its HTTP shutdown route.
            // A missing/recycled PID is a failed freeze observation, never evidence of a
            // naturally exited process being cleaned by Force.
            val nodePid = requireNotNull(first.record.nodePid)
            val nodeStartTicks = requireNotNull(first.record.nodeStartTicks)
            assertNotNull("Node identity changed before the freeze signal", readProcessState(nodePid, nodeStartTicks))
            android.system.Os.kill(nodePid, android.system.OsConstants.SIGSTOP)
            withTimeout(5_000L) {
                while (true) {
                    when (readProcessState(nodePid, nodeStartTicks)) {
                        'T', 't' -> break
                        null -> throw AssertionError(
                            "Real DSH Node exited or its PID was recycled before SIGSTOP was observed"
                        )
                        else -> kotlinx.coroutines.delay(25L)
                    }
                }
            }

            val gracefulStartedNanos = System.nanoTime()
            val graceful = withTimeout(15_000L) { fixture.controller.stop() }
            val gracefulElapsedMs = (System.nanoTime() - gracefulStartedNanos) / 1_000_000L
            assertTrue(
                "Frozen graceful stop returned outside its ten-second budget: ${gracefulElapsedMs}ms",
                gracefulElapsedMs in 8_000L..12_500L
            )
            assertTrue("Frozen Node was incorrectly reported as a graceful exit", graceful.timedOut)
            assertEquals(HarnessRuntimeState.STOP_REQUESTED, graceful.record.state)
            assertEquals("HARNESS_GRACEFUL_STOP_TIMEOUT", graceful.record.errorCode)
            assertNotNull("Graceful stop did not persist its request timestamp", graceful.record.stopRequestedAt)
            assertNull("Graceful timeout escalated to Force", graceful.record.forceStopRequestedAt)
            assertNull("A timed-out graceful stop incorrectly ended the generation", graceful.record.endedAt)

            val stopped = fixture.controller.forceStop()
            assertEquals(HarnessRuntimeState.STOPPED, stopped.record.state)
            assertNotNull("Force stop did not persist its request timestamp", stopped.record.forceStopRequestedAt)
            assertNotNull("Force stop did not persist an end timestamp", stopped.record.endedAt)
            assertTrue("Harness listener remained open", !fixture.listenerOpen(stopped.record.port))
            assertFalse(
                "Broker or a detached descendant survived Force",
                fixture.ownerAlive(first.record)
            )
            assertProcessNotRunning(
                "The frozen Node survived Force",
                requireNotNull(first.record.nodePid),
                requireNotNull(first.record.nodeStartTicks)
            )
            assertProcessNotRunning(
                "The broker survived Force",
                requireNotNull(first.record.brokerPid),
                requireNotNull(first.record.processStartTicks)
            )

            val restarted = fixture.controller.start(HarnessStartRequest(readinessTimeoutMs = 60_000L))
            assertEquals(HarnessRuntimeState.RUNNING, restarted.record.state)
            assertNotEquals("A new start reused the stopped generation", first.record.generation, restarted.record.generation)
            assertTrue(
                "Restart reused the stopped broker identity",
                first.record.brokerPid != restarted.record.brokerPid ||
                    first.record.processStartTicks != restarted.record.processStartTicks
            )
            assertTrue(
                "Restart reused the stopped Node identity",
                first.record.nodePid != restarted.record.nodePid ||
                    first.record.nodeStartTicks != restarted.record.nodeStartTicks
            )
            assertNull("Restart replayed a previous stop request", restarted.record.stopRequestedAt)
            assertNull("Restart replayed a previous Force request", restarted.record.forceStopRequestedAt)
            assertNull("Restart replayed a previous end timestamp", restarted.record.endedAt)
            val restartedStopped = fixture.controller.forceStop()
            assertEquals(HarnessRuntimeState.STOPPED, restartedStopped.record.state)
            assertFalse("Restarted owner survived Force", fixture.ownerAlive(restarted.record))
        } finally {
            try {
                fixture.controller.forceStop()
            } catch (_: Throwable) {
                // The assertion above owns the failure; cleanup remains best effort.
            }
            fixture.close()
        }
    }

    @Test
    fun qaAuthenticatedClientCompletesSessionAndCapabilitiesRpc(): Unit = runBlocking {
        assumeQaCarrier()
        val fixture = QaRuntimeFixture(context)
        val authenticatedClient = OkHttpHarnessClient()
        try {
            val started = fixture.controller.start(HarnessStartRequest(readinessTimeoutMs = 60_000L))
            val endpoint = requireNotNull(started.endpoint)
            assertTrue(
                "Readiness cookie could not be adopted by the app client",
                authenticatedClient.adoptAuthenticatedEndpoint(endpoint.origin, endpoint.cookieHeader) is HarnessAuthResult.Success
            )
            val shutdownResult = CompletableDeferred<Result<HarnessRpcResult>>()
            fixture.beforeStopAction = { request ->
                assertEquals(HarnessStopMode.GRACEFUL, request.mode)
                // Exercise the same authenticated shutdown hook used by HarnessAppRuntime while
                // the loopback client and Android bridge are still alive. Preserve cancellation
                // semantics, but always settle the fixture receipt on ordinary failures.
                val outcome = try {
                    Result.success(authenticatedClient.call("adt", "shutdown"))
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    shutdownResult.complete(Result.failure<HarnessRpcResult>(cancelled))
                    throw cancelled
                } catch (error: Throwable) {
                    Result.failure<HarnessRpcResult>(error)
                }
                shutdownResult.complete(outcome)
            }

            val sessions = authenticatedClient.listSessions()
            assertTrue("Authenticated session/list RPC failed: $sessions", sessions is HarnessRpcResult.Success)
            val capabilities = authenticatedClient.call("adt", "capabilities")
            assertTrue("Authenticated adt/capabilities RPC failed: $capabilities", capabilities is HarnessRpcResult.Success)

            val stopped = fixture.controller.stop()
            assertEquals(HarnessRuntimeState.STOPPED, stopped.record.state)
            assertTrue("Graceful stop exceeded its ten-second budget", !stopped.timedOut)
            assertTrue("Harness listener remained open", !fixture.listenerOpen(stopped.record.port))
            val shutdownOutcome = withTimeout(2_000L) { shutdownResult.await() }
            assertTrue(
                "Authenticated adt/shutdown hook threw: ${shutdownOutcome.exceptionOrNull()?.javaClass?.simpleName}",
                shutdownOutcome.isSuccess
            )
            val shutdown = shutdownOutcome.getOrNull()
            assertTrue("Authenticated adt/shutdown hook failed: $shutdown", shutdown is HarnessRpcResult.Success)
        } finally {
            authenticatedClient.close()
            try {
                fixture.controller.forceStop()
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun qaStopDuringFrozenReadinessCancelsStartupWithoutReleasingUnownedProcesses(): Unit = runBlocking {
        assumeQaCarrier()
        val fixture = QaRuntimeFixture(context)
        val readinessStarted = CompletableDeferred<Unit>()
        val readinessGate = CompletableDeferred<Unit>()
        fixture.readinessStarted = readinessStarted
        fixture.readinessGate = readinessGate
        try {
            val start = async {
                try {
                    Result.success(
                    fixture.controller.start(HarnessStartRequest(readinessTimeoutMs = 60_000L))
                    )
                } catch (failure: Throwable) {
                    Result.failure(failure)
                }
            }
            withTimeout(45_000L) { readinessStarted.await() }

            val stopped = fixture.controller.stop()

            assertEquals(HarnessRuntimeState.STOPPED, stopped.record.state)
            assertFalse("Frozen startup left an owned broker or descendant", fixture.ownerAlive(stopped.record))
            assertFalse("Frozen startup left the Harness listener open", fixture.listenerOpen(stopped.record.port))
            assertTrue(start.await().exceptionOrNull() is kotlinx.coroutines.CancellationException)
        } finally {
            try {
                fixture.controller.forceStop()
            } catch (_: Throwable) {
                // The assertion above owns the failure; cleanup remains best effort.
            }
            fixture.close()
        }
    }

    private fun readProcessState(pid: Int, expectedStartTicks: Long): Char? = runCatching {
        val fields = File("/proc/$pid/stat").readText()
            .substringAfterLast(") ", missingDelimiterValue = "")
            .split(Regex("\\s+"))
        val state = fields.getOrNull(0)?.firstOrNull() ?: return@runCatching null
        val startTicks = fields.getOrNull(19)?.toLongOrNull() ?: return@runCatching null
        state.takeIf { startTicks == expectedStartTicks }
    }.getOrNull()

    private suspend fun assertProcessNotRunning(label: String, pid: Int, startTicks: Long) {
        withTimeout(3_000L) {
            while (readProcessState(pid, startTicks)?.let { it != 'Z' } == true) {
                kotlinx.coroutines.delay(25L)
            }
        }
        val state = readProcessState(pid, startTicks)
        assertTrue("$label (state=$state)", state == null || state == 'Z')
    }

    private fun assumeQaCarrier() {
        assumeTrue("This test requires the explicit x86_64 Harness QA build", BuildConfig.HARNESS_QA_X86)
        assertEquals("x86_64", android.os.Build.SUPPORTED_ABIS.firstOrNull())
    }

    private class QaRuntimeFixture(private val context: android.content.Context) {
        private val scratch = File(
            context.cacheDir,
            "adt-harness-lifecycle-${UUID.randomUUID()}"
        ).canonicalFile
        private val assets = runBlocking { HarnessRuntimeQaAssets.acquire(context) }
        private val credentialNamespace = "qa-harness-credentials-${UUID.randomUUID()}"
        private val paths: HarnessEnvironmentPaths
        private val store = MemoryStore()
        private val runtimeEvents = mutableListOf<HarnessRuntimeLogEvent>()
        private val metadataLogger = HarnessRuntimeMetadataLogger { event ->
            synchronized(runtimeEvents) { runtimeEvents += event }
        }
        private val readiness = HttpHarnessReadinessProbe(
            connectTimeoutMs = 2_000,
            readTimeoutMs = 2_000,
            readinessReceipt = File(scratch, "run/harness-ready.json")
        )
        private val actualReadiness = object : HarnessReadinessProbe {
            override suspend fun awaitReady(
                origin: String,
                webToken: String,
                readinessPath: String,
                timeoutMs: Long
            ): HarnessEndpoint {
                readinessStarted?.complete(Unit)
                readinessGate?.await()
                return readiness.awaitReady(origin, webToken, readinessPath, timeoutMs)
            }
        }
        var readinessStarted: CompletableDeferred<Unit>? = null
        var readinessGate: CompletableDeferred<Unit>? = null
        var beforeStopAction: suspend (HarnessStopRequest) -> Unit = {}
        val controller: HarnessRuntimeController
        private val supervisor: AndroidHarnessProcessSupervisor
        private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var bridge: HarnessAndroidBridge? = null
        /** A process launch was attempted before its identity was persisted. */
        private var launchMayHaveStarted = false
        private val credentials = HarnessCredentialStore(object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("$credentialNamespace-$name", mode)
        })

        init {
            try {
                // The QA lease has already prepared the immutable x86 rootfs and payload before
                // any readiness timeout begins. All mutable bind state remains unique to this
                // fixture.
                paths = assets.pathsFor(scratch)
                val binaries = HarnessNativeBinaries(
                    brokerPath = AgentProotNativeBinaryProvider.requireBroker(context),
                    prootPath = AgentProotNativeBinaryProvider.requireProot(context),
                    loaderPath = AgentProotNativeBinaryProvider.locateLoader(context)
                )
                supervisor = AndroidHarnessProcessSupervisor(
                    ledgerFile = File(paths.runHost, "harness-processes.ledger")
                )
                controller = HarnessRuntimeController(
                    store = store,
                    environmentProvider = object : HarnessEnvironmentProvider {
                        override suspend fun prepare(environmentId: String) = paths
                    },
                    payloadProvider = AssetHarnessPayloadProvider(context),
                    binaries = binaries,
                    processLauncher = object : HarnessProcessLauncher {
                        override fun launch(spec: HarnessLaunchSpec): HarnessProcessHandle {
                            launchMayHaveStarted = true
                            return AndroidHarnessProcessLauncher(context).launch(spec)
                        }
                    },
                    readinessProbe = actualReadiness,
                    processSupervisor = supervisor,
                    hooks = object : HarnessRuntimeHooks {
                        override suspend fun prepareLaunch(request: HarnessHookRequest): HarnessLaunchPreparation {
                            stopBridge()
                            val newBridge = HarnessAndroidBridge(
                                generation = request.generation,
                                scope = bridgeScope,
                                handler = ::handleBridgeCall,
                                modelStream = { _, _ -> error("QA_MODEL_STREAM_UNUSED") }
                            )
                            val address = newBridge.startBridge()
                            bridge = newBridge
                            return HarnessLaunchPreparation(
                                webToken = "qa-runtime-token",
                                environment = mapOf(
                                    "ADT_BRIDGE_URL" to address.origin,
                                    "ADT_BRIDGE_TOKEN" to address.token,
                                    "ADT_BRIDGE_GENERATION" to address.generation,
                                    "ADT_LOCALE" to "en"
                                )
                            )
                        }

                        override suspend fun beforeStop(request: HarnessStopRequest) {
                            try {
                                beforeStopAction(request)
                            } finally {
                                if (request.mode == HarnessStopMode.FORCE) stopBridge()
                            }
                        }
                    },
                    portAllocator = AndroidHarnessPortAllocator(),
                    logger = metadataLogger,
                    listenerProbe = { port -> listenerOpen(port) }
                )
            } catch (error: Throwable) {
                assets.close()
                throw error
            }
        }

        fun listenerOpen(port: Int?): Boolean = port?.let(::listenerOpen) ?: false

        suspend fun sanitizedStartupFailure(): String {
            val event = synchronized(runtimeEvents) {
                runtimeEvents.asReversed().firstOrNull { candidate ->
                    candidate.event in setOf("startup_output", "process_exited", "start_failed")
                }
            }
            val record = store.current()
            val code = safeDiagnosticCode(event?.errorCode)
                ?: safeDiagnosticCode(record?.errorCode)
                ?: "UNKNOWN"
            val phase = safeDiagnosticCode(event?.phase) ?: "UNKNOWN"
            val exitCode = event?.exitCode?.toString() ?: "UNKNOWN"
            return "Harness QA startup failed: code=$code phase=$phase exitCode=$exitCode " +
                "state=${record?.state?.name ?: "UNKNOWN"}"
        }

        fun ownerAlive(record: HarnessRuntimeRecord): Boolean {
            val brokerPid = record.brokerPid ?: return false
            val brokerStart = record.processStartTicks ?: return false
            val group = record.processGroupId ?: return false
            return supervisor.isOwnerAlive(
                HarnessProcessIdentity(
                    brokerPid = brokerPid,
                    brokerStartTimeTicks = brokerStart,
                    brokerUid = android.os.Process.myUid(),
                    processGroupId = group,
                    childPid = record.childPid,
                    childStartTimeTicks = record.childStartTicks,
                    ownerMarker = record.generation,
                    nodePid = record.nodePid,
                    nodeStartTimeTicks = record.nodeStartTicks
                )
            )
        }

        suspend fun close() {
            try {
                stopBridge()
                bridgeScope.cancel()
                val current = store.current()
                val ownerGone = when {
                    current == null -> true
                    current.state != HarnessRuntimeState.STOPPED -> false
                    current.brokerPid == null -> !launchMayHaveStarted
                    else -> !ownerAlive(current) && !listenerOpen(current.port)
                }
                if (!ownerGone) {
                    System.err.println(
                        "Harness QA fixture retained at ${scratch.absolutePath}; " +
                            "native ownership was not proven stopped (state=${current?.state})"
                    )
                    throw AssertionError("Harness QA cleanup was incomplete; fixture was retained")
                }
                check(scratch.deleteRecursively()) {
                    "Unable to delete Harness QA scratch directory ${scratch.absolutePath}"
                }
            } finally {
                // Keep the fixed QA environment for subsequent tests and the suite-final owner
                // check. Only this fixture's mutable state is removed above.
                assets.close()
            }
        }

        private fun stopBridge() {
            bridge?.stopBridge()
            bridge = null
        }

        private fun safeDiagnosticCode(value: String?): String? = value
            ?.takeIf { it.length <= 96 && it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,95}")) }

        private suspend fun handleBridgeCall(
            method: String,
            @Suppress("UNUSED_PARAMETER") sessionId: String?,
            args: org.json.JSONObject
        ): Any? = when (method) {
            "credentials.describe" -> credentials.describe(args.getString("ref"))
            "credentials.resolve" -> credentials.resolve(args.getString("ref"))?.let { value ->
                org.json.JSONObject().put("value", value).put("source", "android-keystore")
            }
            "credentials.set" -> credentials.set(args.getString("ref"), args.getString("value")).let { true }
            "credentials.unset" -> credentials.unset(args.getString("ref")).let { true }
            "credentials.readRecord" -> credentials.readRecord(args.getString("key"))
            "credentials.describeRecord" -> credentials.describeRecord(args.getString("key"))
            "credentials.listRecords" -> credentials.listRecords()
            "credentials.replaceRecord" -> credentials.replaceRecord(
                key = args.getString("key"),
                expectedRevision = args.optString("expectedRevision")
                    .takeIf { it.isNotBlank() && it != "null" },
                record = args.optJSONObject("record")
            )
            "provider.models" -> org.json.JSONObject().put("object", "list").put("data", org.json.JSONArray())
            else -> error("QA_BRIDGE_METHOD_UNSUPPORTED")
        }

        private fun listenerOpen(port: Int): Boolean = runCatching {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(InetAddress.getByName("127.0.0.1"), port),
                    200
                )
            }
            true
        }.getOrDefault(false)

        private class MemoryStore : HarnessRuntimeStore {
            private var row: HarnessRuntimeRecord? = null

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
    }
}
