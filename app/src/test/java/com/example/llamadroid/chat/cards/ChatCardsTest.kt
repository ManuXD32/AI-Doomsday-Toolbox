package com.example.llamadroid.chat.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCardsTest {
    private fun parse(s: String) = ChatCardParser.parse(s)

    @Test fun choices() {
        val c = parse("""{"type":"choices","question":"Q?","options":["A","B","C"],"multi":true,"allow_other":true}""") as ChatCard.Choices
        assertEquals(listOf("A", "B", "C"), c.options)
        assertTrue(c.multi && c.allowOther && c.isInteractive)
    }

    @Test fun input() {
        val c = parse("""{"type":"input","question":"Name?","placeholder":"Your name","multiline":true,"submit":"Go"}""") as ChatCard.Input
        assertEquals("Name?", c.question)
        assertEquals("Your name", c.placeholder)
        assertEquals("Go", c.submit)
        assertTrue(c.multiline && c.isInteractive)
        val d = parse("""{"type":"input","question":"Q"}""") as ChatCard.Input
        assertTrue(!d.multiline && d.placeholder == null && d.submit == null)
        assertNull(parse("""{"type":"input","placeholder":"x"}"""))
    }

    @Test fun omniWithoutTypeAndPlainChecklists() {
        val typeless = parse("""{"title":"Dice","blocks":[{"kind":"dice","id":"d1","count":2},{"kind":"result","label":"Total","expr":"d1"}]}""") as ChatCard.Omni
        assertEquals("d1", (typeless.blocks[0] as com.example.llamadroid.chat.cards.omni.OmniBlock.Dice).id)
        val todo = parse("""{"type":"todo_list","title":"Beach","items":["Towel","Hat"]}""") as ChatCard.Omni
        assertEquals(listOf("Towel", "Hat"), (todo.blocks.single() as com.example.llamadroid.chat.cards.omni.OmniBlock.Checklist).items)
        assertNull(parse("""{"title":"x","items":["a"]}"""))
    }

    @Test fun singleBlockCardTypesBecomeOmni() {
        val quiz = parse("""{"type":"quiz","questions":[{"q":"Largest planet?","options":["Mars","Jupiter"],"answer":1}]}""") as ChatCard.Omni
        assertTrue(quiz.blocks.single() is com.example.llamadroid.chat.cards.omni.OmniBlock.Quiz)
        val cards = parse("""{"type":"flashcards","title":"Verbs","cards":[{"front":"ser","back":"to be"}]}""") as ChatCard.Omni
        assertEquals("Verbs", cards.title)
        assertTrue(cards.blocks.single() is com.example.llamadroid.chat.cards.omni.OmniBlock.Flashcards)
        assertTrue((parse("""{"type":"plot","exprs":["sin(x)"]}""") as ChatCard.Omni).blocks.single() is com.example.llamadroid.chat.cards.omni.OmniBlock.Plot)
        // A plain quiz question is still a choices card, and a data chart is still a chart.
        assertTrue(parse("""{"type":"quiz","question":"2+2?","options":["3","4"]}""") is ChatCard.Choices)
        assertTrue(parse("""{"type":"chart","kind":"bar","labels":["a"],"series":[{"name":"s","data":[1]}]}""") !is ChatCard.Omni)
    }

    @Test fun omniBlocksAndAliases() {
        val c = parse("""{"type":"omni","title":"Calc","blocks":[{"kind":"number","id":"a","label":"A","value":2},{"kind":"result","label":"R","expr":"a*2"}]}""") as ChatCard.Omni
        assertEquals("Calc", c.title)
        assertEquals(2, c.blocks.size)
        assertTrue(!c.isInteractive)
        for (t in listOf("app", "widget", "mini_app", "Mini-App", "omni")) {
            assertTrue(t, parse("""{"type":"$t","blocks":[{"kind":"coin"}]}""") is ChatCard.Omni)
        }
        // html-only (the old mini-app format) and empty block lists are not omni cards any more.
        assertNull(parse("""{"type":"omni","html":"<p>x</p>"}"""))
        assertNull(parse("""{"type":"omni","title":"x"}"""))
    }

    @Test fun omniStateRoundTrip() {
        val json = ChatCardState.encodeOmniValues(mapOf("a" to 2.5), mapOf("t" to listOf(true, false)), "sent")
        val s = ChatCardState.decodeOmni(json)!!
        assertEquals(2.5, s.values["a"]!!, 0.0)
        assertEquals(listOf(true, false), s.checks["t"])
        assertEquals("sent", s.sent)
    }

    @Test fun inputAliases() {
        for (t in listOf("ask", "text_input", "open_question", "Text-Input", "input")) {
            assertTrue(t, parse("""{"type":"$t","question":"Q"}""") is ChatCard.Input)
        }
    }

    @Test fun inputAnswerTextAndState() {
        val c = ChatCard.Input("Name?")
        assertEquals("Name?: Bob", ChatCardAnswerText.input(c, "  Bob "))
        assertEquals("", ChatCardAnswerText.input(c, "  "))
        assertEquals("a \"b\"\nc", ChatCardState.decodeInput(ChatCardState.encodeInput("a \"b\"\nc")))
        assertNull(ChatCardState.decodeInput("nope"))
    }

    @Test fun choicesNeedTwoOptionsAndCapEight() {
        assertNull(parse("""{"type":"choices","question":"Q","options":["A"]}"""))
        val ten = (1..10).joinToString(",") { "\"o$it\"" }
        assertEquals(8, (parse("""{"type":"choices","question":"Q","options":[$ten]}""") as ChatCard.Choices).options.size)
    }

    @Test fun form() {
        val f = parse(
            """{"type":"form","title":"T","fields":[{"id":"a","label":"A","kind":"text","required":true},
            {"id":"b","label":"B","kind":"select","options":["x","y"]},{"label":"C","kind":"select"},
            {"id":"d","label":"D","kind":"toggle"},{"id":"e","label":"E","kind":"number"},{"id":"f","label":"F","kind":"date"}],"submit":"Go"}""",
        ) as ChatCard.Form
        assertEquals("Go", f.submit)
        assertEquals(6, f.fields.size)
        assertTrue(f.fields[0].required)
        assertEquals(FormFieldKind.SELECT, f.fields[1].kind)
        assertEquals(FormFieldKind.TEXT, f.fields[2].kind) // select without options degrades
        assertEquals("c", f.fields[2].id)
        assertEquals(FormFieldKind.TOGGLE, f.fields[3].kind)
    }

    @Test fun followupsCapFour() {
        val f = parse("""{"type":"followups","items":["a","b","c","d","e"]}""") as ChatCard.Followups
        assertEquals(4, f.items.size)
        assertNull(parse("""{"type":"followups","items":[]}"""))
    }

    @Test fun map() {
        val m = parse(
            """{"type":"map","route":true,"places":[{"name":"A","lat":10.5,"lng":20},{"name":"B"},{"name":"C","lat":999,"lng":1}]}""",
        ) as ChatCard.MapCard
        assertTrue(m.route)
        assertEquals(10.5, m.places[0].lat!!, 0.0)
        assertNull(m.places[1].lat)
        assertNull(m.places[2].lat) // out of range coordinates dropped
    }

    @Test fun displayTypes() {
        assertTrue(parse("""{"type":"stats","items":[{"label":"L","value":3,"delta":"+1"}]}""") is ChatCard.Stats)
        assertTrue(parse("""{"type":"timeline","items":[{"time":"9","title":"T"}]}""") is ChatCard.Timeline)
        val c = parse("""{"type":"callout","tone":"danger","text":"X"}""") as ChatCard.Callout
        assertEquals(CalloutTone.DANGER, c.tone)
        val l = parse("""{"type":"links","items":[{"url":"https://a.b"},{"url":"javascript:x"}]}""") as ChatCard.Links
        assertEquals(1, l.items.size)
        val ch = parse("""{"type":"chart","kind":"line","labels":["a","b"],"series":[{"name":"s","data":[1,2.5]}],"unit":"kg"}""") as ChatCard.Chart
        assertEquals(ChartKind.LINE, ch.kind)
        assertEquals(listOf(1.0, 2.5), ch.series[0].data)
        assertTrue(!ch.isInteractive)
    }

    @Test fun aliasesAndWrappers() {
        assertTrue(parse("""{"type":"quick_replies","suggestions":["a"]}""") is ChatCard.Followups)
        assertTrue(parse("""{"card":{"type":"choice","prompt":"Q","choices":["a","b"]}}""") is ChatCard.Choices)
        assertTrue(parse("""{"cards":[{"type":"warning","message":"m"}]}""") is ChatCard.Callout)
        assertTrue(parse("```card\n{\"type\":\"followups\",\"items\":[\"a\"]}\n```") is ChatCard.Followups)
    }

    @Test fun repair() {
        // trailing commas
        assertTrue(parse("""{"type":"followups","items":["a","b",],}""") is ChatCard.Followups)
        // smart quotes
        assertTrue(parse("{\u201Ctype\u201D:\u201Cfollowups\u201D,\u201Citems\u201D:[\u201Ca\u201D]}") is ChatCard.Followups)
        // single quotes
        assertTrue(parse("{'type':'followups','items':['a']}") is ChatCard.Followups)
        // truncated
        val t = parse("""{"type":"choices","question":"Q","options":["A","B"""") as ChatCard.Choices
        assertEquals(listOf("A", "B"), t.options)
        assertTrue(parse("""{"type":"followups","items":["abc""") is ChatCard.Followups)
        assertTrue(parse("""{"type":"followups","items":["a"],"x":""") is ChatCard.Followups)
    }

    @Test fun garbage() {
        assertNull(parse(""))
        assertNull(parse("hello"))
        assertNull(parse("{}"))
        assertNull(parse("""{"type":"unknown"}"""))
        assertNull(parse("[1,2,3]"))
        assertNull(parse("{{{{"))
        assertNull(parse("x".repeat(ChatCardParser.MAX_INPUT_CHARS + 1)))
        assertNull(parse("{" + "[".repeat(20000)))
    }

    @Test fun answerText() {
        val c = ChatCard.Choices("Pick", listOf("A", "B"))
        assertEquals("Pick: A, B, Z", ChatCardAnswerText.choices(c, listOf("A", "B"), "Z"))
        assertEquals("Pick: A", ChatCardAnswerText.choices(c, listOf("A"), " "))
        val f = ChatCard.Form(null, listOf(ChatCard.FormField("a", "Name"), ChatCard.FormField("b", "Age")), null)
        assertEquals("Name: Bob\nAge: 3", ChatCardAnswerText.form(f, mapOf("a" to "Bob", "b" to "3")))
        assertEquals("Name: Bob", ChatCardAnswerText.form(f, mapOf("a" to "Bob")))
    }

    @Test fun stateRoundTrip() {
        val s = ChatCardState.decodeChoices(ChatCardState.encodeChoices(listOf("A", "q\"uote"), "other"))!!
        assertEquals(listOf("A", "q\"uote"), s.selected)
        assertEquals("other", s.other)
        assertNull(ChatCardState.decodeChoices(ChatCardState.encodeChoices(emptyList(), null))!!.other)
        val v = mapOf("a" to "x y", "b" to "true")
        assertEquals(v, ChatCardState.decodeForm(ChatCardState.encodeForm(v)))
        assertNull(ChatCardState.decodeForm("nope"))
    }

    @Test fun awarenessBudget() {
        // All types on, omni included (its block list and example are the largest piece).
        assertTrue(ChatCardsAwareness.TEXT.length <= 5000)
        assertEquals(ChatCardsAwareness.TEXT.length / 4, ChatCardsAwareness.approxTokens)
        assertNotNull(ChatCardsAwareness.TEXT)
    }

    @Test fun awarenessExamplesParseAndStateTheRules() {
        val blocks = Regex("```card\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL).findAll(ChatCardsAwareness.TEXT).map { it.groupValues[1] }.toList()
        assertEquals(4, blocks.size)
        assertTrue(parse(blocks[0]) is ChatCard.Choices)
        assertTrue(parse(blocks[1]) is ChatCard.Input)
        assertEquals("Score", (parse(blocks[2]) as ChatCard.Chart).title)
        assertEquals("Tip calculator", (parse(blocks[3]) as ChatCard.Omni).title)
        assertTrue(ChatCardsAwareness.TEXT.contains("Cards are not tools"))
        assertTrue(ChatCardsAwareness.TEXT.contains("Never use an image-generation tool for charts"))
    }

    @Test fun placesAndAliases() {
        val p = parse("""{"type":"restaurants","title":"Eat","items":[{"name":"A","subtitle":"Tapas","tags":["a","b","c","d","e"],"rating":4.5,"price":"$$","image":"A restaurant Madrid","url":"https://a.example"},
            {"name":"B","image":"https://evil/x.jpg","url":"javascript:x"},{"title":"C"},{"x":1}]}""") as ChatCard.Places
        assertEquals(3, p.items.size)
        assertEquals(4, p.items[0].tags.size)
        assertEquals(4.5, p.items[0].rating!!, 1e-9)
        assertEquals("A restaurant Madrid", p.items[0].image)
        assertNull(p.items[1].image)
        assertNull(p.items[1].url)
        assertNull(parse("""{"type":"hotels","items":[]}"""))
        assertTrue(parse("""{"type":"venues","items":["X"]}""") is ChatCard.Places)
    }

    @Test fun placesTypeNoLongerMeansMapButLocationsDoes() {
        assertTrue(parse("""{"type":"places","places":[{"name":"A"}]}""") is ChatCard.Places)
        assertTrue(parse("""{"type":"locations","places":[{"name":"A"}]}""") is ChatCard.MapCard)
    }

    @Test fun imagesNeverAcceptUrls() {
        val i = parse("""{"type":"gallery","items":[{"query":"Eiffel Tower","caption":"c"},{"query":"https://x/y.jpg"},"Louvre"]}""") as ChatCard.Images
        assertEquals(listOf("Eiffel Tower", "Louvre"), i.items.map { it.query })
        assertNull(parse("""{"type":"images","items":[{"query":"http://x"}]}"""))
    }

    @Test fun weatherClampsDays() {
        assertEquals(ChatCard.Weather("Madrid", 5), parse("""{"type":"weather","location":"Madrid"}"""))
        assertEquals(7, (parse("""{"type":"forecast","location":"X","days":30}""") as ChatCard.Weather).days)
        assertEquals(1, (parse("""{"type":"weather","location":"X","days":0}""") as ChatCard.Weather).days)
        assertNull(parse("""{"type":"weather"}"""))
    }

    @Test fun budget() {
        val b = parse("""{"type":"costs","currency":"EUR","people":2,"cap":500,"items":[{"label":"Hotel","icon":"🏨","amount":"120","min":100,"max":150},{"label":"Bad"},{"label":"Food","amount":40,"note":"n"}]}""") as ChatCard.Budget
        assertEquals("EUR", b.currency)
        assertEquals(2, b.people)
        assertEquals(500.0, b.cap!!, 1e-9)
        assertEquals(2, b.items.size)
        assertEquals("🏨", b.items[0].icon)
        assertEquals(120.0, b.items[0].amount, 1e-9)
        assertEquals(150.0, b.items[0].max!!, 1e-9)
        assertNull(parse("""{"type":"budget","items":[{"label":"x"}]}"""))
    }

    @Test fun iconsOnExistingTypes() {
        val s = parse("""{"type":"stats","items":[{"label":"L","value":"1","icon":"🔥"},{"label":"M","value":"2","icon":"too long icon"}]}""") as ChatCard.Stats
        assertEquals("🔥", s.items[0].icon)
        assertNull(s.items[1].icon)
        val m = parse("""{"type":"map","places":[{"name":"A","icon":"📍"}]}""") as ChatCard.MapCard
        assertEquals("📍", m.places[0].icon)
    }

    @Test fun aliasesQuizAndPlot() {
        assertTrue(parse("""{"type":"quiz","question":"Q","options":["A","B"]}""") is ChatCard.Choices)
        assertTrue(parse("""{"type":"question","question":"Q","answers":["A","B"]}""") is ChatCard.Choices)
        assertTrue(parse("""{"type":"multiple_choice","question":"Q","choices":["A","B"]}""") is ChatCard.Choices)
        assertTrue(parse("""{"type":"poll","question":"Q","options":["A","B"]}""") is ChatCard.Choices)
        val plot = parse("""{"type":"plot","labels":["a","b"],"series":[{"name":"s","data":[1,2]}]}""") as ChatCard.Chart
        assertEquals(ChartKind.BAR, plot.kind)
        assertTrue(parse("""{"type":"graph","labels":["a"],"series":[{"data":[1]}]}""") is ChatCard.Chart)
        assertEquals(ChartKind.LINE, (parse("""{"type":"line_chart","labels":["a"],"series":[{"data":[1]}]}""") as ChatCard.Chart).kind)
        assertEquals(ChartKind.BAR, (parse("""{"type":"bar_chart","labels":["a"],"series":[{"data":[1]}]}""") as ChatCard.Chart).kind)
    }

    @Test fun chartPieDonutAliases() {
        val pie = parse("""{"type":"pie","labels":["a","b"],"series":[{"data":[1,3]},{"data":[9,9]}]}""") as ChatCard.Chart
        assertEquals(ChartKind.PIE, pie.kind)
        assertEquals(1, pie.series.size)
        assertEquals(false, pie.donut)
        val donut = parse("""{"type":"donut","labels":["a","b"],"series":[{"data":[1,3]}]}""") as ChatCard.Chart
        assertEquals(ChartKind.PIE, donut.kind)
        assertEquals(true, donut.donut)
        val flag = parse("""{"type":"chart","kind":"pie","donut":true,"labels":["a"],"series":[{"data":[2]}]}""") as ChatCard.Chart
        assertEquals(true, flag.donut)
        assertNull(parse("""{"type":"pie","labels":["a","b"],"series":[{"data":[-1,3]}]}"""))
        assertNull(parse("""{"type":"pie","labels":["a","b"],"series":[{"data":[0,0]}]}"""))
    }

    @Test fun chartAreaAndHbarKinds() {
        assertEquals(ChartKind.AREA, (parse("""{"type":"area","labels":["a","b"],"series":[{"data":[1,2]}]}""") as ChatCard.Chart).kind)
        assertEquals(ChartKind.AREA, (parse("""{"type":"chart","kind":"area","labels":["a"],"series":[{"data":[1]}]}""") as ChatCard.Chart).kind)
        for (t in listOf("hbar", "horizontal_bar", "barh")) {
            assertEquals(t, ChartKind.HBAR, (parse("""{"type":"$t","labels":["a"],"series":[{"data":[1]}]}""") as ChatCard.Chart).kind)
        }
        assertEquals(ChartKind.HBAR, (parse("""{"type":"chart","kind":"horizontal_bar","labels":["a"],"series":[{"data":[1]}]}""") as ChatCard.Chart).kind)
    }

    @Test fun chartScatterPoints() {
        val c = parse("""{"type":"scatter","x_label":"h","y_label":"w","series":[{"name":"s","points":[[1,2],[3,4.5],["x",1]]}]}""") as ChatCard.Chart
        assertEquals(ChartKind.SCATTER, c.kind)
        assertEquals(listOf(1.0 to 2.0, 3.0 to 4.5), c.series.first().points)
        assertEquals("h", c.xLabel)
        assertEquals("w", c.yLabel)
        assertNull(parse("""{"type":"scatter","series":[{"points":[]}]}"""))
        assertNull(parse("""{"type":"scatter","labels":["a"],"series":[{"data":[1]}]}"""))
    }

    @Test fun chartTitle() {
        val c = parse("""{"type":"chart","title":"My performance","labels":["a"],"series":[{"data":[1]}]}""") as ChatCard.Chart
        assertEquals("My performance", c.title)
        assertNull((parse("""{"type":"chart","labels":["a"],"series":[{"data":[1]}]}""") as ChatCard.Chart).title)
    }
}
