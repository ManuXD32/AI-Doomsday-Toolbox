package com.example.llamadroid.service

import com.example.llamadroid.data.db.AgentTodoEntity
import org.json.JSONArray
import java.util.Locale

/**
 * A bounded, runtime-only view of an approved Direct plan.
 *
 * [StructuredApprovedPlan] remains the canonical representation. This view is
 * deliberately derived after parsing so the approval record, the original
 * Markdown, and the canonical structured JSON keep every user/model decision.
 * Only the TODO rows used to drive the small-model loop are collapsed.
 */
internal data class DirectApprovedPlanProjection(
    val sourcePlan: StructuredApprovedPlan,
    val todos: List<StructuredPlanTodo>,
    val sourceTodoIdsByProjectedTodoId: Map<String, List<String>>,
    val projected: Boolean
) {
    val sourceTodoCount: Int get() = sourcePlan.todos.size
    val projectedTodoCount: Int get() = todos.size
    val collapsedTodoCount: Int get() = (sourceTodoCount - projectedTodoCount).coerceAtLeast(0)

    /** The projected plan is useful in tests and in callers that need a pure view. */
    fun asProjectedPlan(): StructuredApprovedPlan = sourcePlan.copy(todos = todos)
}

/**
 * Direct plan projection policy. Three semantic phase buckets keep a detailed
 * plan within the small-model continuation budget while retaining the source
 * item text and acceptance criteria in the durable approved plan.
 */
internal object AgentDirectPlanProjection {
    const val MAX_PHASE_TODOS = 4
    const val TARGET_MAX_MODEL_RESPONSES = 15
    const val MAX_PROJECTED_TODO_TEXT_CHARS = 2_400
    const val MAX_PROJECTED_ACCEPTANCE_CRITERIA = 8
    const val MAX_PROJECTED_CRITERION_CHARS = 360

    private enum class DirectPhase(
        val key: String,
        val title: String,
        val ownerRole: String
    ) {
        BUILD("build", "Build", "CODER"),
        VERIFY("verify", "Verify", "EXECUTOR")
    }

    private data class AssignedTodo(
        val sourceIndex: Int,
        val todo: StructuredPlanTodo,
        val phase: DirectPhase
    )

    private val phaseOrder = DirectPhase.entries

    private val verifyTerms = Regex(
        "(?i)\\b(?:verify|verification|test|tests|testing|check|checks|" +
            "validate|validation|validated|assert|assertion|preview|" +
            "lint|compile|compilation|regression|qa)\\b"
    )
    private val completeTerms = Regex(
        "(?i)\\b(?:final|finish|finishing|cleanup|clean[- ]?up|" +
            "documentation|document|release|ship|handoff|hand[- ]?off|" +
            "close[- ]?out|rollout|publish)\\b"
    )
    private val explicitMutationTerms = Regex(
        "(?i)^(?:\\s*[-*+]\\s*)?(?:\\*{1,2})?" +
            "(?:build|create|implement|add|write|edit|update|modify|refactor)\\b"
    )
    private val buildPhaseTitleTerms = Regex(
        "(?i)\\b(?:build|implementation|implement|create|creation|development|" +
            "develop|design|coding|code|state|ui|backend|data|integration)\\b"
    )
    private val verifyPhaseTitleTerms = Regex(
        "(?i)\\b(?:verify|verification|test|tests|testing|check|checks|qa|" +
            "validation|quality)\\b"
    )
    private val completePhaseTitleTerms = Regex(
        "(?i)\\b(?:complete|completion|final|finish|cleanup|clean[- ]?up|" +
            "docs?|documentation|document|release|ship|handoff|hand[- ]?off|" +
            "close[- ]?out|rollout|publish)\\b"
    )

