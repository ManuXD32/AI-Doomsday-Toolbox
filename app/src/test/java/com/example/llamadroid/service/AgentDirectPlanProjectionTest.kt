package com.example.llamadroid.service

import com.example.llamadroid.data.db.AgentTodoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDirectPlanProjectionTest {
    @Test
    fun `detailed approved plan collapses into ordered direct phase todos`() {
        val markdown = """
            ## Implementation
            - Add the state model in `src/main/java/State.kt`.
            - Persist the state transition in `app/src/main/java/Store.kt`.
            - Keep the existing decision records unchanged.
            ## Verification
            - Run the focused unit tests and inspect the preview.
            - Verify the migration on a fresh database.
            ## Final handoff
            - Document the changed behavior in `docs/agent-runtime-guide.md`.
            - Record the final artifacts and evidence.
        """.trimIndent()

        val source = AgentProjectControlPlane.parseApprovedPlan(
            summary = "Direct phase projection",
            markdown = markdown
        )
        val projection = projectDirectApprovedPlan(source)

        assertEquals(7, source.todos.size)
        assertEquals(listOf("Build", "Verify"), projection.todos.map { it.phaseTitle })
        assertTrue(projection.todos.size in 2..4)
        assertEquals(source.todos.size, projection.sourceTodoIdsByProjectedTodoId.values.sumOf { it.size })
        assertEquals(
            source.todos.map { it.id }.toSet(),
            projection.sourceTodoIdsByProjectedTodoId.values.flatten().toSet()
        )
        assertEquals(emptyList<String>(), projection.todos.first().dependencies)
        assertEquals(listOf(projection.todos[0].id), projection.todos[1].dependencies)
        assertTrue(projection.todos.first().text.contains("src/main/java/State.kt"))
        assertTrue(projection.todos.last().text.contains("docs/agent-runtime-guide.md"))

        // The canonical plan, including the full Markdown body, is untouched.
        assertEquals(markdown, projection.sourcePlan.markdown)
        assertEquals(source.planHash, projection.sourcePlan.planHash)
        assertTrue(projection.projected)
        assertEquals(5, projection.collapsedTodoCount)
    }

    @Test
    fun `projection is deterministic and never exceeds direct phase budget`() {
        val source = AgentProjectControlPlane.parseApprovedPlan(
            summary = "Many implementation details",
            markdown = buildString {
                repeat(24) { index ->
                    appendLine("- Implement action ${index + 1} in src/feature/Step$index.kt.")
                }
            }
        )

        val first = projectDirectApprovedPlan(source)
        val second = projectDirectApprovedPlan(source)

        assertTrue(first.todos.size <= AgentDirectPlanProjection.MAX_PHASE_TODOS)
        assertEquals(first.todos, second.todos)
        assertEquals(first.sourceTodoIdsByProjectedTodoId, second.sourceTodoIdsByProjectedTodoId)
        assertEquals(24, first.sourceTodoIdsByProjectedTodoId.values.flatten().size)
    }

    @Test
    fun `explicit implementation heading keeps item mentioning tests in build`() {
        val source = AgentProjectControlPlane.parseApprovedPlan(
            summary = "Build and test the feature",
            markdown = """
                ## Implementation
                - Implement the feature and add focused tests in `src/Feature.kt`.
                ## Verification
                - Run the focused test suite and inspect the result.
            """.trimIndent()
        )

        val projection = projectDirectApprovedPlan(source)

        assertEquals(listOf("Build", "Verify"), projection.todos.map { it.phaseTitle })
        assertTrue(projection.todos.first().text.contains("add focused tests"))
        assertEquals(listOf(source.todos.first().id), projection.sourceTodoIdsByProjectedTodoId[projection.todos.first().id])
    }

    @Test
    fun `headingless plan uses item prose to separate verify work`() {
        val source = AgentProjectControlPlane.parseApprovedPlan(
            summary = "Implement and verify",
            markdown = """
                - Implement the feature in `src/Feature.kt`.
                - Run the focused tests and verify the result.
            """.trimIndent()
        )

        val projection = projectDirectApprovedPlan(source)

        assertEquals(listOf("Build", "Verify"), projection.todos.map { it.phaseTitle })
    }

    @Test
    fun `ambiguous tasks heading keeps mutation with check prose in build`() {
        val source = AgentProjectControlPlane.parseApprovedPlan(
            summary = "Worker project",
            markdown = """
                ### Tasks
                1. **Build `primes.worker.js`** and check each bounded segment.
                2. **Create `index.html`** for the interface.

                ### Verification
                - Launch the preview and inspect the DOM.
            """.trimIndent()
        )

        val projection = projectDirectApprovedPlan(source)

        assertTrue(projection.todos.first().text.contains("primes.worker.js"))
        assertTrue(projection.todos.first().text.contains("index.html"))
        assertTrue(projection.todos.last().text.contains("inspect the DOM"))
    }

    @Test
    fun `artifact labels reject button names while retaining actual project paths`() {
        val text =
            "Create files: index.html, style.css, script.js for controls (Start, Pause, Reset), " +
                "and .adt/run.json."

        assertEquals(
            listOf("index.html", "style.css", "script.js", ".adt/run.json"),
            AgentDirectPlanProjection.extractDirectArtifactPaths(text)
        )
    }

    @Test
    fun `declared build artifacts unlock finish task only after committed writes`() {
        val todo = AgentTodoEntity(
            id = "todo-direct-build",
            conversationId = 12L,
            text = "Build phase: add `src/main/App.kt` and `app/src/main/res/values/strings.xml`. " +
                "Declared artifacts: src/main/App.kt, app/src/main/res/values/strings.xml.",
            status = AgentTodoStatus.IN_PROGRESS,
            position = 0
        )
        val completeLedger = AgentArtifactLedger(
            entries = listOf(
                AgentArtifactLedgerEntry("src/main/App.kt", "write", "action-1"),
                AgentArtifactLedgerEntry("app/src/main/res/values/strings.xml", "edit", "action-2")
            )
        )

        assertTrue(
            AgentDirectPlanProjection.declaredBuildArtifactsAreCommitted(
                todo,
                completeLedger
            )
        )
        assertTrue(
            AgentDirectPlanProjection.buildStepArtifactsAreReadyForFinish(
                todo,
                completeLedger
            )
        )
        assertFalse(
            AgentDirectPlanProjection.buildStepArtifactsAreReadyForFinish(
                todo,
                completeLedger.copy(
                    entries = completeLedger.entries +
                        AgentArtifactLedgerEntry("styles.css", "partial", "action-partial-extra")
                )
            )
        )
        assertFalse(
            AgentDirectPlanProjection.declaredBuildArtifactsAreCommitted(
                todo,
                AgentArtifactLedger(
                    entries = completeLedger.entries.dropLast(1)
                )
            )
        )
        assertEquals(
            listOf("app/src/main/res/values/strings.xml"),
            AgentDirectPlanProjection.missingDeclaredBuildArtifacts(
                todo,
                AgentArtifactLedger(entries = completeLedger.entries.take(1))
            )
        )
        assertEquals(
            listOf("src/main/App.kt"),
            AgentDirectPlanProjection.missingDeclaredBuildArtifacts(
                todo,
                AgentArtifactLedger(
                    entries = listOf(AgentArtifactLedgerEntry("src/main/App.kt", "partial", "action-partial")) +
                        completeLedger.entries.drop(1)
                )
            )
        )
        assertFalse(
            AgentDirectPlanProjection.declaredBuildArtifactsAreCommitted(
                todo.copy(status = AgentTodoStatus.READY),
                completeLedger
            )
        )
        assertFalse(
            AgentDirectPlanProjection.declaredBuildArtifactsAreCommitted(
                todo,
                completeLedger.copy(incomplete = true)
            )
        )
        assertFalse(
            AgentDirectPlanProjection.declaredBuildArtifactsAreCommitted(
                todo,
                AgentArtifactLedger(
                    entries = completeLedger.entries.map { it.copy(operation = "delete") }
                )
            )
        )
    }
}
