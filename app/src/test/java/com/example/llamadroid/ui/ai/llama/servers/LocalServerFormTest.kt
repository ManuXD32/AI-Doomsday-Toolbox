package com.example.llamadroid.ui.ai.llama.servers

import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.repository.UnifiedServer
import com.example.llamadroid.data.repository.UnifiedServerKind
import com.example.llamadroid.data.repository.UnifiedServerStatus
import com.example.llamadroid.service.LlamaLoadMode
import com.example.llamadroid.service.LlamaLoraSpec
import com.example.llamadroid.service.LlamaServerLaunchProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalServerFormTest {
    private val everythingExists: (String) -> Boolean = { true }
    private val argsOk: (String, String) -> Boolean = { _, _ -> true }

    private fun baseForm() = LocalServerForm.fromProfile(
        LlamaServerLaunchProfile(modelPath = "/models/Qwen3-4B.gguf", contextSize = 4096, threads = 4),
        name = "Qwen", port = null, allowWearStart = false
    )

    private fun server(cardId: Long?, rowId: Long?, kind: UnifiedServerKind, port: Int) = UnifiedServer(
        rowId = rowId, cardId = cardId, kind = kind, name = "s", engine = LlamaServerEntity.ENGINE_LLAMA_SERVER,
        modelName = null, host = "127.0.0.1", port = port, status = UnifiedServerStatus.STOPPED, errorCode = null,
        boundChatCount = 0, allowWearStart = false, isEasy = false
    )

    @Test
    fun validFormHasNoErrors() {
        assertTrue(validateLocalForm(baseForm(), emptySet(), everythingExists, argsOk).isEmpty())
    }

    @Test
    fun missingModelAndMissingFileAreDistinct() {
        val blank = baseForm().copy(modelPath = "")
        assertEquals(
            setOf(LocalFormError.MODEL_NOT_SELECTED),
            validateLocalForm(blank, emptySet(), everythingExists, argsOk)
        )
        assertEquals(
            setOf(LocalFormError.MODEL_FILE_MISSING),
            validateLocalForm(baseForm(), emptySet(), { false }, argsOk)
        )
    }

    @Test
    fun portIsOptionalButRangeAndCollisionChecked() {
        val form = baseForm()
        assertTrue(validateLocalForm(form.copy(portText = ""), setOf(8080), everythingExists, argsOk).isEmpty())
        assertEquals(
            setOf(LocalFormError.PORT_INVALID),
            validateLocalForm(form.copy(portText = "70000"), emptySet(), everythingExists, argsOk)
        )
        assertEquals(
            setOf(LocalFormError.PORT_INVALID),
            validateLocalForm(form.copy(portText = "0"), emptySet(), everythingExists, argsOk)
        )
        assertEquals(
            setOf(LocalFormError.PORT_IN_USE),
            validateLocalForm(form.copy(portText = "8081"), setOf(8081), everythingExists, argsOk)
        )
    }

    @Test
    fun numericBoundsAreEnforced() {
        val form = baseForm()
        assertTrue(LocalFormError.CONTEXT_INVALID in
            validateLocalForm(form.copy(contextText = "64"), emptySet(), everythingExists, argsOk))
        assertTrue(LocalFormError.CONTEXT_INVALID in
            validateLocalForm(form.copy(contextText = ""), emptySet(), everythingExists, argsOk))
        assertTrue(LocalFormError.THREADS_INVALID in
            validateLocalForm(form.copy(threadsText = "0"), emptySet(), everythingExists, argsOk))
        assertTrue(LocalFormError.BATCH_INVALID in
            validateLocalForm(form.copy(batchText = "0"), emptySet(), everythingExists, argsOk))
        assertTrue(LocalFormError.BATCH_INVALID in
            validateLocalForm(form.copy(parallelText = "0"), emptySet(), everythingExists, argsOk))
        // Blank optional fields are fine; sleep-idle may be 0 (llama-server treats it as immediate).
        assertTrue(validateLocalForm(form.copy(sleepIdleText = "0"), emptySet(), everythingExists, argsOk).isEmpty())
    }

    @Test
    fun visionAndDraftNeedTheirFiles() {
        val form = baseForm().copy(mmprojPath = "/m/mmproj.gguf", visionEnabled = true, draftModelPath = "/m/draft.gguf")
        val onlyModel: (String) -> Boolean = { it == "/models/Qwen3-4B.gguf" }
        assertEquals(
            setOf(LocalFormError.MMPROJ_FILE_MISSING, LocalFormError.DRAFT_FILE_MISSING),
            validateLocalForm(form, emptySet(), onlyModel, argsOk)
        )
        // A projector that is present but switched off is not validated.
        assertTrue(validateLocalForm(form.copy(visionEnabled = false, draftModelPath = null), emptySet(), onlyModel, argsOk).isEmpty())
    }

    @Test
    fun invalidArgumentsAreReported() {
        assertEquals(
            setOf(LocalFormError.FLAGS_INVALID),
            validateLocalForm(baseForm().copy(customFlags = "--x \"unterminated"), emptySet(), everythingExists) { f, _ -> !f.contains('"') }
        )
    }

    @Test
    fun applyOverlaysFormAndKeepsHiddenFields() {
        val base = LlamaServerLaunchProfile(
            modelPath = "/old.gguf", serverPort = 9001, ngramModNMatch = 31, nativeBinarySelection = "cpu-auto",
            idleStopSeconds = 600, host = "127.0.0.1", kvCacheTypeK = "q8_0", kvCacheTypeV = "q4_0", kvCacheEnabled = true
        )
        val form = LocalServerForm.fromProfile(base, "n", 9001, false).copy(
            modelPath = "/new.gguf",
            contextText = "16384",
            threadsText = "6",
            batchText = "256",
            physicalBatchText = "128",
            parallelText = "2",
            loadMode = LlamaLoadMode.NONE.value,
            flashAttention = true,
            loras = listOf(LlamaLoraSpec("/l.gguf", 0.5f)),
            customFlags = " --slots ",
            commandTemplate = ""
        )
        val out = applyLocalForm(base, form)
        assertEquals("/new.gguf", out.modelPath)
        assertEquals(16384, out.contextSize)
        assertEquals(6, out.threads)
        assertEquals(256, out.batchSize)
        assertEquals(128, out.physicalBatchSize)
        assertEquals(2, out.parallel)
        assertEquals(LlamaLoadMode.NONE.value, out.loadMode)
        assertTrue(out.noMmap)
        assertTrue(out.flashAttention)
        assertEquals("/l.gguf", out.loraPath)
        assertEquals(listOf(LlamaLoraSpec("/l.gguf", 0.5f)), out.loras)
        assertEquals("--slots", out.customFlags)
        assertNull(out.commandTemplate)
        // Not exposed by the form: preserved.
        assertEquals(31, out.ngramModNMatch)
        assertEquals("cpu-auto", out.nativeBinarySelection)
        assertEquals(600, out.idleStopSeconds)
        // Mixed K/V quantization is shown as custom and left untouched.
        assertEquals(LocalServerForm.KV_QUANT_CUSTOM, form.kvQuant)
        assertEquals("q8_0", out.kvCacheTypeK)
        assertEquals("q4_0", out.kvCacheTypeV)
        assertTrue(out.kvCacheEnabled)
    }

    @Test
    fun kvQuantSelectionSetsBothTypes() {
        val base = LlamaServerLaunchProfile(modelPath = "/m.gguf")
        val q8 = applyLocalForm(base, baseForm().copy(kvQuant = LocalServerForm.KV_Q8))
        assertTrue(q8.kvCacheEnabled)
        assertEquals("q8_0", q8.kvCacheTypeK)
        assertEquals("q8_0", q8.kvCacheTypeV)
        val f16 = applyLocalForm(base.copy(kvCacheEnabled = true, kvCacheTypeK = "q4_0", kvCacheTypeV = "q4_0"),
            baseForm().copy(kvQuant = LocalServerForm.KV_F16))
        assertFalse(f16.kvCacheEnabled)
        assertEquals("f16", f16.kvCacheTypeK)
    }

    @Test
    fun draftModelEnablesSpeculationAndClearingDisablesIt() {
        val base = LlamaServerLaunchProfile(modelPath = "/m.gguf")
        val withDraft = applyLocalForm(base, baseForm().copy(draftModelPath = "/d.gguf"))
        assertTrue(withDraft.speculativeEnabled)
        assertEquals("/d.gguf", withDraft.draftModelPath)
        assertEquals("draft-simple", withDraft.speculativeMode)
        val cleared = applyLocalForm(withDraft, baseForm().copy(draftModelPath = null))
        assertFalse(cleared.speculativeEnabled)
        assertNull(cleared.draftModelPath)
    }

    @Test
    fun visionRequiresAProjector() {
        val noProjector = applyLocalForm(
            LlamaServerLaunchProfile(), baseForm().copy(mmprojPath = null, visionEnabled = true, videoEnabled = true)
        )
        assertFalse(noProjector.visionEnabled)
        assertFalse(noProjector.videoEnabled)
        val withProjector = applyLocalForm(
            LlamaServerLaunchProfile(), baseForm().copy(mmprojPath = "/p.gguf", visionEnabled = true)
        )
        assertTrue(withProjector.visionEnabled)
    }

    @Test
    fun fromProfileRoundTripsCommonFields() {
        val profile = LlamaServerLaunchProfile(
            modelPath = "/a/b/model.gguf", contextSize = 2048, threads = 3, batchSize = 128,
            parallel = 1, sleepIdleSeconds = null, customFlags = "--slots --metrics"
        )
        val form = LocalServerForm.fromProfile(profile, "x", 8123, true)
        assertEquals("8123", form.portText)
        assertTrue(form.allowWearStart)
        assertEquals("", form.sleepIdleText)
        val back = applyLocalForm(profile, form)
        assertEquals(profile.copy(serverPort = 8123), back.copy(loras = profile.loras, loraPath = profile.loraPath))
    }

    @Test
    fun defaultNameStripsDirectoryAndExtension() {
        assertEquals("Qwen3-4B-Q4_K_M", defaultServerName("/data/models/Qwen3-4B-Q4_K_M.gguf"))
        assertEquals("plain", defaultServerName("plain"))
    }

    @Test
    fun usedPortsIgnoreRemoteAndTheEditedServer() {
        val a = server(1, 10, UnifiedServerKind.LOCAL_MANAGED, 8001)
        val b = server(2, 11, UnifiedServerKind.LOCAL_MANAGED, 8002)
        val legacy = server(null, 12, UnifiedServerKind.LOCAL_LEGACY, 8003)
        val remote = server(null, 13, UnifiedServerKind.REMOTE, 8004)
        assertEquals(setOf(8001, 8002, 8003), usedLocalPorts(listOf(a, b, legacy, remote), null))
        assertEquals(setOf(8002, 8003), usedLocalPorts(listOf(a, b, legacy, remote), a))
    }

    @Test
    fun remoteValidationMatchesEngineRules() {
        val llama = LlamaServerEntity.ENGINE_LLAMA_SERVER
        assertEquals(emptySet<RemoteFormError>(), validateRemoteForm(llama, "n", "10.0.0.2", "8080", "", false))
        assertEquals(
            setOf(RemoteFormError.NAME_BLANK, RemoteFormError.HOST_BLANK, RemoteFormError.PORT_INVALID),
            validateRemoteForm(llama, " ", "", "99999", "", false)
        )
        assertEquals(
            setOf(RemoteFormError.MODEL_BLANK),
            validateRemoteForm(LlamaServerEntity.ENGINE_OLLAMA, "n", "h", "11434", "", false)
        )
        assertEquals(
            setOf(RemoteFormError.MODEL_BLANK),
            validateRemoteForm(LlamaServerEntity.ENGINE_LLAMA_SWAP, "n", "h", "9292", "", false)
        )
        // LiteRT ignores host/port and needs a selected model.
        assertEquals(
            setOf(RemoteFormError.LITERT_MODEL_MISSING),
            validateRemoteForm(LlamaServerEntity.ENGINE_LITERT_LM, "n", "", "0", "", false)
        )
        assertEquals(emptySet<RemoteFormError>(), validateRemoteForm(LlamaServerEntity.ENGINE_LITERT_LM, "n", "", "0", "", true))
    }
}
