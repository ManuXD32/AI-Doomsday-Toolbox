package com.example.llamadroid.harness.transfer

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.LinkOption
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Schema-aware handling for the DSH home settings YAML document. */
object HarnessTransferConfiguration {
    private const val MAX_BYTES = 4 * 1024 * 1024
    private val REFERENCE_KEYS = setOf(
        "provider", "providerid", "provider_id", "providerref", "provider_ref",
        "model", "modelid", "model_id", "modelref", "model_ref", "profile", "profileid",
        "credentialref", "credential_ref", "llmprovider", "llmmodel", "agentprovider", "agentmodel",
        "defaultprovider", "defaultmodel",
    )
    private val OPAQUE_KEYS = setOf(
        "content", "arguments", "output", "message", "messages", "prompt", "text", "reasoning",
        "summary", "description", "title", "instructions", "systemprompt", "toolarguments",
        "toolcall", "toolcalls", "toolresult", "rawoutput", "parameters", "meta",
    )
    private val SECRET_KEYS = setOf(
        "token", "accesstoken", "refreshtoken", "secret", "clientsecret", "password", "apikey",
        "api_key", "credential", "credentials", "oauth", "authorization", "privatekey",
    )
    private val PROVIDER_DEFINITION_CONTAINERS = setOf(
        "providers", "providerconfigs", "providerconfigurations", "providerdefinitions",
        "llmproviders", "providerstore",
    )
    private val MODEL_DEFINITION_CONTAINERS = setOf(
        "models", "modelconfigs", "modelconfigurations", "modeldefinitions", "llmmodels",
    )

    /** Returns true only for the DSH home settings document in an archive. */
    fun isSettingsPath(path: String): Boolean =
        path == "dsh_home/settings.yaml" || path == "dsh_home/settings.yml" || path == "dsh_home/settings.json"

    /** Collects references from the pinned DSH settings document directly under one DSH home. */
    fun references(home: File): Map<String, Set<String>> {
        val values = linkedMapOf<String, LinkedHashSet<String>>()
        settingsFiles(home).forEach { file -> merge(values, references(file.readText(Charsets.UTF_8))) }
        return values.mapValues { it.value.toSet() }
    }

    /** Applies only known model/provider scalar fields in the DSH settings document. */
    fun rewrite(home: File, referenceMapper: (key: String, value: String) -> String?) {
        settingsFiles(home).forEach { file ->
            val original = file.readText(Charsets.UTF_8)
            val rewritten = rewriteSettings(original, referenceMapper, file.extension == "json")
            if (rewritten != original) writeAtomic(file, rewritten)
        }
    }

    internal fun references(yamlText: String): Map<String, Set<String>> {
        val values = linkedMapOf<String, LinkedHashSet<String>>()
        walk(load(yamlText), null, values, null, collectOnly = true)
        return values.mapValues { it.value.toSet() }
    }

    internal fun rewrite(yamlText: String, referenceMapper: (key: String, value: String) -> String?): String {
        return rewriteSettings(yamlText, referenceMapper, jsonOutput = false)
    }

