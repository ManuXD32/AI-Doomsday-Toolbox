package com.example.llamadroid.chat.cards.omni

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Lenient parser for the `blocks` of an omni card. */
object OmniParser {
    private val ID_RE = Regex("[A-Za-z_][A-Za-z0-9_]{0,31}")
    private val RESERVED = setOf("pi", "e")

    const val MAX_ISSUES = 8
    private const val MAX_NOTE = 1000

    data class Parsed(val blocks: List<OmniBlock>, val issues: List<OmniIssue>)

    /** Blocks of the card object, or empty when none are valid. Ids are unique and valid. */
    fun parseBlocks(card: JsonObject): List<OmniBlock> = parse(card).blocks

    /** Blocks plus one issue per dropped block (unknown kind / missing required field). */
    fun parse(card: JsonObject): Parsed {
        val raw = (card.first("blocks", "components", "widgets", "items", "elements") as? JsonArray)
            ?: return Parsed(emptyList(), emptyList())
        val used = HashSet<String>(RESERVED)
        var auto = 0
        fun nextId(given: String?): String {
            var id = given?.trim()?.takeIf { ID_RE.matches(it) }
            if (id == null) {
                do { id = "v${++auto}" } while (id in used)
            }
            var out: String = id!!
            var k = 2
            while (!used.add(out)) { val suffix = "_${k++}"; out = id.take(32 - suffix.length) + suffix }
            return out
        }
        val out = ArrayList<OmniBlock>()
        val issues = ArrayList<OmniIssue>()
        fun issue(n: Int, reason: String) { if (issues.size < MAX_ISSUES) issues += OmniIssue("block $n", reason) }
        for ((index, el) in raw.withIndex()) {
            if (out.size >= OmniBlock.MAX_BLOCKS) break
            val n = index + 1
            val o = el as? JsonObject
            if (o == null) { issue(n, "not an object"); continue }
            val rawKind = o.first("kind", "type", "block").str()
            if (rawKind == null) { issue(n, "missing kind"); continue }
            val kind = canonicalKind(rawKind, o)
            if (kind == null) { issue(n, "unknown kind '${rawKind.take(24)}'"); continue }
            var why: String? = null
            val block = build(kind, o, ::nextId) { why = it }
            if (block != null) out += block else issue(n, why ?: "invalid $kind")
        }
        return Parsed(out, issues)
    }

    /** A card written as a plain list ({"type":"todo_list","items":["a","b"]}) becomes one checklist block. */
    fun checklistFallback(card: JsonObject): List<OmniBlock> {
        val items = (card.first("items", "tasks", "entries") as? JsonArray).orEmpty()
            .filterIsInstance<JsonPrimitive>().mapNotNull { it.str() }.take(30)
        return if (items.isEmpty()) emptyList() else listOf(OmniBlock.Checklist("v1", null, items))
    }

    private val NOTE_KINDS = setOf("text", "paragraph", "info", "note", "explanation", "description", "markdown", "heading", "label")

