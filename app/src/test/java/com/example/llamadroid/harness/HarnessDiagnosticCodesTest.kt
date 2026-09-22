package com.example.llamadroid.harness

import org.junit.Assert.assertEquals
import org.junit.Test

class HarnessDiagnosticCodesTest {
    @Test fun unknownBackendCodesCannotSmuggleContentIntoTheMetadataJournal() {
        assertEquals("HARNESS_STOP_TIMEOUT", harnessDiagnosticErrorClass("HARNESS_STOP_TIMEOUT"))
        assertEquals("IOException", harnessDiagnosticErrorClass("IOException"))
        for (value in listOf("private prompt content", "HARNESS_PRIVATE_CREDENTIAL_123456", "sk-secret-value", "gateway/private-argument")) {
            assertEquals("RemoteError", harnessDiagnosticErrorClass(value))
        }
    }
}
