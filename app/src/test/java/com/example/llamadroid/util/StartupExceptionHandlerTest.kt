package com.example.llamadroid.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupExceptionHandlerTest {
    @Test
    fun handlerSwallowsAndLogsFailure() = runBlocking {
        val logs = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + startupExceptionHandler("T") { logs += it })
        scope.launch { throw IllegalStateException("boom") }.join()
        assertEquals(1, logs.size)
        assertTrue(logs[0].contains("IllegalStateException") && logs[0].contains("boom"))
    }

    @Test
    fun handlerSurvivesThrowingLogger() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + startupExceptionHandler("T") { error("log fail") })
        scope.launch { throw RuntimeException("x") }.join()
    }
}
