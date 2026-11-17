package com.example.llamadroid.chat.cards.omni

/** One building block of an omni card. Pure data; the UI renders it, [OmniExpr] evaluates its formulas. */
sealed interface OmniBlock {
    /** Blocks that own a numeric value in the shared variable map (referenced by [id] in expressions). */
    sealed interface Variable : OmniBlock {
        val id: String
        val label: String
        val initial: Double
    }

    data class Number(
        override val id: String, override val label: String, val value: Double?,
        val min: Double?, val max: Double?, val step: Double = 1.0, val unit: String?,
    ) : Variable { override val initial: Double get() = value ?: min ?: 0.0 }

    data class Slider(
        override val id: String, override val label: String, val min: Double = 0.0, val max: Double = 100.0,
        val step: Double?, val value: Double?, val unit: String?,
    ) : Variable { override val initial: Double get() = (value ?: min).coerceIn(min, max) }

    /** Value 1.0 = on, 0.0 = off. */
    data class Toggle(override val id: String, override val label: String, val value: Boolean) : Variable {
        override val initial: Double get() = if (value) 1.0 else 0.0
    }

    /**
     * The selected option is stored as [value] (index) by the model, but the shared variable map holds [numberAt]:
     * the option's numeric value when EVERY option value parses as a number ([numeric]), else the index.
     * In expressions the text form is `listOf(option.value, option.label)`.
     */
    data class Select(
        override val id: String, override val label: String, val options: List<Option>, val value: Int?,
    ) : Variable {
        private val last get() = (options.size - 1).coerceAtLeast(0)
        val numeric: Boolean get() = options.isNotEmpty() && options.all { it.value.trim().replace(',', '.').toDoubleOrNull()?.isFinite() == true }
        /** Selected index (clamped). */
        val initialIndex: Int get() = (value ?: 0).coerceIn(0, last)
        /** Variable-map number for option [index]. */
        fun numberAt(index: Int): Double {
            val i = index.coerceIn(0, last)
            return if (numeric) options[i].value.trim().replace(',', '.').toDouble() else i.toDouble()
        }
        /** Inverse of [numberAt]: index of the option whose number equals [number] (0 when none). */
        fun indexOfNumber(number: Double): Int =
            if (numeric) options.indices.firstOrNull { numberAt(it) == number } ?: 0
            else number.toInt().coerceIn(0, last)
        /** Text values of option [index] for `==` comparisons. */
        fun textsAt(index: Int): List<String> =
            options.getOrNull(index.coerceIn(0, last))?.let { listOf(it.value, it.label) }.orEmpty()
        override val initial: Double get() = numberAt(initialIndex)
    }

    /** [value] = the model's option value (string form); the label is the fallback. */
    data class Option(val label: String, val value: String)