    private val explicitArtifactLabel = Regex(
        "(?i)\\b(?:artifact|artifacts|file|files|path|paths|output|outputs)" +
            "\\s*[:=]\\s*([^\\r\\n;]{1,600})"
    )
    private val codeSpan = Regex("`([^`\\r\\n]{1,300})`")
    private val pathToken = Regex(
        "(?<![A-Za-z0-9_@])(?:\\.?/)?[A-Za-z0-9_.-]+" +
            "(?:/[A-Za-z0-9_.-]+)+|" +
            "(?<![A-Za-z0-9_@])(?:[A-Za-z0-9_.-]+\\.)+" +
            "(?:kt|kts|java|xml|json|md|markdown|js|jsx|ts|tsx|css|html|" +
            "py|toml|yaml|yml|gradle|properties|txt|png|jpg|jpeg|webp|" +
            "svg|gif|sh|aab|apk|whl)(?![A-Za-z0-9_])"
    )
    private val knownPathRoots = setOf(
        "app", "assets", "build", "config", "docs", "feature", "generated",
        "gradle", "lib", "public", "res", "scripts", "src", "test", "tests",
        "tools", "web", ".adt"
    )
    private val artifactOperations = setOf("write", "edit", "patch", "create")
    private val knownFileExtensions = setOf(
        "kt", "kts", "java", "xml", "json", "md", "markdown", "js", "jsx", "ts",
        "tsx", "css", "html", "py", "toml", "yaml", "yml", "gradle", "properties",
        "txt", "png", "jpg", "jpeg", "webp", "svg", "gif", "sh", "aab", "apk", "whl"
    )

    /**
     * Collapse source plan items into at most four deterministic phase TODOs.
     * Direct's durable state machine has Plan, Build, and Verify modes, so
     * final/documentation/handoff actions join Verify instead of creating a
     * runtime phase that cannot be claimed by the Direct loop.
     */
    fun project(
        plan: StructuredApprovedPlan,
        maxPhaseTodos: Int = MAX_PHASE_TODOS
    ): DirectApprovedPlanProjection {
        val limit = maxPhaseTodos.coerceIn(1, MAX_PHASE_TODOS)
        if (plan.todos.isEmpty()) {
            return DirectApprovedPlanProjection(
                sourcePlan = plan,
                todos = emptyList(),
                sourceTodoIdsByProjectedTodoId = emptyMap(),
                projected = false
            )
        }

        val assigned = plan.todos.mapIndexed { index, todo ->
            AssignedTodo(index, todo, classify(todo))
        }
        val grouped = phaseOrder.associateWith { phase ->
            assigned.filter { it.phase == phase }
        }.filterValues { it.isNotEmpty() }

        // Normally there are two groups. If a caller explicitly asks for a
        // smaller limit, merge overflow into the last retained group without
        // dropping any source item.
        val buckets = if (grouped.size <= limit) {
            grouped
        } else {
            val retained = phaseOrder.take(limit)
                .associateWith { phase -> grouped[phase].orEmpty().toMutableList() }
                .toMutableMap()
            val overflowTarget = phaseOrder[limit - 1]
            phaseOrder.drop(limit).forEach { phase ->
                retained.getValue(overflowTarget).addAll(grouped[phase].orEmpty())
            }
            retained.filterValues { it.isNotEmpty() }
        }

        val projected = mutableListOf<StructuredPlanTodo>()
        val sourceIds = linkedMapOf<String, List<String>>()
        var previousId: String? = null
        buckets.entries.forEach { (phase, members) ->
            val id = "direct-todo-${plan.planHash.take(8)}-${phase.key}"
                .take(96)
            val sourceItems = members.map { it.todo }
            val artifactPaths = sourceItems.flatMap { todo ->
                extractDirectArtifactPaths(
                    buildString {
                        append(todo.text)
                        todo.acceptanceCriteria.forEach {
                            append('\n').append(it)
                        }
                    }
                )
            }.distinct()
            val itemText = sourceItems.mapIndexed { index, todo ->
                val sourcePhase = todo.phaseTitle.trim()
                    .takeIf { it.isNotBlank() && !it.equals(phase.title, true) }
                if (sourcePhase == null) {
                    "${index + 1}. ${todo.text.trim()}"
                } else {
                    "${index + 1}. [$sourcePhase] ${todo.text.trim()}"
                }
            }.joinToString(" · ")
            val artifactSuffix = artifactPaths.takeIf { it.isNotEmpty() }
                ?.let { " Declared artifacts: ${it.joinToString(", ")}." }
                .orEmpty()
            val text = boundedTodoText(
                prefix = "${phase.title} phase: ",
                body = itemText,
                suffix = artifactSuffix,
                limit = MAX_PROJECTED_TODO_TEXT_CHARS
            )

            val criteria = sourceItems.flatMap { it.acceptanceCriteria }
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { it.take(MAX_PROJECTED_CRITERION_CHARS) }
                .distinct()
                .take(MAX_PROJECTED_ACCEPTANCE_CRITERIA - 1)
                .toMutableList()
            criteria += "Complete and verify the ${phase.title.lowercase(Locale.ROOT)} phase."

            val priority = when {
                sourceItems.any { it.priority.equals("HIGH", true) } -> "HIGH"
                sourceItems.any { it.priority.equals("NORMAL", true) } -> "NORMAL"
                else -> sourceItems.firstOrNull()?.priority?.uppercase(Locale.ROOT) ?: "NORMAL"
            }
            val todo = StructuredPlanTodo(
                id = id,
                phaseId = "direct-phase-${plan.planHash.take(8)}-${phase.key}".take(96),
                phaseTitle = phase.title,
                text = text,
                ownerRole = phase.ownerRole,
                dependencies = previousId?.let(::listOf).orEmpty(),
                acceptanceCriteria = criteria,
                priority = priority
            )
            projected += todo
            sourceIds[id] = members
                .sortedBy(AssignedTodo::sourceIndex)
                .map { it.todo.id }
            previousId = id
        }

        return DirectApprovedPlanProjection(
            sourcePlan = plan,
            todos = projected,
            sourceTodoIdsByProjectedTodoId = sourceIds,
            projected = projected != plan.todos
        )
    }

