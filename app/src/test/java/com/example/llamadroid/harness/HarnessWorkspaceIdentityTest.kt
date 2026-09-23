package com.example.llamadroid.harness

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class HarnessWorkspaceIdentityTest {
    @Test
    fun sessionCreateUsesOnlyTheRegisteredWorkspaceOrCwdFallback() {
        val registered = parseHarnessPreparedWorkspace(Json.parseToJsonElement(
            """{"workspace":{"workspaceId":"dsh-group-1","path":"/workspace/projects/prueba2","title":"prueba2","sessionIds":[]},"created":true}"""
        ))
        val request = harnessSessionCreateLocation("/workspace/projects/prueba2", registered)
        assertEquals(setOf("workspaceId"), request.keys)
        assertEquals("dsh-group-1", request["workspaceId"]?.jsonPrimitive?.content)

        val fallback = harnessSessionCreateLocation("/workspace/projects/prueba2", null)
        assertEquals(setOf("cwd"), fallback.keys)
        assertEquals("/workspace/projects/prueba2", fallback["cwd"]?.jsonPrimitive?.content)

        val stale = harnessSessionCreateLocation("/workspace/projects/prueba2", registered?.copy(
            guestPath = "/workspace/projects/other"
        ))
        assertEquals(setOf("cwd"), stale.keys)
    }

    @Test
    fun parsesDirectAndNestedPrepareWorkspaceIdentity() {
        val direct = parseHarnessPreparedWorkspace(
            Json.parseToJsonElement(
                """{"workspaceId":"dsh-1","title":"prueba2","guestPath":"/workspace/projects/prueba2/","associationStatus":"associated"}"""
            )
        )
        assertEquals("dsh-1", direct?.workspaceId)
        assertEquals("prueba2", direct?.title)
        assertEquals("/workspace/projects/prueba2", direct?.guestPath)

        val nested = parseHarnessPreparedWorkspace(
            Json.parseToJsonElement(
                """{"ok":true,"result":{"id":"dsh-2","name":"nested","cwd":"/workspace/projects/nested"}}"""
            )
        )
        assertEquals("dsh-2", nested?.workspaceId)
        assertEquals("nested", nested?.title)
        assertEquals("/workspace/projects/nested", nested?.guestPath)
    }

    @Test
    fun rejectsIncompleteOrUnsafePrepareWorkspaceIdentity() {
        assertNull(parseHarnessPreparedWorkspace(Json.parseToJsonElement("""{"workspaceId":"only-id"}""")))
        assertNull(
            parseHarnessPreparedWorkspace(
                Json.parseToJsonElement("""{"workspaceId":"dsh","guestPath":"/workspace/projects/../secret"}""")
            )
        )
        assertFalse(runCatching { normalizeHarnessGuestPath("workspace/projects/no-leading-slash") }.isSuccess)
        assertFalse(runCatching { normalizeHarnessGuestPath("/workspace/projects/\u0000bad") }.isSuccess)
    }

    @Test
    fun normalizesEquivalentGuestPaths() {
        assertEquals(
            "/workspace/projects/prueba2/source",
            normalizeHarnessGuestPath("/workspace/projects/./prueba2//source/"),
        )
        assertEquals("/", normalizeHarnessGuestPath("///"))
    }

    @Test
    fun acceptsOnlyNewerEventsPerSessionAndResetsOnReconnect() {
        val sequencer = HarnessSessionEventSequencer()

        assertTrue(sequencer.accept("session-a", 1))
        assertFalse(sequencer.accept("session-a", 1))
        assertFalse(sequencer.accept("session-a", 0))
        assertTrue(sequencer.accept("session-a", 2))
        assertTrue(sequencer.accept("session-b", 1))
        assertEquals(2L, sequencer.cursor("session-a"))

        sequencer.advance("session-a", 4)
        assertFalse(sequencer.accept("session-a", 4))
        assertTrue(sequencer.accept("session-a", 5))
        sequencer.reset("session-a")
        assertTrue(sequencer.accept("session-a", 1))
        assertEquals(1L, sequencer.cursor("session-a"))
    }
}
