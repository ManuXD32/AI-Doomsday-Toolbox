package com.example.llamadroid.harness

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.BuildConfig
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LITERT_BACKEND_CPU
import com.example.llamadroid.data.model.LiteRtModelEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Opt-in x86_64 smoke test for the real LiteRT CPU worker through HarnessLocalModels.
 *
 * The model path is supplied by instrumentation (`harness_litert_model_path`). The test only
 * creates and removes its temporary Room row; the supplied model file is never modified or
 * deleted. This keeps the downloaded QA model reusable across test invocations.
 */
@RunWith(AndroidJUnit4::class)
class HarnessLiteRtIntegrationQaTest {
    @Test
    fun qaHarnessLocalModelsStreamsAndCancelsOwnedCpuInference(): Unit = runBlocking(Dispatchers.IO) {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("This test requires the explicit x86_64 Harness QA build", BuildConfig.HARNESS_QA_X86)
        assumeTrue("The LiteRT QA carrier must run x86_64", Build.SUPPORTED_ABIS.firstOrNull() == "x86_64")
        val modelPath = arguments.getString(ARG_MODEL_PATH)?.trim().orEmpty()
        assumeTrue("Pass harness_litert_model_path for the downloaded QA model", modelPath.isNotEmpty())

        val modelFile = File(modelPath).canonicalFile
        assertTrue("The supplied LiteRT model is not readable: $modelFile", modelFile.isFile && modelFile.canRead())
        assertEquals("Unexpected SmolLM2 QA model size", EXPECTED_MODEL_BYTES, modelFile.length())
        assertEquals("Unexpected SmolLM2 QA model digest", EXPECTED_MODEL_SHA256, sha256(modelFile))

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(
            "The x86_64 QA APK is missing the real LiteRT JNI library",
            File(context.applicationInfo.nativeLibraryDir, "liblitertlm_jni.so").isFile
        )
        val settings = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        val ownerPrefs = context.getSharedPreferences(OWNER_PREFS, Context.MODE_PRIVATE)
        val initialOwners = ownerPrefs.getStringSet(OWNER_SET_KEY, emptySet()).orEmpty().toSet()
        assertTrue("An existing LiteRT owner receipt must be cleaned before this opt-in test", initialOwners.isEmpty())
        val settingsSnapshot = SettingsSnapshot.capture(settings)
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val models = HarnessLocalModels(context, database)
        val generation = "qa-litert-${UUID.randomUUID()}"
        val owner = "harness-$generation"
        var modelId = 0L
        var failure: Throwable? = null
        try {
            settings.edit()
                .putString("agent_litert_backend", LITERT_BACKEND_CPU)
                .putInt("agent_litert_context_tokens", 1024)
                .putInt("agent_litert_max_output_tokens", 64)
                .putBoolean("agent_litert_thinking_enabled", false)
                .putBoolean("agent_litert_mtp_enabled", false)
                .commitOrThrow()
            models.beginRuntime(generation)
            modelId = database.liteRtModelDao().insert(
                LiteRtModelEntity(
                    displayName = "SmolLM2 360M Instruct QA",
                    path = modelFile.absolutePath,
                    repoId = MODEL_REPOSITORY,
                    filename = modelFile.name,
                    sizeBytes = modelFile.length(),
                    backendPreference = LITERT_BACKEND_CPU,
                    supportsCpu = true,
                    supportsGpu = false,
                    maxContextTokens = 1024,
                    classificationSource = "QA"
                )
            )

            val firstChunks = mutableListOf<JSONObject>()
            withTimeout(120_000L) {
                models.stream(request(modelId, "Reply with one short greeting.")) { chunk ->
                    firstChunks += chunk
                }
            }
            val firstText = firstChunks.joinToString("") { it.deltaContent() }
            assertTrue("The real LiteRT CPU worker emitted no visible delta", firstText.isNotBlank())
            assertTrue(
                "HarnessLocalModels did not persist its owner receipt before cleanup",
                owner in ownerPrefs.getStringSet(OWNER_SET_KEY, emptySet()).orEmpty()
            )
            assertNotNull("The temporary model row was not inserted", database.liteRtModelDao().getById(modelId))

            val streamingStarted = CompletableDeferred<Unit>()
            val longJob = launch(Dispatchers.IO) {
                models.stream(request(modelId, LONG_PROMPT, maxTokens = 64)) { chunk ->
                    if (chunk.deltaContent().isNotBlank()) streamingStarted.complete(Unit)
                }
            }
            withTimeout(120_000L) { streamingStarted.await() }
            assertTrue("The longer inference finished before cancellation could be exercised", longJob.isActive)
            models.cancelOwnedRequests()
            withTimeout(30_000L) { longJob.join() }
            assertTrue("The in-flight inference did not observe cancellation", longJob.isCancelled)
            assertFalse("Owned inference remained active after cancellation", longJob.isActive)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            withContext(NonCancellable) {
                val cleanupFailure = try {
                    models.cancelOwnedRequests()
                    withTimeout(30_000L) { models.releaseOwnedResources() }
                    null
                } catch (failure: Throwable) {
                    failure
                }
                var cleanupError = if (cleanupFailure != null) {
                    AssertionError("LiteRT owner cleanup failed; receipt was retained", cleanupFailure)
                } else {
                    runCatching {
                        val remaining = ownerPrefs.getStringSet(OWNER_SET_KEY, emptySet()).orEmpty()
                        assertFalse("releaseOwnedResources left the QA owner receipt", owner in remaining)
                        assertEquals("releaseOwnedResources changed an unrelated owner receipt", initialOwners, remaining)
                    }.exceptionOrNull()
                }
                try {
                    if (modelId > 0L) database.liteRtModelDao().deleteById(modelId)
                } catch (error: Throwable) {
                    cleanupError = cleanupError ?: error
                }
                runCatching { settingsSnapshot.restore(settings) }
                    .exceptionOrNull()?.let { cleanupError = cleanupError ?: it }
                database.close()
                cleanupError?.let { error ->
                    failure?.addSuppressed(error) ?: throw error
                }
            }
        }
    }

