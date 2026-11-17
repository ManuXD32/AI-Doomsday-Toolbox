package com.example.llamadroid.chat.cards.omni

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Builders for the learning / tracking blocks (quiz, flashcards, steps, progress, bars, date, log, intervals, random, scoreboard). */
internal object OmniParserExtras {
    fun build(kind: String, o: JsonObject, id: (String?) -> String, fail: (String) -> Unit): OmniBlock? {
        val idRaw = o.first("id", "key", "name").str()
        val label = o.first("label", "title", "name").str()
        val unit = o.first("unit", "suffix").str()?.take(12)
        return when (kind) {
            "quiz" -> {
                val qs = (o.first("questions", "items", "quiz", "cards") as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonObject)?.let(::question) }.take(OmniBlock.Quiz.MAX_QUESTIONS)
                if (qs.isEmpty()) { fail("missing questions"); return null }
                val i = id(idRaw)
                OmniBlock.Quiz(i, label ?: "", qs)
            }
            "flashcards" -> {
                val cards = (o.first("cards", "items", "flashcards") as? JsonArray).orEmpty().mapNotNull { e ->
                    val c = e as? JsonObject ?: return@mapNotNull null
                    val f = c.first("front", "q", "question", "term", "prompt").str()
                    val b = c.first("back", "a", "answer", "definition").str()
                    if (f != null && b != null) f to b else null
                }.take(30)
                if (cards.isEmpty()) { fail("missing cards"); return null }
                OmniBlock.Flashcards(label, cards)
            }
            "steps" -> {
                val items = omniStrings(o.first("steps", "items", "instructions"), 30)
                if (items.isEmpty()) { fail("missing steps"); return null }
                OmniBlock.Steps(label, items)
            }
            "progress" -> {
                val expr = o.first("expr", "expression", "formula", "value", "current").str(OmniExpr.MAX_LENGTH)
                    ?: run { fail("missing expr"); return null }
                val raw = o.first("kind", "type", "block").str()?.lowercase()
                val style = (o["style"].str()?.lowercase() ?: raw)?.takeIf { it == "gauge" } ?: "bar"
                OmniBlock.Progress(label ?: expr.take(40), expr,
                    o.first("max", "target", "goal", "total").str(OmniExpr.MAX_LENGTH) ?: "100", unit, style)
            }
            "bars" -> {
                val items = (o.first("items", "bars", "data") as? JsonArray).orEmpty().mapNotNull { e ->
                    val b = e as? JsonObject ?: return@mapNotNull null
                    val expr = b.first("expr", "expression", "value", "formula").str(OmniExpr.MAX_LENGTH) ?: return@mapNotNull null
                    (b.first("label", "name", "title").str() ?: expr.take(20)) to expr
                }.take(8)
                if (items.isEmpty()) { fail("missing items"); return null }
                OmniBlock.Bars(label, items)
            }
            "date" -> {
                val i = id(idRaw)
                OmniBlock.Date(i, label ?: i, o["value"].str()?.takeIf { OmniBlock.Date.parse(it) != null })
            }
            "log" -> {
                val i = id(idRaw)
                OmniBlock.Log(i, label ?: i, unit)
            }
            "intervals" -> {
                val steps = (o.first("steps", "items", "phases", "intervals") as? JsonArray).orEmpty().mapNotNull { e ->
                    val s = e as? JsonObject ?: return@mapNotNull null
                    val secs = s.first("seconds", "duration", "time", "value").num() ?: return@mapNotNull null
                    (s.first("label", "name", "title").str() ?: "") to secs.toInt().coerceIn(1, 86400)
                }.take(20)
                if (steps.isEmpty()) { fail("missing steps"); return null }
                OmniBlock.Intervals(label, steps, (o.first("rounds", "repeat", "sets").num()?.toInt() ?: 1).coerceIn(1, 50))
            }
            "randomnumber" -> {
                val i = id(idRaw)
                var min = o["min"].num() ?: 1.0
                var max = o["max"].num() ?: 100.0
                if (max < min) { val t = min; min = max; max = t }
                OmniBlock.RandomNumber(i, label ?: i, min, max)
            }
            "scoreboard" -> {
                val players = omniStrings(o.first("players", "names", "items", "teams"), 8)
                if (players.size < 2) { fail("missing players"); return null }
                OmniBlock.Scoreboard(id(idRaw), label, players, o["step"].num()?.takeIf { it > 0 } ?: 1.0)
            }
            else -> { fail("unknown kind '$kind'"); null }
        }
    }

    private fun question(c: JsonObject): OmniBlock.QuizQ? {
        val q = c.first("q", "question", "text", "prompt").str() ?: return null
        val options = omniStrings(c.first("options", "choices", "answers"), 6)
        if (options.size < 2) return null
        val ans = c.first("answer", "correct", "correctIndex", "correct_index", "correctAnswer", "correct_answer")
        val index = answerIndex(ans, options) ?: return null
        return OmniBlock.QuizQ(q, options, index)
    }

    /** Correct option index from an index (number) or the option text. */
    private fun answerIndex(v: JsonElement?, options: List<String>): Int? {
        val p = v as? JsonPrimitive ?: return null
        val txt = p.content.trim()
        if (!p.isString) return txt.toDoubleOrNull()?.toInt()?.takeIf { it in options.indices }
        options.indexOfFirst { it.trim().equals(txt, ignoreCase = true) }.takeIf { it >= 0 }?.let { return it }
        return txt.toIntOrNull()?.takeIf { it in options.indices }
    }
}
