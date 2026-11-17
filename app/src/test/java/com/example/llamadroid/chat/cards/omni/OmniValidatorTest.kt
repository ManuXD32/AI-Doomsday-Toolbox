package com.example.llamadroid.chat.cards.omni

import org.junit.Assert.assertEquals
import org.junit.Test

class OmniValidatorTest {
    @Test fun cleanCardHasNoIssues() {
        val blocks = listOf(
            OmniBlock.Number("a", "A", 4.0, null, null, 1.0, null),
            OmniBlock.Select("op", "Op", listOf(OmniBlock.Option("+", "add"), OmniBlock.Option("-", "sub")), 0),
            OmniBlock.Function("f", "f(x)", "x^2"),
            OmniBlock.Result("R", "if (op == 'add') a + 1 else a - 1", null),
            OmniBlock.Result("F", "f(a)", null),
            OmniBlock.Plot(null, listOf("f", "sin(x)")),
            OmniBlock.Table(null, listOf("x*2")),
        )
        assertEquals(emptyList<OmniIssue>(), OmniValidator.issues(blocks))
    }

    @Test fun runtimeErrorsAreNotReported() {
        val blocks = listOf(OmniBlock.Number("a", "A", 0.0, null, null, 1.0, null), OmniBlock.Result("R", "1 / a", null))
        assertEquals(emptyList<OmniIssue>(), OmniValidator.issues(blocks))
    }

    @Test fun staticErrorsAreReported() {
        val blocks = listOf(
            OmniBlock.Result("R", "nope + 1", null),
            OmniBlock.Plot(null, listOf("foo(x)")),
            OmniBlock.Function("f", "f(x)", "x +"),
        )
        val issues = OmniValidator.issues(blocks)
        assertEquals(listOf("result \"R\"", "plot \"foo(x)\"", "function \"f\""), issues.map { it.where })
        assertEquals("unknown:nope", issues[0].reason)
    }
}
