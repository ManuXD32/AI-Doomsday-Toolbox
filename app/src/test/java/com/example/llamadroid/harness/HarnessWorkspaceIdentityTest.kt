package com.example.llamadroid.harness

import com.example.llamadroid.data.db.HarnessWorkspaceEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class HarnessWorkspaceIdentityTest {
    @Test
    fun canonicalGroupCanBeSharedOnlyByAliasesOfTheExactGuestProject() {
        val owner = HarnessWorkspaceEntity("owner", "LOCAL_PROOT", "prueba2", title = "Prueba",
            guestPath = "/workspace/projects/prueba2")
        val alias = owner.copy(id = "alias", backend = "LOCAL_SANDBOX")
        assertTrue(harnessAliasesMayShareGroup(owner, alias))
        assertFalse(harnessAliasesMayShareGroup(owner, alias.copy(guestPath = "/workspace/projects/other")))
        assertFalse(harnessAliasesMayShareGroup(owner, alias.copy(projectFolder = "other")))
    }

    @Test
    fun legacySubfolderSessionsStayInProjectButCannotClaimItsCanonicalGroup() {
        assertTrue(harnessSessionPathMatchesProject(
            "/workspace/projects/prueba2/src", "/workspace/projects/prueba2", exact = false))
        assertFalse(harnessSessionPathMatchesProject(
            "/workspace/projects/prueba2/src", "/workspace/projects/prueba2", exact = true))
        assertFalse(harnessSessionPathMatchesProject(
            "/workspace/projects/prueba20", "/workspace/projects/prueba2", exact = false))
    }

    @Test
    fun workspaceImportPolicySkipsUnmanagedRootButPreservesManagedPathChecks() {
        assertTrue(isManagedProjectGuestPath("/workspace/projects/prueba2"))
        assertTrue(isManagedProjectGuestPath("/workspace/projects/prueba2/src"))
        // Structural candidates still reach the repository's path validation.
        assertTrue(isManagedProjectGuestPath("/workspace/projects/../outside"))
        assertTrue(isManagedProjectGuestPath("/workspace/projects//outside"))
        assertFalse(isManagedProjectGuestPath("/workspace"))
        assertFalse(isManagedProjectGuestPath("/workspace/projects"))

        assertFalse(shouldImportHarnessSessionWorkspace(
            sessionAlreadyIndexed = false,
            cwdMatchesRegisteredWorkspace = false,
            cwd = "/workspace",
        ))
        assertTrue(shouldImportHarnessSessionWorkspace(
            sessionAlreadyIndexed = true,
            cwdMatchesRegisteredWorkspace = false,
            cwd = "/workspace",
        ))
        assertTrue(shouldImportHarnessSessionWorkspace(
            sessionAlreadyIndexed = false,
            cwdMatchesRegisteredWorkspace = true,
            cwd = "/workspace/remote/known-workspace",
        ))
    }

    @Test
    fun sessionCreateRequiresTheExactRegisteredWorkspace() {
        val registered = requireNotNull(parseHarnessPreparedWorkspace(Json.parseToJsonElement(
            """{"workspace":{"workspaceId":"dsh-group-1","path":"/workspace/projects/prueba2","title":"prueba2","sessionIds":[]},"created":true}"""
        )))
        val request = harnessSessionCreateLocation("/workspace/projects/prueba2", registered)
        assertEquals(setOf("workspaceId"), request.keys)
        assertEquals("dsh-group-1", request["workspaceId"]?.jsonPrimitive?.content)

        assertFalse(runCatching {
            harnessSessionCreateLocation("/workspace/projects/prueba2", registered.copy(
                guestPath = "/workspace/projects/other"
            ))
        }.isSuccess)
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

    @Test
    fun rejectsAnOlderWorkspaceGroupSnapshotAfterANewerRename() {
        val guard = HarnessWorkspaceGroupRevisionGuard()
        assertTrue(guard.accept("group", "2026-09-24T02:00:01Z"))
        assertTrue(guard.accept("group", "2026-09-24T02:00:02Z"))
        assertFalse(guard.accept("group", "2026-09-24T02:00:01Z"))
        assertTrue(guard.accept("other", "2026-09-24T02:00:01Z"))
        assertTrue(guard.accept("group", "2026-09-24T02:00:02Z"))
    }
}