    /** Compatibility-friendly alias for callers that describe this as a view. */
    fun projectApprovedPlan(
        plan: StructuredApprovedPlan,
        maxPhaseTodos: Int = MAX_PHASE_TODOS
    ): DirectApprovedPlanProjection = project(plan, maxPhaseTodos)

    /**
     * Extract project-relative paths explicitly named by plan prose. This is
     * intentionally conservative: a path is a useful completion gate only if
     * it is clearly path-shaped or follows an artifact/file/path label.
     */
    fun extractDirectArtifactPaths(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val candidates = linkedSetOf<String>()
        explicitArtifactLabel.findAll(text).forEach { match ->
            collectPathCandidates(match.groupValues[1], candidates, allowBare = false)
        }
        codeSpan.findAll(text).forEach { match ->
            collectPathCandidates(match.groupValues[1], candidates, allowBare = false)
        }
        pathToken.findAll(text).forEach { match ->
            normalizePath(match.value)?.takeIf(::looksLikePath)?.let(candidates::add)
        }
        return candidates.toList()
    }

    /**
     * Returns true only for an in-progress Build TODO whose every declared
     * path has a non-delete successful mutation entry. An incomplete ledger
     * cannot prove that a path is present, and writes alone never pass Verify.
     */
    fun declaredBuildArtifactsAreCommitted(
        todo: AgentTodoEntity,
        ledger: AgentArtifactLedger
    ): Boolean {
        if (todo.status != AgentTodoStatus.IN_PROGRESS || ledger.incomplete) return false
        val declared = declaredArtifactPaths(todo)
        return declared.isNotEmpty() && missingDeclaredBuildArtifacts(todo, ledger).isEmpty()
    }

    /**
     * A Build step may close only when every declared path is committed and no
     * latest artifact receipt remains explicitly partial. The latter applies
     * project-wide because an undeclared split write is still unfinished work.
     */
    fun buildStepArtifactsAreReadyForFinish(
        todo: AgentTodoEntity,
        ledger: AgentArtifactLedger
    ): Boolean = declaredBuildArtifactsAreCommitted(todo, ledger) &&
        ledger.entries.none { it.operation.equals("partial", ignoreCase = true) }

    /** Exact declared paths still lacking a successful non-delete receipt. */
    fun missingDeclaredBuildArtifacts(
        todo: AgentTodoEntity,
        ledger: AgentArtifactLedger
    ): List<String> {
        val declared = declaredArtifactPaths(todo)
        if (declared.isEmpty()) return emptyList()
        val committed = ledger.entries
            .filter { it.operation.trim().lowercase(Locale.ROOT) in artifactOperations }
            .mapNotNull { normalizePath(it.path) }
            .toSet()
        return declared.filterNot(committed::contains)
    }

