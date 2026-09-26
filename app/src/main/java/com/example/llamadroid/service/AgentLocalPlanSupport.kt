package com.example.llamadroid.service

/**
 * Small, deterministic preflight for LOCAL_SANDBOX plan proposals.
 *
 * The local runner supports static web projects and finite Python console
 * checks. It has no Node, Express, Flask, Django, or Python web-server runtime.
 * This is deliberately a conservative declaration check, not a classifier.
 */
internal data class AgentLocalPlanCapabilityIssue(
    val backend: String,
    val excerpt: String,
    val code: String = AgentLocalPlanSupport.UNSUPPORTED_BACKEND_CODE
) {
    val message: String
        get() =
            "$code: LOCAL_SANDBOX cannot run the $backend server/backend. " +
                "Use static HTML/CSS/JS with runtime=web, ui=web, or finite Python checks " +
                "with runtime=python, ui=console."
}

internal data class AgentLocalPlanValidation(
    val accepted: Boolean,
    val issues: List<AgentLocalPlanCapabilityIssue> = emptyList()
) {
    val errorCode: String?
        get() = issues.firstOrNull()?.code

    val errorMessage: String?
        get() = issues.joinToString(" ") { it.message }.takeIf { it.isNotBlank() }

    fun requireAccepted() {
        require(accepted) { errorMessage ?: AgentLocalPlanSupport.UNSUPPORTED_BACKEND_CODE }
    }
}

internal object AgentLocalPlanSupport {
    const val UNSUPPORTED_BACKEND_CODE = "LOCAL_PLAN_UNSUPPORTED_BACKEND"

    private const val MAX_EXCERPT_CHARS = 180
    private const val ARCHITECTURE_WORDS =
        "backend|server(?:\\.js)?|api|application|app|service|framework|runtime|stack|" +
            "architecture|implementation"

    private data class BackendSpec(val name: String, val token: Regex)

    private val backends = listOf(
        BackendSpec("Node.js", Regex("\\bnode(?:\\s*\\.\\s*|\\s*)js?\\b|\\bnode\\b", RegexOption.IGNORE_CASE)),
        BackendSpec("Express", Regex("\\bexpress(?:\\s*\\.\\s*js)?\\b", RegexOption.IGNORE_CASE)),
        BackendSpec("Flask", Regex("\\bflask\\b", RegexOption.IGNORE_CASE)),
        BackendSpec("Django", Regex("\\bdjango\\b", RegexOption.IGNORE_CASE)),
        BackendSpec("Python", Regex("\\bpython(?:\\s*\\d+(?:\\.\\d+)*)?\\b", RegexOption.IGNORE_CASE))
    )

