package com.example.llamadroid.service

/** Pure, allocation-free budget check for plans carried into the next agent turn. */
internal object AgentPlanBudgetSupport {
    const val MAX_WORDS = 500
    const val MAX_CHARS = 4_000

    /**
     * Counts whitespace-delimited words without trimming or copying the input. The character
     * guard runs first so a very large model response is rejected before any scan.
     */
    fun isWithinBudget(plan: String): Boolean {
        if (plan.length > MAX_CHARS) return false

        var wordCount = 0
        var inWord = false
        for (character in plan) {
            if (character.isWhitespace()) {
                inWord = false
            } else if (!inWord) {
                wordCount += 1
                if (wordCount > MAX_WORDS) return false
                inWord = true
            }
        }
        return true
    }
}
