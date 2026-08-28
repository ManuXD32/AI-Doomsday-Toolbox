package com.example.llamadroid.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaRuntimeOwnerStoreTest {
    @Test
    fun `only complete positive owner identities are recoverable`() {
        assertTrue(
            llamaRuntimeOwnerRecordIsValid(
                LlamaRuntimeOwnerRecord(
                    pid = 1234,
                    port = 8080,
                    lifecycleGeneration = 4L,
                    processStartTimeTicks = 98765L
                )
            )
        )
        assertFalse(
            llamaRuntimeOwnerRecordIsValid(
                LlamaRuntimeOwnerRecord(
                    pid = -1,
                    port = 8080,
                    lifecycleGeneration = 4L,
                    processStartTimeTicks = 98765L
                )
            )
        )
        assertFalse(
            llamaRuntimeOwnerRecordIsValid(
                LlamaRuntimeOwnerRecord(
                    pid = 1234,
                    port = 70000,
                    lifecycleGeneration = 4L,
                    processStartTimeTicks = 98765L
                )
            )
        )
        assertFalse(
            llamaRuntimeOwnerRecordIsValid(
                LlamaRuntimeOwnerRecord(
                    pid = 1234,
                    port = 8080,
                    lifecycleGeneration = 0L,
                    processStartTimeTicks = 0L
                )
            )
        )
    }
}
