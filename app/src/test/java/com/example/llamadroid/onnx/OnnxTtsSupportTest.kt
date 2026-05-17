package com.example.llamadroid.onnx

import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.ONNX_CAPABILITY_TTS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory

class OnnxTtsSupportTest {

    @Test
    fun `supertonic bundle validation requires onnx files and voices`() {
        val root = createTempDirectory("supertonic-bundle-test").toFile()
        OnnxTtsBundleValidator.requiredRelativePaths.forEach { relative ->
            File(root, relative).apply {
                parentFile?.mkdirs()
                writeText("stub")
            }
        }
        File(root, "voice_styles/M1.json").apply {
            parentFile?.mkdirs()
            writeText("{}")
        }

        val result = OnnxTtsBundleValidator.validateDirectory(root)

        assertTrue(result.isValid)
        assertEquals(setOf(ONNX_CAPABILITY_TTS), result.supportedCapabilities)
    }

    @Test
    fun `supertonic bundle validation reports missing voice styles`() {
        val root = createTempDirectory("supertonic-missing-voices-test").toFile()
        OnnxTtsBundleValidator.requiredRelativePaths.forEach { relative ->
            File(root, relative).apply {
                parentFile?.mkdirs()
                writeText("stub")
            }
        }

        val result = OnnxTtsBundleValidator.validateDirectory(root)

        assertFalse(result.isValid)
        assertTrue(result.missingPaths.contains("voice_styles/*.json"))
    }

    @Test
    fun `supertonic catalog entry is downloadable as onnx tts`() {
        val entry = OnnxCatalog.entries.first { it.bundleId == "supertonic-3" }

        assertEquals(OnnxCatalogProvider.SUPERTONIC, entry.provider)
        assertEquals(ModelType.ONNX_TTS, entry.modelType)
        assertEquals(ONNX_PIPELINE_FAMILY_SUPERTONIC_TTS, entry.pipelineFamily)
        assertEquals(ONNX_ASSET_KIND_SUPERTONIC_CATALOG_BUNDLE, entry.assetKind)
        assertEquals("https://huggingface.co/Supertone/supertonic-3/tree/main", entry.downloadUrl)
        assertTrue(OnnxCatalog.supertonicRequiredFiles.any { it.relativePath == "onnx/vocoder.onnx" })
        assertTrue(OnnxCatalog.supertonicRequiredFiles.any { it.relativePath == "voice_styles/M1.json" })
        assertEquals(
            "https://huggingface.co/Supertone/supertonic-3/resolve/main/onnx/vocoder.onnx",
            OnnxCatalog.supertonicResolveUrl("onnx/vocoder.onnx")
        )
    }

    @Test
    fun `supertonic voices prefer M1 and language codes expose supported choices`() {
        val root = createTempDirectory("supertonic-voice-order-test").toFile()
        listOf("F1", "M2", "M1").forEach { voice ->
            File(root, "voice_styles/$voice.json").apply {
                parentFile?.mkdirs()
                writeText("{}")
            }
        }

        assertEquals(listOf("M1", "F1", "M2"), resolveSupertonicVoices(root))
        assertTrue(supertonicLanguageCodes.contains("en"))
        assertTrue(supertonicLanguageCodes.contains("es"))
        assertTrue(supertonicLanguageCodes.contains("ko"))
    }

    @Test
    fun `visible text extraction removes thinking and markdown artifacts`() {
        val content = """
            <think>private chain</think>
            Here is the answer.
            ```kotlin
            hiddenCode()
            ```
            ![preview](/tmp/image.png)
            [tool activity]: generated an image
        """.trimIndent()

        val text = stripTextForTts(content, thinking = "private chain")

        assertEquals("Here is the answer.", text)
    }

    @Test
    fun `text chunking keeps sentence chunks under max length when possible`() {
        val chunks = chunkText(
            "First sentence. Second sentence is a little longer. Third sentence closes it.",
            maxLen = 42
        )

        assertEquals(listOf("First sentence.", "Second sentence is a little longer.", "Third sentence closes it."), chunks)
    }

    @Test
    fun `metadata sidecar round trips for generated audio`() {
        val workspace = createTempDirectory("supertonic-metadata-test").toFile()
        val audioFile = File(workspace, "sample.wav").apply { writeText("wav") }
        val metadata = OnnxTtsMetadata(
            audioPath = audioFile.absolutePath,
            wavPath = audioFile.absolutePath,
            mp3Path = null,
            sourceName = "source.txt",
            textPreview = "Hello there.",
            modelName = "Supertonic 3",
            language = "en",
            voiceName = "M1",
            totalSteps = 8,
            speed = 1.05f,
            durationSeconds = 1.2f,
            sampleRate = 24000,
            createdAtEpochMs = 123L,
            mp3ConversionStatus = "wav_fallback"
        )

        OnnxTtsStorage.writeMetadata(audioFile, metadata)
        val loaded = OnnxTtsStorage.readMetadata(audioFile)

        assertEquals(metadata.audioPath, loaded?.audioPath)
        assertEquals(metadata.sourceName, loaded?.sourceName)
        assertEquals(metadata.voiceName, loaded?.voiceName)
        assertEquals(metadata.mp3ConversionStatus, loaded?.mp3ConversionStatus)
    }

    @Test
    fun `wav writer creates a pcm wav header`() {
        val workspace = createTempDirectory("supertonic-wav-test").toFile()
        val wav = File(workspace, "audio.wav")

        writeWav(wav, floatArrayOf(-1f, 0f, 1f), sampleRate = 24000)
        val bytes = wav.readBytes()

        assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVE", bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertEquals("data", bytes.copyOfRange(36, 40).toString(Charsets.US_ASCII))
        assertEquals(44 + 6, bytes.size)
    }

    @Test
    fun `epub and docx extraction cleanup readable text`() {
        val epub = zipBytes(
            "chapter.xhtml" to "<html><body><h1>Title</h1><p>Hello &amp; welcome.</p></body></html>"
        )
        val docx = zipBytes(
            "word/document.xml" to "<w:document><w:p><w:t>Docx text</w:t></w:p></w:document>"
        )

        assertTrue(extractEpubText(epub).contains("Title Hello & welcome."))
        assertEquals("Docx text", extractDocxText(docx))
    }

    private fun zipBytes(vararg entries: Pair<String, String>): ByteArray {
        val workspace = createTempDirectory("supertonic-zip-test").toFile()
        val zipFile = File(workspace, "input.zip")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return zipFile.readBytes()
    }
}
