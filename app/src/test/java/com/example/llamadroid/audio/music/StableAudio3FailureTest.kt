package com.example.llamadroid.audio.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class StableAudio3FailureTest {
    @Test fun `classified failures preserve the real native API operation across IPC`() {
        val native = StableAudio3Failure.fromNative("litert_invalid_data_failed:501:load_model_from_buffer", "conditioning")
        val received = StableAudio3Failure.fromWire(native.code, native.stage, native.nativeStatus, native.operation)
        assertEquals("litert_invalid_data_failed", received.code)
        assertEquals("load_model_from_buffer", received.operation)
        assertEquals(501, received.nativeStatus)
    }

    @Test fun `operation field accepts only known API names`() {
        for (operation in listOf("/private/prompt", "private_prompt", "load_model:secret")) {
            val failure = StableAudio3Failure.fromNative("litert_invalid_data_failed:501:$operation", "conditioning")
            assertEquals("invalid_data", failure.operation)
            assertFalse(failure.diagnosticMetadata().contains(operation))
        }
        assertNull(StableAudio3Failure.fromWire("output_invalid", "decoding", 501, "load_model").operation)
    }

    @Test fun `native failure retains operation status and stage across worker boundary`() {
        val native = StableAudio3Failure.fromNative("litert_create_input_buffer_failed:3", "conditioning")
        val received = StableAudio3Failure.fromWire(native.code, native.stage, native.nativeStatus)
        assertEquals("litert_create_input_buffer_failed", received.code)
        assertEquals("create_input_buffer", received.operation)
        assertEquals(3, received.nativeStatus)
        assertEquals("conditioning", received.stage)
    }

    @Test fun `private messages and unknown stages never enter diagnostic metadata`() {
        val failure = StableAudio3Failure.fromNative("failed loading /private/voice.wav", "private prompt")
        assertEquals("native_pipeline_failed", failure.code)
        assertEquals("starting", failure.stage)
        assertNull(failure.nativeStatus)
        assertFalse(failure.diagnosticMetadata().contains("private"))
    }

    @Test fun `status belongs only to a LiteRT operation failure`() {
        assertNull(StableAudio3Failure.fromNative("output_invalid:7", "decoding").nativeStatus)
        assertNull(StableAudio3Failure.fromNative("litert_run_model_failed:secret", "sampling").nativeStatus)
        assertEquals(-1, StableAudio3Failure.fromNative("litert_run_model_failed:-1", "sampling").nativeStatus)
    }

    @Test fun `LiteRT file, invalid data, and memory statuses keep separate repair classes`() {
        val file = StableAudio3Failure.fromNative("litert_file_io_failed:500", "conditioning")
        val invalid = StableAudio3Failure.fromNative("litert_invalid_data_failed:501", "loading")
        val memory = StableAudio3Failure.fromNative("litert_memory_failed:2", "loading")
        assertEquals("file_io", file.operation)
        assertEquals(500, file.nativeStatus)
        assertEquals("invalid_data", invalid.operation)
        assertEquals(501, invalid.nativeStatus)
        assertEquals("memory", memory.operation)
        assertEquals(2, memory.nativeStatus)
    }
}
