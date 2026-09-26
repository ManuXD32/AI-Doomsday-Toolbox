package com.example.llamadroid.service

import java.util.Locale

/**
 * Stable execution-profile identifiers stored with an Agent conversation.
 *
 * Existing values are accepted only as migration/import aliases and always
 * resolve to [DIRECT].
 */
enum class AgentHarnessProfile(val id: String) {
    /**
     * The only profile used by the redesigned runtime.  The two values below
     * remain readable for old persisted rows while the Room migration moves
     * those rows to DIRECT.
     */
    DIRECT("direct"),
    OPTIMIZED("optimized"),
    LEGACY("legacy");

    companion object {
        /** Old values are import/migration aliases; they never select an old runtime. */
        fun fromId(@Suppress("UNUSED_PARAMETER") value: String?): AgentHarnessProfile = DIRECT
    }
}

/** Runtime phase used to select the small-model output recommendation. */
enum class AgentHarnessStage {
    CONTROL,
    BUILD,
    SUMMARY
}

/** Direct capabilities available to the root Orchestrator in each phase. */
enum class AgentHarnessPhase {
    PLAN,
    BUILD,
    VERIFY
}

/** Bounded external-research allowance for the optimized harness. */
data class AgentHarnessResearchLimits(
    val maxSearchCalls: Int,
    val maxFetchCalls: Int,
    val autoSummarize: Boolean
) {
    /** Compatibility aliases for callers that describe the values as limits. */
    val maxSearches: Int get() = maxSearchCalls
    val maxFetches: Int get() = maxFetchCalls
    val automaticSummarization: Boolean get() = autoSummarize
}

/**
 * Immutable policy snapshot captured by the runtime at the start of a turn.
 *
 * Null tuning values mean that the profile must leave the existing role and
 * runtime settings alone. Optimized token values are recommendations applied
 * only when the corresponding role/global preference is absent. This is how
 * legacy conversations retain their current behavior and how an explicit
 * thinking preference survives profile selection.
 */
data class AgentHarnessPolicySpec(
    val profile: AgentHarnessProfile,
    val defaultContextTokens: Int?,
    /** Context ceiling enforced by the optimized low-end profile. */
    val maximumContextTokens: Int,
    val controlMaxOutputTokens: Int?,
    val buildMaxOutputTokens: Int?,
    val summaryMaxOutputTokens: Int?,
    val optimizedSystemPrompt: String?,
    val optionalSequentialSpecialists: List<String>,
    val researchLimits: AgentHarnessResearchLimits,
    val explicitThinkingEnabled: Boolean?
) {
    /** Direct is the successor of the former optimized low-token profile. */
    val isDirect: Boolean get() = profile == AgentHarnessProfile.DIRECT
    val isOptimized: Boolean get() = profile == AgentHarnessProfile.OPTIMIZED || isDirect
    val isLegacy: Boolean get() = profile == AgentHarnessProfile.LEGACY

    /** Name-aligned aliases for UI and settings callers. */
    val recommendedContextTokens: Int? get() = defaultContextTokens
    val recommendedMaximumContextTokens: Int get() = maximumContextTokens

    /** The caller can use this value without branching on the stage enum. */
    fun maxOutputTokens(stage: AgentHarnessStage): Int? = when (stage) {
        AgentHarnessStage.CONTROL -> controlMaxOutputTokens
        AgentHarnessStage.BUILD -> buildMaxOutputTokens
        AgentHarnessStage.SUMMARY -> summaryMaxOutputTokens
    }

    /** The optimized phase budget is the recommendation used when no value is saved. */
    fun recommendedOutputTokens(stage: AgentHarnessStage): Int? = maxOutputTokens(stage)

    /**
     * Resolve the user-selected context inside the small-device range when the
     * optimized profile is active. Legacy profiles retain their stored value.
     */
    fun resolveContextTokens(
        configuredContextTokens: Int,
        explicit: Boolean = true
    ): Int {
        if (!isOptimized) return configuredContextTokens.coerceAtLeast(1)
        val selected = if (explicit) configuredContextTokens else (defaultContextTokens ?: configuredContextTokens)
        return if (explicit) {
            selected.coerceIn(AgentHarnessPolicy.MIN_CONTEXT_TOKENS, AgentHarnessPolicy.MAX_EXPLICIT_TOKENS)
        } else {
            selected.coerceIn(AgentHarnessPolicy.MIN_CONTEXT_TOKENS, maximumContextTokens)
        }
    }

    /**
     * Resolve output without allowing a phase recommendation to erase a
     * user-selected value. Model/backend validation remains the caller's
     * responsibility.
     */
    fun resolveOutputTokens(
        stage: AgentHarnessStage,
        configuredOutputTokens: Int,
        explicit: Boolean = true
    ): Int {
        if (!isOptimized) return configuredOutputTokens.coerceAtLeast(1)
        val selected = if (explicit) configuredOutputTokens else (maxOutputTokens(stage) ?: configuredOutputTokens)
        return if (explicit) {
            selected.coerceIn(AgentHarnessPolicy.MIN_OUTPUT_TOKENS, AgentHarnessPolicy.MAX_EXPLICIT_TOKENS)
        } else {
            selected.coerceIn(AgentHarnessPolicy.MIN_OUTPUT_TOKENS, AgentHarnessPolicy.BUILD_MAX_OUTPUT_TOKENS)
        }
    }

    /** True only for roles explicitly retained as optional sequential workers. */
    fun allowsOptionalSequentialSpecialist(role: String): Boolean =
        optionalSequentialSpecialists.any { it.equals(role.trim(), ignoreCase = true) }

    /** Alias used by dispatch code that calls these workers simply specialists. */
    fun isOptionalSequentialSpecialist(role: String): Boolean =
        allowsOptionalSequentialSpecialist(role)

    /** A nullable override preserves the current role setting when omitted. */
    val thinkingOverride: Boolean? get() = explicitThinkingEnabled
}

