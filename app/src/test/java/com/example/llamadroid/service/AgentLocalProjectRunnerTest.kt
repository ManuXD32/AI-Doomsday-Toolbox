package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLocalProjectRunnerTest {
    @Test
    fun `Chaquopy start uses the Platform base parameter`() {
        val pythonClass = Class.forName("com.chaquo.python.Python")
        val platformClass = Class.forName("com.chaquo.python.Python\$Platform")
        val androidPlatformClass = Class.forName("com.chaquo.python.android.AndroidPlatform")

        val start = pythonClass.getMethod("start", platformClass)
        assertEquals(platformClass, start.parameterTypes.single())
        assertTrue(platformClass.isAssignableFrom(androidPlatformClass))
        assertTrue(runCatching { pythonClass.getMethod("start", androidPlatformClass) }.isFailure)
    }
}
