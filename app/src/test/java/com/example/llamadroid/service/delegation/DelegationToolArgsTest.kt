package com.example.llamadroid.service.delegation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegationToolArgsTest {
    @Test
    fun attachmentsAcceptCsvJsonAndMessyInput() {
        assertEquals(listOf("f12", "img55"), DelegationToolArgs.parseAttachmentRefs("f12,img55"))
        assertEquals(listOf("f12", "img55"), DelegationToolArgs.parseAttachmentRefs("[\"f12\", \"img55\"]"))
        assertEquals(listOf("f12", "img55"), DelegationToolArgs.parseAttachmentRefs(" [ 'F12' ; img055 ] "))
        assertEquals(listOf("f1"), DelegationToolArgs.parseAttachmentRefs("f1, f01, nonsense, 12"))
        assertTrue(DelegationToolArgs.parseAttachmentRefs(null).isEmpty())
        assertTrue(DelegationToolArgs.parseAttachmentRefs("[broken").isEmpty())
    }

    @Test
    fun attachmentsAreBounded() {
        val raw = (1..100).joinToString(",") { "f$it" }
        assertEquals(DelegationToolArgs.MAX_ATTACHMENTS, DelegationToolArgs.parseAttachmentRefs(raw).size)
    }

    @Test
    fun intCoercionHandlesStringsAndDoubles() {
        assertEquals(15, DelegationToolArgs.coerceInt("15"))
        assertEquals(15, DelegationToolArgs.coerceInt("15.0"))
        assertEquals(16, DelegationToolArgs.coerceInt(" 15.7 "))
        assertEquals(20, DelegationToolArgs.coerceInt("20 min"))
        assertNull(DelegationToolArgs.coerceInt("soon"))
        assertNull(DelegationToolArgs.coerceInt(null))
    }

    @Test
    fun waitSecondsDefaultsAndClamps() {
        assertEquals(45, DelegationToolArgs.coerceWaitSeconds(null))
        assertEquals(120, DelegationToolArgs.coerceWaitSeconds("500"))
        assertEquals(0, DelegationToolArgs.coerceWaitSeconds("-3"))
    }

    @Test
    fun enumsFallBackToDefaults() {
        assertTrue(DelegationToolArgs.parseProjectContinue(" Continue "))
        assertFalse(DelegationToolArgs.parseProjectContinue("bogus"))
        assertTrue(DelegationToolArgs.parseFullDetail("FULL"))
        assertFalse(DelegationToolArgs.parseFullDetail(null))
    }

    @Test
    fun booleansAndBounds() {
        assertTrue(DelegationToolArgs.coerceBoolean("yes", false))
        assertFalse(DelegationToolArgs.coerceBoolean("0", true))
        assertTrue(DelegationToolArgs.coerceBoolean("maybe", true))
        assertEquals(5, DelegationToolArgs.boundedText("  abcdefgh ", 5)!!.length)
        assertNull(DelegationToolArgs.boundedText("   ", 5))
    }

    @Test
    fun argLookupIgnoresCaseAndSeparators() {
        assertEquals("1", DelegationToolArgs.arg(mapOf("Wait-Seconds" to "1"), "wait_seconds"))
    }

    @Test
    fun schemasAreValidObjects() {
        listOf(
            DelegationToolArgs.startSchema(), DelegationToolArgs.statusSchema(), DelegationToolArgs.messageSchema(),
            DelegationToolArgs.cancelSchema(), DelegationToolArgs.scheduleSchema()
        ).forEach {
            val json = JSONObject(it)
            assertEquals("object", json.getString("type"))
            assertFalse(json.getBoolean("additionalProperties"))
        }
        assertEquals("task", JSONObject(DelegationToolArgs.startSchema()).getJSONArray("required").getString(0))
    }
}
