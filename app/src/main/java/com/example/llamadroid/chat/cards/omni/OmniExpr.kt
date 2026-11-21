package com.example.llamadroid.chat.cards.omni

import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Evaluation environment for [OmniExpr].
 * [texts]: text values per id, compared case-insensitively (trimmed) by ==/!= against 'string' literals; any entry matching = equal.
 * [functions]: function-block id -> formula in x. Bare `f` = formula at the current x (numbers["x"], 0 if absent); `f(v)` = formula at x=v.
 */
class OmniEnv(
    val numbers: Map<String, Double> = emptyMap(),
    val texts: Map<String, List<String>> = emptyMap(),
    val functions: Map<String, String> = emptyMap(),
    /** Epoch day used by today(); null = the current date. */
    val today: Long? = null,
)

/** Safe recursive-descent arithmetic evaluator (no reflection, no scripting). */
object OmniExpr {
    sealed interface Result {
        data class Ok(val value: Double) : Result
        data class Error(val reason: String) : Result
    }

    const val MAX_LENGTH = 500
    const val MAX_DEPTH = 32
    private const val MAX_BLOCK_CALLS = 500

    fun evaluate(expr: String, vars: Map<String, Double> = emptyMap()): Result = evaluate(expr, OmniEnv(vars))

    /** Static (card bug) reasons versus runtime (input-dependent) ones such as div0, domain, not_finite. */
    fun isStaticError(reason: String): Boolean =
        reason.startsWith("unexpected") || reason.startsWith("expected_") ||
            reason.startsWith("unknown:") || reason.startsWith("unknown_fn:") || reason.startsWith("args:") ||
            reason == "too_long" || reason == "too_deep" || reason == "text_in_math" ||
            reason == "else_missing" || reason == "bad_number"

    fun evaluate(expr: String, env: OmniEnv): Result {
        if (expr.length > MAX_LENGTH) return Result.Error("too_long")
        return try {
            val p = Parser(expr, env, IntArray(1), 0)
            val v = p.parseAll()
            if (v.isNaN() || v.isInfinite()) Result.Error("not_finite") else Result.Ok(v)
        } catch (e: ExprError) {
            Result.Error(e.message ?: "error")
        }
    }

