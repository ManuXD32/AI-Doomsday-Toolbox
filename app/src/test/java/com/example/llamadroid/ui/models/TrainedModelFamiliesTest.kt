package com.example.llamadroid.ui.models

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrainedModelFamiliesTest {
    private fun m(name: String, origin: String? = null, from: String? = null) =
        ModelEntity(name, "/m/$name", 1L, ModelType.LLM, "", true, origin = origin, derivedFrom = from)

    @Test
    fun ordinaryModelsProduceNoFamilies() {
        assertEquals(emptyList<TrainedModelFamily>(), trainedModelFamilies(listOf(m("a.gguf"))))
    }

    @Test
    fun exportAndQuantizedChainShareOneFamily() {
        val families = trainedModelFamilies(listOf(
            m("b-F16.gguf", MODEL_ORIGIN_BRANCHY_EXPORT),
            m("b-Q8_0.gguf", MODEL_ORIGIN_QUANTIZED, "b-F16.gguf"),
            m("b-Q4_K_M.gguf", MODEL_ORIGIN_QUANTIZED, "b-Q8_0.gguf")
        ))
        assertEquals(1, families.size)
        assertEquals("b-F16.gguf", families[0].rootFilename)
        assertEquals("b-F16.gguf", families[0].members.first().filename)
        assertEquals(3, families[0].members.size)
    }

    @Test
    fun originalOutsideLineageSetBecomesRoot() {
        val orig = m("hf-F16.gguf")
        val families = trainedModelFamilies(listOf(orig, m("hf-Q4_K_M.gguf", MODEL_ORIGIN_QUANTIZED, "hf-F16.gguf")))
        assertEquals("hf-F16.gguf", families[0].rootFilename)
        assertEquals(orig, families[0].root)
        assertEquals(1, families[0].members.size)
    }

    @Test
    fun missingOriginalKeepsFamilyWithNullRootAndSurvivesCycles() {
        val gone = trainedModelFamilies(listOf(m("x-Q4_0.gguf", MODEL_ORIGIN_QUANTIZED, "gone.gguf")))
        assertNull(gone[0].root)
        val cyc = trainedModelFamilies(listOf(
            m("p.gguf", MODEL_ORIGIN_QUANTIZED, "q.gguf"), m("q.gguf", MODEL_ORIGIN_QUANTIZED, "p.gguf")
        ))
        assertEquals(1, cyc.size)
    }

    @Test
    fun quantTagParsing() {
        assertEquals("Q4_K_M", lineageQuantTag("m-Q4_K_M.gguf"))
        assertNull(lineageQuantTag("plain.gguf"))
        assertEquals("Q8_0", lineageQuantTag("m-Q8_0-2.gguf"))
    }
}
