package com.example.llamadroid.chat.cards

import com.example.llamadroid.chat.cards.ChatCardType.BUDGET
import com.example.llamadroid.chat.cards.ChatCardType.CALLOUT
import com.example.llamadroid.chat.cards.ChatCardType.CHART
import com.example.llamadroid.chat.cards.ChatCardType.CHOICES
import com.example.llamadroid.chat.cards.ChatCardType.FOLLOWUPS
import com.example.llamadroid.chat.cards.ChatCardType.FORM
import com.example.llamadroid.chat.cards.ChatCardType.IMAGES
import com.example.llamadroid.chat.cards.ChatCardType.INPUT
import com.example.llamadroid.chat.cards.ChatCardType.LINKS
import com.example.llamadroid.chat.cards.ChatCardType.MAP
import com.example.llamadroid.chat.cards.ChatCardType.OMNI
import com.example.llamadroid.chat.cards.ChatCardType.PLACES
import com.example.llamadroid.chat.cards.ChatCardType.STATS
import com.example.llamadroid.chat.cards.ChatCardType.TIMELINE
import com.example.llamadroid.chat.cards.ChatCardType.WEATHER

/**
 * Instruction text appended to the chat's stable prefix when interactive cards are enabled.
 * Built from per-type pieces so a type the user switched off disappears from the instructions
 * (see [ChatCardsDefaults.currentEnabledTypes]); [TEXT] is the all-types-on text.
 */
object ChatCardsAwareness {
    /** The instructions with every card type enabled. */
    val TEXT: String = text(ChatCardType.entries.toSet())

    /** The instructions for the types currently enabled in the app settings. */
    fun current(): String = text(ChatCardsDefaults.currentEnabledTypes())

    val approxTokens: Int get() = approxTokens(ChatCardsDefaults.currentEnabledTypes())

    fun approxTokens(enabled: Set<ChatCardType>): Int = text(enabled).length / 4

