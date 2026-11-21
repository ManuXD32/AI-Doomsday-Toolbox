package com.example.llamadroid.worlds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldPageRegistryTest {
    @Test
    fun publishedUrlServesExactlyThatPage() {
        val holder = WorldPageHolder()
        val url = holder.publish("<html>a</html>")
        assertTrue(url.startsWith("https://worlds.adt.local/__page/"))
        assertTrue(url.endsWith("/index.html"))
        assertEquals("<html>a</html>", holder.htmlFor(url))
        assertNull(holder.htmlFor(url + "?x=1"))
        assertNull(holder.htmlFor("https://worlds.adt.local/__page/other/index.html"))
        assertNull(holder.htmlFor("https://evil.example/__page/x/index.html"))
    }

    @Test
    fun republishInvalidatesOldUrl() {
        val holder = WorldPageHolder()
        val first = holder.publish("one")
        val second = holder.publish("two")
        assertNotEquals(first, second)
        assertNull(holder.htmlFor(first))
        assertEquals("two", holder.htmlFor(second))
    }

    @Test
    fun diagnosticsKeepLast40AndTruncate() {
        val d = WorldDiagnostics()
        repeat(60) { d.recordProblem("404", "https://x/$it") }
        assertEquals(40, d.problemList().size)
        assertTrue(d.problemList().last().endsWith("/59"))
        d.recordConsole("ERROR", "x".repeat(900), "https://worlds.adt.local/__page/t/index.html", 7)
        val line = d.consoleList().single()
        assertTrue(line.length < 330)
        assertTrue(line.endsWith("(index.html:7)"))
        assertEquals(1, d.consoleErrorCount())
        d.recordServed(); d.recordServed()
        assertEquals(2, d.servedCount)
    }
}
