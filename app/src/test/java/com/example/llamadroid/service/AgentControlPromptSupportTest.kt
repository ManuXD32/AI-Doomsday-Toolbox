package com.example.llamadroid.service

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentControlPromptSupportTest {
    @Test
    fun `control tools and typed error envelopes are protected`() {
        val question = AgentService.Companion.ChatMessage(
            role = "tool",
            toolName = "question",
            toolCallId = "question-call-1",
            content = "status: ok\ntool: question\nnext_hint: follow the answer"
        )
        val error = AgentService.Companion.ChatMessage(
            role = "tool",
            toolName = "read_file",
            toolCallId = "read-call-1",
            content = "status: error\nsummary: file was unavailable\n" +
                "next_hint: retry with a narrower path"
        )

        assertTrue(isControlBearingTool("question"))
        assertTrue(isControlBearingTool(question))
        assertTrue(isControlBearingTool(error))
        assertFalse(isControlBearingTool("read_file"))
    }

    @Test
    fun `opaque report and todo ids are extracted without normalization`() {
        val ids = extractAgentControlOpaqueIds(
            "report_id: report-opaque-42\n" +
                "todoId=todo-7\n" +
                "{\"invocation_id\":\"invoke-9\"}"
        )

        assertEquals(setOf("report-opaque-42"), ids.reportIds)
        assertEquals(setOf("todo-7"), ids.todoIds)
        assertEquals(setOf("invoke-9"), ids.invocationIds)
        assertTrue("report-opaque-42" in ids.all)
    }

    @Test
    fun `question answer correction and next hint survive bounded normalization`() {
        val message = AgentService.Companion.ChatMessage(
            role = "tool",
            toolName = "question",
            toolCallId = "question-call-2",
            content = buildString {
                appendLine("status: ok")
                appendLine("tool: question")
                appendLine("summary: The user answered the question.")
                appendLine("important_output:")
                appendLine(
                    "{\"answers\":{\"range\":\"[2,1000000]\",\"stack\":\"Flask\"}," +
                        "\"correction\":\"keep downloads resumable\"}"
                )
                appendLine("next_hint: Treat this answer as authoritative and do not ask again.")
                appendLine("debug_trace: ")
                append("x".repeat(2_000))
            }
        )

        val preserved = preserveToolControlContent(
            message = message,
            maxChars = 900,
            maxLines = 20
        )

        assertTrue(preserved.fits)
        assertTrue(preserved.content.contains("[2,1000000]"))
        assertTrue(preserved.content.contains("keep downloads resumable"))
        assertTrue(preserved.content.contains("next_hint: Treat this answer"))
        assertEquals("question-call-2", preserved.opaqueIds.toolCallIds.single())
    }

    @Test
    fun `exact project state read uses a typed current request receipt`() {
        val packet = """
            # Project Control Packet

            - state_revision: 9
            - mode: BUILD

            ## Current TODO
            - todo_id: todo-77
            - action_id: action-77
            - plan_id: plan-77
            - task: preserve this exact current state

            ## Recent Reports
            - report_id: report-77
        """.trimIndent()
        val stateMessage = AgentService.Companion.ChatMessage(
            role = "tool",
            toolName = "project_state_read",
            toolCallId = "state-call-77",
            content = """
                status: ok
                tool: project_state_read
                summary: Current state loaded.
                error_code: none
                important_output:
                ${packet.replace("\n\n", "\n")}
                next_hint: Resume from the current state and recovery guidance.
            """.trimIndent()
        )

        val projected = projectExactProjectStateReadReceipt(
            message = stateMessage,
            canonicalPacket = packet
        )

        assertTrue(projected.content != stateMessage.content)
        assertTrue(projected.content.length < stateMessage.content.length)
        assertTrue(projected.content.contains("status: ok"))
        assertTrue(projected.content.contains("tool: project_state_read"))
        assertTrue(projected.content.contains("summary: Current state loaded."))
        assertTrue(projected.content.contains("error_code: none"))
        assertTrue(projected.content.contains("state_revision: 9"))
        assertTrue(projected.content.contains("mode: BUILD"))
        assertTrue(projected.content.contains("canonical_packet: current_request"))
        assertTrue(projected.content.contains("next_hint: Resume from the current state"))
        assertTrue(projected.content.contains("report_id: report-77"))
        assertTrue(projected.content.contains("todo_id: todo-77"))
        assertTrue(projected.content.contains("action_id: action-77"))
        assertTrue(projected.content.contains("plan_id: plan-77"))
        assertTrue(projected.content.contains("tool_call_id: state-call-77"))
    }

    @Test
    fun `project state receipt keeps mismatches errors and capacity markers intact`() {
        val packet = """
            # Project Control Packet
            - state_revision: 12
            - mode: BUILD
        """.trimIndent()
        fun message(output: String, status: String = "ok") =
            AgentService.Companion.ChatMessage(
                role = "tool",
                toolName = "project_state_read",
                toolCallId = "state-call-12",
                content = "status: $status\ntool: project_state_read\n" +
                    "summary: state result\nimportant_output:\n$output"
            )

        val exact = message(packet)
        assertEquals(
            exact.content,
            projectExactProjectStateReadReceipt(
                exact,
                packet.replace("state_revision: 12", "state_revision: 13")
            ).content
        )

        val error = message(packet, status = "error")
        assertEquals(
            error.content,
            projectExactProjectStateReadReceipt(error, packet).content
        )

        val capacityPacket = """
            # Project Control Packet
            - control_state_does_not_fit: true
        """.trimIndent()
        val capacity = message(capacityPacket)
        assertEquals(
            capacity.content,
            projectExactProjectStateReadReceipt(capacity, capacityPacket).content
        )
    }

    @Test
    fun `evidence projection keeps citation links findings ids and next hint`() {
        val message = AgentService.Companion.ChatMessage(
            role = "tool",
            toolName = "web_search",
            toolCallId = "search-call-1",
            content = buildString {
                appendLine("status: ok")
                appendLine("tool: web_search")
                appendLine("summary: Three sources summarize segmented sieving and worker coordination")
                appendLine("important_output:")
                appendLine("Web search results for: segmented sieve and parallel workers")
                appendLine("1. Segmented Sieve Guide")
                appendLine("   URL: https://example.test/segmented")
                appendLine("   Summary: Process one bounded segment at a time so memory stays proportional to the segment, not the full range.")
                appendLine("2. Worker Coordination Notes")
                appendLine("   URL: https://example.test/workers")
                appendLine("   Summary: Split independent segments among workers and checkpoint the next segment for pause and resume.")
                appendLine("3. Streaming Download Guidance")
                appendLine("   URL: https://example.test/download")
                appendLine("   Summary: Stream completed output and validate the requested format before serving a download.")
                appendLine("source_citations:")
                appendLine("1. [Segmented Sieve Guide](https://example.test/segmented)")
                appendLine("2. [Worker Coordination Notes](https://example.test/workers)")
                appendLine("3. [Streaming Download Guidance](https://example.test/download)")
                appendLine("report_id: report-evidence-1")
                appendLine("next_hint: Cite the retained source links when turning these findings into the plan.")
                append("discarded verbose result body ".repeat(20))
            }
        )

        val preserved = preserveToolEvidenceContent(
            message = message,
            maxChars = 1_400,
            maxLines = 10
        )

        assertEquals(3, preserved.sourceCount)
        assertEquals(3, preserved.retainedSourceCount)
        assertTrue(preserved.truncated)
        assertTrue(preserved.content.length <= 1_400)
        assertTrue(preserved.content.lines().size <= 10)
        assertTrue(preserved.content.contains("https://example.test/segmented"))
        assertTrue(preserved.content.contains("https://example.test/workers"))
        assertTrue(preserved.content.contains("memory stays proportional"))
        assertTrue(preserved.content.contains("checkpoint the next segment"))
        assertTrue(preserved.content.contains("report_id: report-evidence-1"))
        assertTrue(preserved.content.contains("tool_call_id: search-call-1"))
        assertTrue(preserved.content.contains("next_hint: Cite the retained source links"))
    }

    @Test
    fun `report projection keeps research evidence and stored evidence metadata`() {
        val projected = projectAgentReportForControlPacket(
            summary = """
                RESEARCHER result:
                - status: SUCCESS
                - research_question: How should segmented work resume?
                - sources: [Segmented Sieve Guide](https://example.test/segmented), [Worker Notes](https://example.test/workers)
                - facts: Process one bounded segment at a time so memory stays proportional to the segment.
                - recommendations: Checkpoint the next segment before a pause or resume.
            """.trimIndent(),
            evidenceJson = """{"changed_files":["docs/research.md"],"command_ids":["cmd-42"]}""",
            maxChars = 640
        )

        assertTrue(projected.length <= 640)
        assertTrue(projected.contains("https://example.test/segmented"))
        assertTrue(projected.contains("memory stays proportional"))
        assertTrue(projected.contains("Checkpoint the next segment"))
        assertTrue(projected.contains("cmd-42"))
    }

    @Test
    fun `control packet capacity marker is detected before a child can start`() {
        val marker = """
            # Project Control Packet
            - control_state_does_not_fit: true
            - action: increase capacity
        """.trimIndent()

        assertTrue(isControlPacketCapacityFailure(marker))
        assertFalse(
            isControlPacketCapacityFailure(
                marker.replace("control_state_does_not_fit: true", "control_state_does_not_fit: false")
            )
        )
    }

    @Test
    fun `required control state over budget is an explicit cannot send result`() {
        val message = AgentService.Companion.ChatMessage(
            role = "tool",
            toolName = "question",
            content = "status: ok\ntool: question\nimportant_output:\n" +
                "{\"answers\":\"${"a".repeat(2_000)}\"}\n" +
                "next_hint: use the answer"
        )

        val preserved = preserveToolControlContent(
            message = message,
            maxChars = 400,
            maxLines = 8
        )

        assertFalse(preserved.fits)
        assertTrue(preserved.content.isEmpty())
        assertTrue(preserved.requiredCharacters > 400)
        assertTrue("important_output" in preserved.omittedFields)
    }

    @Test
    fun `latest control exchange protects assistant call and every matching result`() {
        val call = OllamaService.ToolCall(
            name = "question",
            arguments = mapOf("questions" to "[]"),
            id = "question-call-3"
        )
        val source = listOf(
            AgentService.Companion.ChatMessage(
                role = "assistant",
                content = "old action",
                pendingToolCall = OllamaService.ToolCall(
                    name = "read_file",
                    arguments = mapOf("path" to "old.txt"),
                    id = "old-call"
                ),
                toolCallId = "old-call"
            ),
            AgentService.Companion.ChatMessage(
                role = "tool",
                content = "old result",
                toolName = "read_file",
                toolCallId = "old-call"
            ),
            AgentService.Companion.ChatMessage(
                role = "assistant",
                content = "Need the user's decision.",
                pendingToolCall = call,
                toolCallId = call.id,
                toolName = call.name
            ),
            AgentService.Companion.ChatMessage(
                role = "tool",
                content = "status: ok\ntool: question\nimportant_output:\n" +
                    "{\"answers\":{\"choice\":\"keep\"}}\n" +
                    "next_hint: continue from this choice",
                toolName = "question",
                toolCallId = call.id
            )
        )

        val units = buildAgentPromptAtomicUnits(source)
        val protected = protectedAgentPromptGroupIds(units)

        assertEquals(2, units.size)
        assertEquals(2, units.last().messages.size)
        assertEquals(setOf(units.last().id), protected)
        assertEquals("question-call-3", agentPromptGroupId(units.last().messages.first()))
    }

    @Test
    fun `newest successful tool exchange survives trailing current mode`() {
        fun failedDirectoryPair(prefix: String): List<AgentService.Companion.ChatMessage> {
            val callId = "$prefix-call"
            return listOf(
                AgentService.Companion.ChatMessage(
                    id = "$prefix-assistant",
                    role = "assistant",
                    content = "Retry the directory read.",
                    pendingToolCall = OllamaService.ToolCall(
                        name = "list_directory",
                        arguments = mapOf("path" to ""),
                        id = callId
                    ),
                    toolCallId = callId,
                    toolName = "list_directory"
                ),
                AgentService.Companion.ChatMessage(
                    id = "$prefix-result",
                    role = "tool",
                    content = "status: error\ntool: list_directory\n" +
                        "summary: $prefix historical failure\n" +
                        "next_hint: retry only with a valid project-relative path",
                    toolName = "list_directory",
                    toolCallId = callId
                )
            )
        }

        val writeCall = OllamaService.ToolCall(
            name = "write_file",
            arguments = linkedMapOf(
                "content" to "generated content that belongs in the artifact\n".repeat(240),
                "action_id" to "action-write-19",
                "path" to "index.html",
                "status" to "completed"
            ),
            id = "write-call-19"
        )
        val history = buildList {
            addAll(failedDirectoryPair("old-one"))
            addAll(failedDirectoryPair("old-two"))
            add(
                AgentService.Companion.ChatMessage(
                    id = "write-assistant-19",
                    role = "assistant",
                    content = "",
                    pendingToolCall = writeCall,
                    toolCallId = writeCall.id,
                    toolName = writeCall.name
                )
            )
            // This successful result intentionally has no inline action ID or
            // error marker, matching the live persisted write receipt shape.
            add(
                AgentService.Companion.ChatMessage(
                    id = "write-result-19",
                    role = "tool",
                    content = "status: ok\ntool: write_file\n" +
                        "summary: File write completed.\n" +
                        "next_hint: continue with the current TODO",
                    toolName = writeCall.name,
                    toolCallId = writeCall.id
                )
            )
            add(
                AgentService.Companion.ChatMessage(
                    id = "current-mode-19",
                    role = "system",
                    content = "CURRENT MODE: BUILD. Implement the approved task directly."
                )
            )
        }
        val units = buildAgentPromptAtomicUnits(history)
        val selection = selectProtectedAgentPromptUnits(
            units = units,
            latestControlExchangeCount = 2,
            latestUserMessageCount = 0
        )
        val writeUnit = units.first { unit ->
            unit.messages.any { it.id == "write-assistant-19" }
        }
        val oldOneUnit = units.first { unit ->
            unit.messages.any { it.id == "old-one-result" }
        }
        val oldTwoUnit = units.first { unit ->
            unit.messages.any { it.id == "old-two-result" }
        }
        assertTrue(writeUnit.id in selection.protectedUnitIds)
        assertTrue(writeUnit.id in selection.protectedControlUnitIds)
        assertFalse(oldOneUnit.id in selection.protectedUnitIds)
        assertTrue(oldTwoUnit.id in selection.protectedUnitIds)
        assertTrue(
            units.last().id in selection.protectedUnitIds
        ) // trailing mode remains a separate protected suffix

        val packedMessages = buildList {
            add(
                AgentService.Companion.ChatMessage(
                    role = "system",
                    content = "Stable optimized system prompt."
                )
            )
            units.filter { it.id in selection.protectedUnitIds }
                .flatMapTo(this) { unit ->
                    unit.messages.map { message ->
                        val pending = message.pendingToolCall
                        if (
                            message.role == "assistant" &&
                            pending != null &&
                            isPromptMutationTool(pending.name) &&
                            unit.messages.any { candidate ->
                                candidate.role == "tool" &&
                                    candidate.toolCallId == (pending.id ?: message.toolCallId)
                            }
                        ) {
                            message.copy(
                                pendingToolCall = compactAgentPromptToolCallArguments(
                                    pending,
                                    maxChars = 320
                                )
                            )
                        } else {
                            message
                        }
                    }
                }
        }

        listOf(8_192, 16_384).forEach { contextTokens ->
            val request = buildCanonicalAgentPromptRequestJson(
                model = "packing-fixture-$contextTokens",
                messages = packedMessages.map {
                    it.toOllamaMessage(includeThinking = false)
                },
                tools = emptyList(),
                thinkingEnabled = false
            )
            assertTrue(request.contains("write-call-19"))
            assertTrue(request.contains("action-write-19"))
            assertTrue(request.contains("index.html"))
            assertTrue(request.contains("completed"))
            assertTrue(request.contains("status: ok"))
            assertTrue(request.contains("CURRENT MODE: BUILD"))
            assertFalse(request.contains("generated content that belongs in the artifact"))
        }
    }

    @Test
    fun `failed propose plan projection removes body and retains typed error receipt`() {
        val invalidPlan = "Node.js Express backend with a Flask fallback. ".repeat(80)
        val call = OllamaService.ToolCall(
            name = "propose_plan",
            arguments = linkedMapOf(
                "plan" to invalidPlan,
                "plan_id" to "proposal-invalid-42"
            ),
            id = "failed-plan-call-42",
            rawArgumentsJson = JSONObject()
                .put("plan", invalidPlan)
                .put("plan_id", "proposal-invalid-42")
                .toString()
        )
        val assistant = AgentService.Companion.ChatMessage(
            id = "failed-plan-assistant-42",
            role = "assistant",
            content = "### Propose Plan\n\n$invalidPlan",
            pendingToolCall = call,
            toolCallId = call.id,
            toolName = call.name,
            isPlan = true
        )
        val result = AgentService.Companion.ChatMessage(
            id = "failed-plan-result-42",
            role = "tool",
            content = "status: error\n" +
                "tool: propose_plan\n" +
                "summary: The backend is unsupported in this runtime.\n" +
                "next_hint: Submit a static web plan with runtime=web.",
            toolName = call.name,
            toolCallId = call.id
        )
        val unit = buildAgentPromptAtomicUnits(listOf(assistant, result)).single()

        val projected = projectFailedProposePlanPromptUnit(unit)
        val projectedAssistant = projected.messages.first()
        val projectedCall = projectedAssistant.pendingToolCall
        val projectedResult = projected.messages.last()

        assertTrue(projectedAssistant.content.length < assistant.content.length)
        assertFalse(projectedAssistant.content.contains("Node.js"))
        assertTrue(projectedAssistant.content.contains("rejected/not-approved"))
        assertEquals(call.id, projectedAssistant.toolCallId)
        assertEquals(call.name, projectedAssistant.toolName)
        assertEquals(call.id, projectedCall?.id)
        assertEquals(call.name, projectedCall?.name)
        assertTrue(projectedCall?.arguments?.get("plan")?.contains("rejected/not-approved") == true)
        assertFalse(projectedCall?.arguments?.get("plan").orEmpty().contains("Node.js"))
        assertEquals("proposal-invalid-42", projectedCall?.arguments?.get("plan_id"))
        assertTrue(projectedCall?.rawArgumentsJson == null)
        assertEquals(result.content, projectedResult.content)
        assertEquals(result.toolCallId, projectedResult.toolCallId)
        assertTrue(projectedResult.content.contains("The backend is unsupported"))
        assertTrue(projectedResult.content.contains("next_hint: Submit a static web plan"))

        // The prompt projection must not mutate the execution/canonical copy.
        assertEquals(invalidPlan, assistant.pendingToolCall?.arguments?.get("plan"))
        assertEquals(call.rawArgumentsJson, assistant.pendingToolCall?.rawArgumentsJson)
    }

    @Test
    fun `failed propose plan projection leaves pending successful and approved calls unchanged`() {
        fun assistant(
            callId: String,
            approved: Boolean? = null
        ): AgentService.Companion.ChatMessage {
            val plan = "Use the supported static runtime."
            val call = OllamaService.ToolCall(
                name = "propose_plan",
                arguments = mapOf("plan" to plan),
                id = callId,
                rawArgumentsJson = JSONObject().put("plan", plan).toString()
            )
            return AgentService.Companion.ChatMessage(
                role = "assistant",
                content = plan,
                pendingToolCall = call,
                toolCallId = callId,
                toolName = "propose_plan",
                isPlan = true,
                isPlanApproved = approved
            )
        }

        fun result(callId: String, status: String) = AgentService.Companion.ChatMessage(
            role = "tool",
            content = "status: $status\ntool: propose_plan\n" +
                "summary: proposal result\nnext_hint: follow the result",
            toolName = "propose_plan",
            toolCallId = callId
        )

        val pending = buildAgentPromptAtomicUnits(
            listOf(assistant("pending-plan-call"))
        ).single()
        val successful = buildAgentPromptAtomicUnits(
            listOf(assistant("successful-plan-call"), result("successful-plan-call", "ok"))
        ).single()
        val approvedFailed = buildAgentPromptAtomicUnits(
            listOf(
                assistant("approved-plan-call", approved = true),
                result("approved-plan-call", "error")
            )
        ).single()
        val mismatched = buildAgentPromptAtomicUnits(
            listOf(
                assistant("mismatched-plan-call"),
                result("mismatched-plan-call", "error").copy(
                    content = "status: error\ntool: read_file\n" +
                        "summary: unrelated error\nnext_hint: inspect the file",
                    toolName = "read_file"
                )
            )
        ).single()

        assertEquals(pending, projectFailedProposePlanPromptUnit(pending))
        assertEquals(successful, projectFailedProposePlanPromptUnit(successful))
        assertEquals(approvedFailed, projectFailedProposePlanPromptUnit(approvedFailed))
        assertEquals(mismatched, projectFailedProposePlanPromptUnit(mismatched))
    }

    @Test
    fun `canonical coverage parses full approved plan identity and exact body`() {
        val planMarkdown = """
            # Approved plan
            - Keep the exact opaque action ID.
            ## Tail constraints
            - Preserve this final constraint after compaction.
        """.trimIndent()
        val packet = buildString {
            appendLine("# Project Control Packet")
            appendLine()
            appendLine("## Durable User Contract")
            appendLine("- initial_goal: Keep the durable contract exact.")
            appendLine()
            appendLine("## Approved Plan")
            appendLine("- id: plan-full-77")
            appendLine("- version: 4")
            appendLine("- hash: short-prefix")
            appendLine(
                "- full_plan_json: " +
                    JSONObject()
                        .put("id", "plan-full-77")
                        .put("version", 4)
                        .put("hash", "full-plan-hash-77")
                        .put("plan_markdown", planMarkdown)
                        .toString()
            )
        }.trimIndent()

        val coverage = canonicalAgentPromptCoverage(packet)

        assertEquals("Keep the durable contract exact.", coverage.initialGoal)
        assertEquals("plan-full-77", coverage.approvedPlanId)
        assertEquals("full-plan-hash-77", coverage.approvedPlanHash)
        assertEquals(planMarkdown, coverage.approvedPlanContent)
    }

    @Test
    fun `historical digest filter omits user duplicates and retains tool state`() {
        val initialGoal = "Build the exact static web app."
        val correction = "Keep every approved constraint and use browser storage."
        val approvedPlanId = "plan-digest-77"
        val approvedPlanBody = """
            # Approved plan
            - Keep the exact static runtime.
            ## Tail constraints
            - Preserve browser storage and bounded output.
        """.trimIndent()
        val packet = buildString {
            appendLine("# Project Control Packet")
            appendLine()
            appendLine("## Durable User Contract")
            appendLine("- initial_goal: $initialGoal")
            appendLine()
            appendLine("## Durable User Corrections")
            appendLine("- decision_id: correction-digest-77")
            appendLine("  content: $correction")
            appendLine()
            appendLine("## Approved Plan")
            appendLine("- id: $approvedPlanId")
            appendLine(
                "- full_plan_json: " +
                    JSONObject()
                        .put("id", approvedPlanId)
                        .put("hash", "digest-plan-hash")
                        .put("plan_markdown", approvedPlanBody)
                        .toString()
            )
        }.trimIndent()

        fun user(id: String, content: String) =
            AgentService.Companion.ChatMessage(
                id = id,
                role = "user",
                content = content
            )

        fun proposal(
            prefix: String,
            status: String,
            planId: String? = approvedPlanId
        ): AgentPromptAtomicUnit {
            val callId = "$prefix-call"
            val call = OllamaService.ToolCall(
                name = "propose_plan",
                arguments = linkedMapOf(
                    "plan" to approvedPlanBody,
                    "plan_id" to (planId ?: "legacy-plan")
                ),
                id = callId
            )
            val result = buildString {
                appendLine("status: $status")
                appendLine("tool: propose_plan")
                planId?.let { appendLine("plan_id: $it") }
                appendLine("summary: proposal transition")
                appendLine("important_output:")
                appendLine(approvedPlanBody)
                if (status == "error") {
                    appendLine("error_code: plan_rejected")
                    appendLine("next_hint: revise the rejected plan")
                }
            }
            return buildAgentPromptAtomicUnits(
                listOf(
                    AgentService.Companion.ChatMessage(
                        id = "$prefix-assistant",
                        role = "assistant",
                        content = approvedPlanBody,
                        pendingToolCall = call,
                        toolCallId = callId,
                        toolName = "propose_plan",
                        isPlan = true
                    ),
                    AgentService.Companion.ChatMessage(
                        id = "$prefix-result",
                        role = "tool",
                        content = result,
                        toolName = "propose_plan",
                        toolCallId = callId
                    )
                )
            ).single()
        }

        fun evidenceUnit(): AgentPromptAtomicUnit {
            val callId = "evidence-call"
            return buildAgentPromptAtomicUnits(
                listOf(
                    AgentService.Companion.ChatMessage(
                        id = "evidence-assistant",
                        role = "assistant",
                        content = "Collect the source evidence.",
                        pendingToolCall = OllamaService.ToolCall(
                            name = "web_search",
                            arguments = mapOf("query" to "bounded output"),
                            id = callId
                        ),
                        toolCallId = callId,
                        toolName = "web_search"
                    ),
                    AgentService.Companion.ChatMessage(
                        id = "evidence-result",
                        role = "tool",
                        content = "status: ok\ntool: web_search\n" +
                            "source_url: https://example.test/evidence\n" +
                            "finding: Keep output bounded.\n" +
                            "report_id: report-evidence-77\n" +
                            "next_hint: cite the source",
                        toolName = "web_search",
                        toolCallId = callId
                    )
                )
            ).single()
        }

        fun errorUnit(): AgentPromptAtomicUnit {
            val callId = "error-call"
            return buildAgentPromptAtomicUnits(
                listOf(
                    AgentService.Companion.ChatMessage(
                        id = "error-assistant",
                        role = "assistant",
                        content = "Read the required file.",
                        pendingToolCall = OllamaService.ToolCall(
                            name = "read_file",
                            arguments = mapOf("path" to "missing.txt"),
                            id = callId
                        ),
                        toolCallId = callId,
                        toolName = "read_file"
                    ),
                    AgentService.Companion.ChatMessage(
                        id = "error-result",
                        role = "tool",
                        content = "status: error\ntool: read_file\n" +
                            "error_code: file_missing\n" +
                            "action_id: action-error-77\n" +
                            "next_hint: retry with the known path",
                        toolName = "read_file",
                        toolCallId = callId
                    )
                )
            ).single()
        }

        val goalCopy = buildAgentPromptAtomicUnits(
            listOf(user("goal-copy", "  $initialGoal  "))
        ).single()
        val correctionCopy = buildAgentPromptAtomicUnits(
            listOf(user("correction-copy", correction))
        ).single()
        val approved = proposal("approved", "ok")
        val rejected = proposal("rejected", "error")
        val evidence = evidenceUnit()
        val error = errorUnit()
        val recovery = buildAgentPromptAtomicUnits(
            listOf(
                AgentService.Companion.ChatMessage(
                    id = "recovery-state",
                    role = "system",
                    content = "RECOVERY MODE: resume from the current durable state."
                )
            )
        ).single()
        val retained = retainHistoricalPromptUnitsForDigest(
            units = listOf(
                goalCopy,
                correctionCopy,
                approved,
                rejected,
                evidence,
                error,
                recovery
            ),
            canonicalCoverage = canonicalAgentPromptCoverage(packet)
        )
        val retainedMessageIds = retained.flatMap { unit ->
            unit.messages.map { it.id }
        }.toSet()

        assertFalse("goal-copy" in retainedMessageIds)
        assertFalse("correction-copy" in retainedMessageIds)
        assertTrue("approved-result" in retainedMessageIds)
        assertTrue("rejected-result" in retainedMessageIds)
        assertTrue("evidence-result" in retainedMessageIds)
        assertTrue("error-result" in retainedMessageIds)
        assertTrue("recovery-state" in retainedMessageIds)
        assertTrue(
            retained.single { unit ->
                unit.messages.any { it.id == "evidence-result" }
            }.messages.any { it.content.contains("report-evidence-77") }
        )
        assertTrue(
            retained.single { unit ->
                unit.messages.any { it.id == "error-result" }
            }.messages.any { it.content.contains("file_missing") }
        )

        val mixedWithReport = buildAgentPromptAtomicUnits(
            approved.messages + AgentService.Companion.ChatMessage(
                id = "mixed-report",
                role = "tool",
                content = "status: ok\ntool: report_progress\n" +
                    "report_id: report-mixed-77\nnext_hint: retain this report",
                toolName = "report_progress",
                toolCallId = "approved-call"
            )
        ).single()
        assertEquals(
            listOf(mixedWithReport),
            retainHistoricalPromptUnitsForDigest(
                units = listOf(mixedWithReport),
                canonicalCoverage = canonicalAgentPromptCoverage(packet)
            )
        )

    }

    @Test
    fun `saved Ling build state releases canonical duplicates and fits 8K replay`() {
        val initialGoal = """write a nice webUI that allows users to calculate prime numbers in the cosen ranges and shows the total numver of numbers found, allows to download them at any given time and pause/stop/resume runs as well as running parallel ones. We should also allow running up to very large numbers without problems and show the users an updated "numbers found per second" that updates using the last 4 found numbers

write an implementation plan and look for the needed mathematics or examples on the internet if needed. There is no need to scout the codebase as there is none because the proyect has not yet started and no files have been written. You can start by doing some online research to acquire the knowledge needed for this task"""
        val firstCorrection = """The proposed plan was rejected.

Revise it according to these requested changes:

Submit a real propose_plan tool call, not a prose tool label. Use supported static HTML/CSS/JS with Web Workers and .adt/run.json runtime=web; no Python web backend. Independent concurrent runs with separate controls and disk-backed results; never retain all primes. Use bounded segments and short intervals near 10^12. Rate is 3/(t3-t0) from the last four discovery timestamps. Include incremental downloads and concrete checks. No more questions or searches. Keep the revised plan under 500 words."""
        val secondCorrection = """The proposed plan was rejected.

Revise it according to these requested changes:

Static browser only: no server or backend, no Node, Express, Flask, or Python web service. Use Web Workers plus browser disk storage. Keep rate exactly 3/(t3-t0). Preserve all other requested features. Submit the corrected plan as a structured propose_plan call."""
        val approvedPlanId = "plan-8-5e2c2738f338"
        val packet = buildString {
            appendLine("# Project Control Packet")
            appendLine()
            appendLine("- state_revision: 4")
            appendLine("- mode: BUILD")
            appendLine()
            appendLine("## Durable User Contract")
            appendLine("- initial_goal: $initialGoal")
            appendLine("- no_more_questions: true")
            appendLine()
            appendLine("## Durable User Corrections")
            appendLine("- decision_id: user-correction:1986277b")
            appendLine("  content: $firstCorrection")
            appendLine("- decision_id: user-correction:160")
            appendLine("  content: $secondCorrection")
            appendLine()
            appendLine("## Approved Plan")
            appendLine("- id: $approvedPlanId")
            appendLine("- version: 1")
            appendLine("- summary: static browser plan with workers and browser storage")
            appendLine()
            appendLine("## Current TODO")
            appendLine("- task: ${"Implement the approved static web UI. ".repeat(50)}")
        }

        fun proposalPair(
            prefix: String,
            status: String,
            planId: String? = null
        ): List<AgentService.Companion.ChatMessage> {
            val callId = "$prefix-call"
            val proposalBody = if (planId != null) {
                "APPROVED_PLAN_FULL_BODY_$prefix ".repeat(350)
            } else {
                prefix
            }
            val call = OllamaService.ToolCall(
                name = "propose_plan",
                arguments = mapOf("plan" to proposalBody),
                id = callId
            )
            return listOf(
                AgentService.Companion.ChatMessage(
                    id = "$prefix-assistant",
                    role = "assistant",
                    content = "Recorded action: propose_plan ($prefix)\n$proposalBody",
                    pendingToolCall = call,
                    toolCallId = callId,
                    toolName = "propose_plan",
                    isPlanApproved = planId != null
                ),
                AgentService.Companion.ChatMessage(
                    id = "$prefix-result",
                    role = "tool",
                    content = buildString {
                        appendLine("status: $status")
                        appendLine("tool: propose_plan")
                        planId?.let { appendLine("plan_id: $it") }
                        append("summary: OLD_PROPOSAL_$prefix")
                    },
                    toolName = "propose_plan",
                    toolCallId = callId
                )
            )
        }

        val currentCall = OllamaService.ToolCall(
            name = "project_state_read",
            arguments = emptyMap(),
            id = "current-state-call"
        )
        val completedWriteBody = "generated source body ".repeat(900)
        val completedWriteCall = OllamaService.ToolCall(
            name = "write_file",
            arguments = mapOf(
                "action_id" to "action-write-42",
                "path" to "src/main.js",
                "status" to "completed",
                "content" to completedWriteBody
            ),
            id = "write-call-42",
            rawArgumentsJson = JSONObject().put("content", completedWriteBody).toString()
        )
        val uncoveredConstraint = "Keep the final output downloadable without retaining every prime in memory."
        val history = buildList {
            addAll(proposalPair("one", "error"))
            addAll(proposalPair("two", "ok"))
            add(AgentService.Companion.ChatMessage(id = "goal-copy", role = "user", content = initialGoal))
            add(AgentService.Companion.ChatMessage(id = "correction-one-copy", role = "user", content = firstCorrection))
            add(AgentService.Companion.ChatMessage(id = "correction-two-copy", role = "user", content = secondCorrection))
            add(AgentService.Companion.ChatMessage(id = "uncovered-copy", role = "user", content = uncoveredConstraint))
            add(
                AgentService.Companion.ChatMessage(
                    id = "write-action",
                    role = "assistant",
                    content = "",
                    pendingToolCall = completedWriteCall,
                    toolCallId = completedWriteCall.id,
                    toolName = completedWriteCall.name
                )
            )
            add(
                AgentService.Companion.ChatMessage(
                    id = "write-result",
                    role = "tool",
                    content = "status: ok\ntool: write_file\n" +
                        "action_id: action-write-42\n" +
                        "path: src/main.js\n" +
                        "status: completed\n" +
                        "next_hint: continue with the current TODO",
                    toolName = completedWriteCall.name,
                    toolCallId = completedWriteCall.id
                )
            )
            add(
                AgentService.Companion.ChatMessage(
                    id = "current-action",
                    role = "assistant",
                    content = "Read the current durable state.",
                    pendingToolCall = currentCall,
                    toolCallId = currentCall.id,
                    toolName = currentCall.name
                )
            )
            add(
                AgentService.Companion.ChatMessage(
                    id = "current-result",
                    role = "tool",
                    content = "status: ok\ntool: project_state_read\nsummary: current TODO is ready\nnext_hint: act on the current TODO",
                    toolName = currentCall.name,
                    toolCallId = currentCall.id
                )
            )
            addAll(proposalPair("approved", "ok", approvedPlanId))
            add(
                AgentService.Companion.ChatMessage(
                    id = "recovery",
                    role = "system",
                    content = "RECOVERY MODE: resume from the current durable state."
                )
            )
        }
        val units = buildAgentPromptAtomicUnits(history)
        val coverage = canonicalAgentPromptCoverage(packet)
        assertEquals(setOf(initialGoal, firstCorrection, secondCorrection), coverage.exactUserContents)
        assertEquals(approvedPlanId, coverage.approvedPlanId)

        val selection = selectProtectedAgentPromptUnits(
            units = units,
            canonicalCoverage = coverage,
            latestControlExchangeCount = 2,
            latestUserMessageCount = 4
        )
        val oldProposalIds = units.take(2).map { it.id }.toSet()
        val approvedProposalIds = units.filter {
            it.messages.any { message -> message.id == "approved-result" }
        }.map { it.id }.toSet()
        val currentControlId = units.first { it.messages.any { message -> message.id == "current-action" } }.id
        val recoveryId = units.last().id
        assertEquals(
            oldProposalIds + approvedProposalIds,
            selection.obsoleteApprovedPlanUnitIds
        )
        assertTrue(selection.coveredUserUnitIds.any { it.contains("goal-copy") })
        assertTrue(selection.coveredUserUnitIds.any { it.contains("correction-one-copy") })
        assertTrue(selection.coveredUserUnitIds.any { it.contains("correction-two-copy") })
        assertTrue(selection.protectedUserUnitIds.any { it.contains("uncovered-copy") })
        assertFalse(selection.protectedUnitIds.any { it in oldProposalIds })
        assertTrue(currentControlId in selection.protectedControlUnitIds)
        assertTrue(currentControlId in selection.protectedUnitIds)
        assertFalse(approvedProposalIds.any { it in selection.protectedUnitIds })
        assertTrue(recoveryId in selection.protectedUnitIds)
        val writeUnitId = units.first {
            it.messages.any { message -> message.id == "write-action" }
        }.id
        assertTrue(writeUnitId in selection.protectedControlUnitIds)
        assertTrue(writeUnitId in selection.protectedUnitIds)

        fun promptMessages(unit: AgentPromptAtomicUnit): List<AgentService.Companion.ChatMessage> =
            unit.messages.map { message ->
                val pending = message.pendingToolCall
                if (
                    message.role == "assistant" &&
                    pending != null &&
                    isPromptMutationTool(pending.name) &&
                    unit.messages.any { candidate ->
                        candidate.role == "tool" &&
                            candidate.toolCallId == (pending.id ?: message.toolCallId)
                    }
                ) {
                    message.copy(
                        pendingToolCall = compactAgentPromptToolCallArguments(
                            toolCall = pending,
                            maxChars = 320
                        )
                    )
                } else {
                    message
                }
            }

        val packed = buildList {
            add(AgentService.Companion.ChatMessage(role = "system", content = "s".repeat(2_500)))
            add(AgentService.Companion.ChatMessage(role = "system", content = packet))
            units.filter { it.id in selection.protectedUnitIds }
                .flatMapTo(this) { promptMessages(it) }
            add(
                AgentService.Companion.ChatMessage(
                    role = "system",
                    content = "CURRENT MODE: BUILD. Implement the approved task and report evidence."
                )
            )
        }
        val request = buildCanonicalAgentPromptRequestJson(
            model = "Spark-X2.5-4B",
            messages = packed.map { it.toOllamaMessage(includeThinking = false) },
            tools = emptyList(),
            thinkingEnabled = false
        )
        val capacity = resolveAgentPromptCapacity(
            configuredContextTokens = 8_192,
            reportedContextTokens = 8_192,
            exactCountingAvailable = false,
            configuredMaxOutputTokens = 4_096
        )
        val schemaTokens = 719 // Captured Ling Build schema palette.
        val messageTokens = estimateRawSerializedAgentRequestTokens(request)
        val authoritativeInputTokens = messageTokens + schemaTokens
        assertEquals(3_072, capacity.maximumInputTokens)
        assertTrue(authoritativeInputTokens <= capacity.maximumInputTokens)
        val outputBudget = resolveAgentPromptOutputBudget(
            configuredMaxOutputTokens = 4_096,
            capacity = capacity,
            authoritativeInputTokens = authoritativeInputTokens
        )
        assertTrue(outputBudget.canSend)
        assertEquals(4_096, outputBudget.effectiveMaxOutputTokens)
        fun jsonEscaped(raw: String): String =
            JSONObject.quote(raw).removePrefix("\"").removeSuffix("\"")
        assertTrue(request.contains(jsonEscaped(initialGoal)))
        assertTrue(request.contains(jsonEscaped(firstCorrection)))
        assertTrue(request.contains(jsonEscaped(secondCorrection)))
        assertTrue(request.contains("current TODO is ready"))
        assertTrue(request.contains(uncoveredConstraint))
        assertTrue(request.contains("RECOVERY MODE: resume from the current durable state."))
        assertTrue(request.contains("action-write-42"))
        assertTrue(request.contains("src/main.js"))
        assertFalse(request.contains("generated source body"))
        assertFalse(request.contains("APPROVED_PLAN_FULL_BODY_approved"))
        assertFalse(request.contains("OLD_PROPOSAL_one"))
        assertFalse(request.contains("OLD_PROPOSAL_two"))
    }

    @Test
    fun `completed write call projection keeps typed identity without replaying body`() {
        val body = "generated source body ".repeat(900)
        val original = OllamaService.ToolCall(
            name = "write_file",
            arguments = mapOf(
                "action_id" to "action-write-42",
                "path" to "src/main.js",
                "status" to "completed",
                "content" to body
            ),
            id = "write-call-42",
            rawArgumentsJson = "{\"content\":\"$body\"}"
        )

        val projected = compactAgentPromptToolCallArguments(
            toolCall = original,
            maxChars = 320
        )

        assertEquals(original.id, projected.id)
        assertEquals("action-write-42", projected.arguments["action_id"])
        assertEquals("src/main.js", projected.arguments["path"])
        assertEquals("completed", projected.arguments["status"])
        assertTrue(projected.arguments["content"].orEmpty().startsWith("[omitted:"))
        assertFalse(projected.arguments["content"].orEmpty().contains(body))
        assertEquals(null, projected.rawArgumentsJson)
        assertTrue(canonicalToolArguments(projected).length <= 320)

        val oversizedPath = "p".repeat(1_000)
        val identityProjection = compactAgentPromptToolCallArguments(
            toolCall = original.copy(
                arguments = original.arguments + ("path" to oversizedPath)
            ),
            maxChars = 96
        )
        assertEquals(oversizedPath, identityProjection.arguments["path"])
        assertTrue(canonicalToolArguments(identityProjection).length > 96)
        assertTrue(isPromptMutationTool("write_file"))
        assertFalse(isPromptMutationTool("read_file"))
    }

    @Test
    fun `final serialized request retains control fields at 8K and 16K`() {
        val questionCall = OllamaService.ToolCall(
            name = "question",
            arguments = mapOf("questions" to "[]"),
            id = "question-call-final"
        )
        val reportCall = OllamaService.ToolCall(
            name = "call_agent",
            arguments = mapOf("agent" to "RESEARCHER"),
            id = "report-call-final"
        )
        val source = listOf(
            AgentService.Companion.ChatMessage(
                role = "system",
                content = "You are the project orchestrator."
            ),
            AgentService.Companion.ChatMessage(
                role = "user",
                content = "Build the prime-number WebUI and keep the latest correction."
            ),
            AgentService.Companion.ChatMessage(
                role = "assistant",
                content = "Ask only unresolved questions.",
                pendingToolCall = questionCall,
                toolCallId = questionCall.id,
                toolName = questionCall.name
            ),
            AgentService.Companion.ChatMessage(
                role = "tool",
                content = "status: ok\ntool: question\n" +
                    "summary: Authoritative user answer\nimportant_output:\n" +
                    "{\"answers\":{\"range\":\"[2,1000000]\",\"format\":\"CSV\"}," +
                    "\"correction\":\"pause must be resumable\"}\n" +
                    "next_hint: Follow the answer and do not ask this question again.",
                toolName = "question",
                toolCallId = questionCall.id
            ),
            AgentService.Companion.ChatMessage(
                role = "assistant",
                content = "The research handoff is ready.",
                pendingToolCall = reportCall,
                toolCallId = reportCall.id,
                toolName = reportCall.name
            ),
            AgentService.Companion.ChatMessage(
                role = "tool",
                content = "status: error\ntool: call_agent\n" +
                    "summary: report lookup failed\n" +
                    "important_output:\nNo work report found: report-opaque-final\n" +
                    "report_id: report-opaque-final\n" +
                    "next_hint: Reload project_state and use the known report ID.",
                toolName = "call_agent",
                toolCallId = reportCall.id
            )
        )

        val units = buildAgentPromptAtomicUnits(source.drop(2))
        val protectedIds = protectedAgentPromptGroupIds(
            units,
            latestControlExchangeCount = 2
        )
        val packed = source.take(2) + units.flatMap { unit ->
            if (unit.id !in protectedIds) return@flatMap emptyList()
            unit.messages.map { message ->
                if (message.role != "tool") {
                    message
                } else {
                    val preserved = preserveToolControlContent(
                        message = message,
                        maxChars = 1_600,
                        maxLines = 40
                    )
                    assertTrue(preserved.fits)
                    message.copy(content = preserved.content)
                }
            }
        }

        listOf(8_192, 16_384).forEach { contextTokens ->
            val request = buildCanonicalAgentPromptRequestJson(
                model = "fixture-model",
                messages = packed.map { it.toOllamaMessage(includeThinking = false) },
                tools = emptyList(),
                thinkingEnabled = false
            )
            val capacity = resolveAgentPromptCapacity(
                configuredContextTokens = contextTokens,
                reportedContextTokens = contextTokens,
                exactCountingAvailable = true
            )
            val promptTokens = estimateRawSerializedAgentRequestTokens(request)
            val budget = resolveAgentPromptOutputBudget(
                configuredMaxOutputTokens = 2_048,
                capacity = capacity,
                authoritativeInputTokens = promptTokens
            )

            assertTrue(budget.canSend)
            assertEquals(2_048, budget.effectiveMaxOutputTokens)
            assertTrue(request.contains("[2,1000000]"))
            assertTrue(request.contains("pause must be resumable"))
            assertTrue(request.contains("next_hint: Follow the answer"))
            assertTrue(request.contains("report-opaque-final"))
        }
    }

    @Test
    fun `fresh control packet wins over stale packet embedded in compaction summary`() {
        val staleSummary = """
            # Context Compaction State Projection

            - summarized_older_messages: 12

            # Project Control Packet
            - state_revision: 4
            - no_more_questions: false
            - latest_correction: OLD_CORRECTION
            ## Decisions
            - decision: OLD_DECISION
        """.trimIndent()
        val freshPacket = """
            # Project Control Packet
            - state_revision: 9
            - no_more_questions: true
            - latest_correction: FRESH_CORRECTION
        """.trimIndent()

        val basis = buildCompactPromptBasisSections(
            systemPrompt = "compact system",
            initialOrder = "build the requested project",
            planContent = null,
            compactionSummary = staleSummary,
            compactStateSnapshot = freshPacket
        )
        val serializedBasis = (basis.requiredSections + basis.optionalSections)
            .joinToString("\n")

        assertTrue(freshPacket in basis.requiredSections)
        assertTrue(serializedBasis.contains("FRESH_CORRECTION"))
        assertTrue(serializedBasis.contains("no_more_questions: true"))
        assertFalse(serializedBasis.contains("OLD_CORRECTION"))
        assertFalse(serializedBasis.contains("OLD_DECISION"))
        assertFalse(serializedBasis.contains("no_more_questions: false"))
        assertTrue(basis.optionalSections.single().contains("summarized_older_messages"))
    }

    @Test
    fun `configured output reservation is stricter than minimum generation reserve`() {
        val capacity = resolveAgentPromptCapacity(
            configuredContextTokens = 8_192,
            reportedContextTokens = 8_192,
            exactCountingAvailable = true
        )
        val inputLimit = maximumAgentPromptInputTokensForOutput(
            capacity = capacity,
            configuredMaxOutputTokens = 2_048
        )
        val budget = resolveAgentPromptOutputBudget(
            configuredMaxOutputTokens = 2_048,
            capacity = capacity,
            authoritativeInputTokens = inputLimit + 1
        )

        assertTrue(inputLimit < capacity.maximumInputTokens)
        assertFalse(budget.canSend)
        assertEquals(0, budget.effectiveMaxOutputTokens)
        assertEquals(
            AgentPromptBudgetFailureReason.CONFIGURED_OUTPUT_DOES_NOT_FIT,
            budget.cannotSendReason
        )
        assertEquals(
            inputLimit + 1 + capacity.safetyReserveTokens + 2_048,
            budget.requiredContextTokens
        )
    }
}
