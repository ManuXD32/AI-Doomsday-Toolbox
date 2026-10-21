package com.example.llamadroid.service.delegation

import com.example.llamadroid.data.model.NativeChatDelegateTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegateThreadReuseTest {
    private fun task(id: Long, created: Long, ws: String?, session: String?) = NativeChatDelegateTaskEntity(
        id = id, chatId = 1, title = "t", request = "r", runtimeId = "rt", workspaceId = ws,
        harnessSessionId = session, createdAt = created
    )

    @Test
    fun projectWithoutThreadReusesTheMostRecentThreadOfThatProject() {
        val tasks = listOf(task(1, 10, "w1", "s-old"), task(2, 20, "w1", "s-new"), task(3, 30, "w2", "s-other"))
        assertEquals("s-new", DelegateThreadReuse.lastThread(tasks, "w1"))
    }

    @Test
    fun noThreadForTheProjectMeansANewThreadIsCreated() {
        val tasks = listOf(task(1, 10, "w2", "s2"), task(2, 20, "w1", null))
        assertNull(DelegateThreadReuse.lastThread(tasks, "w1"))
        assertNull(DelegateThreadReuse.lastThread(emptyList(), "w1"))
    }

    @Test
    fun systemNoteIsPrefixed() {
        assertTrue(DelegateSubagentPrompt.systemNote(" hi ").startsWith("[System] hi"))
    }
}
