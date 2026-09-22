package com.example.llamadroid.harness

import com.example.llamadroid.harness.runtime.HarnessEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HarnessAgentRouteTest {
    @Test
    fun sessionExportUsesOfficialPathAndQuery() {
        val endpoint = HarnessEndpoint("http://127.0.0.1:43127", "dsh-session=token")

        assertEquals(
            "http://127.0.0.1:43127/api/session.export?sessionId=session+one%2Fchild&includeDescendants=true",
            buildHarnessSessionLogExportUrl(endpoint, "session one/child")
        )
    }

    @Test
    fun sessionExportRejectsAnEmptySessionIdentity() {
        val endpoint = HarnessEndpoint("http://127.0.0.1:43127", "dsh-session=token")

        assertThrows(IllegalArgumentException::class.java) {
            buildHarnessSessionLogExportUrl(endpoint, "")
        }
    }
}
