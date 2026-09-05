package com.example.llamadroid.data

import android.content.Context
import android.content.SharedPreferences
import com.example.llamadroid.service.LlamaLoadMode
import com.example.llamadroid.service.LlamaLoraSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryLlamaMigrationTest {
    private lateinit var prefs: SharedPreferences

    @Before
    fun clearSettings() {
        prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        assertTrue(prefs.edit().clear().commit())
    }

    @Test
    fun `legacy noMmap preference migrates false to mmap and true to none`() {
        listOf(
            false to LlamaLoadMode.MMAP,
            true to LlamaLoadMode.NONE
        ).forEach { (legacyNoMmap, expectedMode) ->
            prefs.edit().clear().putBoolean(KEY_LOW_MEMORY_MODE, legacyNoMmap).commit()

            val settings = SettingsRepository(RuntimeEnvironment.getApplication())

            assertEquals(expectedMode, settings.llamaLoadMode.value)
            assertEquals(expectedMode == LlamaLoadMode.NONE, settings.lowMemoryMode.value)
            assertEquals(expectedMode.value, prefs.getString(KEY_LLAMA_LOAD_MODE, null))
            assertEquals(expectedMode == LlamaLoadMode.NONE, prefs.getBoolean(KEY_LOW_MEMORY_MODE, false))
        }
    }

    @Test
    fun `legacy selected lora path migrates to one default-strength adapter`() {
        prefs.edit()
            .putString(KEY_SELECTED_LLM_LORA_PATH, "  /models/legacy.lora  ")
            .commit()

        val settings = SettingsRepository(RuntimeEnvironment.getApplication())

        assertEquals(
            listOf(LlamaLoraSpec("/models/legacy.lora")),
            settings.selectedLlmLoras.value
        )
        assertEquals("/models/legacy.lora", settings.selectedLlmLoraPath.value)
        assertTrue(prefs.getString(KEY_SELECTED_LLM_LORAS, null).orEmpty().contains("/models/legacy.lora"))
        assertEquals("/models/legacy.lora", prefs.getString(KEY_SELECTED_LLM_LORA_PATH, null))
    }

    @Test
    fun `canonical empty lora stack prevents legacy path resurrection`() {
        prefs.edit()
            .putString(KEY_SELECTED_LLM_LORA_PATH, "/models/legacy.lora")
            .putString(KEY_SELECTED_LLM_LORAS, "[]")
            .commit()

        val firstSettings = SettingsRepository(RuntimeEnvironment.getApplication())
        val secondSettings = SettingsRepository(RuntimeEnvironment.getApplication())

        assertTrue(firstSettings.selectedLlmLoras.value.isEmpty())
        assertNull(firstSettings.selectedLlmLoraPath.value)
        assertTrue(secondSettings.selectedLlmLoras.value.isEmpty())
        assertNull(secondSettings.selectedLlmLoraPath.value)
        assertEquals("[]", prefs.getString(KEY_SELECTED_LLM_LORAS, null))
        assertNull(prefs.getString(KEY_SELECTED_LLM_LORA_PATH, null))
    }

    @Test
    fun `migration atomically persists cleaned flags and compatibility mirrors`() {
        prefs.edit()
            .putBoolean(KEY_LOW_MEMORY_MODE, false)
            .putString(KEY_SELECTED_LLM_LORA_PATH, "/models/legacy.lora")
            .putString(KEY_CUSTOM_FLAGS, "--mmap --lora /models/flags.lora --keep value")
            .commit()

        val settings = SettingsRepository(RuntimeEnvironment.getApplication())

        assertEquals(LlamaLoadMode.MMAP, settings.llamaLoadMode.value)
        assertEquals(listOf(LlamaLoraSpec("/models/flags.lora")), settings.selectedLlmLoras.value)
        assertEquals("--keep value", settings.customFlags.value)
        assertFalse(settings.customFlags.value.contains("--mmap"))
        assertFalse(settings.customFlags.value.contains("--lora"))

        assertEquals(1, prefs.getInt(KEY_LLAMA_MANAGED_SCHEMA, 0))
        assertEquals("mmap", prefs.getString(KEY_LLAMA_LOAD_MODE, null))
        assertFalse(prefs.getBoolean(KEY_LOW_MEMORY_MODE, true))
        assertEquals("/models/flags.lora", prefs.getString(KEY_SELECTED_LLM_LORA_PATH, null))
        assertTrue(prefs.getString(KEY_SELECTED_LLM_LORAS, null).orEmpty().contains("/models/flags.lora"))
    }

    @Test
    fun `last valid load mode flag wins while malformed flag remains visible`() {
        prefs.edit()
            .putString(KEY_CUSTOM_FLAGS, "--load-mode none --load-mode invalid --load-mode mlock")
            .commit()

        val settings = SettingsRepository(RuntimeEnvironment.getApplication())

        assertEquals(LlamaLoadMode.MLOCK, settings.llamaLoadMode.value)
        assertEquals("--load-mode invalid", settings.customFlags.value)
    }

    @Test
    fun `plain lora flag suppresses selected stack during migration`() {
        prefs.edit()
            .putString(
                KEY_SELECTED_LLM_LORAS,
                "[{\"path\":\"/models/selected.lora\",\"strength\":1.0}]"
            )
            .putString(KEY_CUSTOM_FLAGS, "--lora /models/plain.lora --other value")
            .commit()

        val settings = SettingsRepository(RuntimeEnvironment.getApplication())

        assertEquals(listOf(LlamaLoraSpec("/models/plain.lora")), settings.selectedLlmLoras.value)
        assertEquals("--other value", settings.customFlags.value)
    }

    @Test
    fun `scaled lora flags coexist with selected stack`() {
        prefs.edit()
            .putString(
                KEY_SELECTED_LLM_LORAS,
                "[{\"path\":\"/models/selected.lora\",\"strength\":1.0}]"
            )
            .putString(KEY_CUSTOM_FLAGS, "--lora-scaled /models/scaled.lora:0.5 --other value")
            .commit()

        val settings = SettingsRepository(RuntimeEnvironment.getApplication())

        assertEquals(
            listOf(
                LlamaLoraSpec("/models/selected.lora"),
                LlamaLoraSpec("/models/scaled.lora", 0.5f)
            ),
            settings.selectedLlmLoras.value
        )
        assertEquals("--other value", settings.customFlags.value)
    }

    @Test
    fun `migration preserves duplicate negative and zero lora strengths`() {
        prefs.edit()
            .putString(
                KEY_SELECTED_LLM_LORAS,
                "[" +
                    "{\"path\":\"/models/duplicate.lora\",\"strength\":-0.5}," +
                    "{\"path\":\"/models/duplicate.lora\",\"strength\":0}," +
                    "{\"path\":\"/models/zero.lora\",\"strength\":0}" +
                    "]"
            )
            .commit()

        val settings = SettingsRepository(RuntimeEnvironment.getApplication())

        assertEquals(
            listOf(
                LlamaLoraSpec("/models/duplicate.lora", -0.5f),
                LlamaLoraSpec("/models/duplicate.lora", 0f),
                LlamaLoraSpec("/models/zero.lora", 0f)
            ),
            settings.selectedLlmLoras.value
        )
        assertEquals("/models/duplicate.lora", settings.selectedLlmLoraPath.value)
    }

    @Test
    fun `thread batch preference is optional and persists when configured`() {
        val settings = SettingsRepository(RuntimeEnvironment.getApplication())

        assertNull(settings.serverThreadsBatch.value)
        assertFalse(prefs.contains(KEY_SERVER_THREADS_BATCH))

        settings.setServerThreadsBatch(6)

        assertEquals(6, settings.serverThreadsBatch.value)
        assertEquals(6, prefs.getInt(KEY_SERVER_THREADS_BATCH, -1))

        settings.setServerThreadsBatch(null)

        assertNull(settings.serverThreadsBatch.value)
        assertFalse(prefs.contains(KEY_SERVER_THREADS_BATCH))
    }

    private companion object {
        const val PREFERENCES_NAME = "llamadroid_settings"
        const val KEY_LLAMA_MANAGED_SCHEMA = "llama_managed_settings_schema"
        const val KEY_LLAMA_LOAD_MODE = "llama_load_mode"
        const val KEY_SELECTED_LLM_LORAS = "selected_llm_loras_json"
        const val KEY_SELECTED_LLM_LORA_PATH = "selected_llm_lora_path"
        const val KEY_LOW_MEMORY_MODE = "low_memory_mode"
        const val KEY_CUSTOM_FLAGS = "custom_flags"
        const val KEY_SERVER_THREADS_BATCH = "server_threads_batch"
    }
}