    /** Free text input ("input" kind). Number = parsed content (comma decimal ok) else NaN; text = the content. */
    data class Text(
        override val id: String, override val label: String, val placeholder: String?, val value: String?,
    ) : Variable {
        override val initial: Double get() = parse(value.orEmpty())
        companion object {
            fun parse(content: String): Double =
                content.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() } ?: Double.NaN
        }
    }

    /** Static explanation paragraph. */
    data class Note(val text: String) : OmniBlock

    /** User-editable formula in x; referenced from expressions by [id] (bare = at current x, `id(v)` = at x=v). */
    data class Function(val id: String, val label: String, val value: String = "x") : OmniBlock

    data class Plot(
        val label: String?, val exprs: List<String>, val from: Double = -10.0, val to: Double = 10.0,
        val yMin: Double? = null, val yMax: Double? = null,
    ) : OmniBlock

    /** At most [MAX_TABLE_ROWS] rows. */
    data class Table(
        val label: String?, val exprs: List<String>, val from: Double = 0.0, val to: Double = 10.0, val step: Double = 1.0,
    ) : OmniBlock

    /** Full matrix calculator widget (A, B up to 4x4). */
    data class Matrix(val label: String?, val rows: Int = 2, val cols: Int = 2) : OmniBlock

    /** [format]: null = plain number; "date" = epoch day shown as a localized date; "duration" = seconds shown as h:mm:ss. */
    data class Result(
        val label: String, val expr: String, val unit: String?, val decimals: Int = 2, val format: String? = null,
    ) : OmniBlock
    data class Keypad(val label: String?) : OmniBlock

    /** Checklist state lives in the checks map under [id] (auto-assigned when missing). */
    data class Checklist(val id: String, val title: String?, val items: List<String>) : OmniBlock

    data class Counter(
        override val id: String, override val label: String, val value: Double = 0.0, val step: Double = 1.0,
    ) : Variable { override val initial: Double get() = value }

    data class Timer(val label: String?, val seconds: Int) : OmniBlock
    data class Stopwatch(val label: String?) : OmniBlock
    /** Its [id] holds the sum of the last roll (0 before the first), so a result can use it. */
    data class Dice(override val id: String, override val label: String, val count: Int = 1, val sides: Int = 6) : Variable {
        override val initial: Double get() = 0.0
    }
    data class Coin(val label: String?) : OmniBlock
    data class Pick(val label: String?, val items: List<String>) : OmniBlock

    /** Sends [text] as a user message; `{id}` placeholders are replaced by the current variable values. */
    data class Send(val label: String, val text: String) : OmniBlock

    /** Question of a [Quiz]; [answer] is the index of the correct option. */
    data class QuizQ(val q: String, val options: List<String>, val answer: Int)

    /** Number = score (correct answers). State (`texts[id]`) = chosen option index per question, "0,2,-1" (-1 = unanswered). */
    data class Quiz(override val id: String, override val label: String, val questions: List<QuizQ>) : Variable {
        override val initial: Double get() = 0.0
        /** Correct answers among [answers] (index = question). */
        fun score(answers: List<Int>): Int = answers.indices.count { questions.getOrNull(it)?.answer == answers[it] }
        companion object {
            fun decodeAnswers(s: String?): List<Int> =
                s.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.take(MAX_QUESTIONS)
            fun encodeAnswers(answers: List<Int>): String = answers.joinToString(",")
            const val MAX_QUESTIONS = 20
        }
    }

    data class Flashcards(val label: String?, val cards: List<Pair<String, String>>) : OmniBlock
    data class Steps(val label: String?, val items: List<String>) : OmniBlock

    /** [max] is a number or an expression. [style] is "bar" or "gauge". */
    data class Progress(
        val label: String, val expr: String, val max: String = "100", val unit: String?, val style: String = "bar",
    ) : OmniBlock

    /** Horizontal bars; each item is (label, expr). */
    data class Bars(val label: String?, val items: List<Pair<String, String>>) : OmniBlock

    /** Number = epoch day. [value] is YYYY-MM-DD; null = today at render time. */
    data class Date(override val id: String, override val label: String, val value: String?) : Variable {
        /** Epoch day of [value] when valid, else [today]. */
        fun initialAt(today: Long): Double = parse(value)?.toDouble() ?: today.toDouble()
        override val initial: Double get() = initialAt(java.time.LocalDate.now().toEpochDay())
        companion object {
            fun parse(s: String?): Long? =
                runCatching { java.time.LocalDate.parse(s?.trim().orEmpty()).toEpochDay() }.getOrNull()
        }
    }

    /** Number = sum of entry amounts. State (`texts[id]`) = JSON array `[["label",amount],...]`, at most [MAX_ENTRIES]. */
    data class Log(override val id: String, override val label: String, val unit: String?) : Variable {
        override val initial: Double get() = 0.0
        companion object {
            const val MAX_ENTRIES = 100
            fun decodeEntries(s: String?): List<Pair<String, Double>> = runCatching {
                (kotlinx.serialization.json.Json.parseToJsonElement(s.orEmpty()) as kotlinx.serialization.json.JsonArray)
                    .mapNotNull { e ->
                        val a = e as? kotlinx.serialization.json.JsonArray ?: return@mapNotNull null
                        val amount = (a.getOrNull(1) as? kotlinx.serialization.json.JsonPrimitive)?.content
                            ?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return@mapNotNull null
                        ((a.getOrNull(0) as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()) to amount
                    }.take(MAX_ENTRIES)
            }.getOrDefault(emptyList())
            fun encodeEntries(entries: List<Pair<String, Double>>): String =
                kotlinx.serialization.json.JsonArray(entries.take(MAX_ENTRIES).map {
                    kotlinx.serialization.json.JsonArray(listOf(
                        kotlinx.serialization.json.JsonPrimitive(it.first), kotlinx.serialization.json.JsonPrimitive(it.second),
                    ))
                }).toString()
            fun sum(entries: List<Pair<String, Double>>): Double = entries.sumOf { it.second }
        }
    }

    /** Work/rest timer: [steps] are (label, seconds), repeated [rounds] times. */
    data class Intervals(val label: String?, val steps: List<Pair<String, Int>>, val rounds: Int = 1) : OmniBlock

    /** Integers when [min] and [max] are integers. Initial NaN (shown as "—") until rolled. */
    data class RandomNumber(
        override val id: String, override val label: String, val min: Double = 1.0, val max: Double = 100.0,
    ) : Variable {
        override val initial: Double get() = Double.NaN
        val integers: Boolean get() = min == Math.floor(min) && max == Math.floor(max)
    }

    /** Player scores live in the numbers map as `${id}_1`..`${id}_n` (all 0 initially). */
    data class Scoreboard(val id: String, val label: String?, val players: List<String>, val step: Double = 1.0) : OmniBlock {
        fun keys(): List<String> = players.indices.map { "${id}_${it + 1}" }
    }

    companion object {
        const val MAX_BLOCKS = 40
        const val MAX_TABLE_ROWS = 50
    }
}

/** A problem found in a card (parse drop or static formula error), shown to the user with an "ask to fix" action. */
data class OmniIssue(val where: String, val reason: String)
