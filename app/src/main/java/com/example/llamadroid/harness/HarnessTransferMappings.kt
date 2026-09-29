package com.example.llamadroid.harness

import android.content.Context
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.harness.runtime.HarnessRuntimePaths
import com.example.llamadroid.harness.runtime.HarnessRuntimeScope
import com.example.llamadroid.harness.transfer.TransferInspection
import com.example.llamadroid.harness.transfer.HarnessTransferConfiguration
import com.example.llamadroid.ui.agent.harness.HarnessRuntimeImportMappingChoiceUi
import com.example.llamadroid.ui.agent.harness.HarnessRuntimeImportMappingOptionUi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Numeric library ids are portable only within the same app installation. */
internal object HarnessTransferMappings {
    data class Review(val automatic: Map<String, String>, val choices: List<HarnessRuntimeImportMappingChoiceUi>)

    suspend fun catalog(context: Context): JSONObject {
        val database = AppDatabase.getDatabase(context)
        val models = JSONArray()
        HarnessLocalModels(context, database).models().getJSONArray("data").objects()
            .filter { it.optBoolean("available", true) }.forEach {
                models.put(JSONObject().put("id", it.getString("id")).put("name", it.optString("name", it.getString("id"))))
            }
        return JSONObject().put("deviceId", deviceId(context)).put("models", models)
    }

    suspend fun review(context: Context, inspection: TransferInspection, targetId: String?): Review {
        val current = catalog(context)
        val availableModels = current.getJSONArray("models").objects().associate { it.getString("id") to it.getString("name") }
        val source = inspection.metadata["catalog"]?.let(::JSONObject)
        val sourceModels = source?.optJSONArray("models")?.objects().orEmpty().associate { it.getString("id") to it.getString("name") }
        val sameDevice = source?.optString("deviceId") == current.getString("deviceId")
        val destination = targetId?.let { configurationReferences(HarnessRuntimePaths.harnessHome(HarnessRuntimeScope.context(context, it))) }.orEmpty()
        val automatic = linkedMapOf<String, String>()
        val missing = mutableListOf<HarnessRuntimeImportMappingChoiceUi>()
        val references = (if (targetId == null) inspection.references else inspection.sessionReferences)
            .filterKeys { !it.endsWith("Definition", ignoreCase = true) }
            .mapValues { it.value.toMutableSet() }.toMutableMap()
        inspection.metadata["settings"]?.takeIf { targetId == null }?.let { encoded ->
            references.getOrPut("modelId") { mutableSetOf() }.addAll(HarnessTransferSettings.modelReferences(JSONObject(encoded)))
        }
        references.forEach { (key, values) ->
            require(key.length <= 128 && values.size <= 4096 && values.all { it.length <= 512 && it.none(Char::isISOControl) }) {
                "HARNESS_TRANSFER_REFERENCE_INVALID"
            }
            val model = key.contains("model", ignoreCase = true)
            val provider = key.contains("provider", ignoreCase = true)
            if (!model && !provider) return@forEach
            values.sorted().forEach { value ->
                val local = value.startsWith("litert:") || value.startsWith("llama:") || value.startsWith("ollama:")
                val present = when {
                    model && local -> sameDevice && value in availableModels
                    targetId == null -> inspection.metadata["room"]?.let { HarnessTransferMetadata.decode(it).configurationIncluded } == true
                    else -> value in destination[if (model) "model" else "provider"].orEmpty() || value == "adt-managed"
                }
                val reference = "$key:$value"
                if (present) {
                    automatic[reference] = value
                    automatic[value] = value
                } else {
                    val options = if (model) availableModels.map { (id, label) -> HarnessRuntimeImportMappingOptionUi(id, label) } +
                        destination["model"].orEmpty().filterNot { it in availableModels }.map { HarnessRuntimeImportMappingOptionUi(it, it) }
                    else destination["provider"].orEmpty().map { HarnessRuntimeImportMappingOptionUi(it, it) }
                    missing += HarnessRuntimeImportMappingChoiceUi(reference, (sourceModels[value] ?: value).take(256), options)
                    // A source numeric ID must never select an unrelated destination model.
                    if (targetId == null && local) {
                        automatic[reference] = "unresolved:$value"
                        automatic[value] = "unresolved:$value"
                    }
                }
            }
        }
        return Review(automatic, missing.distinctBy { it.reference })
    }

    suspend fun resolve(context: Context, inspection: TransferInspection, targetId: String?, selected: Map<String, String>): Map<String, String> {
        val review = review(context, inspection, targetId)
        if (targetId != null) review.choices.forEach { choice ->
            require(selected[choice.reference] in choice.options.map { it.id }) { "HARNESS_TRANSFER_MAPPING_REQUIRED" }
        }
        require(selected.keys.all { key -> review.choices.any { it.reference == key } }) { "HARNESS_TRANSFER_MAPPING_INVALID" }
        selected.forEach { (reference, id) ->
            require(review.choices.single { it.reference == reference }.options.any { it.id == id }) { "HARNESS_TRANSFER_MAPPING_INVALID" }
        }
        require(selected.entries.groupBy { it.key.substringAfter(':') }.values.all { entries -> entries.map { it.value }.distinct().size == 1 }) {
            "HARNESS_TRANSFER_MAPPING_CONFLICT"
        }
        val result = (review.automatic + selected).toMutableMap()
        review.choices.forEach { choice -> selected[choice.reference]?.let { result[choice.reference.substringAfter(':')] = it } }
        return result
    }

    private fun configurationReferences(home: File): Map<String, Set<String>> {
        val result = mutableMapOf("model" to mutableSetOf<String>(), "provider" to mutableSetOf("adt-managed"))
        HarnessTransferConfiguration.references(home).forEach { (key, values) ->
            when {
                key.contains("provider", true) -> result.getValue("provider").addAll(values)
                key.contains("model", true) -> result.getValue("model").addAll(values)
            }
        }
        return result
    }

    // A stable origin ID must be durably saved; KTX edit does not expose commit failure.
    @android.annotation.SuppressLint("UseKtx")
    @Synchronized private fun deviceId(context: Context): String {
        val preferences = HarnessRuntimeScope.host(context).getSharedPreferences(HarnessRuntimeScope.CONTROL_PREFERENCES, Context.MODE_PRIVATE)
        return preferences.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            check(preferences.edit().putString("deviceId", it).commit())
        }
    }
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}