/**
 * Pure policy for the small-model Agent harness.
 *
 * This object has no Android or persistence dependency. Model, backend,
 * endpoint, and role-specific settings remain outside the policy and are not
 * rewritten when a profile is selected.
 */
object AgentHarnessPolicy {
    const val DIRECT = "direct"
    const val OPTIMIZED = "optimized"
    const val LEGACY = "legacy"

    private val CODEBASE_DISCOVERY_TOOL_NAMES = setOf(
        "list_directory",
        "search_code"
    )
    private val EXPLICIT_NO_CODEBASE_SCOUT_DIRECTIVE = Regex(
        "there\\s+is\\s+no\\s+need\\s+to\\s+(?:scout|explore|inspect)\\s+" +
            "(?:the\\s+)?(?:codebase|repository|project)" +
            "|no\\s+need\\s+to\\s+(?:scout|explore|inspect)\\s+" +
            "(?:the\\s+)?(?:codebase|repository|project)" +
            "|(?:skip|avoid)\\s+(?:the\\s+)?(?:codebase|repository|project)\\s+" +
            "(?:scouting|discovery|inspection)" +
            "|do\\s+not\\s+(?:scout|explore|inspect)\\s+" +
            "(?:the\\s+)?(?:codebase|repository|project)" +
            "|no\\s+(?:codebase|repository)\\s+(?:scouting|discovery)" +
            "|(?:no\\s+es\\s+necesario|no\\s+hace\\s+falta)\\s+" +
            "(?:explorar|inspeccionar)\\s+(?:el\\s+)?(?:c[oó]digo|repositorio|proyecto)",
        RegexOption.IGNORE_CASE
    )
    private val EXPLICIT_CODEBASE_SCOUT_PERMISSION = Regex(
        "(?:^|[.!?;]\\s+|\\n\\s*)(?:now\\s+" +
            "(?:scout|explore|inspect)\\s+(?:the\\s+)?(?:existing\\s+)?" +
            "(?:codebase|repository|project)" +
            "|you\\s+may\\s+(?:scout|explore|inspect)\\s+(?:the\\s+)?" +
            "(?:existing\\s+)?(?:codebase|repository|project)" +
            "|(?:codebase|repository|project)\\s+(?:scouting|discovery|inspection)\\s+" +
            "is\\s+(?:allowed|permitted))",
        RegexOption.IGNORE_CASE
    )

