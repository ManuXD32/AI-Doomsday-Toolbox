package com.example.llamadroid.service.delegation

/**
 * Pure keyword detector behind the per-turn DeepSeek reminder. English and Spanish; it is only a
 * nudge for small models, so a false positive costs one extra line of text for that turn.
 */
object NativeChatDelegationHints {
    /** Messages shorter than this are never hinted (greetings, "ok", "thanks"). */
    const val MIN_MESSAGE_CHARS = 12

    const val HINT_AUTO =
        "This request looks like coding/project work: delegate it unless it is a tiny snippet. If it corrects or changes work of an existing DeepSeek task in this chat, send it to that task with deepseek_task_message; otherwise start one with deepseek_task_start."
    const val HINT_PREFER =
        "Coding, file or project request: delegate it instead of writing it in the chat. If it corrects or changes work of an existing DeepSeek task in this chat, send it to that task with deepseek_task_message; otherwise start one with deepseek_task_start."

    private const val L = "(?<![\\p{L}\\p{N}_])"
    private const val R = "(?![\\p{L}\\p{N}_])"

    private fun words(vararg alternatives: String): Regex =
        Regex("$L(?:${alternatives.joinToString("|")})$R", RegexOption.IGNORE_CASE)

    private const val ACTIONS =
        "create|make|write|build|develop|generate|code|implement|fix|edit|modify|change|convert|run|test|debug|compile|" +
            "refactor|port|set up|program|crea|crear|haz|hacer|escribe|escribir|construye|construir|desarrolla|" +
            "desarrollar|genera|generar|programa|programar|implementa|implementar|arregla|arreglar|corrige|corregir|" +
            "edita|editar|modifica|modificar|cambia|cambiar|convierte|convertir|ejecuta|ejecutar|prueba|probar|" +
            "depura|depurar|compila|compilar|refactoriza|refactorizar|añade|agrega|agregar"

    private const val ARTIFACTS =
        "code|script|scripts|app|apps|application|website|web ?site|web ?page|project|program|game|bot|api|tool|" +
            "files?|repo|repository|function|class|module|plugin|apk|server|database|" +
            "código|codigo|aplicación|aplicacion|sitio|página|pagina|proyecto|programa|juego|archivos?|ficheros?|" +
            "función|funcion|clase|módulo|modulo|herramienta|servidor|base de datos|repositorio|web"

    private val actionThenArtifact = Regex(
        "$L(?:$ACTIONS)$R[^\\n]{0,60}?$L(?:$ARTIFACTS)$R",
        RegexOption.IGNORE_CASE
    )

    private val strong = listOf(
        words(
            "debug|debugging|refactor|compile|compiling|stack ?trace|traceback|unit tests?|pull request|" +
                "python|javascript|typescript|kotlin|html|css|bash|sql|node\\.?js|react|flask|django|c\\+\\+",
            "depurar|depura|depuración|compilar|compila|refactorizar|refactoriza|programar|programación|pruebas unitarias"
        ),
        Regex("$L(?:fix|solve|resolve)\\s+(?:the|this|that|a|my)?\\s*(?:bug|error|crash)", RegexOption.IGNORE_CASE),
        Regex("$L(?:arregla|corrige|soluciona|resuelve)\\w*\\s+(?:el|este|ese|un|mi)?\\s*(?:bug|error|fallo)", RegexOption.IGNORE_CASE),
        Regex("```"),
        Regex(
            "[\\p{L}\\p{N}_-]+\\.(?:py|js|ts|tsx|jsx|kt|java|html|css|json|sh|cpp|rs|go|md|csv|xml|ya?ml|sql|php|rb|gradle|apk|zip)$R",
            RegexOption.IGNORE_CASE
        )
    )

    private val broad = words(ARTIFACTS)

    /** True when [text] looks like coding, file or project work for [mode]. */
    fun looksLikeCodingWork(text: String, mode: DelegationMode): Boolean {
        if (mode == DelegationMode.ONLY_WHEN_ASKED) return false
        val trimmed = text.trim()
        if (trimmed.length < MIN_MESSAGE_CHARS) return false
        if (actionThenArtifact.containsMatchIn(trimmed)) return true
        if (strong.any { it.containsMatchIn(trimmed) }) return true
        return mode == DelegationMode.PREFER && broad.containsMatchIn(trimmed)
    }

    /** The one-line reminder for [text], or null when none applies. */
    fun hintFor(text: String, mode: DelegationMode): String? =
        if (!looksLikeCodingWork(text, mode)) null
        else if (mode == DelegationMode.PREFER) HINT_PREFER else HINT_AUTO
}
