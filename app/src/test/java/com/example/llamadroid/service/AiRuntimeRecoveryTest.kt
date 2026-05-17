package com.example.llamadroid.service

import android.content.Context
import android.content.Intent
import com.example.llamadroid.data.db.AiRuntimeJobEntity
import com.example.llamadroid.ui.navigation.Screen
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

class AiRuntimeRecoveryTest {

    @Test
    fun `package replace recovery dispatch returns quickly and runs asynchronously`() {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context

        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val elapsedMs = measureTimeMillis {
        AiRuntimeRecovery.dispatch(
            context = context,
            action = Intent.ACTION_BOOT_COMPLETED,
            scope = scope,
            recover = {
                started.countDown()
                release.await(1, TimeUnit.SECONDS)
            }
        )
        }

        assertTrue("dispatch should return quickly", elapsedMs < 200)
        assertTrue("recovery should run on the background scope", started.await(1, TimeUnit.SECONDS))
        release.countDown()
        assertFalse("package replace should not trigger boot recovery anymore", AiRuntimeRecovery.isRelevantAction(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertFalse("irrelevant actions must not be treated as recovery triggers", AiRuntimeRecovery.isRelevantAction("anything_else"))
    }

    @Test
    fun `boot recovery action shows manual resume without foreground service start`() {
        val action = resolveAiRuntimeBootRecoveryAction(
            listOf(runtimeJob(AiRuntimeJobStore.TYPE_AGENT_CHAT))
        )

        assertTrue(action.shouldShowManualResumeNotification)
        assertEquals(Screen.Agent.route, action.manualResumeRoute)
        assertEquals(1, action.recoverableCount)
        assertNull("boot recovery must not request a foreground service start", action.foregroundServiceAction)
    }

    @Test
    fun `boot recovery action routes dataset-only work to dataset screen`() {
        val action = resolveAiRuntimeBootRecoveryAction(
            listOf(runtimeJob(AiRuntimeJobStore.TYPE_DATASET_PIPELINE))
        )

        assertTrue(action.shouldShowManualResumeNotification)
        assertEquals(Screen.Dataset.route, action.manualResumeRoute)
        assertNull("dataset boot recovery must wait for app-open resume", action.foregroundServiceAction)
    }

    @Test
    fun `boot recovery action ignores empty recoverable job list`() {
        val action = resolveAiRuntimeBootRecoveryAction(emptyList())

        assertFalse(action.shouldShowManualResumeNotification)
        assertNull(action.manualResumeRoute)
        assertNull(action.foregroundServiceAction)
    }

    private fun runtimeJob(type: String): AiRuntimeJobEntity {
        return AiRuntimeJobEntity(
            jobId = "job-$type",
            jobKey = "key-$type",
            type = type,
            status = AiRuntimeJobStore.STATUS_RUNNING,
            payloadJson = "{}",
            createdAt = 0L,
            updatedAt = 0L
        )
    }
}
