package com.example.llamadroid.worlds

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WorldRepositoryTest {
    private lateinit var base: File
    private lateinit var root: File
    private lateinit var repo: WorldRepository
    private val html = "```html\n<html><head></head><body><script type=\"module\">import * as THREE from 'three';</script></body></html>\n```"

    @Before
    fun setUp() {
        base = Files.createTempDirectory("worlds-test").toFile()
        root = File(base, "worlds")
        repo = WorldRepository(root) { mapOf("goTo" to "Go") }
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    @Test
    fun saveListRenameDeleteRoundTrip() = runBlocking {
        val a = repo.save("  First  ", html, 7L, 9L)
        Thread.sleep(5)
        val b = repo.save("", html, null, null)
        assertEquals("First", a.title)
        assertEquals("3D world", b.title)
        assertEquals(listOf(b.id, a.id), repo.list().map { it.id })
        assertEquals(7L, repo.list().last().chatId)
        assertNull(repo.list().first().chatId)

        val renamed = repo.rename(a.id, "Elephants")
        assertEquals("Elephants", renamed?.title)
        assertEquals(a.id, repo.list().first().id) // rename bumps updatedAt

        assertTrue(repo.delete(a.id))
        assertFalse(File(root, a.id).exists())
        assertEquals(listOf(b.id), repo.list().map { it.id })
    }

    @Test
    fun pageIsRebuiltFromSource() = runBlocking {
        val meta = repo.save("w", html, null, null)
        val page = repo.page(meta.id)
        assertNotNull(page)
        assertTrue(page!!.contains("/adt-shim.js"))
        assertTrue(page.contains("\"goTo\":\"Go\""))
        assertEquals(html, repo.source(meta.id))
    }

    @Test
    fun pathTraversalIsRejected() = runBlocking {
        val meta = repo.save("w", html, null, null)
        File(base, "secret").mkdirs()
        File(base, "secret/source.txt").writeText("<html>secret</html>")
        listOf("../secret", "..", ".", "a/b", "", "x".repeat(65), "${meta.id}/..", "..\\secret").forEach { bad ->
            assertNull(bad, repo.page(bad))
            assertNull(bad, repo.source(bad))
            assertNull(bad, repo.rename(bad, "hacked"))
            assertFalse(bad, repo.delete(bad))
        }
        assertTrue(File(base, "secret/source.txt").exists())
        assertEquals(1, repo.list().size)
    }
}