    /** Kept for old optimized settings and tests; Direct defaults to 16K. */
    const val DEFAULT_CONTEXT_TOKENS = 8_192
    const val DIRECT_DEFAULT_CONTEXT_TOKENS = 16_384
    /** Maximum supported by the low-end optimized profile. */
    const val MAX_CONTEXT_TOKENS = 16_384
    const val CONTROL_MAX_OUTPUT_TOKENS = 2_048
    const val BUILD_MAX_OUTPUT_TOKENS = 4_096
    const val SUMMARY_MAX_OUTPUT_TOKENS = 512
    const val MIN_CONTEXT_TOKENS = 2_048
    const val MIN_OUTPUT_TOKENS = 256
    const val MAX_EXPLICIT_TOKENS = 1_048_576
    const val RESEARCH_MAX_SEARCH_CALLS = 2
    const val RESEARCH_MAX_FETCH_CALLS = 4

    /** The optimized research policy remains fixed; token budgets are user-tunable. */
    const val RESEARCH_AUTO_SUMMARIZE = false

    /** Stable Direct prompt/schema budgets expressed in tokenizer tokens. */
    const val DIRECT_STABLE_PREFIX_MAX_TOKENS = 2_500
    const val DIRECT_FIRST_REQUEST_MAX_TOKENS = 4_000
    const val DIRECT_CONTEXT_RESERVE_TOKENS = 512
    const val DIRECT_THINKING_ENABLED = false
    const val DIRECT_WRITE_FILE_MAX_BYTES = 5 * 1_024
    const val DIRECT_EXTEND_ANCHOR = "DIRECT-EXTEND"

    /**
     * Resolve the phase output from the request-scoped Direct settings snapshot.
     * A global override intentionally replaces every phase limit. Without it,
     * Plan and summaries stay compact while the editable Direct default owns
     * the Build/Verify ceiling (4K for a fresh installation).
     */
    fun resolveDirectPhaseOutputTokens(
        stage: AgentHarnessStage,
        configuredOutputTokens: Int,
        globalOverrideEnabled: Boolean
    ): Int {
        val configured = configuredOutputTokens.coerceIn(MIN_OUTPUT_TOKENS, MAX_EXPLICIT_TOKENS)
        if (globalOverrideEnabled) return configured
        return when (stage) {
            AgentHarnessStage.CONTROL -> CONTROL_MAX_OUTPUT_TOKENS
            AgentHarnessStage.BUILD -> configured
            AgentHarnessStage.SUMMARY -> SUMMARY_MAX_OUTPUT_TOKENS
        }
    }

    /**
     * A compact, phase-independent prompt.  Plan/Build/Verify state belongs
     * in [directCheckpointTail] and the authoritative capsule, never here.
     * Keeping this string immutable is what lets llama.cpp reuse its prefix
     * when a phase changes.
     */
    const val DIRECT_SYSTEM_PROMPT =
        "You are one direct project agent. Work in the current project and preserve its files, chat, decisions, approved plans, and artifact history. " +
            "Follow the checkpoint at the end of each request: Plan proposes one bounded Markdown plan and waits for the single plan approval; Build performs only the approved next action; Verify checks the result and finishes with evidence. " +
            "Use only advertised tools. Read an existing file before editing it, keep paths project-relative, make one durable tool call at a time, and never repeat a successful action. Keep each write_file or edit_file replacement to at most 60 source lines and below 5 KiB; if a file must be larger, end the bounded increment with exactly one DIRECT-EXTEND anchor and extend only that anchor through later exact-match edit_file calls. " +
            "Ask question only for a genuine unresolved blocker; choose implementation algorithms, libraries, stacks, and tests yourself. If the contract says greenfield or no scouting, treat project inspection as complete: do not call read, list, or search in Plan, and after approval call write_file for the first artifact before any inspection. " +
            "Research is not a prerequisite merely because the user permits it; use existing knowledge unless the capsule's exact next action says an external fact is required. Use tool_help to activate one optional capability by name only when the core tools cannot perform that exact next action. " +
            "Persist the result of every message, tool call, tool result, and transition before continuing. Do not delegate or invoke other agents, maintain brain files, or invent unavailable commands. " +
            "When the current approved-plan step is complete, call finish_task with its artifacts and evidence; the runtime advances to the next step and ends only after the final Verify pass. " +
            "After a failure, use the exact recovery instruction; after repeated failure or missing progress, pause and make the blocker visible."

