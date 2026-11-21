package com.example.llamadroid.chat.cards.omni

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniExprTest {
    private fun ev(e: String, v: Map<String, Double> = emptyMap()) = OmniExpr.evaluate(e, v)
    private fun ok(e: String, v: Map<String, Double> = emptyMap()): Double = (ev(e, v) as OmniExpr.Result.Ok).value
    private fun err(e: String): String = (ev(e) as OmniExpr.Result.Error).reason

    @Test fun precedence() {
        assertEquals(7.0, ok("1+2*3"), 0.0)
        assertEquals(9.0, ok("(1+2)*3"), 0.0)
        assertEquals(1.0, ok("7%3"), 0.0)
        assertEquals(1.0, ok("1+1==2 && 2<3"), 0.0)
        assertEquals(1.0, ok("0 || 1"), 0.0)
        assertEquals(0.0, ok("1 != 1"), 0.0)
    }

    @Test fun powerAndUnary() {
        assertEquals(512.0, ok("2^3^2"), 0.0)
        assertEquals(-4.0, ok("-2^2"), 0.0)
        assertEquals(0.5, ok("2^-1"), 0.0)
        assertEquals(3.0, ok("--3"), 0.0)
        assertEquals(1.0, ok("!0"), 0.0)
        assertEquals(0.0, ok("!5"), 0.0)
        assertEquals(1000.0, ok("1e3"), 0.0)
    }

    @Test fun functionsAndConstants() {
        assertEquals(5.0, ok("max(1,5,3)"), 0.0)
        assertEquals(1.0, ok("min(4,1,3)"), 0.0)
        assertEquals(3.14, ok("round(pi,2)"), 0.0)
        assertEquals(3.0, ok("round(2.5)"), 0.0)
        assertEquals(3.0, ok("sqrt(9)"), 0.0)
        assertEquals(2.0, ok("log(100)"), 1e-12)
        assertEquals(1.0, ok("ln(e)"), 1e-12)
        assertEquals(8.0, ok("pow(2,3)"), 0.0)
        assertEquals(2.0, ok("ceil(1.2)"), 0.0)
        assertEquals(1.0, ok("floor(1.9)"), 0.0)
        assertEquals(0.0, ok("sin(0)"), 0.0)
        assertEquals(7.0, ok("if(1,7,8)"), 0.0)
        assertEquals(4.0, ok("abs(-4)"), 0.0)
    }

    @Test fun ternaryAndVariables() {
        assertEquals(10.0, ok("x>1 ? x*2 : 0", mapOf("x" to 5.0)), 0.0)
        assertEquals(0.0, ok("x>9 ? 1 : x>1 ? 0 : 2", mapOf("x" to 5.0)), 0.0)
        assertEquals(1.1 * 100, ok("bill*(1+tip/100)", mapOf("bill" to 100.0, "tip" to 10.0)), 1e-9)
    }

    @Test fun unicodeOperators() {
        assertEquals(6.0, ok("2×3"), 0.0)
        assertEquals(2.0, ok("6÷3"), 0.0)
        assertEquals(1.0, ok("3−2"), 0.0)
    }

    @Test fun errors() {
        assertEquals("div0", err("1/0"))
        assertEquals("div0", err("1%0"))
        assertTrue(err("0.5,2").isNotEmpty())
        assertTrue(err("foo+1").startsWith("unknown"))
        assertTrue(err("sqrt(-1)").isNotEmpty())
        assertTrue(err("1+").isNotEmpty())
        assertTrue(err("(1").isNotEmpty())
        assertTrue(err("1 2").isNotEmpty())
        assertTrue(err("10^1000").isNotEmpty())
        assertTrue(err("nope(1)").startsWith("unknown_fn"))
    }

    @Test fun limits() {
        assertEquals("too_long", err("1+".repeat(300) + "1"))
        assertEquals("too_deep", err("(".repeat(40) + "1" + ")".repeat(40)))
        assertEquals(1.0, ok("(".repeat(20) + "1" + ")".repeat(20)), 0.0)
        assertEquals("too_deep", err("-".repeat(60) + "1"))
    }

    @Test fun format() {
        assertEquals("1234.57", OmniExpr.format(1234.5678, 2))
        assertEquals("3", OmniExpr.format(3.0, 2))
        assertEquals("0.5", OmniExpr.format(0.5, 3))
        assertEquals("0", OmniExpr.format(-0.001, 1))
        assertEquals("12", OmniExpr.format(12.4, 0))
        assertEquals("1000000", OmniExpr.format(1e6, 2))
    }

    private fun envOk(e: String, env: OmniEnv): Double = (OmniExpr.evaluate(e, env) as OmniExpr.Result.Ok).value
    private fun envErr(e: String, env: OmniEnv): String = (OmniExpr.evaluate(e, env) as OmniExpr.Result.Error).reason

    private val gemma = "if (op == 'add') (a + b) else if (op == 'sub') (a - b) else if (op == 'mul') (a * b) else (a / b)"

    private fun opEnv(op: String, b: Double = 4.0) = OmniEnv(
        numbers = mapOf("a" to 12.0, "b" to b, "op" to 0.0),
        texts = mapOf("op" to listOf(op, op.uppercase())),
    )

    @Test fun failingGemmaExpression() {
        assertEquals(16.0, envOk(gemma, opEnv("add")), 0.0)
        assertEquals(8.0, envOk(gemma, opEnv("sub")), 0.0)
        assertEquals(48.0, envOk(gemma, opEnv("mul")), 0.0)
        assertEquals(3.0, envOk(gemma, opEnv("div")), 0.0)
    }

    @Test fun untakenBranchDoesNotRaiseRuntimeErrors() {
        assertEquals(12.0, envOk(gemma, opEnv("add", b = 0.0)), 0.0) // a + 0; the a / 0 branch is never evaluated
        assertEquals("div0", envErr(gemma, opEnv("div", b = 0.0)))
        assertEquals(1.0, ok("1 ? 1 : 1/0"), 0.0)
    }

    @Test fun stringComparison() {
        val env = OmniEnv(numbers = mapOf("op" to 1.0), texts = mapOf("op" to listOf("Add ", "plus")))
        assertEquals(1.0, envOk("op == 'add'", env), 0.0)
        assertEquals(1.0, envOk("'PLUS' === op", env), 0.0)
        assertEquals(0.0, envOk("op != \"add\"", env), 0.0)
        assertEquals(1.0, envOk("op !== 'x'", env), 0.0)
        assertEquals(1.0, envOk("'a' == 'A'", env), 0.0)
        assertEquals(0.0, envOk("missing == 'a'", OmniEnv(numbers = mapOf("missing" to 1.0))), 0.0)
        assertEquals("unknown:nope", envErr("nope == 'a'", env))
    }

    @Test fun textInMath() {
        assertEquals("text_in_math", err("'a' + 1"))
        assertEquals("text_in_math", err("1 + 'a'"))
        assertEquals("text_in_math", envErr("x == 1 + 'a'", OmniEnv(numbers = mapOf("x" to 1.0))))
        assertEquals("text_in_math", envErr("name + 1", OmniEnv(texts = mapOf("name" to listOf("z")))))
        assertEquals("expected_quote", err("'abc == 1"))
    }

    @Test fun elseChainAndFunctionForm() {
        assertEquals(2.0, ok("if (x > 9) 1 else if (x > 4) 2 else 3", mapOf("x" to 5.0)), 0.0)
        assertEquals(3.0, ok("if (x > 9) 1 else if (x > 6) 2 else 3", mapOf("x" to 5.0)), 0.0)
        assertEquals(8.0, ok("if(0,7,8)"), 0.0)
        assertEquals("else_missing", err("if (1) 2"))
    }

    @Test fun keywords() {
        assertEquals(1.0, ok("true and not false"), 0.0)
        assertEquals(0.0, ok("true and false"), 0.0)
        assertEquals(1.0, ok("false or 1 == 1"), 0.0)
        assertEquals(1.0, ok("not 0 == 0 or true"), 0.0)
        assertEquals(1.0, ok("1 === 1"), 0.0)
        assertEquals(1.0, ok("1 !== 2"), 0.0)
        assertEquals(1.0, ok("order > 0", mapOf("order" to 2.0)), 0.0)
    }

    @Test fun newFunctions() {
        assertEquals(1.0, ok("mod(7,3)"), 0.0)
        assertEquals(2.0, ok("mod(-1,3)"), 0.0)
        assertEquals("div0", err("mod(1,0)"))
        assertEquals(3.0, ok("log(8,2)"), 1e-12)
        assertEquals(2.0, ok("log(100)"), 1e-12)
        assertEquals("domain", err("log(8,1)"))
        assertEquals(Math.PI / 2, ok("asin(1)"), 1e-12)
        assertEquals(0.0, ok("acos(1)"), 1e-12)
        assertEquals(Math.PI / 4, ok("atan(1)"), 1e-12)
        assertEquals(Math.PI / 2, ok("atan2(1,0)"), 1e-12)
        assertEquals("domain", err("asin(2)"))
        assertEquals(120.0, ok("fact(5)"), 0.0)
        assertEquals(1.0, ok("fact(0)"), 0.0)
        assertEquals("domain", err("fact(171)"))
        assertEquals("domain", err("fact(2.5)"))
        assertEquals("domain", err("fact(-1)"))
        assertEquals(5.0, ok("clamp(9,1,5)"), 0.0)
        assertEquals(1.0, ok("clamp(-9,1,5)"), 0.0)
        assertEquals(180.0, ok("deg(pi)"), 1e-9)
        assertEquals(Math.PI, ok("rad(180)"), 1e-12)
        assertEquals(-1.0, ok("sign(-3)"), 0.0)
        assertEquals(0.0, ok("sign(0)"), 0.0)
        assertEquals(-2.0, ok("trunc(-2.7)"), 0.0)
        assertEquals(2.0, ok("trunc(2.7)"), 0.0)
    }

    @Test fun functionBlocks() {
        val env = OmniEnv(numbers = mapOf("x" to 2.0), functions = mapOf("f" to "x^2 + 1", "g" to "f(x) * 2"))
        assertEquals(5.0, envOk("f", env), 0.0)
        assertEquals(10.0, envOk("f(3)", env), 0.0)
        assertEquals(10.0, envOk("g", env), 0.0)
        assertEquals(1.0, envOk("f", OmniEnv(functions = mapOf("f" to "x^2 + 1"))), 0.0) // x absent -> 0
        assertEquals("args:f", envErr("f(1,2)", env))
    }

    @Test fun selfReferencingFunctionIsTooDeep() {
        assertEquals("too_deep", envErr("f(1)", OmniEnv(functions = mapOf("f" to "f(x) + 1"))))
        assertEquals("too_deep", envErr("f", OmniEnv(functions = mapOf("f" to "f"))))
        // exponential fan-out is bounded by the call budget
        assertEquals("too_deep", envErr("f(1)", OmniEnv(functions = mapOf("f" to "f(x) + f(x)"))))
    }

    @Test fun staticVersusRuntimeErrors() {
        for (r in listOf("unexpected", "expected_paren", "unknown:x", "unknown_fn:f", "args:min", "too_long", "too_deep", "text_in_math", "else_missing"))
            assertTrue(r, OmniExpr.isStaticError(r))
        for (r in listOf("div0", "domain", "not_finite")) assertTrue(r, !OmniExpr.isStaticError(r))
    }
}
