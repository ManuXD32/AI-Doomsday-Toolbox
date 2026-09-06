package com.example.llamadroid.data.model.library

import com.example.llamadroid.data.db.ModelType
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelArtifactRecognizerTest {
    @Test
    fun `unsupported custom file remains family less`() {
        val file = Files.createTempFile("custom", ".bin").toFile()
        file.writeText("not a model")
        try {
            val result = ModelArtifactRecognizer.inspect(file)
            assertEquals(null, result.family)
            assertEquals(ModelLibraryErrorCode.RECOGNITION_FAILED, result.errorCode)
            assertTrue(result.requiresManualPromotion)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `tflite file remains manual until runtime profile is chosen`() {
        val file = Files.createTempFile("model", ".tflite").toFile()
        writeMinimalTflite(file)
        try {
            val result = ModelArtifactRecognizer.inspect(file)
            assertEquals(ModelFamily.LITERT, result.family)
            assertTrue(result.requiresManualPromotion)
            assertEquals(ModelLibraryErrorCode.MANUAL_PROMOTION_REQUIRED, result.errorCode)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `malformed onnx and whisper filename remain unrecognized`() {
        val onnx = Files.createTempFile("model", ".onnx").toFile()
        val whisper = Files.createTempFile("whisper-base", ".bin").toFile()
        onnx.writeBytes(ByteArray(32) { 1 })
        whisper.writeBytes(ByteArray(32) { 2 })
        try {
            assertEquals(ModelLibraryErrorCode.RECOGNITION_FAILED, ModelArtifactRecognizer.inspect(onnx).errorCode)
            assertEquals(null, ModelArtifactRecognizer.inspect(whisper).family)
        } finally {
            onnx.delete()
            whisper.delete()
        }
    }

    @Test
    fun `valid whisper cpp little endian magic still requires explicit family promotion`() {
        val file = Files.createTempFile("custom", ".bin").toFile()
        // whisper.cpp writes the uint32 GGML magic in native little-endian
        // order. The on-disk bytes are therefore "lmgg", not ASCII "ggml".
        writeWhisperCppHeader(file)
        try {
            val result = ModelArtifactRecognizer.validateForPromotion(file, ModelFamily.WHISPER)
            assertEquals(ModelFamily.WHISPER, result.family)
            assertTrue(result.isStructurallyValid)
            assertTrue(result.requiresManualPromotion)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `native whisper header is recognized independently of extension`() {
        val file = Files.createTempFile("download", ".bin").toFile()
        writeWhisperCppHeader(file)
        try {
            val result = ModelArtifactRecognizer.inspect(file)

            assertEquals(ModelFamily.WHISPER, result.family)
            assertEquals(ModelType.WHISPER.name, result.detectedType)
            assertTrue(result.isStructurallyValid)
            assertTrue(result.requiresManualPromotion)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `versioned quantized whisper header accepts q5 one`() {
        val file = Files.createTempFile("quantized", ".bin").toFile()
        writeWhisperCppHeader(file, hparamsFtype = 2_009, tensorType = 7)
        try {
            val result = ModelArtifactRecognizer.validateForPromotion(file, ModelFamily.WHISPER)

            assertTrue(result.isStructurallyValid)
            assertEquals(ModelFamily.WHISPER, result.family)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `ascii whisper magic is rejected as a non native header`() {
        val file = Files.createTempFile("custom", ".bin").toFile()
        file.writeBytes("ggml".toByteArray(Charsets.US_ASCII) + ByteArray(20))
        try {
            val result = ModelArtifactRecognizer.validateForPromotion(file, ModelFamily.WHISPER)
            assertFalse(result.isStructurallyValid)
            assertEquals(ModelLibraryErrorCode.RECOGNITION_FAILED, result.errorCode)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `truncated whisper cpp header is rejected even with native magic`() {
        val file = Files.createTempFile("custom", ".bin").toFile()
        file.writeBytes(
            ByteBuffer.allocate(56)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x67676d6c)
                .putInt(1).putInt(1).putInt(1).putInt(1).putInt(1)
                .putInt(1).putInt(1).putInt(1).putInt(1).putInt(1)
                .putInt(0).putInt(1).putInt(1)
                .array()
        )
        try {
            val result = ModelArtifactRecognizer.validateForPromotion(file, ModelFamily.WHISPER)
            assertFalse(result.isStructurallyValid)
            assertEquals(ModelLibraryErrorCode.RECOGNITION_FAILED, result.errorCode)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `litertlm uses its own versioned container prefix rather than tflite magic`() {
        val file = Files.createTempFile("model", ".litertlm").toFile()
        file.writeBytes(
            ByteBuffer.allocate(48)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("LITERTLM".toByteArray(Charsets.US_ASCII))
                .putInt(1) // major version
                .putInt(5) // minor version used by current published assets
                .putInt(0) // patch version
                .putInt(0) // required four-byte padding
                .putLong(48L) // header end offset
                .putInt(12) // metadata FlatBuffer root, relative to byte 32
                .putShort(6) // vtable size
                .putShort(4) // object size
                .putShort(0) // first vtable field offset
                .putShort(0) // four-byte alignment before the root table
                .putInt(8) // root table -> vtable distance
                .array()
        )
        try {
            val result = ModelArtifactRecognizer.inspect(file)
            assertEquals(ModelFamily.LITERT, result.family)
            assertTrue(result.isStructurallyValid)
            assertTrue(result.requiresManualPromotion)
            assertEquals(ModelLibraryErrorCode.MANUAL_PROMOTION_REQUIRED, result.errorCode)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `empty task archive is not a LiteRT model`() {
        val file = Files.createTempFile("model", ".task").toFile()
        file.writeBytes(
            ByteBuffer.allocate(22)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x06054b50)
                .putShort(0).putShort(0).putShort(0).putShort(0)
                .putInt(0).putInt(0).putShort(0)
                .array()
        )
        try {
            val result = ModelArtifactRecognizer.inspect(file)
            assertFalse(result.isStructurallyValid)
            assertEquals(ModelLibraryErrorCode.RECOGNITION_FAILED, result.errorCode)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `task archive with malformed nested tflite is not structurally promoted`() {
        val file = Files.createTempFile("malformed-task", ".task").toFile()
        ZipOutputStream(file.outputStream()).use { archive ->
            archive.putNextEntry(ZipEntry("models/model.tflite"))
            // A non-empty entry name is not evidence of a model. Keep the
            // payload deliberately malformed so the nested structural gate is
            // exercised instead of the ZIP container check alone.
            archive.write(ByteArray(64) { 0x7f })
            archive.closeEntry()
        }
        try {
            val result = ModelArtifactRecognizer.inspect(file)

            assertEquals(ModelFamily.LITERT, result.family)
            assertFalse(result.isStructurallyValid)
            assertEquals(ModelLibraryErrorCode.RECOGNITION_FAILED, result.errorCode)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `task archive with structurally valid nested tflite remains manual`() {
        val file = Files.createTempFile("valid-task", ".task").toFile()
        ZipOutputStream(file.outputStream()).use { archive ->
            archive.putNextEntry(ZipEntry("models/model.tflite"))
            archive.write(minimalTfliteBytes())
            archive.closeEntry()
        }
        try {
            val result = ModelArtifactRecognizer.inspect(file)

            assertEquals(ModelFamily.LITERT, result.family)
            assertTrue(result.isStructurallyValid)
            assertTrue(result.requiresManualPromotion)
            assertEquals(ModelLibraryErrorCode.MANUAL_PROMOTION_REQUIRED, result.errorCode)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `onnx protobuf header is recognized but never auto promoted`() {
        val file = Files.createTempFile("model", ".onnx").toFile()
        // ModelProto.ir_version = 1 and a non-empty GraphProto field.
        file.writeBytes(byteArrayOf(0x08, 0x01, 0x3a, 0x01, 0x00, 0x10, 0x00, 0x18, 0x00))
        try {
            val result = ModelArtifactRecognizer.inspect(file)
            assertEquals(ModelFamily.ONNX, result.family)
            assertTrue(result.isStructurallyValid)
            assertTrue(result.requiresManualPromotion)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `explicit video companion role accepts a sound container for manual promotion`() {
        val file = Files.createTempFile("motion-module", ".safetensors").toFile()
        writeSafeTensors(file, "unrelated.component.weight")
        try {
            val result = ModelArtifactRecognizer.validateForPromotion(file, ModelFamily.SD, "motionmodule")

            assertTrue(result.isStructurallyValid)
            assertTrue(result.requiresManualPromotion)
            assertEquals(ModelType.SD_MOTION_MODULE.name, result.detectedType)
            assertEquals(ModelLibraryErrorCode.MANUAL_PROMOTION_REQUIRED, result.errorCode)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `explicit video companion role rejects a high confidence contradictory artifact`() {
        val file = Files.createTempFile("motion-module", ".safetensors").toFile()
        // The native Wan signature makes the contradiction high confidence;
        // a generic diffusion tensor would only be medium-confidence evidence.
        writeSafeTensors(file, "model.diffusion_model.blocks.0.cross_attn.norm_k.weight")
        try {
            val result = ModelArtifactRecognizer.validateForPromotion(file, ModelFamily.SD, "motionmodule")

            assertFalse(result.isStructurallyValid)
            assertEquals(ModelLibraryErrorCode.RECOGNITION_FAILED, result.errorCode)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `explicit video companion role still rejects a malformed container`() {
        val file = Files.createTempFile("audio-vae", ".safetensors").toFile()
        file.writeBytes(
            ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(64L)
                .array()
        )
        try {
            val result = ModelArtifactRecognizer.validateForPromotion(file, ModelFamily.SD, "audioVAE")

            assertFalse(result.isStructurallyValid)
            assertEquals(ModelLibraryErrorCode.RECOGNITION_FAILED, result.errorCode)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `llm lora role follows structural adapter evidence`() {
        val file = Files.createTempFile("adapter", ".safetensors").toFile()
        writeSafeTensors(file, "lora_unet.block.lora_down.weight")
        try {
            val result = ModelArtifactRecognizer.validateForPromotion(file, ModelFamily.LLM, "lora")

            assertTrue(result.isStructurallyValid)
            assertEquals(ModelType.LORA.name, result.detectedType)
            assertEquals("lora", result.role)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `llm base role rejects structurally recognized adapter`() {
        val file = Files.createTempFile("adapter", ".safetensors").toFile()
        writeSafeTensors(file, "lora_unet.block.lora_down.weight")
        try {
            val result = ModelArtifactRecognizer.validateForPromotion(file, ModelFamily.LLM, "llm")

            assertFalse(result.isStructurallyValid)
            assertEquals(ModelLibraryErrorCode.RECOGNITION_FAILED, result.errorCode)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `structural video signature is the only path to automatic promotion`() {
        val file = Files.createTempFile("neutral", ".safetensors").toFile()
        writeSafeTensors(file, "model.diffusion_model.blocks.0.cross_attn.norm_k.weight")
        try {
            val result = ModelArtifactRecognizer.inspect(file)

            assertEquals(ModelFamily.SD, result.family)
            assertEquals(ModelType.SD_DIFFUSION.name, result.detectedType)
            assertEquals(ArtifactConfidence.HIGH, result.confidence)
            assertTrue(result.isStructurallyValid)
            assertFalse(result.requiresManualPromotion)
            assertEquals(null, result.errorCode)
        } finally {
            file.delete()
        }
    }

    private fun writeSafeTensors(file: java.io.File, tensorName: String) {
        val header = "{\"$tensorName\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,4]}}"
        val bytes = ByteArrayOutputStream().apply {
            write(
                ByteBuffer.allocate(8)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(header.toByteArray().size.toLong())
                    .array()
            )
            write(header.toByteArray())
            write(byteArrayOf(0, 0, 0, 0))
        }
        file.writeBytes(bytes.toByteArray())
    }

    private fun writeMinimalTflite(file: java.io.File) {
        file.writeBytes(minimalTfliteBytes())
    }

    private fun minimalTfliteBytes(): ByteArray =
        ByteBuffer.allocate(20)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(16) // root table offset
            .put("TFL3".toByteArray(Charsets.US_ASCII))
            .putShort(6) // vtable size
            .putShort(4) // object size
            .putInt(8) // root table -> vtable distance
            .putInt(8) // table vtable distance at root
            .array()

    private fun writeWhisperCppHeader(
        file: java.io.File,
        hparamsFtype: Int = 0,
        tensorType: Int = 0
    ) {
        file.writeBytes(
            ByteBuffer.allocate(90)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x67676d6c) // GGML_FILE_MAGIC, stored little-endian
                .putInt(1) // vocabulary
                .putInt(1) // audio context
                .putInt(1) // audio state
                .putInt(1) // audio heads
                .putInt(1) // audio layers
                .putInt(1) // text context
                .putInt(1) // text state
                .putInt(1) // text heads
                .putInt(1) // text layers
                .putInt(1) // mel bins
                .putInt(hparamsFtype) // GGML ftype (2009 = qnt v2 + Q5_1)
                .putInt(1) // filter rows
                .putInt(1) // filter columns
                .putFloat(0f) // one filter value
                .putInt(1) // one tokenizer entry
                .putInt(1).put('A'.code.toByte())
                .putInt(1) // tensor rank
                .putInt(1) // tensor name length
                .putInt(tensorType) // tensor type (7 = Q5_1)
                .putInt(1) // tensor dimension
                .put('x'.code.toByte())
                .putFloat(0f) // one payload value
                .array()
        )
    }
}
