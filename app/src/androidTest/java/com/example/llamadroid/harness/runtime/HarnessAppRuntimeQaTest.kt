package com.example.llamadroid.harness.runtime

import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import com.example.llamadroid.BuildConfig
import com.example.llamadroid.data.db.HarnessRuntimeEntity
import com.example.llamadroid.harness.HarnessAppRuntime
import com.example.llamadroid.service.AgentForegroundService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Exercises the production AppRuntime hook and foreground lease on the explicit x86 QA carrier.
 * It is opt-in because it uses the real Room row and shared Harness files in the QA application.
 */
@RunWith(AndroidJUnit4::class)
class HarnessAppRuntimeQaTest {
    @Test
    fun qaProductionRuntimeShutdownKeepsForegroundLeaseUntilStop(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("This test requires the explicit x86_64 Harness QA build", BuildConfig.HARNESS_QA_X86)
        assumeTrue(
            "Pass harness_app_runtime_qa=true on the isolated QA carrier",
            arguments.getString("harness_app_runtime_qa") == "true"
        )
        assumeTrue("The Harness QA carrier must run x86_64", android.os.Build.SUPPORTED_ABIS.firstOrNull() == "x86_64")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            val serviceInfo = context.packageManager.getServiceInfo(
                android.content.ComponentName(context, AgentForegroundService::class.java),
                android.content.pm.PackageManager.ComponentInfoFlags.of(0)
            )
            assertTrue("The user-managed runtime must use its declared foreground-service type", serviceInfo.foregroundServiceType and android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0)
        }
        val runtime = HarnessAppRuntime.get(context)
        val foregroundCountBefore = AgentForegroundService.activeRuntimeCount()
        val activity = ActivityScenario.launch(ComponentActivity::class.java)
        var primaryFailure: Throwable? = null
        try {
            runtime.start()
            val running = awaitState(runtime, "RUNNING")
            assertNotNull("Production AppRuntime did not publish an endpoint", runtime.endpoint.value)
            assertNull("A successful start retained an error", runtime.errorCode.value)
            val firstClient = runtime.client
            runtime.start()
            assertTrue("An idempotent Start replaced the live client", runtime.client === firstClient)
            assertEquals(running.generation, runtime.status.value?.generation)
            assertTrue(
                "Harness start did not acquire a foreground lease",
                AgentForegroundService.activeRuntimeCount() > foregroundCountBefore
            )
            val foregroundCountWhileRunning = AgentForegroundService.activeRuntimeCount()
            activity.moveToState(Lifecycle.State.CREATED)
            delay(750)
            assertEquals("Backgrounding stopped the shared runtime", "RUNNING", runtime.status.value?.state)
            assertEquals(foregroundCountWhileRunning, AgentForegroundService.activeRuntimeCount())
            activity.moveToState(Lifecycle.State.RESUMED)

            // A stale idle stop must be harmless while the shared Harness lease is held.
            AgentForegroundService.stop(context)
            delay(750)
            val foregroundCountAfterIdleStop = AgentForegroundService.activeRuntimeCount()
            assertEquals(
                "An idle foreground stop released the active Harness lease",
                foregroundCountWhileRunning,
                foregroundCountAfterIdleStop
            )
            assertTrue(foregroundCountAfterIdleStop > foregroundCountBefore)
            assertEquals("Idle stop changed the running generation", running.generation, runtime.status.value?.generation)
            assertEquals("Idle stop changed the runtime state", "RUNNING", runtime.status.value?.state)

            // HarnessAppRuntime.beforeStop sends the authenticated adt/shutdown request while
            // its bridge is still alive; the controller then owns the native graceful teardown.
            runtime.stop()
            val stopped = awaitState(runtime, "STOPPED")
            assertEquals(running.generation, stopped.generation)
            assertNull("Production stop retained the loopback endpoint", runtime.endpoint.value)
            assertNull("Production shutdown hook reported an error", runtime.errorCode.value)
            awaitForegroundCount(foregroundCountBefore)
            assertFalse("The Harness foreground lease survived a completed stop", AgentForegroundService.activeRuntimeCount() > foregroundCountBefore)

            runtime.start()
            val restarted = awaitState(runtime, "RUNNING")
            assertTrue("Restart reused the terminated process generation", running.generation != restarted.generation)
            val brokerPidLong = requireNotNull(restarted.brokerPid)
            require(brokerPidLong in 1..Int.MAX_VALUE.toLong())
            val brokerPid = brokerPidLong.toInt()
            assertTrue("The runtime owner must not be the application process", brokerPid != android.os.Process.myPid())
            val startTicks = File("/proc/$brokerPid/stat").readText().substringAfterLast(") ").split(' ')[19].toLong()
            assertEquals("Refusing to kill a reused PID", restarted.processStartTicks, startTicks)
            val origin = requireNotNull(runtime.endpoint.value).origin
            val port = java.net.URI(origin).port
            android.os.Process.killProcess(brokerPid)
            awaitState(runtime, "INTERRUPTED")
            withTimeout(30_000L) {
                while (runtime.endpoint.value != null || runtime.client != null) delay(100L)
            }
            awaitForegroundCount(foregroundCountBefore)
            assertEquals("Unexpected process loss did not produce the explicit recovery state", "HARNESS_PROCESS_LOST", runtime.errorCode.value)
            assertFalse("Process-loss recovery retained the Harness listener", isListening(port))

            runtime.start()
            val recovered = awaitState(runtime, "RUNNING")
            assertTrue(recovered.generation != restarted.generation)
            assertNull("Explicit restart did not clear the process-loss error", runtime.errorCode.value)
            runtime.stop()
            awaitState(runtime, "STOPPED")
            awaitForegroundCount(foregroundCountBefore)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                runtime.forceStop()
            } catch (cleanupFailure: Throwable) {
                val failure = primaryFailure
                if (failure == null) throw cleanupFailure
                failure.addSuppressed(cleanupFailure)
            }
            activity.close()
        }
    }

    private fun isListening(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
        true
    }.getOrDefault(false)

    private suspend fun awaitState(runtime: HarnessAppRuntime, state: String): HarnessRuntimeEntity =
        withTimeout(120_000L) {
            var current = runtime.status.value
            while (current?.state != state) { delay(100L); current = runtime.status.value }
            requireNotNull(current)
        }

    private suspend fun awaitForegroundCount(expected: Int) {
        withTimeout(10_000L) {
            while (AgentForegroundService.activeRuntimeCount() != expected) delay(100L)
        }
    }
}
