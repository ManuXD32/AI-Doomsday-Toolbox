package com.example.llamadroid.tama.adventure

/** Hope/items/flags reported by the model in the STATE line. Null fields mean "not reported". */
data class SceneStateUpdate(
    val hope: Int? = null,
    /** True when the model wrote an explicit sign (+5 / -10): the value is a delta, not an absolute. */
    val hopeIsDelta: Boolean = false,
    val items: List<String>? = null,
    val flags: List<String>? = null
)

/** A scene split into prose, tappable choices and the hidden state update. */
data class ParsedScene(
    val text: String,
    val choices: List<String>,
    val state: SceneStateUpdate?,
    /** False when no SCENE/CHOICES/STATE label was found and the whole reply is shown as plain text. */
    val structured: Boolean
)

/**
 * Tolerant parser for the fixed scene format:
 *
 *     SCENE: ...
 *     CHOICES:
 *     1) ...
 *     2) ...
 *     STATE: hope=70, items=Moon Key; Lantern, flags=met_fox
 *
 * Spanish labels (ESCENA / OPCIONES / ESTADO), markdown decoration and bullet styles are accepted.
 * Missing parts fall back to plain text plus free input; the parser never throws.
 */
object AdventureSceneParser {
    private enum class Section { NONE, SCENE, CHOICES, STATE }

    private const val MAX_CHOICES = 4
    private const val MAX_CHOICE_CHARS = 160

    private fun label(names: String) = Regex(
        "^[\\s*#>_\\-•]*(?:$names)[\\s*_]*:[\\s*_]*(.*)$",
        RegexOption.IGNORE_CASE
    )

    private val sceneLabel = label("SCENE|ESCENA")
    private val choicesLabel = label("CHOICES|OPTIONS|OPCIONES|ELECCIONES")
    private val stateLabel = label("STATE|ESTADO")
    private val choiceLine = Regex("^\\s*(?:[-*•–]\\s+|\\(?(?:\\d{1,2}|[A-Da-d])[.)]\\s*)(.+)$")
    private val stateKey = Regex(
        "\\b(hope|esperanza|items|objetos|flags|banderas|marcas)\\s*[=:]",
        RegexOption.IGNORE_CASE
    )
    private val noneWords = setOf("none", "n/a", "na", "-", "—", "ninguno", "ninguna", "nada", "vacío", "vacio", "empty")

    fun parse(raw: String): ParsedScene {
        val text = raw.trim()
        if (text.isEmpty()) return ParsedScene("", emptyList(), null, false)

        var section = Section.NONE
        var sawLabel = false
        val preamble = mutableListOf<String>()
        val scene = mutableListOf<String>()
        val choiceLines = mutableListOf<String>()
        val stateLines = mutableListOf<String>()

        fun add(target: Section, line: String) {
            when (target) {
                Section.NONE -> preamble.add(line)
                Section.SCENE -> scene.add(line)
                Section.CHOICES -> if (line.isNotBlank()) choiceLines.add(line)
                Section.STATE -> if (line.isNotBlank()) stateLines.add(line)
            }
        }

        for (line in text.lines()) {
            val sceneMatch = sceneLabel.find(line)
            val choicesMatch = choicesLabel.find(line)
            val stateMatch = stateLabel.find(line)
            when {
                sceneMatch != null -> {
                    sawLabel = true; section = Section.SCENE
                    add(section, sceneMatch.groupValues[1])
                }
                choicesMatch != null -> {
                    sawLabel = true; section = Section.CHOICES
                    add(section, choicesMatch.groupValues[1])
                }
                stateMatch != null -> {
                    sawLabel = true; section = Section.STATE
                    add(section, stateMatch.groupValues[1])
                }
                else -> add(section, line)
            }
        }

        if (!sawLabel) return ParsedScene(cleanProse(text), emptyList(), null, false)

        val sceneText = cleanProse(
            if (scene.any { it.isNotBlank() }) scene.joinToString("\n") else preamble.joinToString("\n")
        )
        return ParsedScene(
            text = sceneText,
            choices = parseChoices(choiceLines),
            state = parseState(stateLines.joinToString(" ")),
            structured = true
        )
    }

    private fun cleanProse(value: String): String = value.trim().trim('*', '_').trim()

    private fun parseChoices(lines: List<String>): List<String> {
        val items = mutableListOf<String>()
        var sawBullet = false
        for (line in lines) {
            val match = choiceLine.find(line)
            if (match != null) {
                sawBullet = true
                items.add(match.groupValues[1].trim())
            } else if (sawBullet && items.isNotEmpty()) {
                items[items.lastIndex] = items.last() + " " + line.trim()
            } else {
                items.add(line.trim())
            }
        }
        return items
            .map { it.trim().trim('*', '_', '"').trim().take(MAX_CHOICE_CHARS) }
            .filter { it.length > 1 }
            .distinctBy { it.lowercase() }
            .take(MAX_CHOICES)
    }

    fun parseState(value: String): SceneStateUpdate? {
        val matches = stateKey.findAll(value).toList()
        if (matches.isEmpty()) return null
        var hope: Int? = null
        var hopeIsDelta = false
        var items: List<String>? = null
        var flags: List<String>? = null
        matches.forEachIndexed { index, match ->
            val end = matches.getOrNull(index + 1)?.range?.first ?: value.length
            val segment = value.substring(match.range.last + 1, end).trim().trimEnd(',', ' ')
            when (match.groupValues[1].lowercase()) {
                "hope", "esperanza" -> Regex("([+-]?)\\s*(\\d{1,3})").find(segment)?.let { m ->
                    val number = m.groupValues[2].toIntOrNull()
                    if (number != null) {
                        hopeIsDelta = m.groupValues[1].isNotEmpty()
                        hope = if (m.groupValues[1] == "-") -number else number
                    }
                }
                "items", "objetos" -> items = parseList(segment)
                else -> flags = parseList(segment)
            }
        }
        if (hope == null && items == null && flags == null) return null
        return SceneStateUpdate(hope, hopeIsDelta, items, flags)
    }

    private fun parseList(segment: String): List<String> {
        val cleaned = segment.trim().trim('[', ']', '*', '_').trim()
        if (cleaned.lowercase() in noneWords) return emptyList()
        return cleaned.split(';', ',', '|')
            .map { it.trim().trim('*', '_', '"', '\'', '[', ']').trim() }
            .filter { it.isNotEmpty() && it.lowercase() !in noneWords }
    }
}
