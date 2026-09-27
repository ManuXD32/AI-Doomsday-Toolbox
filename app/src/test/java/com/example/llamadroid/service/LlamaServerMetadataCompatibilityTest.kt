package com.example.llamadroid.service

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class LlamaServerMetadataCompatibilityTest {
    private val gson = Gson()

    @Test fun `0993 release owner receipts survive stable field names`() {
        val owner = gson.fromJson("""{"a":"card:1","b":1234,"c":9876,"d":49920,"e":"{}"}""",
            LlamaServerSessionOwner::class.java)
        assertEquals("card:1", owner.sessionId)
        assertEquals(1234, owner.pid)
        assertEquals(9876L, owner.processStartTimeTicks)
        assertEquals(49920, owner.port)
        assertTrue(gson.toJson(owner).contains("\"processStartTimeTicks\""))
    }

    @Test fun `0993 running snapshot and idle checkpoint survive release upgrade`() {
        val snapshot = gson.fromJson("""{"a":"card:1","b":"f","c":49920,"d":1234,"i":3,"j":1000,"l":900,"m":"counter"}""",
            LlamaServerSessionSnapshot::class.java)
        assertEquals(LlamaServerSessionStatus.RUNNING, snapshot.status)
        assertEquals(900L, snapshot.idleActivityAtElapsedMs)
        assertEquals("counter", snapshot.idleActivityFingerprint)
        val encoded = gson.toJson(snapshot)
        assertTrue(encoded.contains("\"status\":\"RUNNING\""))
        assertTrue(encoded.contains("\"idleActivityAtElapsedMs\":900"))
    }
}