    /** Locale-independent, group-free; at most [decimals] fraction digits, trailing zeros trimmed. */
    fun format(value: Double, decimals: Int): String {
        if (value.isNaN() || value.isInfinite()) return "—"
        // Very large magnitudes: short scientific form instead of hundreds of digits.
        if (abs(value) >= 1e15) {
            return String.format(java.util.Locale.ROOT, "%.6g", value)
        }
        val d = decimals.coerceIn(0, 10)
        var s = java.math.BigDecimal(value).setScale(d, java.math.RoundingMode.HALF_UP).toPlainString()
        if (d > 0 && s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        if (s == "-0") s = "0"
        return s
    }

    private val KEYWORDS = setOf("true", "false", "not", "and", "or", "if", "else")

    private class ExprError(reason: String) : RuntimeException(reason, null, false, false)

    private class StrOp(val literal: Boolean, val values: List<String>?, val name: String?)

    private class Parser(src: String, val env: OmniEnv, val budget: IntArray, startDepth: Int) {
        private val s = src.replace('×', '*').replace('÷', '/').replace('−', '-').replace('–', '-')
        private var i = 0
        private var depth = startDepth
        /** >0 while parsing a branch that is not taken: runtime errors are suppressed (lazy if/ternary). */
        private var dry = 0

        fun parseAll(): Double {
            val v = ternary()
            ws()
            if (i < s.length) fail("unexpected")
            return v
        }

        private fun fail(r: String): Nothing = throw ExprError(r)
        private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun peek(): Char { ws(); return if (i < s.length) s[i] else '\u0000' }
        private fun eat(str: String): Boolean {
            ws()
            if (s.startsWith(str, i)) { i += str.length; return true }
            return false
        }
        private fun rt(r: String): Double { if (dry > 0) return 0.0; fail(r) }
        private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'
        private fun eatWord(w: String): Boolean {
            ws()
            if (s.regionMatches(i, w, 0, w.length, ignoreCase = true) &&
                (i + w.length >= s.length || !isWordChar(s[i + w.length]))
            ) { i += w.length; return true }
            return false
        }
        private inline fun <T> branch(taken: Boolean, block: () -> T): T {
            if (!taken) dry++
            try { return block() } finally { if (!taken) dry-- }
        }
        private fun enter() { if (++depth > MAX_DEPTH) fail("too_deep") }
        private fun leave() { depth-- }

        private fun ternary(): Double {
            val c = or()
            if (!eat("?")) return c
            enter()
            val a = branch(c != 0.0) { ternary() }
            if (!eat(":")) fail("expected_colon")
            val b = branch(c == 0.0) { ternary() }
            leave()
            return if (c != 0.0) a else b
        }

        private fun or(): Double {
            var l = and()
            while (eat("||") || eatWord("or")) { val r = and(); l = if (l != 0.0 || r != 0.0) 1.0 else 0.0 }
            return l
        }

        private fun and(): Double {
            var l = not()
            while (eat("&&") || eatWord("and")) { val r = not(); l = if (l != 0.0 && r != 0.0) 1.0 else 0.0 }
            return l
        }

        private fun not(): Double {
            if (!eatWord("not")) return cmp()
            enter()
            val v = not()
            leave()
            return b(v == 0.0)
        }

        private fun strOperand(): StrOp? {
            val c = peek()
            if (c == '\'' || c == '"') {
                i++
                val sb = StringBuilder()
                while (true) {
                    if (i >= s.length) fail("expected_quote")
                    val ch = s[i++]
                    if (ch == c) break
                    if (ch == '\\' && i < s.length) sb.append(s[i++]) else sb.append(ch)
                }
                return StrOp(true, listOf(sb.toString()), null)
            }
            if (c.isLetter() || c == '_') {
                val st = i
                var j = i
                while (j < s.length && isWordChar(s[j])) j++
                val name = s.substring(st, j)
                if (name.lowercase() in KEYWORDS) return null
                var k = j
                while (k < s.length && s[k].isWhitespace()) k++
                if (k < s.length && s[k] == '(') return null
                i = j
                return StrOp(false, env.texts[name], name)
            }
            return null
        }

        /** `ident == 'text'` / `'text' != ident` string comparison; null (position restored) when not applicable. */
        private fun tryStrEq(): Double? {
            val save = i
            val left = strOperand()
            if (left == null) { i = save; return null }
            val eq = when {
                eat("===") -> true
                eat("!==") -> false
                eat("==") -> true
                eat("!=") -> false
                else -> { i = save; return null }
            }
            val right = strOperand()
            if (right == null || (!left.literal && !right.literal)) { i = save; return null }
            for (o in arrayOf(left, right)) {
                val n = o.name
                if (n != null && o.values == null && !env.numbers.containsKey(n) && !env.functions.containsKey(n)) fail("unknown:$n")
            }
            val l = left.values
            val r = right.values
            val same = l != null && r != null &&
                l.any { a -> r.any { b -> a.trim().equals(b.trim(), ignoreCase = true) } }
            return b(same == eq)
        }

        private fun cmp(): Double {
            var l = tryStrEq() ?: add()
            while (true) {
                l = when {
                    eat("<=") -> b(l <= add())
                    eat(">=") -> b(l >= add())
                    eat("===") -> b(l == add())
                    eat("!==") -> b(l != add())
                    eat("==") -> b(l == add())
                    eat("!=") -> b(l != add())
                    eat("<") -> b(l < add())
                    eat(">") -> b(l > add())
                    else -> return l
                }
            }
        }

        private fun b(x: Boolean) = if (x) 1.0 else 0.0

        private fun add(): Double {
            var l = mul()
            while (true) {
                ws()
                l = when {
                    eat("+") -> l + mul()
                    eat("-") -> l - mul()
                    else -> return l
                }
            }
        }

        private fun mul(): Double {
            var l = unary()
            while (true) {
                l = when {
                    eat("*") -> l * unary()
                    eat("/") -> { val r = unary(); if (r == 0.0) rt("div0") else l / r }
                    eat("%") -> { val r = unary(); if (r == 0.0) rt("div0") else l % r }
                    else -> return l
                }
            }
        }

        private fun unary(): Double {
            val prefix = when {
                eat("-") -> 1
                eat("+") -> 2
                peek() == '!' && !s.startsWith("!=", i) -> { i++; 3 }
                else -> return power()
            }
            enter()
            val v = unary()
            leave()
            return when (prefix) { 1 -> -v; 2 -> v; else -> b(v == 0.0) }
        }

        private fun power(): Double {
            val base = primary()
            if (eat("^")) {
                enter()
                val exp = unary() // right-assoc; allows 2^-1
                leave()
                val r = base.pow(exp)
                if (r.isNaN() || r.isInfinite()) return rt("not_finite")
                return r
            }
            return base
        }

        private fun primary(): Double {
            val c = peek()
            if (c == '(') {
                i++
                enter()
                val v = ternary()
                leave()
                if (!eat(")")) fail("expected_paren")
                return v
            }
            if (c.isDigit() || c == '.') return number()
            if (c == '\'' || c == '"') fail("text_in_math")
            if (c.isLetter() || c == '_') {
                val st = i
                while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                val name = s.substring(st, i)
                val lname = name.lowercase()
                val formula = env.functions[name]
                if (lname == "if" && peek() == '(') return ifExpr()
                if (peek() == '(') {
                    i++
                    val args = ArrayList<Double>()
                    enter()
                    if (!eat(")")) {
                        do { args += ternary() } while (eat(","))
                        if (!eat(")")) fail("expected_paren")
                    }
                    leave()
                    if (formula != null) {
                        if (args.size != 1) fail("args:$name")
                        return blockAt(formula, args[0])
                    }
                    return call(lname, args)
                }
                if (formula != null) return blockAt(formula, env.numbers["x"] ?: 0.0)
                return when (lname) {
                    "true" -> 1.0
                    "false" -> 0.0
                    "pi" -> PI
                    "e" -> E
                    else -> env.numbers[name] ?: if (env.texts.containsKey(name)) fail("text_in_math") else fail("unknown:$name")
                }
            }
            fail("unexpected")
        }

        private fun blockAt(formula: String, x: Double): Double {
            if (++budget[0] > MAX_BLOCK_CALLS) fail("too_deep")
            if (depth + 1 > MAX_DEPTH) fail("too_deep")
            if (formula.length > MAX_LENGTH) fail("too_long")
            val sub = OmniEnv(env.numbers + ("x" to x), env.texts, env.functions, env.today)
            val p = Parser(formula, sub, budget, depth + 1)
            p.dry = dry
            val v = p.parseAll()
            return if (v.isNaN() || v.isInfinite()) rt("not_finite") else v
        }

        /** `if (c) a else b` (chainable) or the function form `if(c, a, b)`; untaken branch is not evaluated. */
        private fun ifExpr(): Double {
            i++ // '('
            enter()
            val c = ternary()
            val taken = c != 0.0
            val r: Double
            if (eat(",")) {
                val a = branch(taken) { ternary() }
                if (!eat(",")) fail("args:if")
                val bb = branch(!taken) { ternary() }
                if (!eat(")")) fail("expected_paren")
                r = if (taken) a else bb
            } else {
                if (!eat(")")) fail("expected_paren")
                val a = branch(taken) { ternary() }
                if (!eatWord("else")) fail("else_missing")
                val bb = branch(!taken) { ternary() }
                r = if (taken) a else bb
            }
            leave()
            return r
        }

        private fun number(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                var j = i + 1
                if (j < s.length && (s[j] == '+' || s[j] == '-')) j++
                if (j < s.length && s[j].isDigit()) {
                    while (j < s.length && s[j].isDigit()) j++
                    i = j
                }
            }
            return s.substring(st, i).toDoubleOrNull() ?: fail("bad_number")
        }

        private fun day(v: Double): java.time.LocalDate? =
            if (v.isNaN() || abs(v) > 1e8) null else java.time.LocalDate.ofEpochDay(floor(v).toLong())

        private fun call(name: String, a: List<Double>): Double {
            fun need(n: Int) { if (a.size != n) fail("args:$name") }
            return when (name) {
                "min" -> { if (a.isEmpty()) fail("args:min"); a.min() }
                "max" -> { if (a.isEmpty()) fail("args:max"); a.max() }
                "abs" -> { need(1); abs(a[0]) }
                "round" -> {
                    if (a.size !in 1..2) fail("args:round")
                    val n = if (a.size == 2) a[1].toInt().coerceIn(0, 10) else 0
                    if (a[0].isNaN() || a[0].isInfinite()) fail("not_finite")
                    java.math.BigDecimal(a[0]).setScale(n, java.math.RoundingMode.HALF_UP).toDouble()
                }
                "floor" -> { need(1); floor(a[0]) }
                "ceil" -> { need(1); ceil(a[0]) }
                "sqrt" -> { need(1); if (a[0] < 0) rt("domain") else sqrt(a[0]) }
                "pow" -> { need(2); a[0].pow(a[1]) }
                "ln" -> { need(1); if (a[0] <= 0) rt("domain") else ln(a[0]) }
                "log" -> {
                    if (a.size !in 1..2) fail("args:log")
                    if (a[0] <= 0) rt("domain")
                    else if (a.size == 1) log10(a[0])
                    else if (a[1] <= 0 || a[1] == 1.0) rt("domain")
                    else ln(a[0]) / ln(a[1])
                }
                "mod" -> { need(2); if (a[1] == 0.0) rt("div0") else ((a[0] % a[1]) + a[1]) % a[1] }
                "asin" -> { need(1); if (a[0] < -1 || a[0] > 1) rt("domain") else asin(a[0]) }
                "acos" -> { need(1); if (a[0] < -1 || a[0] > 1) rt("domain") else acos(a[0]) }
                "atan" -> { need(1); atan(a[0]) }
                "atan2" -> { need(2); atan2(a[0], a[1]) }
                "fact" -> {
                    need(1)
                    val n = a[0]
                    if (n.isNaN() || n < 0 || n > 170 || n != floor(n)) rt("domain")
                    else { var f = 1.0; for (k in 2..n.toInt()) f *= k; f }
                }
                "clamp" -> { need(3); min(max(a[0], a[1]), a[2]) }
                "deg" -> { need(1); Math.toDegrees(a[0]) }
                "rad" -> { need(1); Math.toRadians(a[0]) }
                "sign" -> { need(1); if (a[0].isNaN()) a[0] else Math.signum(a[0]) }
                "trunc" -> { need(1); if (a[0] < 0) ceil(a[0]) else floor(a[0]) }
                "today" -> { need(0); (env.today ?: java.time.LocalDate.now().toEpochDay()).toDouble() }
                "years" -> {
                    need(2)
                    val f = day(a[0]); val t = day(a[1])
                    if (f == null || t == null) rt("domain")
                    else java.time.temporal.ChronoUnit.YEARS.between(f, t).toDouble()
                }
                "weekday" -> { need(1); day(a[0])?.dayOfWeek?.value?.toDouble() ?: rt("domain") }
                "exp" -> { need(1); exp(a[0]) }
                "sin" -> { need(1); sin(a[0]) }
                "cos" -> { need(1); cos(a[0]) }
                "tan" -> { need(1); tan(a[0]) }
                "if" -> { need(3); if (a[0] != 0.0) a[1] else a[2] }
                else -> fail("unknown_fn:$name")
            }
        }
    }
}