    fun text(enabled: Set<ChatCardType>): String {
        fun on(type: ChatCardType) = type in enabled
        val out = StringBuilder()
        out.append(
            "INTERACTIVE CARDS are ON for this chat. The user turned them on because they want cards: tappable, visual answers " +
                "instead of walls of text. The app turns special code blocks in your reply into cards.\n" +
                "Cards are not tools: no tool or function makes a card, also when the user says \"card tools\". Write the card yourself inside your normal answer, " +
                "as a fenced code block with the language card that holds ONE JSON object. Several cards per reply are fine, " +
                "and a short sentence before them is enough.\n\n" +
                "Use a card whenever one fits:\n"
        )
        val askWith = listOfNotNull(
            "one choices card per question when there are clear options".takeIf { on(CHOICES) },
            "an input card for one typed answer".takeIf { on(INPUT) },
            "a form for several details at once".takeIf { on(FORM) },
        )
        if (askWith.isNotEmpty()) {
            out.append(
                "- you need ANY information from the user (a choice, a preference, a detail, a date, their opinion), also when they say " +
                    "\"ask me what you need\": never write the questions as plain text or a list, ask them with cards. "
            ).append(askWith.joinToString("; ").replaceFirstChar { it.uppercaseChar() }).append(".\n")
        }
        if (on(CHOICES)) {
            out.append(
                "- quiz, test, exam or poll the user asked for: one choices card per question. Do not repeat the options as text and do not " +
                    "reveal the answer before the user answers. Never add a quiz the user did not ask for.\n"
            )
        }
        if (on(CHART)) {
            out.append(
                "- chart, graph, plot, percentages or numbers to compare: chart. Never use an image-generation tool for charts or graphs.\n"
            )
        }
        val places = when {
            on(PLACES) && on(MAP) -> "trips, places, recommendations: places and/or map."
            on(PLACES) -> "trips, places, recommendations: places."
            on(MAP) -> "trips, places, recommendations: map."
            else -> null
        }
        val misc = listOfNotNull(
            places,
            "Weather: weather.".takeIf { on(WEATHER) },
            "Costs: budget.".takeIf { on(BUDGET) },
            "Dates or steps: timeline.".takeIf { on(TIMELINE) },
            "Key figures: stats.".takeIf { on(STATS) },
        )
        if (misc.isNotEmpty()) out.append("- ").append(misc.joinToString(" ")).append('\n')
        if (on(OMNI)) {
            out.append(
                "- the user wants something to use, learn with or play with (calculator, converter, function grapher, matrix calculator, quiz, " +
                    "flashcards, checklist, tracker, timer, dice, scoreboard): one omni card built from blocks. " +
                    "Whole apps are single blocks: keypad (calculator), matrix (matrix calculator), quiz, flashcards, log (expense tracker), " +
                    "scoreboard, intervals (workout timer); use them instead of building them from inputs. " +
                    "Never write HTML or code for it and never call a tool for it.\n"
            )
        }
        if (on(FOLLOWUPS)) out.append("- end a longer answer with followups (2-3 likely next questions) when helpful.\n")
        out.append("Short factual answers, explanations, code and translations: normal text with no card.\n")
        val waitFor = listOfNotNull("choices".takeIf { on(CHOICES) }, "input".takeIf { on(INPUT) }, "form".takeIf { on(FORM) })
        if (waitFor.isNotEmpty()) {
            val names = if (waitFor.size == 1) waitFor[0] else waitFor.dropLast(1).joinToString(", ") + " or " + waitFor.last()
            out.append("After the last ").append(names).append(" card, stop and wait for the answers.\n")
        }

        val examples = ArrayList<String>()
        if (on(CHOICES)) examples += "```card\n{\"type\":\"choices\",\"question\":\"Yo ___ estudiante.\",\"options\":[\"soy\",\"eres\",\"es\"]}\n```"
        if (on(INPUT)) examples += "```card\n{\"type\":\"input\",\"question\":\"What is the occasion for the dinner?\",\"placeholder\":\"e.g. birthday\"}\n```"
        if (on(CHART)) examples += "```card\n{\"type\":\"chart\",\"kind\":\"bar\",\"title\":\"Score\",\"labels\":[\"Verbs\",\"Nouns\"],\"series\":[{\"name\":\"Correct\",\"data\":[2,1]}]}\n```"
        if (on(OMNI)) {
            examples += "```card\n{\"type\":\"omni\",\"title\":\"Tip calculator\",\"blocks\":[{\"kind\":\"number\",\"id\":\"bill\",\"label\":\"Bill\",\"value\":40}," +
                "{\"kind\":\"slider\",\"id\":\"tip\",\"label\":\"Tip %\",\"min\":0,\"max\":30,\"value\":15}," +
                "{\"kind\":\"result\",\"label\":\"Total\",\"expr\":\"bill*(1+tip/100)\",\"decimals\":2}]}\n```"
        }
        if (examples.isNotEmpty()) {
            out.append("\nExamples:\n")
            examples.forEach { out.append(it).append('\n') }
        }

        val others = ArrayList<String>()
        if (on(FORM)) others += "- form: title?, fields [{id,label,kind:text|number|date|select|toggle,options?}], submit?"
        if (on(INPUT)) others += "- input: question, placeholder?, multiline? (one free-text answer)"
        if (on(FOLLOWUPS)) others += "- followups: items [\"Explain more\",\"Give an example\"] (tappable next messages)"
        if (on(MAP)) others += "- map: title?, places [{name,note?,icon?}], route? (name required, lat/lng optional)"
        if (on(PLACES)) others += "- places: title?, items [{name,subtitle?,description?,tags?[..],rating?,price?,image?}]"
        if (on(IMAGES)) others += "- images: items [{query,caption?}]"
        if (on(WEATHER)) others += "- weather: location, days? (1-7)"
        if (on(BUDGET)) others += "- budget: title?, currency?, people?, items [{label,icon?,amount,min?,max?}]"
        if (on(STATS)) others += "- stats: items [{label,value,delta?,icon?}]"
        if (on(TIMELINE)) others += "- timeline: items [{time?,title,detail?,icon?}]"
        if (on(CALLOUT)) others += "- callout: tone info|tip|warning|danger, title?, text"
        if (on(LINKS)) others += "- links: items [{url,title?,note?}] (never invent URLs)"
        if (on(OMNI)) {
            others += "- omni: title, blocks [{kind,...}]. Only these kinds exist. Inputs: number or slider {id,label,value?,min?,max?,step?,unit?}; " +
                "input {id,label} (text); toggle {id,label}; select {id,label,options [{label,value}]}; date {id,label}; " +
                "function {id,label,value} (user edits f(x), e.g. \"x^2\"). " +
                "Outputs: result {label,expr,unit?,decimals?}; note {text}; plot {exprs [\"sin(x)\",\"f\"],from?,to?}; " +
                "table {exprs,from?,to?,step?}; progress {label,expr,max}; bars {items [{label,expr}]}. " +
                "Tools: keypad (full calculator); matrix (full matrix calculator); quiz {questions [{q,options,answer}]}; " +
                "flashcards {cards [{front,back}]}; steps {items}; checklist {items}; log {id,label} (user adds amounts; id is the total); " +
                "counter {id,label}; scoreboard {players}; timer {seconds}; intervals {steps [{label,seconds}],rounds?}; stopwatch; " +
                "dice {count?,sides?}; random {id,min,max}; coin; pick {items}; " +
                "send {label,text} (button that sends text as the user; {id} becomes the value). " +
                "expr: math over the ids (and x): + - * / ^ sqrt abs round min max sin cos ln log mod, == < > and or, if (c) a else b, today(); " +
                "op == 'add' tests a select's option value."
        }
        if (others.isNotEmpty()) {
            out.append("Other types, same block format:\n")
            others.forEach { out.append(it).append('\n') }
        }

        if (on(CHART)) {
            out.append(
                "chart: kind bar|line|area|hbar|pie|donut|scatter, unit?; pie and donut use one series; scatter uses series " +
                    "[{\"name\",\"points\":[[x,y],...]}] with x_label? and y_label?.\n"
            )
        }
        val notes = listOfNotNull(
            "choices: \"multi\":true for several answers, \"allow_other\":true lets the user type an answer.".takeIf { on(CHOICES) },
            "icon is one emoji.".takeIf { on(MAP) || on(BUDGET) || on(STATS) || on(TIMELINE) },
            "image and query are photo search phrases, not URLs.".takeIf { on(PLACES) || on(IMAGES) },
        )
        if (notes.isNotEmpty()) out.append(notes.joinToString(" "))
        return out.toString().trim()
    }

    /** Appended to the image-generation tool description while cards are on. */
    const val IMAGE_TOOL_NOTE: String =
        "Not for charts, apps, calculators or any card: write the card in your reply instead."
}
