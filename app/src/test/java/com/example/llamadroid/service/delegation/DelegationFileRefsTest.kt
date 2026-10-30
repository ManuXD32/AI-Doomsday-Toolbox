package com.example.llamadroid.service.delegation

import com.example.llamadroid.data.model.LlamaServerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegationFileRefsTest {
    @Test
    fun parsesAttachmentAndImageRefs() {
        assertEquals("f" to 12L, DelegationFileRefs.parse("f12"))
        assertEquals("img" to 55L, DelegationFileRefs.parse(" IMG55 "))
        assertNull(DelegationFileRefs.parse("x1"))
        assertNull(DelegationFileRefs.parse("f"))
        assertNull(DelegationFileRefs.parse("f1/../2"))
    }

    @Test
    fun resultNamesAreSanitized() {
        assertEquals("result_7_report.pdf", DelegationFileRefs.resultFileName(7, "out/report.pdf"))
        assertEquals("result_7_a_b_.txt", DelegationFileRefs.resultFileName(7, "a?b*.txt"))
        assertTrue(DelegationFileRefs.resultFileName(7, "x".repeat(300) + ".md").length < 120)
        assertEquals("result_7_file", DelegationFileRefs.resultFileName(7, "..."))
    }

    private fun row(engine: String) = LlamaServerEntity(id = 1, name = "s", host = "h", port = 1, engine = engine)

    @Test
    fun subagentServerValidation() {
        val same = DelegationServerChoice.SameAsChat
        val litert = row(LlamaServerEntity.ENGINE_LITERT_LM)
        val rejectedLiteRt = DelegationServerValidation.validate(same, litert, null)
        assertTrue(rejectedLiteRt is DelegationServerValidation.Result.Rejected)
        assertTrue(DelegationServerValidation.validate(same, row(LlamaServerEntity.ENGINE_OLLAMA), null)
            is DelegationServerValidation.Result.Rejected)
        assertTrue(DelegationServerValidation.validate(same, row(LlamaServerEntity.ENGINE_LLAMA_SERVER), null)
            is DelegationServerValidation.Result.Ok)
        // LiteRT chat with an explicit llama-server subagent is allowed.
        val explicit = DelegationServerChoice.Server(2)
        assertTrue(DelegationServerValidation.validate(explicit, litert, row(LlamaServerEntity.ENGINE_LLAMA_SWAP))
            is DelegationServerValidation.Result.Ok)
        assertTrue(DelegationServerValidation.validate(explicit, litert, null) is DelegationServerValidation.Result.Rejected)
    }
}