    /**
     * The complete core contract is intentionally stable across phases.  The
     * backend-specific additions are supplied by AgentToolSchemaPolicy.
     */
    val DIRECT_CORE_TOOL_NAMES: Set<String> = linkedSetOf(
        "read_file",
        "list_directory",
        "search_code",
        "write_file",
        "edit_file",
        "question",
        "finish_task",
        "tool_help"
    )

    /** Returns a tiny phase tail; it is not part of the cacheable prefix. */
    fun directCheckpointTail(phase: AgentHarnessPhase): String = when (phase) {
        AgentHarnessPhase.PLAN ->
            "CHECKPOINT phase=PLAN; inspect only; return one actionable plan; wait for approval."
        AgentHarnessPhase.BUILD ->
            "CHECKPOINT phase=BUILD; use the approved plan; perform the exact next action; record its receipt."
        AgentHarnessPhase.VERIFY ->
            "CHECKPOINT phase=VERIFY; run focused checks and preview inspection; repair only a concrete defect; finish with evidence."
    }

    /** Stable system prompt accessor used by Direct request assembly. */
    fun directSystemPrompt(): String = DIRECT_SYSTEM_PROMPT

    /**
     * Direct cache identity deliberately omits phase, role, and turn branch.
     * The caller must invalidate this key only for endpoint/model/backend/core
     * schema/project-context changes or compaction.
     */
    fun directPromptCacheKey(
        conversationId: Long,
        backend: String,
        model: String,
        endpointGeneration: String,
        coreSchemaHash: String,
        projectContextHash: String
    ): String = buildString {
        append("direct-cache|")
        append("conversation=").append(conversationId).append('|')
        append("backend=").append(cachePart(backend)).append('|')
        append("model=").append(cachePart(model)).append('|')
        append("endpoint=").append(cachePart(endpointGeneration)).append('|')
        append("core=").append(cachePart(coreSchemaHash)).append('|')
        append("project=").append(cachePart(projectContextHash))
    }

    private fun cachePart(value: String): String {
        val normalized = value.trim()
        return "${normalized.length}:$normalized"
    }

    /**
     * Returns true for a durable greenfield declaration or an explicit user
     * instruction to skip codebase scouting. An ordered later correction that
     * explicitly permits scouting overrides that default. Callers must pass
     * the canonical initial goal and durable user-correction contents;
     * generated prompts and tool output are intentionally outside this policy.
     */
    fun shouldSuppressCodebaseDiscovery(
        greenfield: Boolean,
        initialGoal: String,
        corrections: List<String>
    ): Boolean {
        var suppress = greenfield
        when (explicitCodebaseScoutDirective(initialGoal)) {
            true -> suppress = true
            false -> suppress = false
            null -> Unit
        }
        corrections.forEach { correction ->
            when (explicitCodebaseScoutDirective(correction)) {
                true -> suppress = true
                false -> suppress = false
                null -> Unit
            }
        }
        return suppress
    }

    /** True for the broad Plan-mode codebase discovery tools only. */
    fun isCodebaseDiscoveryTool(toolName: String): Boolean =
        toolName.trim().lowercase(Locale.ROOT) in CODEBASE_DISCOVERY_TOOL_NAMES

    /**
     * Direct greenfield projects have no useful source state to inspect. Keep
     * the model on the bounded plan in Plan and block Build inspection until
     * the first durable mutation. Once a committed artifact exists, ordinary
     * read-before-edit behavior resumes.
     */
    fun shouldBlockDirectGreenfieldInspection(
        phase: AgentHarnessPhase,
        codebaseDiscoverySuppressed: Boolean,
        hasCommittedArtifact: Boolean,
        toolName: String
    ): Boolean {
        if (!codebaseDiscoverySuppressed) return false
        val inspectionTool = toolName.trim().lowercase(Locale.ROOT) in setOf(
            "read_file",
            "list_directory",
            "search_code"
        )
        if (!inspectionTool) return false
        return phase == AgentHarnessPhase.PLAN ||
            (phase == AgentHarnessPhase.BUILD && !hasCommittedArtifact)
    }

