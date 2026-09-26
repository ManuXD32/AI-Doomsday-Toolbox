package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentLocalPatchSupportTest {
    @Test
    fun appliesFocusedPatchAndPreservesTrailingNewline() {
        val patch = """
            --- a/src/app.js
            +++ b/src/app.js
            @@ -1,2 +1,3 @@
             const a = 1;
            -const b = 2;
            +const b = 3;
            +export { a, b };
        """.trimIndent()
        val result = AgentLocalPatchSupport.apply(patch) { "const a = 1;\nconst b = 2;\n" }.single()
        assertEquals("const a = 1;\nconst b = 3;\nexport { a, b };\n", result.content)
    }

    @Test
    fun rejectsMismatchedContextAndEscapingPaths() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentLocalPatchSupport.apply("--- a/x\n+++ b/x\n@@ -1 +1 @@\n-old\n+new") { "different\n" }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentLocalPatchSupport.apply("--- a/../x\n+++ b/../x\n@@ -1 +1 @@\n-old\n+new") { "old\n" }
        }
    }
}
