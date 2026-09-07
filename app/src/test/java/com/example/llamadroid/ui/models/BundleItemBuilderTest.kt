package com.example.llamadroid.ui.models

import com.example.llamadroid.data.db.ModelBundleItemEntity
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelProvenanceEntity
import com.example.llamadroid.data.db.ModelSourceEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.data.model.library.InstalledModelAsset
import com.example.llamadroid.data.model.library.ModelFamily
import com.example.llamadroid.data.model.library.ModelSourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BundleItemBuilderTest {
    private fun source(
        id: String,
        family: ModelFamily,
        filePath: String,
        label: String = filePath.substringAfterLast('/')
    ) = ModelSourceEntity(
        id = id,
        kind = ModelSourceKind.HTTPS.storedValue,
        family = family.storedValue,
        label = label,
        url = "https://example.test/$id",
        normalizedKey = id,
        filePath = filePath
    )

    private fun model(
        filename: String,
        path: String = "/models/$filename",
        type: ModelType = ModelType.LLM
    ) = InstalledModelAsset.fromModel(
        ModelEntity(
            filename = filename,
            path = path,
            sizeBytes = 10L,
            type = type,
            repoId = "fixture/$filename"
        ),
        family = ModelFamily.LLM,
        role = "llm"
    )

    private fun input(
        family: ModelFamily,
        assets: List<InstalledModelAsset> = emptyList(),
        sources: List<ModelSourceEntity> = emptyList(),
        existing: List<ModelBundleItemEntity> = emptyList(),
        selectedModels: Set<String> = assets.map { it.stableId }.toSet(),
        selectedSources: Set<String> = sources.map { it.id }.toSet(),
        sourceRoles: Map<String, String?> = emptyMap(),
        sourceRelativePaths: Map<String, String> = emptyMap(),
        directories: Map<String, List<BundleDirectoryMember>> = emptyMap(),
        roots: Map<String, String> = emptyMap(),
        provenance: List<ModelProvenanceEntity> = emptyList()
    ) = BundleItemBuilderInput(
        bundleId = "bundle",
        family = family,
        existingItems = existing,
        selectedModelKeys = selectedModels,
        selectedSourceIds = selectedSources,
        sourceRoles = sourceRoles,
        sourceRelativePaths = sourceRelativePaths,
        installedAssets = assets,
        availableSources = sources,
        provenance = provenance,
        directoryRoots = roots,
        directoryMembers = directories
    )

    @Test
    fun `installed file and selected source become one item`() {
        val asset = model("model.gguf")
        val source = source("source", ModelFamily.LLM, "model.gguf")

        val result = buildBundleItems(input(ModelFamily.LLM, assets = listOf(asset), sources = listOf(source)))

        assertTrue(result.isValid)
        assertEquals(1, result.items.size)
        assertEquals(source.id, result.items.single().sourceId)
        assertEquals("model.gguf", result.items.single().relativePath)
    }

    @Test
    fun `nested same basename files keep separate source edges`() {
        val a = model("a/model.gguf", "/models/a/model.gguf")
        val b = model("b/model.gguf", "/models/b/model.gguf")
        val sourceA = source("a-source", ModelFamily.LLM, "a/model.gguf")
        val sourceB = source("b-source", ModelFamily.LLM, "b/model.gguf")

        val result = buildBundleItems(
            input(ModelFamily.LLM, assets = listOf(a, b), sources = listOf(sourceA, sourceB))
        )

        assertTrue(result.isValid)
        assertEquals(
            mapOf("a/model.gguf" to "a-source", "b/model.gguf" to "b-source"),
            result.items.associate { it.relativePath.orEmpty() to it.sourceId }
        )
    }

    @Test
    fun `same member path in two directory assets is an explicit conflict`() {
        val a = model("repo-a", "/models/repo-a")
        val b = model("repo-b", "/models/repo-b")
        val source = source("source", ModelFamily.LLM, "weights/model.safetensors")

        val result = buildBundleItems(
            input(
                ModelFamily.LLM,
                assets = listOf(a, b),
                sources = listOf(source),
                directories = mapOf(
                    a.stableId to listOf(BundleDirectoryMember("weights/model.safetensors", "/models/repo-a/weights/model.safetensors")),
                    b.stableId to listOf(BundleDirectoryMember("weights/model.safetensors", "/models/repo-b/weights/model.safetensors"))
                ),
                roots = mapOf("repo-a" to "/models/repo-a", "repo-b" to "/models/repo-b")
            )
        )

        assertFalse(result.isValid)
        assertTrue(result.conflicts.any { it.code == BundleItemBuildConflictCode.AMBIGUOUS_SOURCE_TARGET })
        assertEquals(2, result.items.count { it.sourceId == null })
    }

    @Test
    fun `exact provenance selects the matching directory member despite same basename`() {
        val a = model("repo-a", "/models/repo-a")
        val b = model("repo-b", "/models/repo-b")
        val sourceA = source("source-a", ModelFamily.LLM, "model.safetensors")
        val sourceB = source("source-b", ModelFamily.LLM, "model.safetensors")
        val memberA = "/models/repo-a/model.safetensors"
        val memberB = "/models/repo-b/model.safetensors"
        val provenance = listOf(
            ModelProvenanceEntity(
                id = "edge-a", sourceId = sourceA.id, modelKey = a.stableId,
                family = ModelFamily.LLM.storedValue, localPath = memberA
            ),
            ModelProvenanceEntity(
                id = "edge-b", sourceId = sourceB.id, modelKey = b.stableId,
                family = ModelFamily.LLM.storedValue, localPath = memberB
            )
        )

        val result = buildBundleItems(
            input(
                ModelFamily.LLM,
                assets = listOf(a, b),
                sources = listOf(sourceA, sourceB),
                directories = mapOf(
                    a.stableId to listOf(BundleDirectoryMember("model.safetensors", memberA)),
                    b.stableId to listOf(BundleDirectoryMember("model.safetensors", memberB))
                ),
                provenance = provenance
            )
        )

        assertTrue(result.isValid)
        assertEquals(
            mapOf("repo-a/model.safetensors" to "source-a", "repo-b/model.safetensors" to "source-b"),
            result.items.associate { it.relativePath.orEmpty() to it.sourceId }
        )
    }

    @Test
    fun `selected stale draft survives missing installed asset and source`() {
        val old = ModelBundleItemEntity(
            bundleId = "old",
            itemKey = "missing.gguf",
            family = ModelFamily.LLM.storedValue,
            role = "llm",
            sourceId = "deleted-source",
            relativePath = "missing.gguf",
            localFilename = "missing.gguf"
        )

        val result = buildBundleItems(
            input(
                ModelFamily.LLM,
                existing = listOf(old),
                selectedModels = setOf(old.itemKey),
                selectedSources = emptySet()
            )
        )

        assertEquals(1, result.items.size)
        assertEquals("deleted-source", result.items.single().sourceId)
        assertEquals("missing.gguf", result.items.single().relativePath)
    }

    @Test
    fun `cross family source requires explicit compatible role`() {
        val source = source("llm-source", ModelFamily.LLM, "text_encoder.gguf")
        val withoutRole = buildBundleItems(input(ModelFamily.SD, sources = listOf(source)))
        assertTrue(withoutRole.conflicts.any { it.code == BundleItemBuildConflictCode.SOURCE_ROLE_REQUIRED })

        val withRole = buildBundleItems(
            input(ModelFamily.SD, sources = listOf(source), sourceRoles = mapOf(source.id to "text_encoder"))
        )
        assertTrue(withRole.isValid)
        assertEquals("text_encoder", withRole.items.single().role)
    }

    @Test
    fun `per source role overrides an attached inferred role`() {
        val asset = model("vision.gguf")
        val source = source("vision-source", ModelFamily.LLM, "vision.gguf")
        val edge = ModelProvenanceEntity(
            id = "edge",
            sourceId = source.id,
            modelKey = asset.stableId,
            family = ModelFamily.LLM.storedValue,
            role = "llm",
            localPath = asset.path
        )

        val result = buildBundleItems(
            input(
                ModelFamily.SD,
                assets = listOf(asset),
                sources = listOf(source),
                sourceRoles = mapOf(source.id to "mmproj"),
                provenance = listOf(edge)
            )
        )

        assertTrue(result.isValid)
        assertEquals("mmproj", result.items.single().role)
    }

    @Test
    fun `attached source still rejects incompatible family after role edit`() {
        val asset = model("attached.gguf")
        val source = source("attached-source", ModelFamily.LLM, "attached.gguf")
        val edge = ModelProvenanceEntity(
            id = "attached-edge",
            sourceId = source.id,
            modelKey = asset.stableId,
            family = ModelFamily.LLM.storedValue,
            role = "llm",
            localPath = asset.path
        )

        val result = buildBundleItems(
            input(
                ModelFamily.ONNX,
                assets = listOf(asset),
                sources = listOf(source),
                sourceRoles = mapOf(source.id to "text_encoder"),
                provenance = listOf(edge)
            )
        )

        assertFalse(result.isValid)
        assertTrue(result.conflicts.any { it.code == BundleItemBuildConflictCode.INCOMPATIBLE_SOURCE_FAMILY })
    }

    @Test
    fun `source-only split shard keeps namespaced group metadata`() {
        val source = source(
            id = "shard-source",
            family = ModelFamily.LLM,
            filePath = "org/repo/model-00001-of-00002.gguf"
        )

        val result = buildBundleItems(
            input(ModelFamily.LLM, sources = listOf(source))
        )

        assertTrue(result.isValid)
        val item = result.items.single()
        assertEquals("org/repo/model.gguf", item.partGroup)
        assertEquals(0, item.partIndex)
        assertEquals(2, item.partCount)
        assertEquals("org/repo/model-00001-of-00002.gguf", item.relativePath)
    }

    @Test
    fun `existing source-only split shard restores missing group metadata`() {
        val source = source(
            id = "legacy-shard-source",
            family = ModelFamily.LLM,
            filePath = "org/repo/model-00002-of-00003.gguf"
        )
        val old = ModelBundleItemEntity(
            bundleId = "old",
            itemKey = "source:${source.id}",
            family = ModelFamily.LLM.storedValue,
            sourceId = source.id,
            relativePath = source.filePath!!,
            localFilename = "model-00002-of-00003.gguf"
        )

        val result = buildBundleItems(
            input(ModelFamily.LLM, sources = listOf(source), existing = listOf(old))
        )

        assertTrue(result.isValid)
        val item = result.items.single()
        assertEquals("org/repo/model.gguf", item.partGroup)
        assertEquals(1, item.partIndex)
        assertEquals(3, item.partCount)
    }

    @Test
    fun `explicit source path selects exact nested member and preserves source URL`() {
        val first = model("repo-a", "/models/repo-a")
        val second = model("repo-b", "/models/repo-b")
        val source = source("shared-name", ModelFamily.LLM, "model.safetensors")

        val result = buildBundleItems(
            input(
                ModelFamily.LLM,
                assets = listOf(first, second),
                sources = listOf(source),
                sourceRelativePaths = mapOf(source.id to "repo-b/model.safetensors"),
                directories = mapOf(
                    first.stableId to listOf(BundleDirectoryMember("model.safetensors", "/models/repo-a/model.safetensors")),
                    second.stableId to listOf(BundleDirectoryMember("model.safetensors", "/models/repo-b/model.safetensors"))
                )
            )
        )

        assertTrue(result.isValid)
        assertEquals("https://example.test/${source.id}", source.url)
        assertEquals(source.id, result.items.single { it.relativePath == "repo-b/model.safetensors" }.sourceId)
        assertEquals(null, result.items.single { it.relativePath == "repo-a/model.safetensors" }.sourceId)
    }

    @Test
    fun `explicit source path becomes a source-only destination when member is not installed`() {
        val source = source("remote-shard", ModelFamily.LLM, "model.safetensors")
        val result = buildBundleItems(
            input(
                ModelFamily.LLM,
                sources = listOf(source),
                sourceRelativePaths = mapOf(source.id to "repo-b/model.safetensors")
            )
        )

        assertTrue(result.isValid)
        assertEquals("repo-b/model.safetensors", result.items.single().relativePath)
        assertEquals(source.id, result.items.single().sourceId)
    }

    @Test
    fun `unsafe explicit source paths are rejected before mapping`() {
        val unsafePaths = listOf(
            "../escape.gguf",
            "nested/../../escape.gguf",
            "/absolute/model.gguf",
            "C:\\models\\model.gguf",
            "nested//model.gguf",
            "nested/./model.gguf",
            "nested/\u0000model.gguf",
            "C:relative.gguf",
            "model.gguf\n",
            "\t"
        )

        unsafePaths.forEachIndexed { index, unsafePath ->
            val source = source("unsafe-$index", ModelFamily.LLM, "model.gguf")
            val result = buildBundleItems(
                input(
                    ModelFamily.LLM,
                    sources = listOf(source),
                    sourceRelativePaths = mapOf(source.id to unsafePath)
                )
            )

            assertFalse("$unsafePath should be rejected", result.isValid)
            assertTrue(
                "$unsafePath should report INVALID_SOURCE_PATH",
                result.conflicts.any { it.code == BundleItemBuildConflictCode.INVALID_SOURCE_PATH }
            )
            assertTrue(result.items.none { it.sourceId == source.id })
        }
    }

    @Test
    fun `explicit source path updates source-only local filename`() {
        val source = source("remapped", ModelFamily.LLM, "old/model.gguf")
        val old = ModelBundleItemEntity(
            bundleId = "old",
            itemKey = "source:${source.id}",
            family = ModelFamily.LLM.storedValue,
            sourceId = source.id,
            relativePath = "old/model.gguf",
            localFilename = "model.gguf"
        )

        val result = buildBundleItems(
            input(
                ModelFamily.LLM,
                sources = listOf(source),
                existing = listOf(old),
                sourceRelativePaths = mapOf(source.id to "new/weights.safetensors")
            )
        )

        assertTrue(result.isValid)
        assertEquals("new/weights.safetensors", result.items.single().relativePath)
        assertEquals("weights.safetensors", result.items.single().localFilename)
    }

    @Test
    fun `two LiteRT packages are namespaced while legacy path remains stable`() {
        val first = InstalledModelAsset.fromLiteRt(
            LiteRtModelEntity(id = 1L, displayName = "one", path = "/models/litert_models/one/model.tflite", filename = "model.tflite")
        )
        val second = InstalledModelAsset.fromLiteRt(
            LiteRtModelEntity(id = 2L, displayName = "two", path = "/models/litert_models/two/model.tflite", filename = "model.tflite")
        )
        val members = listOf(BundleDirectoryMember("model.tflite", "/models/litert_models/model.tflite"))
        val result = buildBundleItems(
            input(
                ModelFamily.LITERT,
                assets = listOf(first, second),
                directories = mapOf(first.stableId to members, second.stableId to members)
            )
        )
        val paths = result.items.mapNotNull { it.relativePath }
        assertEquals(2, paths.size)
        assertEquals(2, paths.distinct().size)
        assertTrue(paths.all { it.startsWith("package-litert_") })

        val legacy = ModelBundleItemEntity(
            bundleId = "old",
            itemKey = "${first.stableId}:model.tflite",
            family = ModelFamily.LITERT.storedValue,
            relativePath = "model.tflite",
            localFilename = "model.tflite"
        )
        val restored = buildBundleItems(
            input(
                ModelFamily.LITERT,
                assets = listOf(first),
                existing = listOf(legacy),
                directories = mapOf(first.stableId to members),
                selectedModels = setOf(first.stableId, legacy.itemKey)
            )
        )
        assertEquals("model.tflite", restored.items.single().relativePath)
    }
}