    /**
     * Keep the first greenfield Build mutation deterministic. Control calls
     * can still explain a genuine blocker or inspect tool help, but the first
     * project-affecting call must create an artifact with [write_file].
     */
    fun shouldBlockDirectGreenfieldFirstMutation(
        phase: AgentHarnessPhase,
        codebaseDiscoverySuppressed: Boolean,
        hasCommittedArtifact: Boolean,
        toolName: String
    ): Boolean {
        if (
            phase != AgentHarnessPhase.BUILD ||
            !codebaseDiscoverySuppressed ||
            hasCommittedArtifact
        ) return false
        return toolName.trim().lowercase(Locale.ROOT) !in setOf(
            "write_file",
            "question",
            "tool_help",
            "finish_task"
        )
    }

    private fun explicitCodebaseScoutDirective(text: String): Boolean? {
        if (text.isBlank()) return null
        val noScout = EXPLICIT_NO_CODEBASE_SCOUT_DIRECTIVE
            .findAll(text)
            .lastOrNull()
        val permitScout = EXPLICIT_CODEBASE_SCOUT_PERMISSION
            .findAll(text)
            .lastOrNull()
        return when {
            noScout == null && permitScout == null -> null
            permitScout == null -> true
            noScout == null -> false
            permitScout.range.first > noScout.range.first -> false
            else -> true
        }
    }

    /**
     * Ordered, compact instructions for a small model. Detailed schemas and
     * approval contracts remain generated by the existing runtime, while the
     * high-cost choices (research, delegation, and the plan boundary) are
     * stated here so they survive history packing.
     */
    const val OPTIMIZED_SYSTEM_PROMPT =
        "Act as one direct root agent. In Plan, use the provided project state; read project_state_read only if needed. For a declared greenfield project skip CODEBASE_SCOUT and inspect only relevant files. Research is optional and bounded: at most 2 search calls total (web_search or kiwix_search) and at most 4 fetch_url calls in this planning episode; use snippets/citations first, never repeat a sufficient search, and do not invoke an automatic summarizer. State exact formulas, runtime dependencies, bounded resource behavior, and concrete checks for requested features. As soon as the requirements and needed facts are adequate, return one clear Markdown plan of at most 500 words; the harness submits it through propose_plan and waits for approval. A native propose_plan call is also accepted. Call question only for a real unresolved blocker, never for preferences. In Build, after approval make the required mutations, create .adt/run.json for LOCAL_SANDBOX, run run_project/check_project_run, and verify the result (use observe_preview/interact_preview for a web preview). All advertised tools remain available within their phase; optional specialists CODEBASE_SCOUT, RESEARCHER, PLANNER, CODER, REVIEWER, EXECUTOR, SUMMARIZER, and VISUAL_TESTER are retained, optional, and sequential."

    /**
     * Stable phase-specific base prompts. Common authority, question, local
     * runtime, and tool-contract instructions are appended by the service.
     */
    fun optimizedSystemPromptForPhase(phase: AgentHarnessPhase): String = when (phase) {
        AgentHarnessPhase.PLAN ->
            "Direct root Plan. Use provided project state; greenfield skips scouting. Research only unresolved facts: at most 2 search calls total, 4 fetches; use snippets/citations and never auto-summarize. State exact formulas, runtime dependencies, bounded resource behavior, and concrete checks. When ready, return one clear Markdown plan under 500 words; the harness submits it through propose_plan, then wait for approval. Use question only for unresolved user decisions. Do not mutate or run. $OPTIMIZED_PLAN_REQUIRED_ACTION_CONTRACT"

        AgentHarnessPhase.BUILD ->
            "Direct root Build after explicit plan approval. Implement the approved next step. For greenfield work, create entry files first with write_file; for existing work, read only files relevant to that step before editing. Keep each increment runnable and run focused checks after changes. Preserve approved scope; report a concrete blocker when execution cannot continue."

        AgentHarnessPhase.VERIFY ->
            "Direct root Verify. Perform one bounded review of changed artifacts against the approved acceptance criteria and run only focused checks using actual runtime or preview evidence. Use tool_help only when a required tool contract is unclear. Reuse checks that still cover current files; repeat one after a related repair. Do not write, edit, patch, install dependencies, or start unrelated work. For a concrete defect, use report_progress with phase=build and a repair summary. Otherwise call finish_task with changed artifacts, validation evidence, and review findings after checks pass. Use question for an unresolved user decision. Preserve the plan and TODO."
    }

