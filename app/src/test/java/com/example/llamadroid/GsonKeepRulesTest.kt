package com.example.llamadroid

import java.io.File
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression gate for R8 full mode: every class reachable from a Gson root must match a
 * `-keep class ... { *; }` rule in proguard-rules.pro, otherwise generic fields decode as LinkedTreeMap.
 * Add new Gson roots (fromJson / TypeToken / Retrofit DTO) to [roots].
 */
class GsonKeepRulesTest {
    private val roots = listOf(
        "com.example.llamadroid.harness.HarnessTransferPublication\$Plan",
        "com.example.llamadroid.harness.HarnessTransferMetadata\$Document",
        "com.example.llamadroid.harness.HarnessTransferMetadata\$ImportMarker",
        "com.example.llamadroid.ui.ai.llama.LlamaChatExportPayload",
        "com.example.llamadroid.ui.ai.llama.NotesExportPayload",
        "com.example.llamadroid.ui.ai.llama.NoteExportPayload",
        "com.example.llamadroid.service.LlamaServerLaunchProfile",
        "com.example.llamadroid.service.SDConfig",
        "com.example.llamadroid.service.SDUpscaleConfig",
        "com.example.llamadroid.service.VideoGenerationConfig",
        "com.example.llamadroid.service.LiteRtLmDoctorRequestDto",
        "com.example.llamadroid.service.LiteRtLmWorkerRequestDto",
        "com.example.llamadroid.service.LiteRtLmChatStatsDto",
        "com.example.llamadroid.service.LiteRtBackendDoctorResult",
        "com.example.llamadroid.service.DistributedLlamaLaunchProfile",
        "com.example.llamadroid.service.ResolvedDistributedLaunch",
        "com.example.llamadroid.service.HelperTransfer",
        "com.example.llamadroid.service.LlamaServerSessionOwner",
        "com.example.llamadroid.service.LlamaServerSessionSnapshot",
        "com.example.llamadroid.service.AgentRemoteChatRequest",
        "com.example.llamadroid.service.OllamaService\$ChatResponse",
        "com.example.llamadroid.service.AgentRemoteStreamSnapshot",
        "com.example.llamadroid.service.LlamaOcrExclusiveLease",
        "com.example.llamadroid.service.perf.LockedScreenReport",
        "com.example.llamadroid.data.api.LlamaChatResponse",
        "com.example.llamadroid.data.model.LlamaServerCardEntity",
        "com.example.llamadroid.data.repository.KnowledgeEmbeddingService\$OllamaEmbedResponse",
        "com.example.llamadroid.data.repository.KnowledgeEmbeddingService\$OllamaLegacyEmbeddingResponse",
        "com.example.llamadroid.data.repository.KnowledgeEmbeddingService\$OpenAiEmbeddingResponse",
        "com.example.llamadroid.data.backup.NativeChatNotesBackupManifest",
    )

    private val appPrefix = "com.example.llamadroid."

    @Test
    fun everyGsonRootGraphClassIsKept() {
        val patterns = loadKeepPatterns()
        val missing = sortedSetOf<String>()
        roots.forEach { root ->
            val seen = mutableSetOf<String>()
            collect(Class.forName(root), seen)
            seen.filterNot { name -> patterns.any { it.matches(name) } }.forEach { missing += "$it (from $root)" }
        }
        assertTrue("Add -keep class rules to app/proguard-rules.pro for: $missing", missing.isEmpty())
    }

    @Test
    fun everyGsonRootInSourceIsListed() {
        val srcDir = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }
        val ignored = setOf(
            "Map", "MutableMap", "List", "MutableList", "Set", "Collection", "Array", "String", "Any",
            "Int", "Long", "Boolean", "Double", "Float", "JsonObject", "JsonElement", "JsonArray",
        )
        val fromJsonRoot = Regex("""fromJson\s*\((?:[^()]|\([^()]*\))*?(?:Array<)?([A-Z]\w*(?:\.[A-Z]\w*)*)>?::class\.java""")
        val typeToken = Regex("""TypeToken<(.*?)>\(\)""")
        val ident = Regex("""[A-Z]\w*""")
        val rootSimple = roots.map { it.substringAfterLast('.').substringAfterLast('$') }.toSet()
        val problems = sortedSetOf<String>()
        srcDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            val found = mutableSetOf<String>()
            fromJsonRoot.findAll(text).forEach { found += it.groupValues[1].substringAfterLast('.') }
            typeToken.findAll(text).forEach { m -> ident.findAll(m.groupValues[1]).forEach { found += it.value } }
            found.filter { it !in ignored && it !in rootSimple }
                .forEach { problems += "New Gson root $it in ${file.path}: add it to roots and keep rules" }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    private fun loadKeepPatterns(): List<Regex> {
        val file = listOf("proguard-rules.pro", "app/proguard-rules.pro").map(::File).first { it.isFile }
        return file.readLines().map { it.trim() }
            .filter { it.startsWith("-keep class ") && it.contains("{ *; }") }
            .map { line ->
                val name = line.removePrefix("-keep class ").substringBefore('{').trim()
                val regex = name.split("**").joinToString(".*") { part ->
                    part.split("*").joinToString("[^.]*") { Regex.escape(it) }
                }
                Regex(regex)
            }
    }

    private fun collect(type: Type, seen: MutableSet<String>) {
        when (type) {
            is Class<*> -> {
                if (type.isArray) return collect(type.componentType, seen)
                if (!type.name.startsWith(appPrefix) || !seen.add(type.name)) return
                var k: Class<*>? = type
                while (k != null && k != Any::class.java) {
                    if (k != type) collect(k, seen)
                    k.declaredFields
                        .filter { !Modifier.isStatic(it.modifiers) && !Modifier.isTransient(it.modifiers) }
                        .forEach { collect(it.genericType, seen) }
                    k = k.superclass
                }
            }
            is ParameterizedType -> {
                collect(type.rawType, seen)
                type.actualTypeArguments.forEach { collect(it, seen) }
            }
            is GenericArrayType -> collect(type.genericComponentType, seen)
            is WildcardType -> type.upperBounds.forEach { collect(it, seen) }
            else -> Unit
        }
    }
}