    private fun canonicalKind(raw: String, o: JsonObject): String? {
        val k = raw.lowercase().replace(Regex("[\\s_-]+"), "")
        if (k in NOTE_KINDS && o.first("text", "content") != null && o.first("id", "key") == null) return "note"
        return when (k) {
            "number" -> "number"
            "input", "text", "textfield", "textinput", "field" ->
                if (o["value"].num() != null || o["min"].num() != null || o["max"].num() != null || o["step"].num() != null) "number" else "text"
            "paragraph", "info", "note", "explanation", "description", "markdown", "heading" ->
                if (o.first("text", "content") != null) "note" else null
            "slider", "range" -> "slider"
            "toggle", "switch", "checkbox" -> "toggle"
            "select", "dropdown", "choice" -> "select"
            "result", "output", "formula", "calc" -> "result"
            "function", "formulainput", "fx" -> "function"
            "plot", "graph", "chart", "curve" -> "plot"
            "table", "values" -> "table"
            "matrix", "matrices", "matrixcalculator" -> "matrix"
            "keypad", "calculator" -> "keypad"
            "checklist", "todo", "tasks", "list" -> "checklist"
            "counter", "tally", "score" -> "counter"
            "timer", "countdown" -> "timer"
            "stopwatch" -> "stopwatch"
            "dice", "die" -> "dice"
            "coin" -> "coin"
            "pick", "choose" -> "pick"
            "random" -> if (listOf("items", "options", "choices").any { o[it] is JsonArray }) "pick" else "randomnumber"
            "quiz", "test", "trivia" -> "quiz"
            "flashcards", "flashcard", "cards" -> "flashcards"
            "steps", "stepper", "guide", "tutorial", "instructions" -> "steps"
            "progress", "gauge", "meter", "bar", "goal" -> "progress"
            "bars", "barchart", "budget" -> "bars"
            "date", "datepicker", "day" -> "date"
            "log", "ledger", "expenses", "entries", "tracker" -> "log"
            "intervals", "workout", "pomodoro", "hiit", "rounds" -> "intervals"
            "scoreboard", "scores", "players" -> "scoreboard"
            "send", "button" -> "send"
            else -> null
        }
    }