    /**
     * Stable LOCAL_SANDBOX guidance appended to optimized turns. Keep this
     * short and static so cached phase prefixes remain reusable.
     */
    const val OPTIMIZED_LOCAL_SANDBOX_GUIDANCE =
        "LOCAL_SANDBOX WEB FIRST: Use project-relative files. For a user interface, use version=1, runtime=web and ui=web with static HTML/CSS/JS and entrypoint=index.html in .adt/run.json. Prefer browser APIs: Web Workers for parallel work, IndexedDB for durable results, bounded export chunks for downloads, and worker control messages with checkpoints for pause/stop/resume. Node/Express/Flask/Django, server processes, package managers, and shell APIs are unavailable. Use embedded Python only for finite non-UI checks with runtime=python and ui=console; use the standard library and do not design a dynamic Python web backend. Create .adt/run.json after approval, then run run_project/check_project_run and inspect the web preview when available."

    /**
     * Existing specialists remain available, but optimized mode treats every
     * handoff as optional and serializes them one at a time. VISUAL_TESTER is
     * retained in the same list so its existing enablement/visual policy stays
     * authoritative.
     */
    val OPTIONAL_SEQUENTIAL_SPECIALISTS: List<String> = listOf(
        "CODEBASE_SCOUT",
        "RESEARCHER",
        "PLANNER",
        "CODER",
        "REVIEWER",
        "EXECUTOR",
        "SUMMARIZER",
        "VISUAL_TESTER"
    )

    val OPTIONAL_SPECIALISTS: List<String> get() = OPTIONAL_SEQUENTIAL_SPECIALISTS

    const val OPTIONAL_VISUAL_TESTER = "VISUAL_TESTER"

    /** Read-only planning and research tools for direct root work. */
    val PLAN_ROOT_TOOLS: Set<String> = setOf(
        "project_state",
        "project_state_read",
        "project_order_read",
        "read_file",
        "read_file_lines",
        "file_line_count",
        "list_directory",
        "search_code",
        "read_memory",
        "list_memory",
        "kb_search",
        "kb_read_chunk",
        "kb_list_sources",
        "web_search",
        "fetch_url",
        "kiwix_search",
        "run_tools_sequential",
        "question",
        "propose_plan",
        "finish_task",
        "call_agent",
        "agent_report_read",
        "plan_read",
        "read_skill_resource",
        "tool_help",
        "get_datetime",
        "sleep_until"
    )

    /** File mutation and command tools for direct root Build work. */
    val BUILD_ROOT_TOOLS: Set<String> = setOf(
        "project_state",
        "project_state_read",
        "project_order_read",
        "plan_read",
        "todo_read",
        "todo_write",
        "todo_reconcile",
        "todo_transition",
        "agent_report_read",
        "read_file",
        "read_file_lines",
        "file_line_count",
        "list_directory",
        "search_code",
        "read_memory",
        "write_memory",
        "list_memory",
        "write_file",
        "append_file",
        "edit_lines",
        "apply_patch",
        "create_folder",
        "run_command",
        "check_command",
        "wait_command",
        "command_list",
        "cancel_command",
        "send_command_input",
        "run_project",
        "check_project_run",
        "stop_project_run",
        "force_stop_project_run",
        "install_python_dependency",
        "view_image",
        "observe_preview",
        "interact_preview",
        "run_tools_sequential",
        "call_agent",
        "report_progress",
        "question",
        "reflection",
        "finish_task",
        "tool_help",
        "get_datetime",
        "sleep_until"
    )