    private fun classify(todo: StructuredPlanTodo): DirectPhase {
        val phaseTitle = todo.phaseTitle.trim()
        // The parser uses "Implementation" for headingless Markdown. Treat
        // that synthetic title as ambiguous so item prose can still create a
        // Build or Verify bucket; a real first `## Implementation` heading
        // receives phase suffix -2 and keeps heading precedence.
        val syntheticImplementationTitle =
            phaseTitle.equals("Implementation", ignoreCase = true) &&
                todo.phaseId.trim().matches(Regex("(?i)^phase-[0-9a-f]{8}-1$"))
        return when {
            // An explicit phase heading wins over item prose. Build plans often
            // mention that tests should be added, but those tests are still
            // implementation work until the dedicated verification phase.
            !syntheticImplementationTitle &&
                buildPhaseTitleTerms.containsMatchIn(phaseTitle) -> DirectPhase.BUILD
            !syntheticImplementationTitle &&
                verifyPhaseTitleTerms.containsMatchIn(phaseTitle) -> DirectPhase.VERIFY
            !syntheticImplementationTitle &&
                completePhaseTitleTerms.containsMatchIn(phaseTitle) -> DirectPhase.VERIFY
            // Ambiguous headings such as "Tasks" must not move a file creation
            // into Verify merely because its implementation prose also says
            // "check". Verify is read-only in Direct, so that projection would
            // make an approved plan impossible to complete.
            explicitMutationTerms.containsMatchIn(todo.text.trim()) &&
                extractDirectArtifactPaths(todo.text).isNotEmpty() -> DirectPhase.BUILD
            verifyTerms.containsMatchIn(todo.text) ->
                DirectPhase.VERIFY
            completeTerms.containsMatchIn(phaseTitle) || completeTerms.containsMatchIn(todo.text) ->
                DirectPhase.VERIFY
            else -> DirectPhase.BUILD
        }
    }

    private fun readAcceptanceCriteria(todo: AgentTodoEntity): List<String> =
        runCatching {
            val values = JSONArray(todo.acceptanceCriteriaJson)
            buildList {
                for (index in 0 until values.length()) {
                    values.optString(index).trim()
                        .takeIf { it.isNotBlank() }
                        ?.let(::add)
                }
            }
        }.getOrDefault(emptyList())

    private fun declaredArtifactPaths(todo: AgentTodoEntity): List<String> =
        extractDirectArtifactPaths(
            buildString {
                append(todo.text)
                readAcceptanceCriteria(todo).forEach {
                    append('\n').append(it)
                }
            }
        )

    private fun boundedTodoText(
        prefix: String,
        body: String,
        suffix: String,
        limit: Int
    ): String {
        val full = prefix + body + suffix
        if (full.length <= limit) return full
        val marker = " … additional approved actions remain in the full plan."
        val bodyBudget = (limit - prefix.length - suffix.length - marker.length)
            .coerceAtLeast(40)
        return (prefix + body.take(bodyBudget) + marker + suffix).take(limit)
    }

    private fun collectPathCandidates(
        raw: String,
        output: MutableSet<String>,
        allowBare: Boolean
    ) {
        raw.split(',', ';', '|', '·', '＋')
            .flatMap { piece -> piece.trim().split(Regex("\\s+and\\s+|\\s+or\\s+")) }
            .forEach { candidate ->
                val normalized = normalizePath(candidate)
                if (normalized != null && (allowBare || looksLikePath(normalized))) {
                    output += normalized
                }
                pathToken.findAll(candidate).forEach { match ->
                    normalizePath(match.value)
                        ?.takeIf(::looksLikePath)
                        ?.let(output::add)
                }
            }
    }

    private fun normalizePath(raw: String): String? {
        var path = raw.trim()
            .trim('`', '"', '\'', '(', ')', '[', ']', '{', '}', '<', '>')
            .trimEnd('.', ',', ';', ':')
        while (path.startsWith("./")) path = path.removePrefix("./")
        if (
            path.isBlank() || path.startsWith('/') || path.startsWith("http", true) ||
            path.contains('\\') || path.contains('\n') || path.contains('\r') ||
            path.any(Char::isWhitespace) ||
            path.length > 1_024
        ) return null
        val segments = path.split('/')
        if (segments.any { it.isBlank() || it == ".." }) return null
        return path
    }

    private fun looksLikePath(path: String): Boolean {
        if (path.contains('/')) {
            val first = path.substringBefore('/').lowercase(Locale.ROOT)
            val last = path.substringAfterLast('/')
            val extension = last.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return first in knownPathRoots || extension in knownFileExtensions
        }
        val extension = path.substringAfterLast('.', "")
        return extension.lowercase(Locale.ROOT) in knownFileExtensions
    }
}

/** Top-level seam for focused tests and control-plane callers. */
internal fun projectDirectApprovedPlan(
    plan: StructuredApprovedPlan,
    maxPhaseTodos: Int = AgentDirectPlanProjection.MAX_PHASE_TODOS
): DirectApprovedPlanProjection = AgentDirectPlanProjection.project(plan, maxPhaseTodos)
