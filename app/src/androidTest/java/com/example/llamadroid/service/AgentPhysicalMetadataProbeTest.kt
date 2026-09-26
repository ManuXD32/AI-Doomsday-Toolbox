package com.example.llamadroid.service

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.data.db.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in metadata-only probe for a named physical-device QA project. */
@RunWith(AndroidJUnit4::class)
class AgentPhysicalMetadataProbeTest {
    @Test
    fun reportBoundedRuntimeMetadata() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val projectName = InstrumentationRegistry.getArguments().getString("qaProjectName")?.trim().orEmpty()
        assumeTrue("qaProjectName is required", projectName.isNotBlank())
        val database = AppDatabase.getDatabase(context)
        val conversation = database.agentChatDao().getAllConversations().first()
            .firstOrNull { it.title == projectName }
        assertNotNull("QA project was not found", conversation)
        val selected = requireNotNull(conversation)
        val pending = database.agentWorkflowDao().getPendingPlan(selected.id)
        val messages = database.agentChatDao().getMessagesForConversationSync(selected.id)
        val run = database.agentChatDao().getLatestProjectRun(selected.id)
        val events = database.agentChatDao().getRecentProjectEventsSync(selected.id, limit = 128)
        val assistantShapes = messages
            .filter { it.role == "assistant" }
            .takeLast(8)
            .map { message ->
                val body = message.content
                val fenceCount = Regex("```").findAll(body).count()
                val normalized = body.trimStart()
                listOf(
                    message.toolName ?: "prose",
                    "chars=${body.length}",
                    "fences=$fenceCount",
                    "jsonTool=${body.contains(Regex("\\\"(name|tool)\\\"\\s*:"))}",
                    "startsFence=${normalized.startsWith("```")}",
                    "startsJson=${normalized.startsWith("{")}",
                    "html=${body.contains("<html", ignoreCase = true)}"
                ).joinToString("/")
            }
        val report = buildString {
            append("QA_METADATA conversation=").append(selected.id)
            append(" resume=").append(selected.resumeState)
            append(" backend=").append(selected.workspaceBackend)
            append(" planState=").append(pending?.state ?: "none")
            append(" planContinuation=").append(pending?.continuationEnqueued ?: false)
            append(" planErrorClass=").append(
                pending?.errorMessage?.substringBefore(':')?.take(80) ?: "none"
            )
            append(" messages=").append(messages.size)
            append(" approvedPlans=").append(messages.count { it.isPlanApproved == true })
            append(" pendingPlans=").append(messages.count { it.isPlan && it.isPlanApproved == null })
            append(" nonPlanApprovals=").append(messages.count { it.needsApproval && !it.isPlan })
            append(" runStatus=").append(run?.status ?: "none")
            append(" preview=").append(!run?.previewUrl.isNullOrBlank())
            append(" structuredBoundaryCount=").append(
                events.count { it.eventType == "structured_boundary_missing" }
            )
            append(" assistantShapes=").append(assistantShapes.joinToString(","))
            append(" events=")
            append(events.joinToString(",") { event ->
                listOfNotNull(event.eventType, event.toolName, event.phase, event.status, event.errorClass)
                    .joinToString("/")
            })
        }
        instrumentation.sendStatus(
            2,
            Bundle().apply { putString("stream", report.take(3_500) + "\n") }
        )
    }
}
