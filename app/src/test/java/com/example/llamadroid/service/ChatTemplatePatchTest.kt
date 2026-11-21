package com.example.llamadroid.service

import com.example.llamadroid.service.ram.GgufModelShapeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatTemplatePatchTest {
    private val gate = "loop.index0 > ns.last_query_index"
    private val patchedGate = "(loop.index0 > ns.last_query_index or (preserve_thinking is defined and preserve_thinking))"

    private fun qwen(): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("chat_templates/qwen3_5.jinja")) { "golden template missing" }
            .use { String(it.readBytes(), Charsets.UTF_8) }

    private fun le(size: Int, fill: (ByteBuffer) -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).also(fill).array()

    private fun gguf(template: String?): ByteArray {
        val body = ByteArrayOutputStream()
        fun str(s: String) {
            val bytes = s.toByteArray()
            body.write(le(8) { it.putLong(bytes.size.toLong()) }); body.write(bytes)
        }
        var count = 0L
        str("general.architecture"); body.write(le(4) { it.putInt(8) }); str("qwen35"); count++
        if (template != null) {
            str("tokenizer.chat_template"); body.write(le(4) { it.putInt(8) }); str(template); count++
        }
        str("tokenizer.ggml.tokens"); body.write(le(4) { it.putInt(9) })
        body.write(le(4) { it.putInt(8) }); body.write(le(8) { it.putLong(1L) }); str("a"); count++
        val out = ByteArrayOutputStream()
        out.write("GGUF".toByteArray()); out.write(le(4) { it.putInt(3) })
        out.write(le(8) { it.putLong(0L) }); out.write(le(8) { it.putLong(count) })
        out.write(body.toByteArray())
        return out.toByteArray()
    }

    @Test
    fun goldenQwenTemplateOnlyChangesTheReasoningGate() {
        val original = qwen()
        val patched = checkNotNull(ChatTemplatePatch.patch(original))
        assertEquals(original.replace(gate, patchedGate), patched)
        assertTrue(patched.contains("preserve_thinking is defined and preserve_thinking"))
        assertFalse(patched.replace(patchedGate, "").contains(gate))
        val originalLines = original.lines()
        val patchedLines = patched.lines()
        assertEquals(originalLines.size, patchedLines.size)
        assertEquals(1, originalLines.indices.count { originalLines[it] != patchedLines[it] })
    }

    @Test
    fun templatesWithoutTheGateOrAlreadySupportingTheFlagAreLeftAlone() {
        assertNull(ChatTemplatePatch.patch("{% for m in messages %}<|{{ m.role }}|>{{ m.content }}{% endfor %}"))
        assertNull(ChatTemplatePatch.patch(qwen() + "{# preserve_thinking #}"))
        assertNull(ChatTemplatePatch.patch(""))
        assertNull(ChatTemplatePatch.patch(null))
    }

    @Test
    fun readsTheTemplateFromTheGgufHeaderAndRespectsTheSizeCap() {
        val template = qwen()
        assertEquals(template, GgufModelShapeReader.readChatTemplate(ByteArrayInputStream(gguf(template))))
        assertNull(GgufModelShapeReader.readChatTemplate(ByteArrayInputStream(gguf(template)), maxChars = 100))
        assertNull(GgufModelShapeReader.readChatTemplate(ByteArrayInputStream(gguf(null))))
        assertNull(GgufModelShapeReader.readChatTemplate(ByteArrayInputStream("nope".toByteArray())))
    }

    @Test
    fun preparedTemplateFileIsWrittenOnceAndNullForOtherTemplates() {
        val context = RuntimeEnvironment.getApplication()
        val dir = File(context.cacheDir, "tpl-test").apply { deleteRecursively(); mkdirs() }
        val qwenModel = File(dir, "qwen.gguf").apply { writeBytes(gguf(qwen())) }
        val first = checkNotNull(ChatTemplatePatch.preparedTemplateFile(context, qwenModel.absolutePath))
        assertTrue(first.parentFile!!.name == "chat_templates")
        assertEquals(ChatTemplatePatch.patch(qwen()), first.readText())
        val second = ChatTemplatePatch.preparedTemplateFile(context, qwenModel.absolutePath)
        assertEquals(first, second)
        assertTrue(first.parentFile!!.listFiles().orEmpty().none { it.name.endsWith(".tmp") })

        val other = File(dir, "other.gguf").apply { writeBytes(gguf("{{ messages }}")) }
        assertNull(ChatTemplatePatch.preparedTemplateFile(context, other.absolutePath))
        assertNull(ChatTemplatePatch.preparedTemplateFile(context, File(dir, "missing.gguf").absolutePath))
    }
}
