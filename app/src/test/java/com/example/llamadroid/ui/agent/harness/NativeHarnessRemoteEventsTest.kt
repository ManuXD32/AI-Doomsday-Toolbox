package com.example.llamadroid.ui.agent.harness

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NativeHarnessRemoteEventsTest {
    @Test
    fun questionAnswerKeepsSelectedOptionsAndCustomTextSeparate() {
        val answer = buildHarnessQuestionAnswer(
            answerId = "format",
            selected = listOf("table", "csv"),
            custom = "Include headers"
        )

        assertEquals("format", answer["id"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("table", "csv"),
            answer["selected"]?.jsonArray?.map { it.jsonPrimitive.content }
        )
        assertEquals("Include headers", answer["custom"]?.jsonPrimitive?.content)
        assertFalse(answer.containsKey("unexpected"))
    }

    @Test
    fun blankCustomTextIsOmittedFromQuestionAnswer() {
        val answer = buildHarnessQuestionAnswer("format", listOf("table"), " ")

        assertFalse(answer.containsKey("custom"))
    }

    @Test
    fun planRequestChangesUsesOfficialCancelledOutcome() {
        val outcome = buildHarnessPlanCancellationOutcome()

        assertEquals("rejected", outcome["kind"]?.jsonPrimitive?.content)
        assertEquals("UserQuestionError", outcome["error"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
        assertEquals("ASK_CANCELLED", outcome["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }
}
