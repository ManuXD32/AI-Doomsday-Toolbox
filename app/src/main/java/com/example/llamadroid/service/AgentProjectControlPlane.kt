package com.example.llamadroid.service

import android.content.Context
import androidx.room.withTransaction
import com.example.llamadroid.data.db.AgentInvocationEntity
import com.example.llamadroid.data.db.AgentPlanVersionEntity
import com.example.llamadroid.data.db.AgentProjectStateEntity
import com.example.llamadroid.data.db.AgentTodoEntity
import com.example.llamadroid.data.db.AgentWorkReportEntity
import com.example.llamadroid.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

internal const val AGENT_CONTROL_PLANE_VERSION = 1

internal object AgentTodoStatus {
    const val PENDING = "PENDING"
    const val READY = "READY"
    const val IN_PROGRESS = "IN_PROGRESS"
    const val READY_FOR_REVIEW = "READY_FOR_REVIEW"
    const val NEEDS_FIX = "NEEDS_FIX"
    const val READY_FOR_VERIFICATION = "READY_FOR_VERIFICATION"
    const val VERIFIED = "VERIFIED"
    const val COMPLETED = "COMPLETED"
    const val BLOCKED = "BLOCKED"
    const val CANCELLED = "CANCELLED"

    val terminal: Set<String> = setOf(COMPLETED, CANCELLED)
    val open: Set<String> = setOf(
        PENDING,
        READY,
        IN_PROGRESS,
        READY_FOR_REVIEW,
        NEEDS_FIX,
        READY_FOR_VERIFICATION,
        VERIFIED,
        BLOCKED
    )
}

internal object AgentCompactionStatus {
    const val REQUESTED = "REQUESTED"
    const val RUNNING = "RUNNING"
    const val APPLIED = "APPLIED"
    const val SATURATED = "SATURATED"
    const val FAILED = "FAILED"
}

internal data class StructuredPlanTodo(
    val id: String,
    val phaseId: String,
    val phaseTitle: String,
    val text: String,
    val ownerRole: String,
    val dependencies: List<String>,
    val acceptanceCriteria: List<String>,
    val priority: String = "NORMAL"
)

internal data class StructuredApprovedPlan(
    val id: String,
    val summary: String,
    val markdown: String,
    val planHash: String,
    val todos: List<StructuredPlanTodo>,
    val structuredJson: String
)

internal data class AgentPlanMaterializationResult(
    val planVersion: AgentPlanVersionEntity,
    val todos: List<AgentTodoEntity>,
    val created: Boolean,
    val stateRevision: Long
)

internal data class AgentWorkReportTransition(
    val report: AgentWorkReportEntity,
    val previousTodoStatus: String?,
    val nextTodoStatus: String?,
    val nextOwnerRole: String?,
    val stateRevision: Long
) {
    fun compactEnvelope(): String = buildString {
        appendLine("Specialist report committed.")
        appendLine("- report_id: ${report.id}")
        appendLine("- role: ${report.agentRole}")
        appendLine("- status: ${report.status}")
        report.todoId?.let { appendLine("- todo_id: $it") }
        nextTodoStatus?.let { appendLine("- todo_status: $it") }
        appendLine("- summary: ${report.summary.take(320)}")
        appendLine("- full_report: call agent_report_read(report_id=\"${report.id}\")")
        append("- next_action: ${recommendedAction()}")
    }.trim()

    private fun recommendedAction(): String = when (nextTodoStatus) {
        AgentTodoStatus.READY_FOR_REVIEW ->
            "delegate this TODO to REVIEWER"
        AgentTodoStatus.READY_FOR_VERIFICATION ->
            "delegate this TODO to EXECUTOR"
        AgentTodoStatus.NEEDS_FIX ->
            "delegate this TODO back to CODER with the report findings"
        AgentTodoStatus.BLOCKED ->
            "resolve the blocker or ask the user"
        AgentTodoStatus.COMPLETED ->
            "read project_state and choose the next READY TODO"
        else ->
            "read project_state and follow the permitted next action"
    }
}

internal data class AgentCompactionGateDecision(
    val shouldCompact: Boolean,
    val reason: String,
    val compactionKey: String? = null
)

internal data class AgentCompactionMeasurement(
    val status: String,
    val preTokens: Int,
    val postTokens: Int,
    val savedTokens: Int,
    val minimumUsefulSavings: Int,
    val stateRevision: Long
)

internal object AgentProjectControlPlane {
    private val stateCache =
        ConcurrentHashMap<Long, AgentProjectStateEntity>()

    private val sequentialWorkerRoles = setOf(
        "CODER",
        "REVIEWER",
        "EXECUTOR",
        "VISUAL_TESTER",
        "SUMMARIZER"
    )

    private val planningSpecialists = setOf(
        "CODEBASE_SCOUT",
        "RESEARCHER",
        "PLANNER"
    )

    private val validTodoTransitions: Map<String, Set<String>> = mapOf(
        AgentTodoStatus.PENDING to setOf(
            AgentTodoStatus.READY,
            AgentTodoStatus.BLOCKED,
            AgentTodoStatus.CANCELLED
        ),
        AgentTodoStatus.READY to setOf(
            AgentTodoStatus.IN_PROGRESS,
            AgentTodoStatus.BLOCKED,
            AgentTodoStatus.CANCELLED
        ),
        AgentTodoStatus.IN_PROGRESS to setOf(
            AgentTodoStatus.READY_FOR_REVIEW,
            AgentTodoStatus.NEEDS_FIX,
            AgentTodoStatus.READY_FOR_VERIFICATION,
            AgentTodoStatus.COMPLETED,
            AgentTodoStatus.BLOCKED
        ),
        AgentTodoStatus.READY_FOR_REVIEW to setOf(
            AgentTodoStatus.IN_PROGRESS,
            AgentTodoStatus.NEEDS_FIX,
            AgentTodoStatus.READY_FOR_VERIFICATION,
            AgentTodoStatus.BLOCKED
        ),
        AgentTodoStatus.NEEDS_FIX to setOf(
            AgentTodoStatus.IN_PROGRESS,
            AgentTodoStatus.BLOCKED,
            AgentTodoStatus.CANCELLED
        ),
        AgentTodoStatus.READY_FOR_VERIFICATION to setOf(
            AgentTodoStatus.IN_PROGRESS,
            AgentTodoStatus.VERIFIED,
            AgentTodoStatus.COMPLETED,
            AgentTodoStatus.NEEDS_FIX,
            AgentTodoStatus.BLOCKED
        ),
        AgentTodoStatus.VERIFIED to setOf(
            AgentTodoStatus.COMPLETED,
            AgentTodoStatus.NEEDS_FIX
        ),
        AgentTodoStatus.BLOCKED to setOf(
            AgentTodoStatus.READY,
            AgentTodoStatus.NEEDS_FIX,
            AgentTodoStatus.CANCELLED
        ),
        AgentTodoStatus.COMPLETED to emptySet(),
        AgentTodoStatus.CANCELLED to emptySet()
    )

    fun cachedState(conversationId: Long): AgentProjectStateEntity? =
        stateCache[conversationId]

    fun cacheState(state: AgentProjectStateEntity?) {
        if (state != null) {
            stateCache[state.conversationId] = state
        }
    }

