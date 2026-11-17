package com.example.llamadroid.chat.cards.omni

import com.example.llamadroid.chat.cards.ChatCard
import com.example.llamadroid.chat.cards.ChatCardParser
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniExtrasTest {
    private val today = LocalDate.of(2026, 11, 17).toEpochDay()
    private fun blocks(b: String) =
        (ChatCardParser.parse("""{"type":"omni","blocks":[$b]}""") as? ChatCard.Omni)?.blocks.orEmpty()
    private fun ok(expr: String, env: OmniEnv) = (OmniExpr.evaluate(expr, env) as OmniExpr.Result.Ok).value

    @Test fun dateFunctions() {
        val env = OmniEnv(numbers = mapOf("b" to LocalDate.of(1990, 5, 20).toEpochDay().toDouble()), today = today)
        assertEquals(today.toDouble(), ok("today()", env), 0.0)
        assertEquals(36.0, ok("years(b, today())", env), 0.0)
        assertEquals(35.0, ok("years(b, b + 365*36)", env), 0.0)
        assertEquals(LocalDate.of(2026, 11, 17).dayOfWeek.value.toDouble(), ok("weekday(today())", env), 0.0)
        assertEquals("domain", (OmniExpr.evaluate("weekday(1e12)", env) as OmniExpr.Result.Error).reason)
    }

    @Test fun parsesQuizAndScores() {
        val q = blocks(
            """{"kind":"trivia","id":"q","questions":[
            {"q":"2+2","options":["3","4","5"],"answer":1},
            {"q":"Capital","options":["Paris","Rome"],"answer":"rome"},
            {"q":"bad","options":["only"],"answer":0}]}""",
        ).single() as OmniBlock.Quiz
        assertEquals(2, q.questions.size)
        assertEquals(1, q.questions[1].answer)
        assertEquals(2, q.score(OmniBlock.Quiz.decodeAnswers("1,1")))
        assertEquals(1, q.score(OmniBlock.Quiz.decodeAnswers("1,0,-1")))
        assertEquals("0,2,-1", OmniBlock.Quiz.encodeAnswers(listOf(0, 2, -1)))
        assertEquals(emptyList<Int>(), OmniBlock.Quiz.decodeAnswers(null))
    }

    @Test fun logRoundTripAndSum() {
        val e = listOf("Coffee" to 2.5, "Lunch" to 9.0)
        val back = OmniBlock.Log.decodeEntries(OmniBlock.Log.encodeEntries(e))
        assertEquals(e, back)
        assertEquals(11.5, OmniBlock.Log.sum(back), 0.0)
        assertEquals(emptyList<Pair<String, Double>>(), OmniBlock.Log.decodeEntries("garbage"))
        assertEquals(OmniBlock.Log.MAX_ENTRIES, OmniBlock.Log.decodeEntries(
            OmniBlock.Log.encodeEntries((1..150).map { "e$it" to 1.0 })).size)
    }

    @Test fun simpleBlocksAndAliases() {
        val b = blocks(
            """{"kind":"flashcard","cards":[{"term":"a","definition":"b"},{"q":"c","a":"d"}]},
            {"kind":"tutorial","steps":["one","two"]},
            {"kind":"gauge","label":"G","expr":"40","max":80,"unit":"kg"},
            {"kind":"budget","items":[{"label":"Rent","value":500},{"name":"Food","expr":"x1"}]},
            {"kind":"datepicker","id":"d","value":"2026-01-02"},
            {"kind":"ledger","id":"l","unit":"€"},
            {"kind":"hiit","steps":[{"label":"Work","seconds":30},{"label":"Rest","seconds":9999999}],"rounds":3},
            {"kind":"random","id":"r","min":1,"max":6},
            {"kind":"random","items":["a","b"]},
            {"kind":"scores","id":"s","players":["A","B","C"]}""",
        )
        assertEquals(listOf("a" to "b", "c" to "d"), (b[0] as OmniBlock.Flashcards).cards)
        assertEquals(listOf("one", "two"), (b[1] as OmniBlock.Steps).items)
        val p = b[2] as OmniBlock.Progress
        assertEquals("80", p.max); assertEquals("gauge", p.style)
        assertEquals(2, (b[3] as OmniBlock.Bars).items.size)
        val d = b[4] as OmniBlock.Date
        assertEquals(LocalDate.of(2026, 1, 2).toEpochDay().toDouble(), d.initialAt(today), 0.0)
        assertEquals(today.toDouble(), OmniBlock.Date("d2", "d", null).initialAt(today), 0.0)
        assertTrue(b[5] is OmniBlock.Log)
        val iv = b[6] as OmniBlock.Intervals
        assertEquals(86400, iv.steps[1].second); assertEquals(3, iv.rounds)
        assertTrue((b[7] as OmniBlock.RandomNumber).integers)
        assertTrue(b[8] is OmniBlock.Pick)
        assertEquals(listOf("s_1", "s_2", "s_3"), (b[9] as OmniBlock.Scoreboard).keys())
    }

    @Test fun resultFormatAndInvalidDates() {
        val r = blocks("""{"kind":"result","label":"R","expr":"today()","format":"date"},{"kind":"date","id":"d","value":"nope"}""")
        assertEquals("date", (r[0] as OmniBlock.Result).format)
        assertNull((r[1] as OmniBlock.Date).value)
    }

    @Test fun envsBuild() {
        val bl = listOf(
            OmniBlock.Text("t", "T", null, null),
            OmniBlock.Function("f", "f", "x^2"),
            OmniBlock.Quiz("q", "Q", listOf(OmniBlock.QuizQ("a", listOf("x", "y"), 1))),
            OmniBlock.Log("l", "L", null),
            OmniBlock.Scoreboard("s", null, listOf("A", "B")),
            OmniBlock.Select("op", "Op", listOf(OmniBlock.Option("+", "add"), OmniBlock.Option("-", "sub")), 0),
        )
        val n = OmniEnvs.initialNumbers(bl, today)
        assertEquals(0.0, n["s_2"]!!, 0.0)
        assertTrue(n["t"]!!.isNaN())
        assertEquals(mapOf("t" to "", "f" to "x^2"), OmniEnvs.initialTexts(bl))
        val env = OmniEnvs.build(bl, n + ("op" to 1.0), mapOf("t" to "4,5", "f" to "x+1", "q" to "1", "l" to """[["a",3]]"""), x = 2.0, today = today)
        assertEquals(4.5, env.numbers["t"]!!, 0.0)
        assertEquals(listOf("4,5"), env.texts["t"])
        assertEquals(listOf("sub", "-"), env.texts["op"])
        assertEquals("x+1", env.functions["f"])
        assertEquals(1.0, env.numbers["q"]!!, 0.0)
        assertEquals(3.0, env.numbers["l"]!!, 0.0)
        assertEquals(3.0, ok("f", env), 0.0)
        assertEquals(today, env.today)
        // blank function formula falls back to the block default
        assertEquals("x^2", OmniEnvs.build(bl, n, mapOf("f" to " ")).functions["f"])
    }

    @Test fun validatorChecksNewBlocks() {
        val bl = listOf(
            OmniBlock.Text("t", "T", null, null),
            OmniBlock.RandomNumber("r", "R", 3.0, 9.0),
            OmniBlock.Progress("P", "r + t", "bogus_max", null),
            OmniBlock.Bars("B", listOf("ok" to "r*2", "bad" to "nope + 1")),
            OmniBlock.Result("D", "years(today(), today())", null),
        )
        val issues = OmniValidator.issues(bl, today)
        assertEquals(listOf("unknown:bogus_max", "unknown:nope"), issues.map { it.reason })
    }
}
