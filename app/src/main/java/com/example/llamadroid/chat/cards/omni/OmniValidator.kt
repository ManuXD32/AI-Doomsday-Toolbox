package com.example.llamadroid.chat.cards.omni

/** Evaluates every formula of a card with its initial values and reports only static (card bug) errors. */
object OmniValidator {
    fun issues(blocks: List<OmniBlock>, today: Long? = null): List<OmniIssue> {
        val fallback = blocks.filterIsInstance<OmniBlock.RandomNumber>().associate { it.id to it.min }
        val base = OmniEnvs.build(blocks, OmniEnvs.initialNumbers(blocks, today), OmniEnvs.initialTexts(blocks), x = 1.0, today = today)
        // Blank text inputs / unrolled random numbers are NaN; use a neutral stand-in so only card bugs surface.
        val env = OmniEnv(
            base.numbers.mapValues { (k, v) -> if (v.isFinite()) v else fallback[k] ?: 0.0 },
            base.texts, base.functions, today,
        )
        val out = ArrayList<OmniIssue>()
        fun check(where: String, expr: String) {
            val r = OmniExpr.evaluate(expr, env)
            if (r is OmniExpr.Result.Error && OmniExpr.isStaticError(r.reason)) out += OmniIssue(where, r.reason)
        }
        for (b in blocks) when (b) {
            is OmniBlock.Result -> check("result \"${b.label.take(30)}\"", b.expr)
            is OmniBlock.Plot -> b.exprs.forEach { check("plot \"${it.take(30)}\"", it) }
            is OmniBlock.Table -> b.exprs.forEach { check("table \"${it.take(30)}\"", it) }
            is OmniBlock.Function -> check("function \"${b.id}\"", b.value)
            is OmniBlock.Progress -> {
                check("progress \"${b.label.take(30)}\"", b.expr)
                if (b.max.trim().replace(',', '.').toDoubleOrNull() == null) check("progress max \"${b.max.take(30)}\"", b.max)
            }
            is OmniBlock.Bars -> b.items.forEach { check("bars \"${it.first.take(30)}\"", it.second) }
            else -> Unit
        }
        return out
    }
}