    /** Rewrites settings while retaining JSON syntax for the settings.json variant. */
    internal fun rewriteSettings(
        yamlText: String,
        referenceMapper: (key: String, value: String) -> String?,
        jsonOutput: Boolean,
    ): String {
        require(yamlText.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "TRANSFER_SETTINGS_TOO_LARGE" }
        val document = load(yamlText)
        var changed = false
        walk(document, null, linkedMapOf(), referenceMapper, collectOnly = false) { changed = true }
        if (!changed) return yamlText
        return (if (jsonOutput) dumpJson(document) else dump(document)).also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "TRANSFER_SETTINGS_TOO_LARGE" }
        }
    }

    /** Removes known secret fields from settings without scanning or rewriting opaque prompt text. */
    internal fun sanitize(yamlText: String): String {
        return sanitizeSettings(yamlText, jsonOutput = false)
    }

    /** Removes secrets while retaining JSON syntax for the settings.json variant. */
    internal fun sanitizeSettings(yamlText: String, jsonOutput: Boolean): String {
        require(yamlText.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "TRANSFER_SETTINGS_TOO_LARGE" }
        val document = load(yamlText)
        var changed = false
        stripSecrets(document, null) { changed = true }
        if (!changed) return yamlText
        return (if (jsonOutput) dumpJson(document) else dump(document)).also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "TRANSFER_SETTINGS_TOO_LARGE" }
        }
    }

    private fun settingsFiles(home: File): List<File> = listOf(
        File(home, "settings.yaml"), File(home, "settings.yml"), File(home, "settings.json"),
    ).filter { file ->
        val path = file.toPath()
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return@filter false
        require(Files.size(path) <= MAX_BYTES) { "TRANSFER_SETTINGS_TOO_LARGE" }
        true
    }

    private fun load(text: String): Any? {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "TRANSFER_SETTINGS_TOO_LARGE" }
        try {
            val yaml = Yaml(SafeConstructor(loaderOptions()))
            val documents = yaml.loadAll(text).iterator()
            if (!documents.hasNext()) return null
            val first = documents.next()
            require(!documents.hasNext()) { "TRANSFER_SETTINGS_MULTIPLE_DOCUMENTS" }
            return first
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Throwable) {
            throw IllegalArgumentException("TRANSFER_SETTINGS_YAML_INVALID", error)
        }
    }

    private fun loaderOptions(): LoaderOptions = LoaderOptions().apply {
        setAllowDuplicateKeys(false)
        setWarnOnDuplicateKeys(false)
        setMaxAliasesForCollections(0)
        setNestingDepthLimit(64)
        setCodePointLimit(MAX_BYTES)
        setAllowRecursiveKeys(false)
        setMergeOnCompose(false)
    }

    private fun dump(document: Any?): String {
        val options = DumperOptions().apply {
            setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK)
            setPrettyFlow(false)
            setIndent(2)
            setIndicatorIndent(0)
            setWidth(4096)
            setSplitLines(false)
            setDereferenceAliases(true)
            setAllowUnicode(true)
        }
        return Yaml(SafeConstructor(loaderOptions()), org.yaml.snakeyaml.representer.Representer(options), options, loaderOptions())
            .dump(document)
    }

    private fun dumpJson(document: Any?): String {
        val json = when (document) {
            is Map<*, *> -> JSONObject().apply {
                document.forEach { (key, value) ->
                    if (key is String) put(key, jsonValue(value))
                }
            }
            is List<*> -> JSONArray().apply { document.forEach { put(jsonValue(it)) } }
            else -> jsonValue(document)
        }
        return when (json) {
            is JSONObject -> json.toString(2) + "\n"
            is JSONArray -> json.toString(2) + "\n"
            null, JSONObject.NULL -> "null\n"
            else -> JSONObject.wrap(json)?.toString()?.plus("\n") ?: "null\n"
        }
    }

    private fun jsonValue(value: Any?): Any? = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().apply {
            value.forEach { (key, child) -> if (key is String) put(key, jsonValue(child)) }
        }
        is Iterable<*> -> JSONArray().apply { value.forEach { put(jsonValue(it)) } }
        is String, is Number, is Boolean -> value
        else -> value.toString()
    }

    private fun walk(
        value: Any?,
        key: String?,
        references: MutableMap<String, LinkedHashSet<String>>,
        mapper: ((String, String) -> String?)?,
        collectOnly: Boolean,
        onChanged: () -> Unit = {},
    ) {
        if (key != null && key.lowercase() in OPAQUE_KEYS) return
        when (value) {
            is MutableMap<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val map = value as MutableMap<Any?, Any?>
                val definitionKey = when (key?.lowercase()) {
                    in PROVIDER_DEFINITION_CONTAINERS -> "providerDefinition"
                    in MODEL_DEFINITION_CONTAINERS -> "modelDefinition"
                    else -> null
                }
                if (collectOnly && definitionKey != null) {
                    map.keys.filterIsInstance<String>().forEach { definition ->
                        require(definition.isNotEmpty() && '\u0000' !in definition) {
                            "TRANSFER_REFERENCE_INVALID"
                        }
                        references.getOrPut(definitionKey) { linkedSetOf() }.add(definition)
                    }
                }
                map.entries.toList().forEach { entry ->
                    val childKey = entry.key as? String ?: return@forEach
                    val scalar = entry.value as? String
                    if (childKey.lowercase() in REFERENCE_KEYS && scalar != null) {
                        if (collectOnly) references.getOrPut(childKey) { linkedSetOf() }.add(scalar)
                        else mapper?.invoke(childKey, scalar)?.let { mapped ->
                            require(mapped.isNotEmpty() && '\u0000' !in mapped) { "TRANSFER_REFERENCE_INVALID" }
                            if (mapped != scalar) {
                                map[entry.key] = mapped
                                onChanged()
                            }
                        }
                    } else {
                        walk(entry.value, childKey, references, mapper, collectOnly, onChanged)
                    }
                }
            }
            is MutableList<*> -> value.forEach { child ->
                walk(child, key, references, mapper, collectOnly, onChanged)
            }
        }
    }

    private fun stripSecrets(value: Any?, key: String?, onChanged: () -> Unit) {
        if (key != null && key.lowercase() in OPAQUE_KEYS) return
        when (value) {
            is MutableMap<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val map = value as MutableMap<Any?, Any?>
                map.keys.toList().forEach { rawKey ->
                    val childKey = rawKey as? String ?: return@forEach
                    if (childKey.lowercase() in SECRET_KEYS) {
                        map.remove(rawKey)
                        onChanged()
                    } else {
                        stripSecrets(map[rawKey], childKey, onChanged)
                    }
                }
            }
            is MutableList<*> -> value.forEach { child -> stripSecrets(child, key, onChanged) }
        }
    }

    private fun merge(destination: MutableMap<String, LinkedHashSet<String>>, source: Map<String, Set<String>>) {
        source.forEach { (key, values) -> destination.getOrPut(key) { linkedSetOf() }.addAll(values) }
    }

    private fun writeAtomic(file: File, text: String) {
        val temporary = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }
}
