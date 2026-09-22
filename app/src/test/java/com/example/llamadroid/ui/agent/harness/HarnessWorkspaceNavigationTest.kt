package com.example.llamadroid.ui.agent.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessWorkspaceNavigationTest {
    @Test
    fun everyPanelHasABoundedExitThroughItsSessionAndProjects() {
        for (tab in HarnessSurfaceTab.entries) {
            var current = HarnessNavigationLocation("project-a", tab)
            var steps = 0
            while (true) {
                val parent = harnessNavigationParent(current, true) ?: break
                steps++
                assertTrue("Back must reach the dashboard in at most two internal steps", steps <= 2)
                if (current.tab != HarnessSurfaceTab.CONVERSATION) assertEquals("project-a", parent.projectId)
                current = parent
            }
            assertEquals(HarnessNavigationLocation(null, HarnessSurfaceTab.CONVERSATION), current)
        }
        assertEquals(
            HarnessNavigationLocation(null, HarnessSurfaceTab.CONVERSATION),
            harnessNavigationParent(HarnessNavigationLocation(null, HarnessSurfaceTab.RUNTIME), true),
        )
    }

    @Test
    fun historyKeepsProjectIdentityAcrossTabsAndReturnsToProjectsBeforeLeavingHarness() {
        val landing = HarnessNavigationLocation(null, HarnessSurfaceTab.CONVERSATION)
        val project = HarnessNavigationLocation("project-a", HarnessSurfaceTab.CONVERSATION)
        val settings = HarnessNavigationLocation("project-a", HarnessSurfaceTab.SETTINGS)
        val history = harnessNavigationPush(
            harnessNavigationPush(
                listOf(harnessNavigationLocationKey(landing)),
                project,
            ),
            settings,
        )

        val fromSettings = harnessNavigationBack(history)
        assertEquals(project, fromSettings.destination)
        val fromProject = harnessNavigationBack(fromSettings.history)
        assertEquals(landing, fromProject.destination)
        assertEquals(1, fromProject.history.size)
        assertFalse(harnessNavigationBack(fromProject.history).destination != null)
    }

    @Test
    fun pushingTheCurrentDestinationDoesNotDuplicateHistory() {
        val conversation = HarnessNavigationLocation("project-a", HarnessSurfaceTab.CONVERSATION)
        val key = harnessNavigationLocationKey(conversation)

        assertEquals(
            listOf(key),
            harnessNavigationPush(listOf(key), conversation),
        )
        assertEquals(conversation, harnessNavigationLocationFromKey(key))
    }

    @Test
    fun removedProjectsArePrunedWithoutLosingTheLandingRoot() {
        val landing = HarnessNavigationLocation(null, HarnessSurfaceTab.CONVERSATION)
        val removed = HarnessNavigationLocation("removed", HarnessSurfaceTab.CONVERSATION)
        val retained = HarnessNavigationLocation("retained", HarnessSurfaceTab.PLAN)
        val history = listOf(
            harnessNavigationLocationKey(landing),
            harnessNavigationLocationKey(removed),
            harnessNavigationLocationKey(retained),
        )

        val pruned = harnessNavigationPruneProjects(history, setOf("retained"))
        assertEquals(
            listOf(harnessNavigationLocationKey(landing), harnessNavigationLocationKey(retained)),
            pruned,
        )
        assertEquals(
            listOf(harnessNavigationLocationKey(landing)),
            harnessNavigationPruneProjects(
                listOf(harnessNavigationLocationKey(removed)),
                emptySet(),
            ),
        )
    }

    @Test
    fun projectSelectionUsesCanonicalIdInsteadOfDisplayTitle() {
        val projects = listOf(
            HarnessProjectUi(
                id = "workspace-new",
                title = "same name",
                projectFolder = "same-name-9f3a1c2d",
                backendLabel = "Local",
            ),
        )

        assertTrue(isHarnessProjectSelectionValid("workspace-new", projects))
        assertFalse(isHarnessProjectSelectionValid("workspace-deleted", projects))
        assertFalse(isHarnessProjectSelectionValid(null, projects))
    }

    @Test
    fun projectSelectionClearsStaleTranscriptUntilCanonicalSessionReturns() {
        val staleTranscript = listOf(
            HarnessTranscriptItem("stale", HarnessTranscriptRole.ASSISTANT, "old project reply")
        )
        val state = NativeHarnessUiState(
            selectedSessionId = null,
            transcript = staleTranscript,
            questions = listOf(HarnessQuestionUi("stale-question", "Old question", "Old prompt")),
            workspace = HarnessWorkspaceUiState(projectFolder = "old-project"),
        )
        val visible = listOf(
            HarnessSessionUiState(
                id = "new-session",
                title = "New project",
                projectFolder = "new-project",
                backendLabel = "Local",
            )
        )
        val cleared = projectScopedHarnessState(
            state = state,
            visibleSessions = visible,
            visibleSessionIds = visible.mapTo(mutableSetOf()) { it.id },
            projectWorkspace = HarnessWorkspaceUiState(projectFolder = "new-project"),
            projectNavigationEnabled = true,
            selectedProjectPresent = true,
        )

        assertEquals(listOf("new-session"), cleared.sessions.map { it.id })
        assertEquals(null, cleared.selectedSessionId)
        assertTrue(cleared.transcript.isEmpty())
        assertTrue(cleared.questions.isEmpty())
        assertEquals("new-project", cleared.workspace.projectFolder)

        val loadingOrLanding = projectScopedHarnessState(
            state = state,
            visibleSessions = emptyList(),
            visibleSessionIds = emptySet(),
            projectWorkspace = HarnessWorkspaceUiState(projectFolder = "old-project"),
            projectNavigationEnabled = true,
            selectedProjectPresent = false,
        )
        assertTrue(loadingOrLanding.transcript.isEmpty())

        val selected = projectScopedHarnessState(
            state = state.copy(selectedSessionId = "new-session"),
            visibleSessions = visible,
            visibleSessionIds = setOf("new-session"),
            projectWorkspace = HarnessWorkspaceUiState(projectFolder = "new-project"),
            projectNavigationEnabled = true,
            selectedProjectPresent = true,
        )
        assertEquals(staleTranscript, selected.transcript)

        val nonProjectHarness = projectScopedHarnessState(
            state = state,
            visibleSessions = visible,
            visibleSessionIds = setOf("new-session"),
            projectWorkspace = HarnessWorkspaceUiState(projectFolder = "new-project"),
            projectNavigationEnabled = false,
            selectedProjectPresent = false,
        )
        assertEquals(staleTranscript, nonProjectHarness.transcript)
    }

    @Test
    fun runtimeShortcutOnlyAppearsForActionableFailuresOrTimeouts() {
        assertFalse(harnessRuntimeNeedsAttention(HarnessRuntimeUiState(HarnessRuntimeStatus.RUNNING)))
        assertFalse(harnessRuntimeNeedsAttention(HarnessRuntimeUiState(HarnessRuntimeStatus.STOPPED)))
        assertTrue(harnessRuntimeNeedsAttention(HarnessRuntimeUiState(HarnessRuntimeStatus.ERROR)))
        assertTrue(harnessRuntimeNeedsAttention(HarnessRuntimeUiState(HarnessRuntimeStatus.INTERRUPTED)))
        assertTrue(
            harnessRuntimeNeedsAttention(
                HarnessRuntimeUiState(
                    status = HarnessRuntimeStatus.STOPPING,
                    errorCode = "HARNESS_GRACEFUL_STOP_TIMEOUT",
                )
            )
        )
    }
}