    /** Verification keeps reads, checks, and visual inspection available. */
    val VERIFY_ROOT_TOOLS: Set<String> = setOf(
        "project_state",
        "project_state_read",
        "project_order_read",
        "plan_read",
        "todo_read",
        "agent_report_read",
        "read_file",
        "read_file_lines",
        "file_line_count",
        "list_directory",
        "search_code",
        "read_memory",
        "list_memory",
        "run_command",
        "check_command",
        "wait_command",
        "command_list",
        "run_project",
        "check_project_run",
        "stop_project_run",
        "force_stop_project_run",
        "view_image",
        "observe_preview",
        "interact_preview",
        "run_tools_sequential",
        "call_agent",
        "report_progress",
        "question",
        "reflection",
        "finish_task",
        "tool_help",
        "get_datetime",
        "sleep_until"
    )

    fun rootToolsForPhase(phase: AgentHarnessPhase): Set<String> = when (phase) {
        AgentHarnessPhase.PLAN -> PLAN_ROOT_TOOLS
        AgentHarnessPhase.BUILD -> BUILD_ROOT_TOOLS
        AgentHarnessPhase.VERIFY -> VERIFY_ROOT_TOOLS
    }

    fun directToolsForPhase(phase: AgentHarnessPhase): Set<String> =
        rootToolsForPhase(phase)

    fun rootToolsForPhase(phase: String): Set<String> =
        rootToolsForPhase(
            runCatching {
                AgentHarnessPhase.valueOf(phase.trim().uppercase(Locale.ROOT))
            }.getOrDefault(AgentHarnessPhase.BUILD)
        )

    val OPTIMIZED_RESEARCH_LIMITS = AgentHarnessResearchLimits(
        maxSearchCalls = RESEARCH_MAX_SEARCH_CALLS,
        maxFetchCalls = RESEARCH_MAX_FETCH_CALLS,
        autoSummarize = RESEARCH_AUTO_SUMMARIZE
    )

    /** Normalize persisted IDs while retaining legacy behavior on bad data. */
    fun profileFromId(value: String?): AgentHarnessProfile =
        AgentHarnessProfile.fromId(value)

    /**
     * Migration-facing normalization.  Any old profile is an alias for the
     * Direct runtime; no new conversation can opt back into a legacy lane.
     */
    fun directProfileFromId(@Suppress("UNUSED_PARAMETER") value: String?): AgentHarnessProfile =
        AgentHarnessProfile.DIRECT

    fun normalizeDirectProfileId(@Suppress("UNUSED_PARAMETER") value: String?): String = DIRECT

    fun isDirect(@Suppress("UNUSED_PARAMETER") value: String?): Boolean = true

    /** String form for Room/preferences and callback boundaries. */
    fun normalizeProfileId(@Suppress("UNUSED_PARAMETER") value: String?): String = DIRECT

    /**
     * Builds the identity for prompt-only caches for one root-turn branch.
     *
     * The existing branch string remains responsible for root-turn, role, and
     * custom-agent identity. Profile and phase are part of this separate key
     * because one root turn can cross Plan, Build, and Verify without starting
     * a new durable turn. Length-prefixed values keep branch delimiters from
     * creating accidental key collisions while preserving a readable key.
     */
    fun promptCacheKey(
        branch: String,
        normalizedProfile: String,
        phase: AgentHarnessPhase
    ): String {
        fun part(value: String): String {
            val normalized = value.trim()
            return "${normalized.length}:$normalized"
        }

        return "prompt-cache|branch=${part(branch)}|profile=${part(normalizeProfileId(normalizedProfile))}|phase=${phase.name}"
    }

    /** Avoids callers having to compare a persisted string themselves. */
    fun isOptimized(value: String?): Boolean = isDirect(value)

