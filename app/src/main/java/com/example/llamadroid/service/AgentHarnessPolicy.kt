package com.example.llamadroid.service

import java.util.Locale

/**
 * Stable execution-profile identifiers stored with an Agent conversation.
 *
 * Existing conversations are intentionally resolved to [LEGACY] when their
 * value is absent or unknown. New conversations are assigned [OPTIMIZED] by
 * the conversation owner.
 */
enum class AgentHarnessProfile(val id: String) {
    OPTIMIZED("optimized"),
    LEGACY("legacy");

    companion object {
        fun fromId(value: String?): AgentHarnessProfile = entries.firstOrNull {
            it.id.equals(value?.trim(), ignoreCase = true) ||
                it.name.equals(value?.trim(), ignoreCase = true)
        } ?: LEGACY
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
    /**
     * Conservative context ceiling recommended for small devices. This is a
     * recommendation shown to users, not a runtime cap for an explicit
     * setting.
     */
    val maximumContextTokens: Int,
    val controlMaxOutputTokens: Int?,
    val buildMaxOutputTokens: Int?,
    val summaryMaxOutputTokens: Int?,
    val optimizedSystemPrompt: String?,
    val optionalSequentialSpecialists: List<String>,
    val researchLimits: AgentHarnessResearchLimits,
    val explicitThinkingEnabled: Boolean?
) {
    val isOptimized: Boolean get() = profile == AgentHarnessProfile.OPTIMIZED
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

    /** The optimized phase budget is a recommendation when no value is saved. */
    fun recommendedOutputTokens(stage: AgentHarnessStage): Int? = maxOutputTokens(stage)

    /**
     * Resolve context without allowing an optimized recommendation to erase a
     * user-selected value. The minimum protects malformed/partially edited
     * fields; there is intentionally no optimized maximum here.
     */
    fun resolveContextTokens(
        configuredContextTokens: Int,
        explicit: Boolean = true
    ): Int {
        val normalized = configuredContextTokens.coerceAtLeast(AgentHarnessPolicy.MIN_CONTEXT_TOKENS)
        return if (isOptimized && !explicit) {
            (defaultContextTokens ?: normalized)
                .coerceAtLeast(AgentHarnessPolicy.MIN_CONTEXT_TOKENS)
        } else {
            normalized
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
        val normalized = configuredOutputTokens.coerceAtLeast(AgentHarnessPolicy.MIN_OUTPUT_TOKENS)
        return if (isOptimized && !explicit) {
            (maxOutputTokens(stage) ?: normalized)
                .coerceAtLeast(AgentHarnessPolicy.MIN_OUTPUT_TOKENS)
        } else {
            normalized
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

    const val DEFAULT_CONTEXT_TOKENS = 8_192
    /** Conservative recommendation; explicit user values may exceed it. */
    const val MAX_CONTEXT_TOKENS = 16_384
    const val CONTROL_MAX_OUTPUT_TOKENS = 2_048
    const val BUILD_MAX_OUTPUT_TOKENS = 4_096
    const val SUMMARY_MAX_OUTPUT_TOKENS = 512
    const val MIN_CONTEXT_TOKENS = 1_024
    const val MIN_OUTPUT_TOKENS = 1
    const val RESEARCH_MAX_SEARCH_CALLS = 2
    const val RESEARCH_MAX_FETCH_CALLS = 4

    /** The optimized research policy remains fixed; token budgets are user-tunable. */
    const val RESEARCH_AUTO_SUMMARIZE = false

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
        "Act as one direct root agent. In Plan, use the provided project state; read project_state_read only if needed. For a declared greenfield project skip CODEBASE_SCOUT and inspect only relevant files. Research is optional and bounded: at most 2 search calls total (web_search or kiwix_search) and at most 4 fetch_url calls in this planning episode; use snippets/citations first, never repeat a sufficient search, and do not invoke an automatic summarizer. State exact formulas, runtime dependencies, bounded resource behavior, and concrete checks for requested features. As soon as the requirements and needed facts are adequate, call propose_plan with a plan of at most 500 words and wait for approval. Call question only for a real unresolved blocker, never for preferences. In Build, after approval make the required mutations, create .adt/run.json for LOCAL_SANDBOX, run run_project/check_project_run, and verify the result (use observe_preview/interact_preview for a web preview). All advertised tools remain available within their phase; optional specialists CODEBASE_SCOUT, RESEARCHER, PLANNER, CODER, REVIEWER, EXECUTOR, SUMMARIZER, and VISUAL_TESTER are retained, optional, and sequential."

    /**
     * Stable phase-specific base prompts. Common authority, question, local
     * runtime, and tool-contract instructions are appended by the service.
     */
    fun optimizedSystemPromptForPhase(phase: AgentHarnessPhase): String = when (phase) {
        AgentHarnessPhase.PLAN ->
            "Direct root Plan. Use provided project state. Greenfield work skips codebase scouting. Research only unresolved facts: at most 2 search calls total, 4 fetches; use snippets/citations; never auto-summarize. State exact formulas, runtime dependencies, bounded resource behavior, concrete checks. When ready, emit native structured propose_plan with required plan and summary (at most 500 words, 4000 characters), then wait for approval. Example: propose_plan({plan: <body>, summary: <one line>}). Use question only for unresolved user decisions; prose cannot open controls. finish_task BLOCKED only for execution blockers. Do not mutate or run before approval. $OPTIMIZED_PLAN_REQUIRED_ACTION_CONTRACT"

        AgentHarnessPhase.BUILD ->
            "Direct root Build after explicit plan approval. Implement the approved next step. For greenfield work, create entry files first with write_file; for existing work, read only files relevant to that step before editing. Keep each increment runnable and run focused checks after changes. Preserve approved scope; report a concrete blocker when execution cannot continue."

        AgentHarnessPhase.VERIFY ->
            "Direct root Verify. Review the changed artifacts against the approved acceptance criteria and inspect actual runtime or preview evidence. Check relevant behavior, not just that a server started. Reuse checks that still cover the current files; repeat a check after a related repair. Do not write, edit, patch, install dependencies, or start unrelated work. For a concrete defect, use report_progress with phase=build and a repair summary. Otherwise call finish_task with changed artifacts, validation evidence, and review findings only after passing checks. Use question for an unresolved user decision. Preserve the plan and TODO."
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
        "get_datetime"
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
        "get_datetime"
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
        "get_datetime"
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

    /** String form for Room/preferences and callback boundaries. */
    fun normalizeProfileId(value: String?): String = profileFromId(value).id

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
    fun isOptimized(value: String?): Boolean =
        profileFromId(value) == AgentHarnessProfile.OPTIMIZED

    /**
     * Resolve one immutable policy snapshot. Legacy deliberately returns null
     * for optimized-only tuning so the runtime can keep its current settings.
     */
    fun forProfile(
        profile: AgentHarnessProfile,
        explicitThinkingEnabled: Boolean? = null
    ): AgentHarnessPolicySpec = when (profile) {
        AgentHarnessProfile.OPTIMIZED -> AgentHarnessPolicySpec(
            profile = profile,
            defaultContextTokens = DEFAULT_CONTEXT_TOKENS,
            maximumContextTokens = MAX_CONTEXT_TOKENS,
            controlMaxOutputTokens = CONTROL_MAX_OUTPUT_TOKENS,
            buildMaxOutputTokens = BUILD_MAX_OUTPUT_TOKENS,
            summaryMaxOutputTokens = SUMMARY_MAX_OUTPUT_TOKENS,
            optimizedSystemPrompt = OPTIMIZED_SYSTEM_PROMPT,
            optionalSequentialSpecialists = OPTIONAL_SEQUENTIAL_SPECIALISTS,
            researchLimits = OPTIMIZED_RESEARCH_LIMITS,
            explicitThinkingEnabled = explicitThinkingEnabled
        )

        AgentHarnessProfile.LEGACY -> AgentHarnessPolicySpec(
            profile = profile,
            defaultContextTokens = null,
            maximumContextTokens = MAX_CONTEXT_TOKENS,
            controlMaxOutputTokens = null,
            buildMaxOutputTokens = null,
            summaryMaxOutputTokens = null,
            optimizedSystemPrompt = null,
            optionalSequentialSpecialists = emptyList(),
            researchLimits = AgentHarnessResearchLimits(
                maxSearchCalls = Int.MAX_VALUE,
                maxFetchCalls = Int.MAX_VALUE,
                autoSummarize = true
            ),
            explicitThinkingEnabled = explicitThinkingEnabled
        )
    }

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
}
