package com.example.llamadroid.chat.cards.omni

/** The one place that turns blocks plus live values into an [OmniEnv] (used by the validator and the UI). */
object OmniEnvs {
    /**
     * [numbers]: live numeric values by id (variables + scoreboard keys); [texts]: live text by id (text content,
     * function formula, quiz/log state). Text numbers are re-derived from [texts]; quiz score and log sum are derived
     * from their state when present.
     */
    fun build(
        blocks: List<OmniBlock>, numbers: Map<String, Double>, texts: Map<String, String>,
        x: Double? = null, today: Long? = null,
    ): OmniEnv {
        val nums = HashMap(numbers)
        val tx = HashMap<String, List<String>>()
        val fn = HashMap<String, String>()
        for (b in blocks) when (b) {
            is OmniBlock.Select -> tx[b.id] = b.textsAt(b.indexOfNumber(nums[b.id] ?: b.initial))
            is OmniBlock.Text -> {
                val c = texts[b.id] ?: b.value.orEmpty()
                nums[b.id] = OmniBlock.Text.parse(c)
                tx[b.id] = listOf(c)
            }
            is OmniBlock.Function -> fn[b.id] = texts[b.id]?.takeIf { it.isNotBlank() } ?: b.value
            is OmniBlock.Quiz -> texts[b.id]?.let { nums[b.id] = b.score(OmniBlock.Quiz.decodeAnswers(it)).toDouble() }
            is OmniBlock.Log -> texts[b.id]?.let { nums[b.id] = OmniBlock.Log.sum(OmniBlock.Log.decodeEntries(it)) }
            else -> Unit
        }
        if (x != null) nums["x"] = x
        return OmniEnv(nums, tx, fn, today)
    }

    /** Every variable's initial value plus scoreboard keys (0). Text/random variables start as NaN when blank/unrolled. */
    fun initialNumbers(blocks: List<OmniBlock>, today: Long? = null): Map<String, Double> {
        val day = today ?: java.time.LocalDate.now().toEpochDay()
        val out = LinkedHashMap<String, Double>()
        for (b in blocks) when (b) {
            is OmniBlock.Date -> out[b.id] = b.initialAt(day)
            is OmniBlock.Variable -> out[b.id] = b.initial
            is OmniBlock.Scoreboard -> b.keys().forEach { out[it] = 0.0 }
            else -> Unit
        }
        return out
    }

    /** Text input contents and function formulas. */
    fun initialTexts(blocks: List<OmniBlock>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (b in blocks) when (b) {
            is OmniBlock.Text -> out[b.id] = b.value.orEmpty()
            is OmniBlock.Function -> out[b.id] = b.value
            else -> Unit
        }
        return out
    }
}