    private fun build(kind: String, o: JsonObject, id: (String?) -> String, fail: (String) -> Unit): OmniBlock? {
        val idRaw = o.first("id", "key", "name").str()
        val label = o.first("label", "title", "name").str()
        val unit = o.first("unit", "suffix").str()?.take(12)
        return when (kind) {
            "number" -> {
                val i = id(idRaw)
                OmniBlock.Number(i, label ?: i, o["value"].num(), o["min"].num(), o["max"].num(),
                    o["step"].num()?.takeIf { it > 0 } ?: 1.0, unit)
            }
            "slider" -> {
                val i = id(idRaw)
                val min = o["min"].num() ?: 0.0
                var max = o["max"].num() ?: 100.0
                if (max <= min) max = min + 100.0
                OmniBlock.Slider(i, label ?: i, min, max, o["step"].num()?.takeIf { it > 0 }, o["value"].num(), unit)
            }
            "toggle" -> {
                val i = id(idRaw)
                OmniBlock.Toggle(i, label ?: i, o["value"].flag())
            }
            "select" -> {
                val opts = (o.first("options", "choices", "items") as? JsonArray).orEmpty().take(24).mapNotNull { e ->
                    if (e is JsonObject) {
                        val v = e["value"].str()
                        val l = e.first("label", "text", "title", "name").str() ?: v ?: return@mapNotNull null
                        OmniBlock.Option(l, v ?: l)
                    } else e.str()?.let { OmniBlock.Option(it, it) }
                }
                if (opts.isEmpty()) { fail("missing options"); return null }
                val i = id(idRaw)
                OmniBlock.Select(i, label ?: i, opts, selectedIndex(o["value"], opts))
            }
            "result" -> {
                val expr = o.first("expr", "expression", "formula", "value").str() ?: run { fail("missing expr"); return null }
                OmniBlock.Result(label ?: expr.take(40), expr.take(OmniExpr.MAX_LENGTH + 1),
                    unit, (o["decimals"].num()?.toInt() ?: 2).coerceIn(0, 10),
                    o["format"].str()?.lowercase()?.takeIf { it == "date" || it == "duration" })
            }
            "keypad" -> OmniBlock.Keypad(label)
            "checklist" -> {
                val items = omniStrings(o.first("items", "tasks", "options", "entries"), 30)
                if (items.isEmpty()) { fail("missing items"); return null }
                OmniBlock.Checklist(id(idRaw), label, items)
            }
            "counter" -> {
                val i = id(idRaw)
                OmniBlock.Counter(i, label ?: i, o["value"].num() ?: 0.0, o["step"].num()?.takeIf { it > 0 } ?: 1.0)
            }
            "timer" -> {
                val s = (o.first("seconds", "duration", "value").num() ?: run { fail("missing seconds"); return null }).toInt()
                OmniBlock.Timer(label, s.coerceIn(1, 86400))
            }
            "stopwatch" -> OmniBlock.Stopwatch(label)
            "dice" -> OmniBlock.Dice(
                id(idRaw), label ?: "",
                (o.first("count", "dice").num()?.toInt() ?: 1).coerceIn(1, 10),
                (o["sides"].num()?.toInt() ?: 6).coerceIn(2, 100),
            )
            "coin" -> OmniBlock.Coin(label)
            "pick" -> {
                val items = omniStrings(o.first("items", "options", "choices"), 30)
                if (items.isEmpty()) { fail("missing items"); return null }
                OmniBlock.Pick(label, items)
            }
            "send" -> {
                val text = o.first("text", "message", "value").str() ?: run { fail("missing text"); return null }
                OmniBlock.Send(label ?: text.take(40), text)
            }
            "text" -> {
                val i = id(idRaw)
                OmniBlock.Text(i, label ?: i, o.first("placeholder", "hint").str(), o["value"].str())
            }
            "note" -> OmniBlock.Note(o.first("text", "content").str(MAX_NOTE) ?: run { fail("missing text"); return null })
            "function" -> {
                val i = id(idRaw)
                OmniBlock.Function(i, label ?: "f(x)", o.first("value", "expr", "formula", "expression", "default").str() ?: "x")
            }
            "plot" -> {
                val exprs = exprList(o).take(4)
                if (exprs.isEmpty()) { fail("missing expr"); return null }
                val from = o.first("from", "xMin", "x_min", "min").num() ?: -10.0
                var to = o.first("to", "xMax", "x_max", "max").num() ?: (from + 20.0)
                if (to <= from) to = from + 20.0
                OmniBlock.Plot(label, exprs, from, to, o.first("yMin", "y_min").num(), o.first("yMax", "y_max").num())
            }
            "table" -> {
                val exprs = exprList(o).take(3)
                if (exprs.isEmpty()) { fail("missing expr"); return null }
                val from = o.first("from", "xMin", "x_min", "min").num() ?: 0.0
                var to = o.first("to", "xMax", "x_max", "max").num() ?: (from + 10.0)
                if (to <= from) to = from + 10.0
                var step = o["step"].num()?.takeIf { it > 0 } ?: 1.0
                if ((to - from) / step + 1 > OmniBlock.MAX_TABLE_ROWS) step = (to - from) / (OmniBlock.MAX_TABLE_ROWS - 1)
                OmniBlock.Table(label, exprs, from, to, step)
            }
            "matrix" -> OmniBlock.Matrix(
                label,
                (o["rows"].num()?.toInt() ?: 2).coerceIn(1, 4),
                (o.first("cols", "columns").num()?.toInt() ?: 2).coerceIn(1, 4),
            )
            else -> OmniParserExtras.build(kind, o, id, fail)
        }
    }

    /** Selected index from a value that may be an index, or an option value/label. */
    private fun selectedIndex(v: JsonElement?, opts: List<OmniBlock.Option>): Int? {
        val txt = (v as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim() ?: return null
        opts.indexOfFirst { it.value == txt || it.label == txt }.takeIf { it >= 0 }?.let { return it }
        return txt.toDoubleOrNull()?.toInt()?.coerceIn(0, opts.size - 1)
    }

    /** Formulas of a plot/table: a string, or an array of strings/objects under exprs/functions/expr. */
    private fun exprList(o: JsonObject): List<String> {
        val el = o.first("exprs", "functions", "expr", "expression", "formula", "fn", "y", "formulas") ?: return emptyList()
        if (el is JsonArray) {
            return el.mapNotNull { e ->
                if (e is JsonObject) e.first("expr", "expression", "formula", "fn", "y", "value").str(OmniExpr.MAX_LENGTH) else e.str(OmniExpr.MAX_LENGTH)
            }
        }
        return listOfNotNull(el.str(OmniExpr.MAX_LENGTH))
    }
}