    private val architectureBefore = Regex(
        "(?i)(?:^|\\b)(?:$ARCHITECTURE_WORDS)\\s*(?:[-:()/,]\\s*)*" +
            "(?:(?:is|=|:|uses?|using|with|via|built\\s+(?:with|on)|" +
            "implemented\\s+(?:with|in)|written\\s+in|powered\\s+by)\\s*)?" +
            "(?:a|an|the|new)?\\s*$"
    )
    private val architectureAfter = Regex(
        "(?i)^\\s*(?:(?:[-:()/,]|\\b(?:and|or|with|using|as|for|a|an|the|based|" +
            "web|http|https|" +
            "powered|is|are|was|were|used|chosen|selected|configured|provided)\\b)\\s*){0,5}" +
            "(?:$ARCHITECTURE_WORDS)\\b"
    )
    private val behaviorAfter = Regex(
        "(?i)^\\s*(?:(?:will|can|does|should|must|may)\\s+)?" +
            "(?:serve|serves|run|runs|host|hosts|handle|handles|power|powers|" +
            "provide|provides|expose|exposes)\\b"
    )
    private val directActionBefore = Regex(
        "(?i)(?:^|\\b)(?:use|using|build|built|create|created|implement|implemented|" +
            "run|running|serve|serving|host|hosting|deploy|deployed|choose|select|adopt)\\s+" +
            "(?:(?:a|an|the|new)\\s+)?$"
    )
    private val positiveAction = Regex(
        "(?i)\\b(?:use|using|build|built|create|created|implement|implemented|run|running|" +
            "serve|serving|host|hosting|deploy|deployed|choose|select|adopt)\\b"
    )
    private val negation = Regex(
        "(?i)(?:\\b(?:no|without|avoid|exclude|omit|forbid|prohibit|unsupported|" +
            "unavailable|cannot|can't|won't|never|not)\\b|" +
            "\\b(?:do|does|did|must|should)\\s+not\\b|\\bdon't\\b)"
    )
    private val positiveReset = Regex(
        "(?i)(?:,|;|\\bbut\\b|\\bhowever\\b|\\binstead\\b|\\brather\\b)\\s*" +
            "(?:then\\s+)?(?:use|using|build|built|create|implement|run|serve|host|" +
            "deploy|choose|select|adopt)\\b"
    )
    private val negativeAfter = Regex(
        "(?i)^\\s*(?:(?:is|are|was|were)\\s+)?(?:not\\s+(?:supported|available|usable|" +
            "allowed|permitted|used|chosen|selected|required|needed)|unsupported|" +
            "unavailable|prohibited|forbidden|disallowed|disabled|impossible|" +
            "isn't|aren't|wasn't|weren't)\\b"
    )
    private val comparison = Regex(
        "(?i)\\b(?:instead\\s+of|rather\\s+than|compared\\s+(?:with|to)|" +
            "comparison\\s+(?:with|to)|versus|vs\\.?|unlike)\\b"
    )
    // Keep the dots in Node.js, Express.js, and server.js intact.
    private val clauseBreak = Regex("[\\n!?;]+|\\.(?!\\s*js\\b)")

    /** Validate only explicit unsupported architecture declarations. */
    fun validateLocalSandboxPlan(plan: String, summary: String? = null): AgentLocalPlanValidation {
        val source = buildString {
            summary?.trim()?.takeIf { it.isNotBlank() }?.let { append(it).append('\n') }
            append(plan.trim())
        }
        if (source.isBlank()) return AgentLocalPlanValidation(accepted = true)

        val issues = mutableListOf<AgentLocalPlanCapabilityIssue>()
        val reported = mutableSetOf<String>()
        val clauses = source.split(clauseBreak)
        backends.forEach { backend ->
            clauses.forEach { clause ->
                val text = clause.trim()
                if (text.isBlank() || backend.name in reported) return@forEach
                if (backend.token.findAll(text).any { match -> isPositiveDeclaration(text, match.range, backend.name) }) {
                    issues += AgentLocalPlanCapabilityIssue(
                        backend = backend.name,
                        excerpt = text.take(MAX_EXCERPT_CHARS)
                    )
                    reported += backend.name
                }
            }
        }
        return AgentLocalPlanValidation(accepted = issues.isEmpty(), issues = issues)
    }

    private fun isPositiveDeclaration(text: String, range: IntRange, backend: String): Boolean {
        val before = text.substring(0, range.first)
        val after = text.substring(range.last + 1)
        if (isNegated(before) || negativeAfter.containsMatchIn(after)) return false

        // Python itself is supported for finite console checks. Only reject it
        // when the text names a server/backend relation or server behavior.
        if (backend == "Python" && Regex("(?i)\\bruntime\\s*[=:]\\s*$").containsMatchIn(before)) {
            return false
        }

        // A name immediately after a comparison marker is the comparison target.
        val marker = comparison.findAll(before).lastOrNull()
        if (marker != null) {
            val afterMarker = before.substring(marker.range.last + 1)
            if (!positiveAction.containsMatchIn(afterMarker)) return false
        }

        val explicitAction = backend != "Python" && directActionBefore.containsMatchIn(before)
        return explicitAction ||
            architectureBefore.containsMatchIn(before) ||
            architectureAfter.containsMatchIn(after) ||
            behaviorAfter.containsMatchIn(after)
    }

    private fun isNegated(before: String): Boolean {
        val lastNegation = negation.findAll(before).lastOrNull() ?: return false
        val reset = positiveReset.findAll(before).lastOrNull()
        return reset == null || reset.range.first < lastNegation.range.first
    }
}