    suspend fun ensureState(
        context: Context,
        conversationId: Long,
        goal: String? = null,
        mode: String? = null
    ): AgentProjectStateEntity = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getDatabase(context.applicationContext)
            .agentWorkflowDao()
        dao.insertProjectStateIfMissing(
            AgentProjectStateEntity(
                conversationId = conversationId,
                mode = mode ?: "PLAN",
                currentGoal = goal.orEmpty().trim()
            )
        )
        if (!goal.isNullOrBlank() || !mode.isNullOrBlank()) {
            dao.updateProjectStateBasics(
                conversationId = conversationId,
                mode = mode,
                currentGoal = goal?.trim()?.takeIf { it.isNotBlank() }
            )
        }
        dao.getProjectState(conversationId)
            ?.also(::cacheState)
            ?: error("Failed to initialize project control state")
    }

    suspend fun noteSemanticEvent(
        context: Context,
        conversationId: Long,
        kind: String,
        goal: String? = null
    ): AgentProjectStateEntity = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context.applicationContext)
        val state = db.withTransaction {
            val dao = db.agentWorkflowDao()
            dao.insertProjectStateIfMissing(
                AgentProjectStateEntity(
                    conversationId = conversationId,
                    currentGoal = goal.orEmpty().trim()
                )
            )
            dao.bumpProjectStateRevision(
                conversationId = conversationId,
                currentGoal = goal?.trim()?.takeIf { it.isNotBlank() },
                semanticEvent = kind.take(80)
            )
            dao.getProjectState(conversationId)
                ?: error("Project state disappeared during semantic update")
        }
        cacheState(state)
        state
    }

    fun isSequentialWorkerRole(role: String): Boolean =
        role.uppercase(Locale.ROOT) in sequentialWorkerRoles

    fun isPlanningSpecialist(role: String): Boolean =
        role.uppercase(Locale.ROOT) in planningSpecialists

    fun allowedToolsForRole(
        role: String,
        localBackend: Boolean
    ): Set<String> {
        val commonState = setOf(
            "project_state_read",
            "project_order_read",
            "plan_read",
            "agent_report_read",
            "todo_read",
            "reflection",
            "get_datetime"
        )
        val codeRead = setOf(
            "read_file",
            "read_file_lines",
            "file_line_count",
            "list_directory",
            "search_code",
            "run_tools_sequential"
        )
        val memoryRead = setOf(
            "read_memory",
            "list_memory"
        )
        val roleName = role.uppercase(Locale.ROOT)
        return when (roleName) {
            "ORCHESTRATOR" -> commonState + setOf(
                "question",
                "todo_transition",
                "todo_reconcile",
                "call_agent",
                "propose_plan",
                "report_progress",
                "skill",
                "read_skill_resource"
            )

            "CODEBASE_SCOUT" -> commonState + codeRead + memoryRead +
                setOf("finish_task")

            "RESEARCHER" -> commonState + memoryRead + setOf(
                "web_search",
                "fetch_url",
                "kiwix_search",
                "kb_search",
                "kb_read_chunk",
                "kb_list_sources",
                "finish_task"
            )

            "PLANNER" -> commonState + memoryRead + setOf(
                "finish_task"
            )

            "CODER" -> commonState + codeRead + memoryRead + setOf(
                "write_file",
                "edit_lines",
                "apply_patch",
                "create_folder",
                "write_memory",
                "rewrite_memory",
                "skill",
                "read_skill_resource",
                "run_skill_script",
                "generate_image",
                "remove_image_background",
                "view_image",
                "finish_task"
            )

            "REVIEWER" -> commonState + codeRead + memoryRead +
                setOf("finish_task")

            "EXECUTOR" -> commonState + codeRead + memoryRead + setOf(
                "run_command",
                "wait_command",
                "check_command",
                "command_list",
                "cancel_command",
                "send_command_input",
                "run_project",
                "check_project_run",
                "stop_project_run",
                "force_stop_project_run",
                "finish_task"
            )

            "SUMMARIZER" -> commonState + setOf(
                "read_memory",
                "list_memory",
                "write_memory",
                "rewrite_memory",
                "delete_memory",
                "finish_task"
            )

            "VISUAL_TESTER" -> setOf(
                "observe_preview",
                "interact_preview",
                "finish_task"
            )

            else -> emptySet()
        }.let { tools ->
            if (localBackend) {
                tools - setOf(
                    "run_command",
                    "wait_command",
                    "check_command",
                    "command_list",
                    "cancel_command",
                    "send_command_input",
                    "apply_patch"
                )
            } else {
                tools
            }
        }
    }

    fun parseApprovedPlan(
        summary: String,
        markdown: String
    ): StructuredApprovedPlan {
        val normalizedMarkdown = markdown.replace("\r\n", "\n").trim()
        val planHash = sha256(normalizedMarkdown)
        val structuredFromJson = parseStructuredPlanJson(
            summary,
            normalizedMarkdown,
            planHash
        )
        if (structuredFromJson != null) return structuredFromJson

        val headingRegex = Regex("""^#{2,4}\s+(.+)$""")
        val todoRegex = Regex(
            """^(?:[-*]\s+(?:\[[ xX]\]\s*)?|\d+[.)]\s+)(.+)$"""
        )
        var phaseTitle = "Implementation"
        var phaseId = "phase-${planHash.take(8)}-1"
        var phaseIndex = 1
        val rawItems = mutableListOf<Triple<String, String, String>>()

        normalizedMarkdown.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            headingRegex.matchEntire(line)?.let { match ->
                phaseTitle = match.groupValues[1].trim().take(120)
                phaseIndex += 1
                phaseId = "phase-${planHash.take(8)}-$phaseIndex"
                return@forEach
            }
            todoRegex.matchEntire(line)?.groupValues?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.length >= 8 }
                ?.let { item ->
                    rawItems += Triple(phaseId, phaseTitle, item.take(500))
                }
        }

        val fallbackItems = if (rawItems.isEmpty()) {
            normalizedMarkdown
                .split(Regex("""(?<=[.!?])\s+"""))
                .map { it.trim() }
                .filter { it.length >= 20 }
                .take(20)
                .map { Triple(phaseId, phaseTitle, it.take(500)) }
        } else {
            rawItems
        }

        val todos = fallbackItems.mapIndexed { index, (itemPhaseId, title, text) ->
            val id = "todo-${planHash.take(8)}-${(index + 1).toString().padStart(3, '0')}"
            val dependency = if (index == 0) {
                emptyList()
            } else {
                listOf(
                    "todo-${planHash.take(8)}-${index.toString().padStart(3, '0')}"
                )
            }
            StructuredPlanTodo(
                id = id,
                phaseId = itemPhaseId,
                phaseTitle = title,
                text = text,
                ownerRole = inferOwnerRole(text),
                dependencies = dependency,
                acceptanceCriteria = listOf(
                    "Complete and verify: ${text.take(240)}"
                ),
                priority = inferPriority(text)
            )
        }.ifEmpty {
            listOf(
                StructuredPlanTodo(
                    id = "todo-${planHash.take(8)}-001",
                    phaseId = phaseId,
                    phaseTitle = phaseTitle,
                    text = summary.ifBlank { "Implement the approved plan." },
                    ownerRole = "CODER",
                    dependencies = emptyList(),
                    acceptanceCriteria = listOf(
                        "The approved implementation is complete and verified."
                    )
                )
            )
        }

        return structuredPlan(
            id = "plan-${planHash.take(12)}",
            summary = summary,
            markdown = normalizedMarkdown,
            planHash = planHash,
            todos = todos
        )
    }

    private fun parseStructuredPlanJson(
        summary: String,
        markdown: String,
        planHash: String
    ): StructuredApprovedPlan? {
        val candidate = when {
            markdown.trimStart().startsWith("{") -> markdown.trim()
            else -> Regex(
                """```(?:json)?\s*(\{[\s\S]*?"phases"[\s\S]*?})\s*```""",
                RegexOption.IGNORE_CASE
            ).find(markdown)?.groupValues?.getOrNull(1)
        } ?: return null

        return runCatching {
            val root = JSONObject(candidate)
            val phases = root.optJSONArray("phases") ?: return@runCatching null
            val todos = mutableListOf<StructuredPlanTodo>()
            for (phaseIndex in 0 until phases.length()) {
                val phase = phases.optJSONObject(phaseIndex) ?: continue
                val phaseId = phase.optString("id").trim().ifBlank {
                    "phase-${planHash.take(8)}-${phaseIndex + 1}"
                }
                val phaseTitle = phase.optString("title").trim().ifBlank {
                    "Phase ${phaseIndex + 1}"
                }
                val phaseTodos = phase.optJSONArray("todos") ?: continue
                for (todoIndex in 0 until phaseTodos.length()) {
                    val todo = phaseTodos.optJSONObject(todoIndex) ?: continue
                    val text = todo.optString("text")
                        .ifBlank { todo.optString("title") }
                        .trim()
                    if (text.isBlank()) continue
                    val stableIndex = todos.size + 1
                    val id = todo.optString("id").trim().ifBlank {
                        "todo-${planHash.take(8)}-${stableIndex.toString().padStart(3, '0')}"
                    }
                    todos += StructuredPlanTodo(
                        id = id.take(96),
                        phaseId = phaseId.take(96),
                        phaseTitle = phaseTitle.take(120),
                        text = text.take(500),
                        ownerRole = todo.optString("owner_role")
                            .trim()
                            .uppercase(Locale.ROOT)
                            .ifBlank { inferOwnerRole(text) },
                        dependencies = todo.optJSONArray("dependencies")
                            .toStringList(),
                        acceptanceCriteria =
                            todo.optJSONArray("acceptance_criteria")
                                .toStringList()
                                .ifEmpty {
                                    listOf("Complete and verify: ${text.take(240)}")
                                },
                        priority = todo.optString("priority")
                            .trim()
                            .uppercase(Locale.ROOT)
                            .takeIf { it in setOf("LOW", "NORMAL", "HIGH") }
                            ?: inferPriority(text)
                    )
                }
            }
            if (todos.isEmpty()) return@runCatching null
            structuredPlan(
                id = root.optString("plan_version").trim().ifBlank {
                    "plan-${planHash.take(12)}"
                },
                summary = root.optString("summary")
                    .trim()
                    .ifBlank { summary },
                markdown = markdown,
                planHash = planHash,
                todos = todos
            )
        }.getOrNull()
    }

    private fun structuredPlan(
        id: String,
        summary: String,
        markdown: String,
        planHash: String,
        todos: List<StructuredPlanTodo>
    ): StructuredApprovedPlan {
        val phases = todos.groupBy { it.phaseId }
        val structured = JSONObject().apply {
            put("version", AGENT_CONTROL_PLANE_VERSION)
            put("plan_version", id)
            put("summary", summary)
            put("plan_hash", planHash)
            put(
                "phases",
                JSONArray().apply {
                    phases.forEach { (phaseId, phaseTodos) ->
                        put(
                            JSONObject().apply {
                                put("id", phaseId)
                                put(
                                    "title",
                                    phaseTodos.firstOrNull()?.phaseTitle
                                        ?: phaseId
                                )
                                put(
                                    "todos",
                                    JSONArray().apply {
                                        phaseTodos.forEach { todo ->
                                            put(
                                                JSONObject().apply {
                                                    put("id", todo.id)
                                                    put("text", todo.text)
                                                    put(
                                                        "owner_role",
                                                        todo.ownerRole
                                                    )
                                                    put(
                                                        "dependencies",
                                                        JSONArray(
                                                            todo.dependencies
                                                        )
                                                    )
                                                    put(
                                                        "acceptance_criteria",
                                                        JSONArray(
                                                            todo.acceptanceCriteria
                                                        )
                                                    )
                                                    put(
                                                        "priority",
                                                        todo.priority
                                                    )
                                                }
                                            )
                                        }
                                    }
                                )
                            }
                        )
                    }
                }
            )
        }.toString()

        return StructuredApprovedPlan(
            id = id.take(96),
            summary = summary.trim().take(500),
            markdown = markdown,
            planHash = planHash,
            todos = todos,
            structuredJson = structured
        )
    }

    suspend fun materializeApprovedPlan(
        context: Context,
        conversationId: Long,
        pendingPlanId: String?,
        summary: String,
        approvedPlan: String
    ): AgentPlanMaterializationResult = withContext(Dispatchers.IO) {
        val parsed = parseApprovedPlan(summary, approvedPlan)
        val scopedTodoIds = parsed.todos.mapIndexed { index, todo ->
            todo.id to (
                "todo-$conversationId-${parsed.planHash.take(8)}-" +
                    (index + 1).toString().padStart(3, '0')
                )
        }.toMap()
        val scopedTodos = parsed.todos.map { todo ->
            todo.copy(
                id = scopedTodoIds.getValue(todo.id),
                dependencies = todo.dependencies.mapNotNull(scopedTodoIds::get)
            )
        }
        val scopedPlan = structuredPlan(
            id = "plan-$conversationId-${parsed.planHash.take(12)}",
            summary = parsed.summary,
            markdown = parsed.markdown,
            planHash = parsed.planHash,
            todos = scopedTodos
        )
        val db = AppDatabase.getDatabase(context.applicationContext)
        val result = db.withTransaction {
            val dao = db.agentWorkflowDao()
            dao.insertProjectStateIfMissing(
                AgentProjectStateEntity(
                    conversationId = conversationId,
                    mode = "BUILD",
                    currentGoal = summary.trim()
                )
            )

            val existing = dao.getPlanVersionByHash(
                conversationId,
                scopedPlan.planHash
            )
            val created = existing == null
            val planVersion = existing ?: AgentPlanVersionEntity(
                id = scopedPlan.id,
                conversationId = conversationId,
                sourcePendingPlanId = pendingPlanId,
                versionNumber = dao.getNextPlanVersionNumber(conversationId),
                summary = scopedPlan.summary,
                planMarkdown = scopedPlan.markdown,
                structuredJson = scopedPlan.structuredJson,
                planHash = scopedPlan.planHash,
                status = "APPROVED",
                approvedAt = System.currentTimeMillis()
            ).also { dao.upsertPlanVersion(it) }

            var todos = dao.getTodosForPlanVersion(
                conversationId,
                planVersion.id
            )
            if (todos.isEmpty()) {
                val knownIds = scopedPlan.todos.map { it.id }.toSet()
                todos = scopedPlan.todos.mapIndexed { index, todo ->
                    val dependencies = todo.dependencies
                        .filter { it in knownIds }
                    AgentTodoEntity(
                        id = todo.id,
                        conversationId = conversationId,
                        text = todo.text,
                        status = if (dependencies.isEmpty()) {
                            AgentTodoStatus.READY
                        } else {
                            AgentTodoStatus.PENDING
                        },
                        priority = todo.priority,
                        position = index,
                        source = "APPROVED_PLAN",
                        planVersionId = planVersion.id,
                        planStepId = todo.id,
                        phaseId = todo.phaseId,
                        ownerRole = todo.ownerRole,
                        dependenciesJson = JSONArray(dependencies).toString(),
                        acceptanceCriteriaJson =
                            JSONArray(todo.acceptanceCriteria).toString()
                    )
                }
                dao.upsertTodos(todos)
            }

            val currentTodo = todos.firstOrNull {
                it.status in setOf(
                    AgentTodoStatus.READY,
                    AgentTodoStatus.NEEDS_FIX,
                    AgentTodoStatus.READY_FOR_REVIEW,
                    AgentTodoStatus.READY_FOR_VERIFICATION,
                    AgentTodoStatus.IN_PROGRESS,
                    AgentTodoStatus.BLOCKED
                )
            } ?: todos.firstOrNull { it.status == AgentTodoStatus.PENDING }

            dao.activateApprovedPlanState(
                conversationId = conversationId,
                planVersionId = planVersion.id,
                currentPhaseId = currentTodo?.phaseId,
                currentTodoId = currentTodo?.id,
                currentGoal = scopedPlan.summary
            )
            val state = dao.getProjectState(conversationId)
                ?: error("Project state missing after plan materialization")
            Triple(planVersion, todos, created) to state
        }
        cacheState(result.second)
        AgentPlanMaterializationResult(
            planVersion = result.first.first,
            todos = result.first.second,
            created = result.first.third,
            stateRevision = result.second.revision
        )
    }

    suspend fun reconcileTodos(
        context: Context,
        conversationId: Long,
        incoming: List<AgentTodoEntity>,
        reason: String
    ): List<AgentTodoEntity> = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context.applicationContext)
        val result = db.withTransaction {
            val dao = db.agentWorkflowDao()
            dao.insertProjectStateIfMissing(
                AgentProjectStateEntity(conversationId = conversationId)
            )
            val existing = dao.getTodos(conversationId)
            val existingById = existing.associateBy { it.id }
            val activePlanVersion = dao.getProjectState(conversationId)
                ?.activePlanVersionId
            val merged = incoming.mapIndexed { index, candidate ->
                val previous = existingById[candidate.id]
                if (previous == null) {
                    candidate.copy(
                        status = normalizeLegacyTodoStatus(candidate.status),
                        position = candidate.position.takeIf { it >= 0 } ?: index,
                        planVersionId =
                            candidate.planVersionId ?: activePlanVersion,
                        source = candidate.source.ifBlank {
                            "SAFE_RECONCILE"
                        }
                    )
                } else {
                    val requestedStatus =
                        normalizeLegacyTodoStatus(candidate.status)
                    previous.copy(
                        text = candidate.text.trim()
                            .takeIf { it.isNotBlank() }
                            ?: previous.text,
                        priority = candidate.priority.takeIf {
                            it in setOf("LOW", "NORMAL", "HIGH")
                        } ?: previous.priority,
                        position = candidate.position.takeIf { it >= 0 }
                            ?: previous.position,
                        status = nonRegressiveTodoStatus(
                            previous.status,
                            requestedStatus
                        ),
                        updatedAt = System.currentTimeMillis()
                    )
                }
            }
            if (merged.isNotEmpty()) dao.upsertTodos(merged)
            unlockDependencyReadyTodos(dao, conversationId)
            dao.bumpProjectStateRevision(
                conversationId = conversationId,
                semanticEvent = "todo_reconcile:${reason.take(60)}"
            )
            val all = dao.getTodos(conversationId)
            val state = dao.getProjectState(conversationId)
            if (state != null && state.currentTodoId == null) {
                val next = chooseNextTodo(all)
                dao.setProjectCurrentTodo(
                    conversationId,
                    next?.phaseId,
                    next?.id
                )
            }
            all to (dao.getProjectState(conversationId)
                ?: error("Project state missing after TODO reconcile"))
        }
        cacheState(result.second)
        result.first
    }

    suspend fun transitionTodo(
        context: Context,
        conversationId: Long,
        todoId: String,
        expectedStatus: String?,
        requestedStatus: String,
        resultSummary: String? = null,
        blockReason: String? = null,
        evidenceJson: String? = null
    ): AgentTodoEntity = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context.applicationContext)
        val result = db.withTransaction {
            val dao = db.agentWorkflowDao()
            val current = dao.getTodoById(todoId)
                ?: error("Unknown TODO: $todoId")
            require(current.conversationId == conversationId) {
                "TODO belongs to another project"
            }
            val expected = expectedStatus
                ?.uppercase(Locale.ROOT)
                ?.takeIf { it.isNotBlank() }
                ?: current.status
            require(current.status == expected) {
                "TODO $todoId changed from the expected state: " +
                    "expected=$expected actual=${current.status}"
            }
            val target = requestedStatus.uppercase(Locale.ROOT)
            require(target in validTodoTransitions[current.status].orEmpty()) {
                "Invalid TODO transition ${current.status} -> $target"
            }
            val nextOwner = ownerRoleForTodoStatus(target, current.ownerRole)
            val updated = dao.transitionTodoExactlyOnce(
                id = todoId,
                expectedStatus = current.status,
                newStatus = target,
                ownerRole = nextOwner,
                assignedInvocationId = null,
                resultSummary = resultSummary?.trim()?.take(4_000),
                blockReason = blockReason?.trim()?.take(1_000),
                evidenceJson = evidenceJson ?: current.evidenceJson,
                completedAt = if (target == AgentTodoStatus.COMPLETED) {
                    System.currentTimeMillis()
                } else {
                    null
                }
            )
            require(updated == 1) {
                "TODO changed concurrently; reload project_state and retry"
            }
            unlockDependencyReadyTodos(dao, conversationId)
            dao.bumpProjectStateRevision(
                conversationId = conversationId,
                semanticEvent = "todo_transition:$todoId:$target"
            )
            val todos = dao.getTodos(conversationId)
            val next = chooseNextTodo(todos)
            dao.setProjectCurrentTodo(
                conversationId,
                next?.phaseId,
                next?.id
            )
            val state = dao.getProjectState(conversationId)
                ?: error("Project state missing after TODO transition")
            (dao.getTodoById(todoId)
                ?: error("TODO disappeared after transition")) to state
        }
        cacheState(result.second)
        result.first
    }

    suspend fun allocateInvocationForTodo(
        context: Context,
        prototype: AgentInvocationEntity,
        todoId: String,
        role: String
    ): AgentInvocationEntity = withContext(Dispatchers.IO) {
        val roleName = role.uppercase(Locale.ROOT)
        val db = AppDatabase.getDatabase(context.applicationContext)
        val result = db.withTransaction {
            val dao = db.agentWorkflowDao()
            val todo = dao.getTodoById(todoId)
                ?: error("Unknown TODO: $todoId")
            require(todo.conversationId == prototype.conversationId) {
                "TODO belongs to another project"
            }
            val allowedStatuses = allowedClaimStatuses(roleName)
            require(todo.status in allowedStatuses) {
                "$roleName cannot claim TODO $todoId while it is ${todo.status}. " +
                    "Expected one of ${allowedStatuses.joinToString()}."
            }
            require(
                todo.ownerRole.isNullOrBlank() ||
                    todo.ownerRole.equals(roleName, ignoreCase = true) ||
                    todo.status == AgentTodoStatus.NEEDS_FIX
            ) {
                "TODO $todoId is owned by ${todo.ownerRole}, not $roleName"
            }
            val all = dao.getTodos(todo.conversationId)
            val byId = all.associateBy { it.id }
            val unmetDependencies = todo.dependencies()
                .filter { dependency ->
                    byId[dependency]?.status != AgentTodoStatus.COMPLETED
                }
            require(unmetDependencies.isEmpty()) {
                "TODO $todoId has incomplete dependencies: " +
                    unmetDependencies.joinToString()
            }

            val claimed = dao.claimTodoExactlyOnce(
                id = todoId,
                expectedStatus = todo.status,
                invocationId = prototype.id,
                ownerRole = roleName
            )
            require(claimed == 1) {
                "TODO $todoId was claimed concurrently"
            }
            val allocated = dao.allocateInvocation(
                prototype.copy(todoId = todoId)
            )
            dao.bumpProjectStateRevision(
                conversationId = todo.conversationId,
                semanticEvent = "todo_claim:$todoId:$roleName"
            )
            dao.setProjectCurrentTodo(
                todo.conversationId,
                todo.phaseId,
                todo.id
            )
            allocated to (dao.getProjectState(todo.conversationId)
                ?: error("Project state missing after TODO claim"))
        }
        cacheState(result.second)
        result.first
    }

    suspend fun recordWorkReportAndTransition(
        context: Context,
        invocationId: String,
        rawSummary: String,
        result: AgentResult,
        evidence: AgentEvidenceBundle
    ): AgentWorkReportTransition = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context.applicationContext)
        val transition = db.withTransaction {
            val dao = db.agentWorkflowDao()
            val invocation = dao.getInvocation(invocationId)
                ?: error("Invocation no longer exists: $invocationId")
            val reportId = "report-${UUID.randomUUID()}"
            val reportStatus = normalizeReportStatus(result.status)
            val structuredJson = normalizeStructuredReportJson(
                rawSummary,
                reportStatus
            )
            val changedFiles = changedFilesForResult(result, evidence)
            val risks = risksForResult(result)
            val recommendations = recommendationsForResult(result)
            val report = AgentWorkReportEntity(
                id = reportId,
                conversationId = invocation.conversationId,
                invocationId = invocation.id,
                todoId = invocation.todoId,
                agentRole = invocation.agentClass,
                status = reportStatus,
                summary = result
                    .toParentFacingSummary(
                        invocation.agentClass,
                        evidence
                    )
                    .take(4_000),
                structuredJson = structuredJson,
                evidenceJson = evidence.toJson(),
                changedFilesJson = JSONArray(changedFiles).toString(),
                risksJson = JSONArray(risks).toString(),
                recommendationsJson =
                    JSONArray(recommendations).toString()
            )
            dao.upsertWorkReport(report)
            dao.attachInvocationWorkReport(
                invocation.id,
                report.id
            )

            var previousTodoStatus: String? = null
            var nextTodoStatus: String? = null
            var nextOwnerRole: String? = null
            invocation.todoId?.let { todoId ->
                val todo = dao.getTodoById(todoId)
                    ?: error("Invocation TODO disappeared: $todoId")
                previousTodoStatus = todo.status
                val outcome = todoOutcomeForReport(
                    invocation.agentClass,
                    result,
                    reportStatus
                )
                nextTodoStatus = outcome.first
                nextOwnerRole = outcome.second
                val transitioned = dao.completeTodoInvocationExactlyOnce(
                    id = todo.id,
                    invocationId = invocation.id,
                    expectedStatus = AgentTodoStatus.IN_PROGRESS,
                    newStatus = nextTodoStatus!!,
                    ownerRole = nextOwnerRole,
                    resultSummary = report.summary,
                    blockReason = if (
                        nextTodoStatus == AgentTodoStatus.BLOCKED
                    ) {
                        risks.firstOrNull()
                            ?: recommendations.firstOrNull()
                            ?: "Specialist reported a blocker."
                    } else {
                        null
                    },
                    evidenceJson = report.evidenceJson,
                    completedAt = if (
                        nextTodoStatus == AgentTodoStatus.COMPLETED
                    ) {
                        System.currentTimeMillis()
                    } else {
                        null
                    }
                )
                require(transitioned == 1) {
                    "TODO ${todo.id} is no longer owned by invocation " +
                        invocation.id
                }
                unlockDependencyReadyTodos(
                    dao,
                    invocation.conversationId
                )
            }

            dao.bumpProjectStateRevision(
                conversationId = invocation.conversationId,
                semanticEvent = "work_report:${invocation.agentClass}:${report.status}"
            )
            val todos = dao.getTodos(invocation.conversationId)
            val next = chooseNextTodo(todos)
            dao.setProjectCurrentTodo(
                invocation.conversationId,
                next?.phaseId,
                next?.id
            )
            val state = dao.getProjectState(invocation.conversationId)
                ?: error("Project state missing after work report")
            AgentWorkReportTransition(
                report = report,
                previousTodoStatus = previousTodoStatus,
                nextTodoStatus = nextTodoStatus,
                nextOwnerRole = nextOwnerRole,
                stateRevision = state.revision
            ) to state
        }
        cacheState(transition.second)
        transition.first
    }

    suspend fun buildControlPacket(
        context: Context,
        conversationId: Long,
        initialOrder: String? = null,
        maxChars: Int = 12_000
    ): String = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getDatabase(context.applicationContext)
            .agentWorkflowDao()
        val state = ensureState(
            context,
            conversationId,
            goal = initialOrder?.let(::firstMeaningfulLine)
        )
        val todos = dao.getTodos(conversationId)
        val invocations = dao.getInvocations(conversationId)
        val reports = dao.getRecentWorkReports(conversationId, 6)
        val questions = dao.getPendingQuestions(conversationId)
        val pendingPlan = dao.getPendingPlan(conversationId)
        val activePlan = state.activePlanVersionId?.let {
            dao.getPlanVersionById(it)
        } ?: dao.getLatestApprovedPlan(conversationId)
        val currentTodo = state.currentTodoId?.let {
            dao.getTodoById(it)
        } ?: chooseNextTodo(todos)
        val counts = todos.groupingBy { it.status }.eachCount()
        val activeInvocations = invocations.filter {
            it.status.equals("RUNNING", ignoreCase = true)
        }
        val blockers = todos.filter {
            it.status == AgentTodoStatus.BLOCKED
        }

        val sections = mutableListOf<String>()
        sections += buildString {
            appendLine("# Project Control Packet")
            appendLine()
            appendLine("- control_version: $AGENT_CONTROL_PLANE_VERSION")
            appendLine("- state_revision: ${state.revision}")
            appendLine("- mode: ${state.mode}")
            appendLine(
                "- goal: ${
                    state.currentGoal.ifBlank {
                        initialOrder?.let(::firstMeaningfulLine)
                            ?: "No active goal recorded."
                    }.take(600)
                }"
            )
        }.trim()

        activePlan?.let { plan ->
            sections += buildString {
                appendLine("## Approved Plan")
                appendLine("- id: ${plan.id}")
                appendLine("- version: ${plan.versionNumber}")
                appendLine("- hash: ${plan.planHash.take(16)}")
                appendLine("- summary: ${plan.summary.take(500)}")
                appendLine(
                    "- full_plan: call plan_read(plan_id=\"${plan.id}\")"
                )
            }.trim()
        }

        sections += buildString {
            appendLine("## Progress")
            appendLine("- total: ${todos.size}")
            listOf(
                AgentTodoStatus.PENDING,
                AgentTodoStatus.READY,
                AgentTodoStatus.IN_PROGRESS,
                AgentTodoStatus.READY_FOR_REVIEW,
                AgentTodoStatus.NEEDS_FIX,
                AgentTodoStatus.READY_FOR_VERIFICATION,
                AgentTodoStatus.BLOCKED,
                AgentTodoStatus.COMPLETED,
                AgentTodoStatus.CANCELLED
            ).forEach { status ->
                val count = counts[status] ?: 0
                if (count > 0) {
                    appendLine("- ${status.lowercase()}: $count")
                }
            }
        }.trim()

        currentTodo?.let { todo ->
            sections += buildString {
                appendLine("## Current TODO")
                appendLine("- id: ${todo.id}")
                appendLine("- phase: ${todo.phaseId ?: "legacy"}")
                appendLine("- status: ${todo.status}")
                appendLine("- expected_owner: ${todo.ownerRole ?: "unassigned"}")
                appendLine("- task: ${todo.text.take(800)}")
                val criteria = todo.acceptanceCriteria()
                if (criteria.isNotEmpty()) {
                    appendLine("- acceptance_criteria:")
                    criteria.take(6).forEach {
                        appendLine("  - ${it.take(300)}")
                    }
                }
                val dependencies = todo.dependencies()
                if (dependencies.isNotEmpty()) {
                    appendLine(
                        "- dependencies: ${dependencies.joinToString()}"
                    )
                }
                todo.blockReason?.let {
                    appendLine("- blocker: ${it.take(600)}")
                }
            }.trim()
        }

        if (activeInvocations.isNotEmpty()) {
            sections += buildString {
                appendLine("## Active Invocations")
                activeInvocations.take(6).forEach { invocation ->
                    appendLine(
                        "- ${invocation.id}: ${invocation.agentClass} / " +
                            "${invocation.resolvedName}; " +
                            "todo=${invocation.todoId ?: "none"}; " +
                            "task=${invocation.task.take(260)}"
                    )
                }
            }.trim()
        }

        if (reports.isNotEmpty()) {
            sections += buildString {
                appendLine("## Recent Specialist Reports")
                reports.take(5).forEach { report ->
                    appendLine(
                        "- ${report.id}: ${report.agentRole} " +
                            "${report.status}; " +
                            "todo=${report.todoId ?: "none"}; " +
                            "${report.summary.replace(Regex("\\s+"), " ").take(300)}"
                    )
                }
            }.trim()
        }

        if (blockers.isNotEmpty() || questions.isNotEmpty() || pendingPlan != null) {
            sections += buildString {
                appendLine("## Blocking State")
                blockers.take(6).forEach { todo ->
                    appendLine(
                        "- blocked TODO ${todo.id}: " +
                            (todo.blockReason ?: todo.text).take(400)
                    )
                }
                questions.take(5).forEach { question ->
                    appendLine(
                        "- pending user question: ${question.id}"
                    )
                }
                pendingPlan?.let {
                    appendLine(
                        "- pending plan approval: ${it.id} (${it.state})"
                    )
                }
            }.trim()
        }

        sections += buildString {
            appendLine("## Permitted Next Actions")
            permittedNextActions(
                state = state,
                currentTodo = currentTodo,
                activeInvocations = activeInvocations,
                pendingQuestionCount = questions.size,
                pendingPlanPresent = pendingPlan != null
            ).forEach { appendLine("- $it") }
        }.trim()

        boundedSections(sections, maxChars)
    }

    suspend fun renderCompactionSummary(
        context: Context,
        conversationId: Long,
        summarizedMessageCount: Int,
        retainedRecentMessageCount: Int,
        retainedRecentTokenEstimate: Int,
        retainedRecentTargetTokens: Int,
        maxChars: Int
    ): String {
        val packet = buildControlPacket(
            context = context,
            conversationId = conversationId,
            maxChars = (maxChars - 500).coerceAtLeast(1_500)
        )
        return buildString {
            appendLine("# Context Compaction State Projection")
            appendLine()
            appendLine(
                "- summarized_older_messages: $summarizedMessageCount"
            )
            appendLine(
                "- retained_recent_messages: $retainedRecentMessageCount"
            )
            appendLine(
                "- retained_recent_tokens: " +
                    "$retainedRecentTokenEstimate / " +
                    "$retainedRecentTargetTokens target"
            )
            appendLine(
                "- authority: Room project state, TODOs, invocations, " +
                    "work reports, pending questions, and approved plans"
            )
            appendLine(
                "- note: this document is a rendered projection; it is not " +
                    "used as evidence for the next projection"
            )
            appendLine()
            append(packet)
        }.take(maxChars.coerceAtLeast(2_000))
    }

    suspend fun readPlan(
        context: Context,
        conversationId: Long,
        planId: String?
    ): String = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getDatabase(context.applicationContext)
            .agentWorkflowDao()
        val plan = planId?.takeIf { it.isNotBlank() }?.let {
            dao.getPlanVersionById(it)
        } ?: dao.getLatestApprovedPlan(conversationId)
        plan?.let {
            buildString {
                appendLine("# ${it.summary}")
                appendLine()
                appendLine("Plan ID: ${it.id}")
                appendLine("Version: ${it.versionNumber}")
                appendLine("Hash: ${it.planHash}")
                appendLine()
                append(it.planMarkdown)
            }
        } ?: "No approved plan is stored for this project."
    }

    suspend fun readWorkReport(
        context: Context,
        conversationId: Long,
        reportId: String
    ): String = withContext(Dispatchers.IO) {
        val report = AppDatabase.getDatabase(context.applicationContext)
            .agentWorkflowDao()
            .getWorkReport(reportId)
            ?.takeIf { it.conversationId == conversationId }
            ?: return@withContext "No work report found: $reportId"
        JSONObject().apply {
            put("id", report.id)
            put("invocation_id", report.invocationId)
            put("todo_id", report.todoId)
            put("agent_role", report.agentRole)
            put("status", report.status)
            put("summary", report.summary)
            put(
                "structured",
                parseJsonOrString(report.structuredJson)
            )
            put("evidence", JSONObject(report.evidenceJson))
            put("changed_files", JSONArray(report.changedFilesJson))
            put("risks", JSONArray(report.risksJson))
            put(
                "recommendations",
                JSONArray(report.recommendationsJson)
            )
            put("created_at", report.createdAt)
        }.toString(2)
    }

    fun compactDocumentReference(
        title: String,
        content: String?,
        maxChars: Int
    ): String? {
        val normalized = content
            ?.replace("\r\n", "\n")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val hash = sha256(normalized)
        val lines = normalized.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        val headings = lines.filter { it.startsWith("#") }.take(8)
        val constraints = lines.filter {
            it.startsWith("- ") ||
                it.startsWith("* ") ||
                Regex("""^\d+[.)]\s+""").containsMatchIn(it)
        }.take(12)
        val preview = buildString {
            appendLine("# $title Reference")
            appendLine("- hash: ${hash.take(16)}")
            appendLine("- full_document_is_durable: true")
            appendLine("- first_line: ${lines.firstOrNull()?.take(500).orEmpty()}")
            if (headings.isNotEmpty()) {
                appendLine("- headings:")
                headings.forEach { appendLine("  - ${it.take(240)}") }
            }
            if (constraints.isNotEmpty()) {
                appendLine("- key_items:")
                constraints.forEach { appendLine("  - ${it.take(300)}") }
            }
        }.trim()
        return if (preview.length <= maxChars) {
            preview
        } else {
            preview.take(maxChars)
        }
    }

    fun compactionDecision(
        conversationId: Long,
        percentUsed: Int,
        thresholdPercent: Int,
        emergencyThresholdPercent: Int,
        rootTurnId: String,
        toolDefinitionsHash: String
    ): AgentCompactionGateDecision {
        if (percentUsed < thresholdPercent) {
            return AgentCompactionGateDecision(
                false,
                "usage_below_threshold"
            )
        }
        val state = cachedState(conversationId)
            ?: return AgentCompactionGateDecision(
                shouldCompact = true,
                reason = "state_not_loaded"
            )
        val key = listOf(
            conversationId,
            state.revision,
            rootTurnId,
            toolDefinitionsHash
        ).joinToString("|")

        if (
            state.lastCompactedRevision == state.revision &&
            state.lastCompactionStatus in setOf(
                AgentCompactionStatus.RUNNING,
                AgentCompactionStatus.APPLIED,
                AgentCompactionStatus.SATURATED
            )
        ) {
            return AgentCompactionGateDecision(
                false,
                "same_semantic_revision_already_compacted"
            )
        }

        val semanticChangesSinceCompaction =
            state.semanticEventCount -
                state.lastCompactionSemanticEventCount
        val emergency = percentUsed >= emergencyThresholdPercent
        if (!emergency && semanticChangesSinceCompaction <= 0L) {
            return AgentCompactionGateDecision(
                false,
                "no_semantic_state_change"
            )
        }
        if (
            emergency &&
            state.lastCompactionKey == key
        ) {
            return AgentCompactionGateDecision(
                false,
                "emergency_already_used_for_revision"
            )
        }
        return AgentCompactionGateDecision(
            true,
            if (emergency) "emergency" else "semantic_threshold",
            key
        )
    }

    suspend fun markCompactionStarted(
        context: Context,
        conversationId: Long,
        compactionKey: String,
        preTokens: Int
    ): AgentProjectStateEntity = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getDatabase(context.applicationContext)
            .agentWorkflowDao()
        dao.recordProjectCompactionStarted(
            conversationId = conversationId,
            compactionKey = compactionKey,
            preTokens = preTokens.coerceAtLeast(0)
        )
        dao.getProjectState(conversationId)
            ?.also(::cacheState)
            ?: error("Project state missing at compaction start")
    }

    suspend fun completeCompactionMeasurement(
        context: Context,
        conversationId: Long,
        postTokens: Int,
        maximumInputTokens: Int
    ): AgentCompactionMeasurement = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getDatabase(context.applicationContext)
            .agentWorkflowDao()
        val current = dao.getProjectState(conversationId)
            ?: ensureState(context, conversationId)
        val pre = current.lastCompactionPreTokens ?: postTokens
        val post = postTokens.coerceAtLeast(0)
        val saved = (pre - post).coerceAtLeast(0)
        val minimumSavings = max(
            1_024,
            maximumInputTokens.coerceAtLeast(1) / 10
        )
        val status = if (saved >= minimumSavings) {
            AgentCompactionStatus.APPLIED
        } else {
            AgentCompactionStatus.SATURATED
        }
        dao.recordProjectCompactionCompleted(
            conversationId = conversationId,
            status = status,
            postTokens = post,
            savedTokens = saved,
            saturationReason = if (
                status == AgentCompactionStatus.SATURATED
            ) {
                "Compaction saved $saved tokens; minimum useful savings " +
                    "is $minimumSavings. Required state/tool basis is saturated."
            } else {
                null
            }
        )
        val updated = dao.getProjectState(conversationId)
            ?: error("Project state missing after compaction measurement")
        cacheState(updated)
        AgentCompactionMeasurement(
            status = status,
            preTokens = pre,
            postTokens = post,
            savedTokens = saved,
            minimumUsefulSavings = minimumSavings,
            stateRevision = updated.revision
        )
    }

    suspend fun markCompactionFailed(
        context: Context,
        conversationId: Long,
        reason: String
    ) = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getDatabase(context.applicationContext)
            .agentWorkflowDao()
        dao.recordProjectCompactionFailed(
            conversationId,
            reason.take(1_000)
        )
        cacheState(dao.getProjectState(conversationId))
    }

    fun renderTodoMarkdown(todos: List<AgentTodoEntity>): String =
        buildString {
            appendLine("# TODO")
            appendLine()
            if (todos.isEmpty()) {
                appendLine("- No pending tasks recorded.")
            } else {
                todos.sortedBy { it.position }.forEach { todo ->
                    val marker = if (
                        todo.status == AgentTodoStatus.COMPLETED
                    ) {
                        "x"
                    } else {
                        " "
                    }
                    appendLine(
                        "- [$marker] ${todo.id} | ${todo.status} | " +
                            "${todo.ownerRole ?: "unassigned"} | ${todo.text}"
                    )
                }
            }
        }.trimEnd()

    private suspend fun unlockDependencyReadyTodos(
        dao: com.example.llamadroid.data.db.AgentWorkflowDao,
        conversationId: Long
    ) {
        var changed: Boolean
        do {
            changed = false
            val todos = dao.getTodos(conversationId)
            val byId = todos.associateBy { it.id }
            todos.filter { it.status == AgentTodoStatus.PENDING }
                .forEach { todo ->
                    val dependencies = todo.dependencies()
                    if (
                        dependencies.all {
                            byId[it]?.status == AgentTodoStatus.COMPLETED
                        }
                    ) {
                        if (
                            dao.markTodoReadyExactlyOnce(
                                todo.id,
                                AgentTodoStatus.PENDING,
                                AgentTodoStatus.READY
                            ) == 1
                        ) {
                            changed = true
                        }
                    }
                }
        } while (changed)
    }

    private fun chooseNextTodo(
        todos: List<AgentTodoEntity>
    ): AgentTodoEntity? {
        val order = listOf(
            AgentTodoStatus.IN_PROGRESS,
            AgentTodoStatus.NEEDS_FIX,
            AgentTodoStatus.READY_FOR_REVIEW,
            AgentTodoStatus.READY_FOR_VERIFICATION,
            AgentTodoStatus.READY,
            AgentTodoStatus.BLOCKED,
            AgentTodoStatus.PENDING
        )
        return todos
            .filter { it.status !in AgentTodoStatus.terminal }
            .minWithOrNull(
                compareBy<AgentTodoEntity> {
                    order.indexOf(it.status).takeIf { index -> index >= 0 }
                        ?: Int.MAX_VALUE
                }.thenBy { it.position }
            )
    }

    private fun allowedClaimStatuses(role: String): Set<String> =
        when (role) {
            "CODER" -> setOf(
                AgentTodoStatus.READY,
                AgentTodoStatus.NEEDS_FIX
            )
            "REVIEWER" -> setOf(
                AgentTodoStatus.READY_FOR_REVIEW
            )
            "EXECUTOR", "VISUAL_TESTER" -> setOf(
                AgentTodoStatus.READY_FOR_VERIFICATION
            )
            "SUMMARIZER" -> setOf(
                AgentTodoStatus.READY,
                AgentTodoStatus.NEEDS_FIX
            )
            else -> setOf(
                AgentTodoStatus.READY,
                AgentTodoStatus.PENDING,
                AgentTodoStatus.NEEDS_FIX
            )
        }

    private fun todoOutcomeForReport(
        role: String,
        result: AgentResult,
        reportStatus: String
    ): Pair<String, String?> {
        if (reportStatus == "BLOCKED") {
            return AgentTodoStatus.BLOCKED to role.uppercase(Locale.ROOT)
        }
        val success = reportStatus == "SUCCESS"
        return when (role.uppercase(Locale.ROOT)) {
            "CODER" -> if (success) {
                AgentTodoStatus.READY_FOR_REVIEW to "REVIEWER"
            } else {
                AgentTodoStatus.NEEDS_FIX to "CODER"
            }

            "REVIEWER" -> {
                val hasFindings = (result as? AgentResult.ReviewerResult)
                    ?.findings
                    ?.isNotEmpty() == true
                if (success && !hasFindings) {
                    AgentTodoStatus.READY_FOR_VERIFICATION to "EXECUTOR"
                } else {
                    AgentTodoStatus.NEEDS_FIX to "CODER"
                }
            }

            "EXECUTOR", "VISUAL_TESTER" -> if (success) {
                AgentTodoStatus.COMPLETED to null
            } else {
                AgentTodoStatus.NEEDS_FIX to "CODER"
            }

            "CODEBASE_SCOUT", "RESEARCHER", "PLANNER", "SUMMARIZER" ->
                if (success) {
                    AgentTodoStatus.COMPLETED to null
                } else {
                    AgentTodoStatus.BLOCKED to role.uppercase(Locale.ROOT)
                }

            else -> if (success) {
                AgentTodoStatus.COMPLETED to null
            } else {
                AgentTodoStatus.BLOCKED to role.uppercase(Locale.ROOT)
            }
        }
    }

    private fun ownerRoleForTodoStatus(
        status: String,
        previousOwner: String?
    ): String? = when (status) {
        AgentTodoStatus.READY_FOR_REVIEW -> "REVIEWER"
        AgentTodoStatus.NEEDS_FIX -> "CODER"
        AgentTodoStatus.READY_FOR_VERIFICATION -> "EXECUTOR"
        AgentTodoStatus.COMPLETED,
        AgentTodoStatus.CANCELLED -> null
        else -> previousOwner
    }

    private fun permittedNextActions(
        state: AgentProjectStateEntity,
        currentTodo: AgentTodoEntity?,
        activeInvocations: List<AgentInvocationEntity>,
        pendingQuestionCount: Int,
        pendingPlanPresent: Boolean
    ): List<String> {
        if (pendingPlanPresent) {
            return listOf("wait for the user to resolve the proposed plan")
        }
        if (pendingQuestionCount > 0) {
            return listOf("wait for the pending user answer")
        }
        if (activeInvocations.isNotEmpty()) {
            return listOf(
                "wait for the running specialist invocation",
                "read project_state again after it returns"
            )
        }
        if (state.mode == "PLAN") {
            return listOf(
                "delegate repository discovery to CODEBASE_SCOUT",
                "delegate external research to RESEARCHER only when needed",
                "delegate plan synthesis to PLANNER",
                "ask any remaining blocking user question",
                "submit one final propose_plan"
            )
        }
        return when (currentTodo?.status) {
            AgentTodoStatus.READY,
            AgentTodoStatus.NEEDS_FIX ->
                listOf(
                    "delegate ${currentTodo.id} to CODER",
                    "include the TODO acceptance criteria and latest relevant report"
                )
            AgentTodoStatus.READY_FOR_REVIEW ->
                listOf("delegate ${currentTodo.id} to REVIEWER")
            AgentTodoStatus.READY_FOR_VERIFICATION ->
                listOf("delegate ${currentTodo.id} to EXECUTOR")
            AgentTodoStatus.BLOCKED ->
                listOf(
                    "read the blocker evidence",
                    "ask the user only when an external decision is required"
                )
            AgentTodoStatus.PENDING ->
                listOf("complete its dependency TODOs first")
            null ->
                listOf(
                    "verify all required TODOs are terminal",
                    "run final reflection and present the result"
                )
            else ->
                listOf("read project_state and follow the current TODO state")
        }
    }

    private fun boundedSections(
        sections: List<String>,
        maxChars: Int
    ): String {
        val limit = maxChars.coerceAtLeast(1_500)
        val builder = StringBuilder()
        sections.forEach { section ->
            val separator = if (builder.isEmpty()) "" else "\n\n"
            if (builder.length + separator.length + section.length <= limit) {
                builder.append(separator).append(section)
            } else if (builder.length < limit) {
                val remaining = limit - builder.length - separator.length
                if (remaining > 100) {
                    builder.append(separator)
                    builder.append(section.take(remaining))
                }
                return@forEach
            }
        }
        return builder.toString().take(limit)
    }

    private fun inferOwnerRole(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            lower.contains("research") ||
                lower.contains("documentation") ||
                lower.contains("internet") -> "RESEARCHER"
            lower.contains("inspect") ||
                lower.contains("locate") ||
                lower.contains("map the code") ||
                lower.contains("explore") -> "CODEBASE_SCOUT"
            lower.contains("review") ||
                lower.contains("audit") -> "REVIEWER"
            lower.contains("test") ||
                lower.contains("build") ||
                lower.contains("verify") ||
                lower.contains("run ") -> "EXECUTOR"
            lower.contains("visual") ||
                lower.contains("webui") ||
                lower.contains("preview") -> "VISUAL_TESTER"
            lower.contains("summary") ||
                lower.contains("memory") -> "SUMMARIZER"
            else -> "CODER"
        }
    }

    private fun inferPriority(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            lower.contains("critical") ||
                lower.contains("security") ||
                lower.contains("data loss") -> "HIGH"
            lower.contains("optional") ||
                lower.contains("nice to have") -> "LOW"
            else -> "NORMAL"
        }
    }

    private fun normalizeLegacyTodoStatus(status: String): String =
        when (status.uppercase(Locale.ROOT)) {
            "DONE", "SUCCESS", "VERIFIED" -> AgentTodoStatus.COMPLETED
            "IN_PROGRESS", "RUNNING" -> AgentTodoStatus.IN_PROGRESS
            "CANCELLED", "CANCELED" -> AgentTodoStatus.CANCELLED
            "BLOCKED" -> AgentTodoStatus.BLOCKED
            "READY_FOR_REVIEW" -> AgentTodoStatus.READY_FOR_REVIEW
            "NEEDS_FIX", "FAILED" -> AgentTodoStatus.NEEDS_FIX
            "READY_FOR_VERIFICATION" ->
                AgentTodoStatus.READY_FOR_VERIFICATION
            "READY" -> AgentTodoStatus.READY
            else -> AgentTodoStatus.PENDING
        }

    private fun nonRegressiveTodoStatus(
        existing: String,
        requested: String
    ): String {
        if (existing in AgentTodoStatus.terminal) return existing
        if (requested == existing) return existing
        return if (requested in validTodoTransitions[existing].orEmpty()) {
            requested
        } else {
            existing
        }
    }

    private fun normalizeReportStatus(status: String): String =
        when (status.uppercase(Locale.ROOT)) {
            "SUCCESS", "PASSED", "PASS", "COMPLETED" -> "SUCCESS"
            "BLOCKED" -> "BLOCKED"
            else -> "FAILED"
        }

    private fun normalizeStructuredReportJson(
        raw: String,
        status: String
    ): String = runCatching {
        JSONObject(raw).toString()
    }.getOrElse {
        JSONObject()
            .put("status", status)
            .put("summary", raw.take(8_000))
            .toString()
    }

    private fun changedFilesForResult(
        result: AgentResult,
        evidence: AgentEvidenceBundle
    ): List<String> = (
        evidence.changedFiles +
            when (result) {
                is AgentResult.CoderResult -> result.changedFiles
                else -> emptyList()
            }
        ).map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .take(100)

    private fun risksForResult(result: AgentResult): List<String> =
        when (result) {
            is AgentResult.CoderResult -> result.remainingRisks
            is AgentResult.ReviewerResult -> result.remainingRisks +
                result.findings.map {
                    "${it.severity} ${it.file}: ${it.description}"
                }
            is AgentResult.ExecutorResult ->
                if (
                    result.status.equals("SUCCESS", true) &&
                    Regex("pass|success|complete", RegexOption.IGNORE_CASE)
                        .containsMatchIn(result.finalStatus)
                ) {
                    emptyList()
                } else {
                    listOf(result.finalStatus)
                }
            is AgentResult.SummarizerResult -> emptyList()
            is AgentResult.ScoutResult -> result.risks
            is AgentResult.ResearcherResult -> result.uncertainties
            is AgentResult.PlannerResult -> result.openQuestions
            is AgentResult.GenericResult ->
                if (result.status.equals("SUCCESS", true)) {
                    emptyList()
                } else {
                    listOf(result.summary)
                }
        }.map { it.take(500) }.distinct().take(30)

    private fun recommendationsForResult(
        result: AgentResult
    ): List<String> = when (result) {
        is AgentResult.ReviewerResult ->
            result.findings.map { it.recommendation }
        is AgentResult.ExecutorResult ->
            listOf(result.nextRecommendation)
        is AgentResult.SummarizerResult ->
            result.carryForwardNotes
        is AgentResult.ScoutResult ->
            result.recommendedScope
        is AgentResult.ResearcherResult ->
            result.recommendations
        is AgentResult.PlannerResult ->
            result.recommendedNextSteps
        is AgentResult.CoderResult ->
            result.remainingRisks.map { "Resolve: $it" }
        is AgentResult.GenericResult ->
            listOf(result.summary)
    }.map { it.take(500) }.filter { it.isNotBlank() }.distinct().take(30)

    private fun AgentEvidenceBundle.toJson(): String =
        JSONObject()
            .put("changed_files", JSONArray(changedFiles))
            .put("command_ids", JSONArray(commandIds))
            .put("line_references", JSONArray(lineReferences))
            .put("memory_files_touched", JSONArray(memoryFilesTouched))
            .toString()

    private fun AgentTodoEntity.dependencies(): List<String> =
        runCatching {
            JSONArray(dependenciesJson).toStringList()
        }.getOrDefault(emptyList())

    private fun AgentTodoEntity.acceptanceCriteria(): List<String> =
        runCatching {
            JSONArray(acceptanceCriteriaJson).toStringList()
        }.getOrDefault(emptyList())

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).trim()
                    .takeIf { it.isNotBlank() }
                    ?.let(::add)
            }
        }
    }

    private fun parseJsonOrString(raw: String): Any =
        runCatching { JSONObject(raw) }.getOrElse { raw }

    private fun firstMeaningfulLine(text: String): String =
        text.lineSequence()
            .map { it.trim().removePrefix("#").trim() }
            .firstOrNull { it.isNotBlank() }
            ?.take(600)
            ?: "No active goal recorded."

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
