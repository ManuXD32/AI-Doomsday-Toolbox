package com.example.llamadroid.ui.ai.llama.servers

import com.example.llamadroid.service.LlamaServerLaunchProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerProfileEditorLogicTest {
    private val exists: (String) -> Boolean = { true }
    private val argsOk: (String, String) -> Boolean = { _, _ -> true }
    private val profile = LlamaServerLaunchProfile(modelPath = "/m/a.gguf")

    @Test
    fun `valid profile with blank port has no errors`() {
        assertTrue(validateProfileEditor(profile, "", emptySet(), exists, argsOk).isEmpty())
    }

    @Test
    fun `missing model blank model vision without projector and bad port are reported`() {
        assertEquals(
            setOf(ProfileEditorError.MODEL_NOT_SELECTED),
            validateProfileEditor(LlamaServerLaunchProfile(), "", emptySet(), exists, argsOk)
        )
        assertTrue(ProfileEditorError.MODEL_FILE_MISSING in
            validateProfileEditor(profile, "", emptySet(), { false }, argsOk))
        assertTrue(ProfileEditorError.MMPROJ_FILE_MISSING in
            validateProfileEditor(profile.copy(visionEnabled = true), "", emptySet(), exists, argsOk))
        assertTrue(ProfileEditorError.PORT_INVALID in validateProfileEditor(profile, "99999", emptySet(), exists, argsOk))
        assertTrue(ProfileEditorError.PORT_IN_USE in validateProfileEditor(profile, "8081", setOf(8081), exists, argsOk))
        assertTrue(ProfileEditorError.FLAGS_INVALID in
            validateProfileEditor(profile, "", emptySet(), exists) { _, _ -> false })
        assertTrue(ProfileEditorError.DRAFT_FILE_MISSING in
            validateProfileEditor(profile.copy(draftModelPath = "/m/d.gguf"), "", emptySet(), { it == "/m/a.gguf" }, argsOk))
    }

    @Test
    fun `save profile is forced to loopback and takes the chosen port`() {
        val saved = profileForSave(profile.copy(host = "0.0.0.0", serverPort = 8080), 9100)
        assertEquals("127.0.0.1", saved.host)
        assertEquals(9100, saved.serverPort)
        assertEquals(8080, profileForSave(profile.copy(serverPort = 8080), null).serverPort)
    }

    @Test
    fun `fingerprint changes only when the profile changes`() {
        assertEquals(profileFingerprint(profile), profileFingerprint(profile.copy()))
        assertFalse(profileFingerprint(profile) == profileFingerprint(profile.copy(contextSize = 1234)))
    }

    @Test
    fun `suggested name follows the model until the user types one`() {
        assertEquals("Qwen3-4B", suggestedServerName("", false, "/m/Qwen3-4B.gguf"))
        assertEquals("Mine", suggestedServerName("Mine", true, "/m/Qwen3-4B.gguf"))
        assertEquals("Qwen3-4B", suggestedServerName("", true, "/m/Qwen3-4B.gguf"))
    }
}