    private fun request(modelId: Long, prompt: String, maxTokens: Int = 16): JSONObject = JSONObject()
        .put("model", "litert:$modelId")
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        .put("max_tokens", maxTokens)

    private fun JSONObject.deltaContent(): String {
        val choices = optJSONArray("choices") ?: return ""
        return buildString {
            for (index in 0 until choices.length()) {
                append(choices.optJSONObject(index)?.optJSONObject("delta")?.optString("content").orEmpty())
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private data class SettingsSnapshot(private val values: Map<String, Any?>) {
        fun restore(preferences: android.content.SharedPreferences) {
            val editor = preferences.edit()
            values.forEach { (key, value) ->
                when (value) {
                    null -> editor.remove(key)
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    else -> error("Unsupported preference type for $key: ${value::class.java.name}")
                }
            }
            check(editor.commit()) { "LITERT_QA_SETTINGS_RESTORE_FAILED" }
        }

        companion object {
            private val KEYS = listOf(
                "agent_litert_model_id",
                "agent_litert_backend",
                "agent_litert_mtp_enabled",
                "agent_litert_context_tokens",
                "agent_litert_max_output_tokens",
                "agent_litert_thinking_enabled"
            )

            fun capture(preferences: android.content.SharedPreferences): SettingsSnapshot =
                SettingsSnapshot(KEYS.associateWith { key -> if (preferences.contains(key)) preferences.all[key] else null })
        }
    }

    private fun android.content.SharedPreferences.Editor.commitOrThrow() {
        check(commit()) { "LITERT_QA_SETTINGS_WRITE_FAILED" }
    }

    private companion object {
        const val ARG_MODEL_PATH = "harness_litert_model_path"
        const val SETTINGS_PREFS = "llamadroid_settings"
        const val OWNER_PREFS = "harness_inference_owners"
        const val OWNER_SET_KEY = "owners"
        const val MODEL_REPOSITORY = "litert-community/SmolLM2-360M-Instruct"
        const val EXPECTED_MODEL_BYTES = 373_719_040L
        const val EXPECTED_MODEL_SHA256 = "8e2834da211b439751af968ed650febdde5a8cb8d88bc6c1a3059f049caa5c2e"
        const val LONG_PROMPT = "Write a detailed answer about why bounded cancellation matters in a mobile inference runtime. Use several complete sentences and continue until the requested output limit."
    }
}
