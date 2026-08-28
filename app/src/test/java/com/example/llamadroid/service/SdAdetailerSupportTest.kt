package com.example.llamadroid.service

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SdAdetailerSupportTest {
    @Test fun `serialization is deterministic and locale independent`() {
        val detector = compatibleDetectorFile()
        try {
            val config = SdADetailerConfig(
                modelPath = detector.absolutePath,
                confidence = 0.35f,
                maskBlur = 6,
                advancedArgs = "sort_by=area"
            )
            val first = serializeSdADetailerExtraArgs(config)
            val second = serializeSdADetailerExtraArgs(config)
            assertEquals(first, second)
            assertTrue(first.contains("confidence=0.35"))
            assertTrue(first.contains("inpaint_padding=32"))
            assertFalse(first.startsWith("{"))
            assertFalse(first.contains("0,35"))
        } finally { detector.delete() }
    }

    @Test(expected = SdADetailerConfigurationException::class)
    fun `advanced args cannot override typed keys`() {
        val detector = compatibleDetectorFile()
        try {
            serializeSdADetailerExtraArgs(
                SdADetailerConfig(modelPath = detector.absolutePath, advancedArgs = "confidence=0.9")
            )
        } finally { detector.delete() }
    }

    @Test fun `ordinary safetensors file is rejected before native launch`() {
        val detector = File.createTempFile("detector", ".safetensors")
        try {
            detector.writeText("not a converted detector")
            assertFalse(isCompatibleSdADetailerDetector(detector))
            assertThrows(SdADetailerConfigurationException::class.java) {
                validateSdADetailerConfig(SdADetailerConfig(modelPath = detector.absolutePath))
            }
        } finally { detector.delete() }
    }

    private fun compatibleDetectorFile(): File {
        val detector = File.createTempFile("detector", ".safetensors")
        val header = """{"__metadata__":{"yolov8.variant":"detect"},"model.0.conv.weight":{},"model.22.cv2.0.2.weight":{},"model.22.cv3.0.2.weight":{}}"""
        detector.outputStream().use { output ->
            output.write(ByteBuffer.allocate(Long.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(header.toByteArray().size.toLong()).array())
            output.write(header.toByteArray())
            output.write(byteArrayOf(0, 0))
        }
        return detector
    }
}
