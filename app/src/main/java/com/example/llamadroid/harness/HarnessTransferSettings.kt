package com.example.llamadroid.harness

import android.content.Context
import android.content.SharedPreferences
import com.example.llamadroid.data.db.AppDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Typed preferences avoid changing numeric types or importing app-global settings. */
internal object HarnessTransferSettings {
    private val secretKey = Regex("(?i).*(password|api_?key|secret|credential).*")
    private fun sensitiveKey(key: String) = secretKey.matches(key) || key.endsWith("_token", ignoreCase = true)

    suspend fun snapshot(context: Context, database: AppDatabase): JSONObject {
        val groups = JSONObject()
        groups.put("agent", encode(context.getSharedPreferences("llamadroid_settings", Context.MODE_PRIVATE)
            .all.filterKeys { it.startsWith("agent_") && !sensitiveKey(it) }))
        groups.put("capabilities", encode(context.getSharedPreferences("harness_local_model_capabilities", Context.MODE_PRIVATE).all))
        // IDs from another device are not stable; preserve filenames for matching or review.
        val models = JSONArray()
        database.liteRtModelDao().getAllOnce().forEach { model ->
            models.put(JSONObject().put("id", "litert:${model.id}").put("filename", File(model.path).name)
                .put("name", model.displayName))
        }
        return JSONObject().put("version", 1).put("groups", groups).put("localModels", models)
    }

    fun restore(context: Context, settings: JSONObject, modelMappings: Map<String, String>) {
        require(settings.getInt("version") == 1) { "HARNESS_SETTINGS_VERSION_UNSUPPORTED" }
        val groups = settings.getJSONObject("groups")
        val agent = context.getSharedPreferences("llamadroid_settings", Context.MODE_PRIVATE)
        write(agent, groups.getJSONObject("agent"), clear = false) { key, value ->
            require(key.startsWith("agent_") && !sensitiveKey(key)) { "HARNESS_SETTINGS_KEY_INVALID" }
            if (key.contains("litert") && key.endsWith("model_id") && value is Number) {
                val old = "litert:${value.toLong()}"
                modelMappings[old]?.removePrefix("litert:")?.toLongOrNull() ?: -1L
            } else if (value is String && key.endsWith("_model")) modelMappings[value] ?: value else value
        }
        val capabilities = groups.optJSONObject("capabilities") ?: JSONObject()
        write(context.getSharedPreferences("harness_local_model_capabilities", Context.MODE_PRIVATE), capabilities, clear = true) { key, value ->
            if (key == "overrides" && value is String) {
                val source = JSONObject(value)
                JSONObject().apply {
                    source.keys().forEach { id ->
                        val mapped = modelMappings[id] ?: id.takeUnless { it.startsWith("litert:") || it.startsWith("llama:") }
                        if (mapped != null && !mapped.startsWith("unresolved:")) put(mapped, source.get(id))
                    }
                }.toString()
            } else value
        }
    }

    fun modelReferences(settings: JSONObject): Set<String> = buildSet {
        val groups = settings.optJSONObject("groups") ?: return@buildSet
        groups.optJSONObject("agent")?.let { agent ->
            agent.keys().forEach { key ->
                val value = agent.getJSONObject(key).opt("value")
                if (key.contains("litert") && key.endsWith("model_id") && value is Number && value.toLong() > 0) add("litert:${value.toLong()}")
                if (key.endsWith("_model") && value is String && value.isNotBlank()) add(value)
            }
        }
        groups.optJSONObject("capabilities")?.optJSONObject("overrides")?.optString("value")
            ?.takeIf { it.isNotBlank() }?.let { JSONObject(it).keys().forEach(::add) }
    }

    private fun encode(values: Map<String, *>): JSONObject = JSONObject().apply {
        values.forEach { (key, value) ->
            val type = when (value) {
                is String -> "string"
                is Boolean -> "boolean"
                is Int -> "int"
                is Long -> "long"
                is Float -> "float"
                is Set<*> -> "strings"
                else -> return@forEach
            }
            put(key, JSONObject().put("type", type).put("value", if (value is Set<*>) JSONArray(value.toList()) else value))
        }
    }

    // Import must stop when persistence fails; KTX edit discards commit's Boolean result.
    @android.annotation.SuppressLint("UseKtx")
    private fun write(preferences: SharedPreferences, values: JSONObject, clear: Boolean,
                      map: (String, Any) -> Any) {
        require(values.length() <= 4096) { "HARNESS_SETTINGS_TOO_LARGE" }
        val editor = preferences.edit()
        if (clear) editor.clear()
        values.keys().forEach { key ->
            require(key.length in 1..256 && key.none(Char::isISOControl)) { "HARNESS_SETTINGS_KEY_INVALID" }
            val row = values.getJSONObject(key)
            val value = map(key, row.get("value"))
            when (row.getString("type")) {
                "string" -> editor.putString(key, value as String)
                "boolean" -> editor.putBoolean(key, value as Boolean)
                "int" -> editor.putInt(key, (value as Number).toInt())
                "long" -> editor.putLong(key, (value as Number).toLong())
                "float" -> editor.putFloat(key, (value as Number).toFloat())
                "strings" -> editor.putStringSet(key, (value as JSONArray).let { list ->
                    (0 until list.length()).map { list.getString(it) }.toSet()
                })
                else -> error("HARNESS_SETTINGS_TYPE_INVALID")
            }
        }
        check(editor.commit()) { "HARNESS_SETTINGS_WRITE_FAILED" }
    }
}
