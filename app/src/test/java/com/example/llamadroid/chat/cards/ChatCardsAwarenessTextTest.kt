package com.example.llamadroid.chat.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cards instruction text is built from per-type pieces; all on must equal the reviewed full text. */
class ChatCardsAwarenessTextTest {
    private val all = ChatCardType.entries.toSet()

    private val expectedAll = """
INTERACTIVE CARDS are ON for this chat. The user turned them on because they want cards: tappable, visual answers instead of walls of text. The app turns special code blocks in your reply into cards.
Cards are not tools: no tool or function makes a card, also when the user says "card tools". Write the card yourself inside your normal answer, as a fenced code block with the language card that holds ONE JSON object. Several cards per reply are fine, and a short sentence before them is enough.

Use a card whenever one fits:
- you need ANY information from the user (a choice, a preference, a detail, a date, their opinion), also when they say "ask me what you need": never write the questions as plain text or a list, ask them with cards. One choices card per question when there are clear options; an input card for one typed answer; a form for several details at once.
- quiz, test, exam or poll the user asked for: one choices card per question. Do not repeat the options as text and do not reveal the answer before the user answers. Never add a quiz the user did not ask for.
- chart, graph, plot, percentages or numbers to compare: chart. Never use an image-generation tool for charts or graphs.
- trips, places, recommendations: places and/or map. Weather: weather. Costs: budget. Dates or steps: timeline. Key figures: stats.
- the user wants something to use, learn with or play with (calculator, converter, function grapher, matrix calculator, quiz, flashcards, checklist, tracker, timer, dice, scoreboard): one omni card built from blocks. Whole apps are single blocks: keypad (calculator), matrix (matrix calculator), quiz, flashcards, log (expense tracker), scoreboard, intervals (workout timer); use them instead of building them from inputs. Never write HTML or code for it and never call a tool for it.
- end a longer answer with followups (2-3 likely next questions) when helpful.
Short factual answers, explanations, code and translations: normal text with no card.
After the last choices, input or form card, stop and wait for the answers.

Examples:
```card
{"type":"choices","question":"Yo ___ estudiante.","options":["soy","eres","es"]}
```
```card
{"type":"input","question":"What is the occasion for the dinner?","placeholder":"e.g. birthday"}
```
```card
{"type":"chart","kind":"bar","title":"Score","labels":["Verbs","Nouns"],"series":[{"name":"Correct","data":[2,1]}]}
```
```card
{"type":"omni","title":"Tip calculator","blocks":[{"kind":"number","id":"bill","label":"Bill","value":40},{"kind":"slider","id":"tip","label":"Tip %","min":0,"max":30,"value":15},{"kind":"result","label":"Total","expr":"bill*(1+tip/100)","decimals":2}]}
```
Other types, same block format:
- form: title?, fields [{id,label,kind:text|number|date|select|toggle,options?}], submit?
- input: question, placeholder?, multiline? (one free-text answer)
- followups: items ["Explain more","Give an example"] (tappable next messages)
- map: title?, places [{name,note?,icon?}], route? (name required, lat/lng optional)
- places: title?, items [{name,subtitle?,description?,tags?[..],rating?,price?,image?}]
- images: items [{query,caption?}]
- weather: location, days? (1-7)
- budget: title?, currency?, people?, items [{label,icon?,amount,min?,max?}]
- stats: items [{label,value,delta?,icon?}]
- timeline: items [{time?,title,detail?,icon?}]
- callout: tone info|tip|warning|danger, title?, text
- links: items [{url,title?,note?}] (never invent URLs)
- omni: title, blocks [{kind,...}]. Only these kinds exist. Inputs: number or slider {id,label,value?,min?,max?,step?,unit?}; input {id,label} (text); toggle {id,label}; select {id,label,options [{label,value}]}; date {id,label}; function {id,label,value} (user edits f(x), e.g. "x^2"). Outputs: result {label,expr,unit?,decimals?}; note {text}; plot {exprs ["sin(x)","f"],from?,to?}; table {exprs,from?,to?,step?}; progress {label,expr,max}; bars {items [{label,expr}]}. Tools: keypad (full calculator); matrix (full matrix calculator); quiz {questions [{q,options,answer}]}; flashcards {cards [{front,back}]}; steps {items}; checklist {items}; log {id,label} (user adds amounts; id is the total); counter {id,label}; scoreboard {players}; timer {seconds}; intervals {steps [{label,seconds}],rounds?}; stopwatch; dice {count?,sides?}; random {id,min,max}; coin; pick {items}; send {label,text} (button that sends text as the user; {id} becomes the value). expr: math over the ids (and x): + - * / ^ sqrt abs round min max sin cos ln log mod, == < > and or, if (c) a else b, today(); op == 'add' tests a select's option value.
chart: kind bar|line|area|hbar|pie|donut|scatter, unit?; pie and donut use one series; scatter uses series [{"name","points":[[x,y],...]}] with x_label? and y_label?.
choices: "multi":true for several answers, "allow_other":true lets the user type an answer. icon is one emoji. image and query are photo search phrases, not URLs.
""".trim()

