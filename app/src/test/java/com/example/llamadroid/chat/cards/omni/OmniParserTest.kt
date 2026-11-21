package com.example.llamadroid.chat.cards.omni

import com.example.llamadroid.chat.cards.ChatCard
import com.example.llamadroid.chat.cards.ChatCardParser
import com.example.llamadroid.chat.cards.ChatCardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniParserTest {
    private fun omni(s: String) = ChatCardParser.parse(s) as? ChatCard.Omni
    private fun blocks(b: String) = omni("""{"type":"omni","blocks":[$b]}""")?.blocks.orEmpty()

    @Test fun tipCalculator() {
        val c = omni(
            """{"type":"omni","title":"Tip calculator","blocks":[
            {"kind":"number","id":"bill","label":"Bill","value":50,"unit":"€"},
            {"kind":"slider","id":"tip","label":"Tip","min":0,"max":30,"value":15,"unit":"%"},
            {"kind":"number","id":"people","label":"People","value":2,"min":1,"step":1},
            {"kind":"result","label":"Each pays","expr":"bill*(1+tip/100)/people","decimals":2,"unit":"€"}]}""",
        )!!
        assertEquals("Tip calculator", c.title)
        assertEquals(4, c.blocks.size)
        assertEquals(50.0, (c.blocks[0] as OmniBlock.Number).value!!, 0.0)
        assertEquals(15.0, (c.blocks[1] as OmniBlock.Slider).initial, 0.0)
        assertEquals("bill*(1+tip/100)/people", (c.blocks[3] as OmniBlock.Result).expr)
        assertTrue(!c.isInteractive)
    }

    @Test fun eachKind() {
        val b = blocks(
            """{"kind":"toggle","id":"t","label":"T","value":true},
            {"kind":"select","id":"s","label":"S","options":[{"label":"A","value":"x"},"B"],"value":1},
            {"kind":"keypad"},{"kind":"checklist","title":"L","items":["a","b"]},
            {"kind":"counter","label":"C","value":3,"step":2},{"kind":"timer","seconds":90},
            {"kind":"stopwatch"},{"kind":"dice","count":2,"sides":20},{"kind":"coin"},
            {"kind":"pick","items":["a","b"]},{"kind":"send","label":"Go","text":"hi {t}"}""",
        )
        assertEquals(11, b.size)
        assertEquals(1.0, (b[0] as OmniBlock.Toggle).initial, 0.0)
        val sel = b[1] as OmniBlock.Select
        assertEquals(listOf("A", "B"), sel.options.map { it.label })
        assertEquals(1.0, sel.initial, 0.0)
        assertEquals(listOf("a", "b"), (b[3] as OmniBlock.Checklist).items)
        assertEquals(2.0, (b[4] as OmniBlock.Counter).step, 0.0)
        assertEquals(90, (b[5] as OmniBlock.Timer).seconds)
        assertEquals(20, (b[7] as OmniBlock.Dice).sides)
        assertEquals("hi {t}", (b[10] as OmniBlock.Send).text)
    }

    @Test fun aliases() {
        val c = omni(
            """{"type":"widget","components":[{"type":"input","id":"a"},{"block":"range","id":"b"},{"kind":"switch","id":"c"},
            {"kind":"dropdown","id":"d","options":["x"]},{"kind":"formula","expr":"a"},{"kind":"calculator"},{"kind":"todo","items":["x"]},
            {"kind":"tally","id":"e"},{"kind":"countdown","seconds":5},{"kind":"die"},{"kind":"random","items":["q"]},{"kind":"button","label":"b","text":"t"}]}""",
        )!!
        assertEquals(
            listOf(
                OmniBlock.Text::class, OmniBlock.Slider::class, OmniBlock.Toggle::class, OmniBlock.Select::class,
                OmniBlock.Result::class, OmniBlock.Keypad::class, OmniBlock.Checklist::class, OmniBlock.Counter::class,
                OmniBlock.Timer::class, OmniBlock.Dice::class, OmniBlock.Pick::class, OmniBlock.Send::class,
            ),
            c.blocks.map { it::class },
        )
        for (k in listOf("widgets", "items", "elements")) {
            assertEquals(k, 1, omni("""{"type":"omni","$k":[{"kind":"coin"}]}""")!!.blocks.size)
        }
    }

    @Test fun invalidInputs() {
        assertNull(omni("""{"type":"omni","html":"<p>x</p>"}"""))
        assertNull(omni("""{"type":"omni","title":"x","code":"alert(1)"}"""))
        assertNull(omni("""{"type":"omni","blocks":[{"kind":"nonsense"},"str",5,{"kind":"timer"},{"kind":"select","options":[]}]}"""))
        assertNull(omni("""{"type":"omni","blocks":"nope"}"""))
        assertEquals(1, blocks("""{"kind":"wat"},{"kind":"coin"}""").size)
    }

    @Test fun limitsAndClamps() {
        val many = (1..41).joinToString(",") { """{"kind":"coin"}""" }
        assertEquals(OmniBlock.MAX_BLOCKS, blocks(many).size)
        assertEquals(86400, (blocks("""{"kind":"timer","seconds":999999}""")[0] as OmniBlock.Timer).seconds)
        assertEquals(1, (blocks("""{"kind":"timer","seconds":0}""")[0] as OmniBlock.Timer).seconds)
        val d = blocks("""{"kind":"dice","count":99,"sides":1}""")[0] as OmniBlock.Dice
        assertEquals(10, d.count)
        assertEquals(2, d.sides)
    }

    @Test fun ids() {
        val b = blocks(
            """{"kind":"number"},{"kind":"number","id":"x"},{"kind":"slider","id":"x"},{"kind":"toggle","id":"bad id!"},
            {"kind":"number","id":"pi"},{"kind":"number","id":"v1"}""",
        )
        val ids = b.map { (it as OmniBlock.Variable).id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals("v1", ids[0])
        assertEquals("x", ids[1])
        assertEquals("x_2", ids[2])
        assertTrue(ids.all { Regex("[A-Za-z_][A-Za-z0-9_]{0,31}").matches(it) })
        assertTrue("pi" !in ids)
        assertNotNull(b)
    }

    @Test fun stateRoundTrip() {
        val json = ChatCardState.encodeOmniValues(
            mapOf("a" to 1.5, "b" to 2.0), mapOf("list" to listOf(true, false, true)), "sent text",
        )
        val s = ChatCardState.decodeOmni(json)!!
        assertEquals(mapOf("a" to 1.5, "b" to 2.0), s.values)
        assertEquals(listOf(true, false, true), s.checks["list"])
        assertEquals("sent text", s.sent)
        val empty = ChatCardState.decodeOmni(ChatCardState.encodeOmniValues(emptyMap(), emptyMap(), null))!!
        assertNull(empty.sent)
        assertNull(ChatCardState.decodeOmni("garbage"))
    }

    @Test fun stateRoundTripWithTextsAndLegacy() {
        val json = ChatCardState.encodeOmniValues(mapOf("a" to 1.0), emptyMap(), null, mapOf("name" to "hola", "f" to "x^2"))
        val s = ChatCardState.decodeOmni(json)!!
        assertEquals(mapOf("name" to "hola", "f" to "x^2"), s.texts)
        val legacy = ChatCardState.decodeOmni("""{"values":{"a":2},"checks":{},"sent":"s"}""")!!
        assertEquals(emptyMap<String, String>(), legacy.texts)
        assertEquals(2.0, legacy.values["a"]!!, 0.0)
    }

    @Test fun textInputBecomesText() {
        val t = blocks("""{"kind":"input","id":"func_name","label":"Nombre","placeholder":"Ej: factorial"}""")[0] as OmniBlock.Text
        assertEquals("func_name", t.id)
        assertEquals("Ej: factorial", t.placeholder)
        assertTrue(t.initial.isNaN())
        assertEquals(3.5, OmniBlock.Text.parse(" 3,5 "), 0.0)
        assertTrue(blocks("""{"kind":"input","id":"n","value":5}""")[0] is OmniBlock.Number)
        assertTrue(blocks("""{"kind":"text","id":"n","min":0}""")[0] is OmniBlock.Number)
        assertTrue(blocks("""{"kind":"text","id":"n"}""")[0] is OmniBlock.Text)
    }

    @Test fun noteFunctionPlotTableMatrix() {
        val b = blocks(
            """{"kind":"paragraph","text":"Hello"},{"kind":"text","content":"Hi"},{"kind":"fx","id":"f"},
            {"kind":"formula_input","id":"g","value":"x^2"},{"kind":"graph","expr":"sin(x)"},
            {"kind":"plot","functions":["x","x^2",{"expr":"x^3"},"1","5th"],"from":-2,"to":2,"yMin":-1},
            {"kind":"values","exprs":["x*2"],"from":0,"to":1000,"step":1},{"kind":"matrix_calculator","rows":9,"cols":0}""",
        )
        assertEquals("Hello", (b[0] as OmniBlock.Note).text)
        assertTrue(b[1] is OmniBlock.Note)
        assertEquals("x", (b[2] as OmniBlock.Function).value)
        assertEquals("x^2", (b[3] as OmniBlock.Function).value)
        assertEquals(listOf("sin(x)"), (b[4] as OmniBlock.Plot).exprs)
        val p = b[5] as OmniBlock.Plot
        assertEquals(listOf("x", "x^2", "x^3", "1"), p.exprs)
        assertEquals(-2.0, p.from, 0.0); assertEquals(-1.0, p.yMin!!, 0.0)
        val t = b[6] as OmniBlock.Table
        assertTrue((t.to - t.from) / t.step + 1 <= OmniBlock.MAX_TABLE_ROWS + 1e-9)
        val m = b[7] as OmniBlock.Matrix
        assertEquals(4, m.rows); assertEquals(1, m.cols)
    }

    @Test fun numericSelect() {
        val s = blocks("""{"kind":"select","id":"u","options":[{"label":"km","value":1000},{"label":"m","value":1}],"value":0}""")[0] as OmniBlock.Select
        assertTrue(s.numeric)
        assertEquals(1000.0, s.initial, 0.0)
        assertEquals(1.0, s.numberAt(1), 0.0)
        assertEquals(1, s.indexOfNumber(1.0))
        assertEquals(listOf("1000", "km"), s.textsAt(0))
        val t = blocks("""{"kind":"select","id":"op","options":[{"label":"+","value":"add"},{"label":"-","value":"sub"}],"value":1}""")[0] as OmniBlock.Select
        assertTrue(!t.numeric)
        assertEquals(1.0, t.initial, 0.0)
        assertEquals(listOf("sub", "-"), t.textsAt(1))
    }

    @Test fun issuesForDroppedBlocks() {
        val c = omni("""{"type":"omni","blocks":[{"kind":"form"},{"kind":"result"},{"kind":"coin"}]}""")!!
        assertEquals(1, c.blocks.size)
        assertEquals(listOf("block 1", "block 2"), c.issues.map { it.where })
        assertEquals("unknown kind 'form'", c.issues[0].reason)
        assertEquals("missing expr", c.issues[1].reason)
    }

    @Test fun failingChatCardsHaveNoIssues() {
        val c = omni(
            """{"type":"omni","blocks":[
            {"kind":"number","id":"a","value":6},{"kind":"number","id":"b","value":3},
            {"kind":"select","id":"op","options":[{"label":"+","value":"add"},{"label":"-","value":"sub"},{"label":"/","value":"div"}]},
            {"kind":"result","label":"Resultado","expr":"if (op == 'add') (a + b) else if (op == 'sub') (a - b) else (a / b)"}]}""",
        )!!
        assertEquals(emptyList<OmniIssue>(), c.issues)
        val bad = omni("""{"type":"omni","blocks":[{"kind":"result","label":"R","expr":"zzz + 1"}]}""")!!
        assertEquals(1, bad.issues.size)
        assertEquals("unknown:zzz", bad.issues[0].reason)
        assertEquals("result \"R\"", bad.issues[0].where)
    }
}
