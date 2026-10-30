package com.example.llamadroid.ui.easy

import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.sd.SdArtifactRole
import com.example.llamadroid.sd.SdMainLayout
import com.example.llamadroid.sd.SdModelFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EasyImportKindsTest {
    private val gguf = "GGUF\u0003\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
    private val safetensors = byteArrayOf(10, 0, 0, 0, 0, 0, 0, 0, '{'.code.toByte(), '"'.code.toByte())
    private val whisperBin = byteArrayOf(0x6c, 0x6d, 0x67, 0x67, 1, 0, 0, 0)

    @Test fun `gguf magic wins over extension`() {
        assertEquals(EasyFileKind.GGUF, detectEasyFileKind("model.bin", gguf))
        assertEquals(EasyFileKind.GGUF, detectEasyFileKind("model.gguf", gguf))
    }

    @Test fun `gguf extension with wrong magic is unknown`() {
        assertEquals(EasyFileKind.UNKNOWN, detectEasyFileKind("model.gguf", "<html>".toByteArray()))
    }

    @Test fun `empty header trusts the extension`() {
        assertEquals(EasyFileKind.GGUF, detectEasyFileKind("x.GGUF", ByteArray(0)))
        assertEquals(EasyFileKind.GGML_BIN, detectEasyFileKind("ggml-base.bin", ByteArray(0)))
    }

    @Test fun `safetensors checks the json header`() {
        assertEquals(EasyFileKind.SAFETENSORS, detectEasyFileKind("sd.safetensors", safetensors))
        assertEquals(EasyFileKind.UNKNOWN, detectEasyFileKind("sd.safetensors", ByteArray(16) { 1 }))
    }

    @Test fun `whisper bin and zip detection`() {
        assertEquals(EasyFileKind.GGML_BIN, detectEasyFileKind("ggml-base.bin", whisperBin))
        assertEquals(EasyFileKind.UNKNOWN, detectEasyFileKind("other.bin", ByteArray(8) { 7 }))
        assertEquals(EasyFileKind.ZIP, detectEasyFileKind("bundle.zip", "PK\u0003\u0004".toByteArray()))
        assertEquals(EasyFileKind.CKPT, detectEasyFileKind("old.ckpt", "PK\u0003\u0004".toByteArray()))
    }

    @Test fun `chat slot rejects projectors and non gguf files`() {
        assertEquals(EasyImportVerdict.OK, easyImportVerdict(EasyImportSlot.CHAT_MODEL, "Qwen3-4B-Q4.gguf", gguf))
        assertEquals(EasyImportVerdict.PROJECTOR_AS_MODEL, easyImportVerdict(EasyImportSlot.CHAT_MODEL, "mmproj-F16.gguf", gguf))
        assertEquals(EasyImportVerdict.WRONG_FILE, easyImportVerdict(EasyImportSlot.CHAT_MODEL, "a.safetensors", safetensors))
        assertEquals(EasyImportVerdict.OK, easyImportVerdict(EasyImportSlot.CHAT_PROJECTOR, "mmproj-F16.gguf", gguf))
    }

    @Test fun `per slot verdicts`() {
        assertEquals(EasyImportVerdict.OK, easyImportVerdict(EasyImportSlot.SD_MODEL, "sd.safetensors", safetensors))
        assertEquals(EasyImportVerdict.OK, easyImportVerdict(EasyImportSlot.SD_MODEL, "sd.gguf", gguf))
        assertEquals(EasyImportVerdict.WRONG_FILE, easyImportVerdict(EasyImportSlot.SD_MODEL, "ggml.bin", whisperBin))
        assertEquals(EasyImportVerdict.OK, easyImportVerdict(EasyImportSlot.WHISPER, "ggml-base.bin", whisperBin))
        assertEquals(EasyImportVerdict.WRONG_FILE, easyImportVerdict(EasyImportSlot.WHISPER, "m.gguf", gguf))
        assertEquals(EasyImportVerdict.OK, easyImportVerdict(EasyImportSlot.ONNX_ARCHIVE, "b.zip", "PK".toByteArray()))
    }

    @Test fun `sd type from inspection`() {
        assertEquals(ModelType.SD_CHECKPOINT, easySdImportType(SdArtifactRole.FULL_MODEL, SdMainLayout.FULL_MODEL))
        assertEquals(ModelType.SD_DIFFUSION, easySdImportType(SdArtifactRole.STANDALONE_DIFFUSION, SdMainLayout.UNKNOWN))
        assertEquals(ModelType.SD_CHECKPOINT, easySdImportType(null, SdMainLayout.UNKNOWN))
        assertNull(easySdImportType(SdArtifactRole.VAE, SdMainLayout.COMPONENT))
        assertNull(easySdImportType(SdArtifactRole.LORA, SdMainLayout.UNKNOWN))
    }

    @Test fun `video families are not image families`() {
        assertTrue(isEasyImageFamily(null))
        assertTrue(isEasyImageFamily(SdModelFamily.FLUX_1))
        assertFalse(isEasyImageFamily(SdModelFamily.WAN))
    }
}