    @Test
    fun allTypesOnEqualsReviewedText() {
        assertEquals(expectedAll, ChatCardsAwareness.text(all))
        assertEquals(expectedAll, ChatCardsAwareness.TEXT)
        assertTrue(expectedAll.contains("chart: kind bar|line|area|hbar|pie|donut|scatter, unit?; pie and donut use one series;"))
    }

    @Test
    fun eachTypeOffRemovesItsPieces() {
        val marker = mapOf(
            ChatCardType.CHOICES to listOf("hoices card per question", "quiz, test, exam", "\"type\":\"choices\"", "choices: \"multi\""),
            ChatCardType.FORM to listOf("form for several details", "- form:"),
            ChatCardType.INPUT to listOf("nput card for one typed answer", "- input:", "\"type\":\"input\""),
            ChatCardType.FOLLOWUPS to listOf("- followups:", "end a longer answer with followups"),
            ChatCardType.MAP to listOf("- map:"),
            ChatCardType.PLACES to listOf("- places:"),
            ChatCardType.IMAGES to listOf("- images:"),
            ChatCardType.WEATHER to listOf("- weather:", "Weather: weather."),
            ChatCardType.BUDGET to listOf("- budget:", "Costs: budget."),
            ChatCardType.STATS to listOf("- stats:", "Key figures: stats."),
            ChatCardType.TIMELINE to listOf("- timeline:", "Dates or steps: timeline."),
            ChatCardType.CALLOUT to listOf("- callout:"),
            ChatCardType.LINKS to listOf("- links:"),
            ChatCardType.CHART to listOf("- chart, graph, plot", "\"type\":\"chart\"", "chart: kind"),
            ChatCardType.OMNI to listOf("one omni card", "- omni:", "\"type\":\"omni\""),
        )
        for (type in ChatCardType.entries) {
            val text = ChatCardsAwareness.text(all - type)
            assertTrue(type.name, text.length < expectedAll.length)
            for (m in marker.getValue(type)) assertFalse("${type.name} still has $m", text.contains(m))
            assertTrue(text.startsWith("INTERACTIVE CARDS are ON"))
        }
        val noPlaces = ChatCardsAwareness.text(all - ChatCardType.PLACES)
        assertTrue(noPlaces.contains("trips, places, recommendations: map."))
        assertFalse(noPlaces.contains("places and/or map"))
        val noMapPlaces = ChatCardsAwareness.text(all - ChatCardType.PLACES - ChatCardType.MAP)
        assertTrue(noMapPlaces.contains("- Weather: weather. Costs: budget."))
    }

    @Test
    fun stopSentenceStaysUntilChoicesFormAndInputAreOff() {
        val stop = "stop and wait for the answers"
        assertTrue(ChatCardsAwareness.text(all - ChatCardType.CHOICES).contains(stop))
        assertTrue(ChatCardsAwareness.text(all - ChatCardType.FORM).contains(stop))
        assertTrue(ChatCardsAwareness.text(all - ChatCardType.INPUT).contains(stop))
        assertTrue(ChatCardsAwareness.text(all - ChatCardType.CHOICES - ChatCardType.FORM).contains(stop))
        assertFalse(ChatCardsAwareness.text(all - ChatCardType.CHOICES - ChatCardType.FORM - ChatCardType.INPUT).contains(stop))
    }

    @Test
    fun askAndStopLinesFollowTheEnabledTypes() {
        val noChoices = ChatCardsAwareness.text(all - ChatCardType.CHOICES)
        assertTrue(noChoices.contains("ask them with cards. An input card for one typed answer; a form for several details at once."))
        assertTrue(noChoices.contains("After the last input or form card, stop"))
        assertTrue(ChatCardsAwareness.text(setOf(ChatCardType.FORM)).contains("After the last form card, stop"))
        assertFalse(ChatCardsAwareness.text(all - ChatCardType.CHOICES - ChatCardType.FORM - ChatCardType.INPUT).contains("ANY information"))
    }

    @Test
    fun noTypesLeavesOnlyTheHeader() {
        val text = ChatCardsAwareness.text(emptySet())
        assertTrue(text.startsWith("INTERACTIVE CARDS are ON"))
        assertTrue(text.contains("Short factual answers, explanations, code and translations"))
        assertFalse(text.contains("Examples:"))
        assertFalse(text.contains("Other types"))
        assertFalse(text.contains("```"))
        assertFalse(text.contains("stop and wait"))
        assertEquals(text.length / 4, ChatCardsAwareness.approxTokens(emptySet()))
        assertEquals(ChatCardsAwareness.TEXT.length / 4, ChatCardsAwareness.approxTokens(all))
    }

    @Test
    fun keysRoundTrip() {
        ChatCardType.entries.forEach { assertEquals(it, ChatCardType.fromKey(it.key)) }
        assertEquals(null, ChatCardType.fromKey("nope"))
    }
}