    /**
     * Resolve one immutable policy snapshot. Legacy deliberately returns null
     * for optimized-only tuning so the runtime can keep its current settings.
     */
    fun forProfile(
        @Suppress("UNUSED_PARAMETER") profile: AgentHarnessProfile,
        explicitThinkingEnabled: Boolean? = null
    ): AgentHarnessPolicySpec = AgentHarnessPolicySpec(
            profile = AgentHarnessProfile.DIRECT,
            defaultContextTokens = DIRECT_DEFAULT_CONTEXT_TOKENS,
            maximumContextTokens = MAX_CONTEXT_TOKENS,
            controlMaxOutputTokens = CONTROL_MAX_OUTPUT_TOKENS,
            buildMaxOutputTokens = BUILD_MAX_OUTPUT_TOKENS,
            summaryMaxOutputTokens = SUMMARY_MAX_OUTPUT_TOKENS,
            optimizedSystemPrompt = DIRECT_SYSTEM_PROMPT,
            optionalSequentialSpecialists = emptyList(),
            researchLimits = AgentHarnessResearchLimits(
                maxSearchCalls = RESEARCH_MAX_SEARCH_CALLS,
                maxFetchCalls = RESEARCH_MAX_FETCH_CALLS,
                autoSummarize = false
            ),
            explicitThinkingEnabled = explicitThinkingEnabled ?: DIRECT_THINKING_ENABLED
        )

    fun forProfile(
        profileId: String?,
        explicitThinkingEnabled: Boolean? = null
    ): AgentHarnessPolicySpec = forProfile(
        profile = profileFromId(profileId),
        explicitThinkingEnabled = explicitThinkingEnabled
    )

    /** Optimized-only context recommendation; null tells legacy callers to preserve theirs. */
    fun defaultContextTokens(profileId: String?): Int? =
        forProfile(profileId).defaultContextTokens

    /** Optimized-only output recommendation; null leaves legacy role tuning untouched. */
    fun maxOutputTokens(profileId: String?, stage: AgentHarnessStage): Int? =
        forProfile(profileId).maxOutputTokens(stage)

    /** Explicit aliases that make the recommendation semantics clear at call sites. */
    fun recommendedContextTokens(profileId: String?): Int? =
        forProfile(profileId).recommendedContextTokens

    fun recommendedOutputTokens(profileId: String?, stage: AgentHarnessStage): Int? =
        forProfile(profileId).recommendedOutputTokens(stage)

    fun resolveContextTokens(
        profileId: String?,
        configuredContextTokens: Int,
        explicit: Boolean = true
    ): Int = forProfile(profileId).resolveContextTokens(configuredContextTokens, explicit)

    fun resolveOutputTokens(
        profileId: String?,
        stage: AgentHarnessStage,
        configuredOutputTokens: Int,
        explicit: Boolean = true
    ): Int = forProfile(profileId).resolveOutputTokens(stage, configuredOutputTokens, explicit)

    /**
     * Estimate input tokens when the endpoint does not expose an exact
     * tokenizer count.  A 1.25 safety factor is applied to the conventional
     * four UTF-8 characters/token estimate so the caller pauses early rather
     * than silently dropping authoritative state.
     */
    fun directInputTokenCount(text: String, exactTokenCount: Int? = null): Int {
        exactTokenCount?.let { return it.coerceAtLeast(0) }
        val base = (text.codePointCount(0, text.length) + 3) / 4
        return kotlin.math.ceil(base * 1.25).toInt().coerceAtLeast(0)
    }

    /** Required invariant: input + reserved output + safety reserve <= context. */
    fun directFitsContext(
        inputTokens: Int,
        reservedOutputTokens: Int,
        contextTokens: Int = DIRECT_DEFAULT_CONTEXT_TOKENS
    ): Boolean = inputTokens.coerceAtLeast(0) +
        reservedOutputTokens.coerceAtLeast(0) +
        DIRECT_CONTEXT_RESERVE_TOKENS <= contextTokens.coerceAtLeast(1)

    /**
     * The highest input budget that may be sent without losing the control
     * capsule.  A negative value means the required state itself cannot fit
     * and the runtime must pause/compact instead of pruning it.
     */
    fun directInputBudget(
        contextTokens: Int = DIRECT_DEFAULT_CONTEXT_TOKENS,
        reservedOutputTokens: Int
    ): Int = contextTokens - reservedOutputTokens - DIRECT_CONTEXT_RESERVE_TOKENS
}
