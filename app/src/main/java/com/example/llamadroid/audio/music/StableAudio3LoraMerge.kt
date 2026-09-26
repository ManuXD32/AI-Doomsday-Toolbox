package com.example.llamadroid.audio.music

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Progress emitted while a disposable merged DiT is prepared. */
data class StableAudio3LoraMergeProgress(
    val stage: String,
    val completedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val completedTensors: Int = 0,
    val totalTensors: Int = 0
)

typealias StableAudio3LoraProgressSink = suspend (StableAudio3LoraMergeProgress) -> Unit

/**
 * Bakes Stable Audio 3 safetensor LoRAs into a disposable LiteRT DiT copy.
 *
 * LiteRT packs constants when the interpreter allocates tensors, so the only
 * safe Android implementation is to patch the original FP32 or FP16 buffer
 * before the worker starts. The original component is never modified. Cache
 * entries are keyed by the actual base and adapter content digests, adapter
 * types/scales, precision, family, and requested strengths.
 */
object StableAudio3LoraMerge {
    private const val MERGE_VERSION = "stable-audio3-lora-android-v2"
    private const val CACHE_DIRECTORY = "stable_audio3_lora_cache"
    private const val MANIFEST_VERSION = 1
    private val mergeMutex = Mutex()

    suspend fun prepare(
        context: Context,
        request: StableAudio3Request,
        onProgress: StableAudio3LoraProgressSink = {},
        isCancelled: () -> Boolean = { false }
    ): StableAudio3Request = withContext(Dispatchers.IO) {
        val normalized = request.normalized()
        if (normalized.loras.isEmpty()) return@withContext normalized
        checkCancelled(isCancelled)
        require(normalized.ditPrecision.supportsLora) {
            "Stable Audio LoRA merging supports only fp32 and w16a32 DiT precision"
        }
        val dit = normalized.components.dit.validate("DiT")
        val base = File(dit.path)
        require(base.isFile) { "Stable Audio DiT is missing: ${base.absolutePath}" }
        require(base.extension.equals("tflite", ignoreCase = true)) {
            "Stable Audio LoRA merging requires a .tflite DiT component"
        }
        val expectedBaseSize = dit.sizeBytes
        val baseDigest = digestFile(base, "hashing_base", onProgress, isCancelled)
        require(dit.sha256 == null || dit.sha256.equals(baseDigest, ignoreCase = true)) {
            "Stable Audio DiT digest does not match the installed component"
        }
        if (expectedBaseSize != null) {
            require(expectedBaseSize == base.length()) {
                "Stable Audio DiT size does not match the installed component"
            }
        }

        val mergeContext = coroutineContext
        val checkpoint = { mergeContext.ensureActive(); if (isCancelled()) throw CancellationException("LoRA cancelled") }
        var remainingAdapterBytes = Runtime.getRuntime().let { it.maxMemory() - (it.totalMemory() - it.freeMemory()) } / 4L
        val parsed = normalized.loras.mapIndexed { index, lora ->
            checkCancelled(isCancelled)
            val resolved = StableAudio3SafeTensors.resolveAdapter(lora.path)
            val digest = digestFile(resolved, "hashing_adapter_${index + 1}", onProgress, isCancelled)
            val adapter = StableAudio3SafeTensors.parse(resolved.absolutePath, digest, remainingAdapterBytes, checkpoint)
            remainingAdapterBytes -= adapter.layers.values.sumOf { layer ->
                layer.parameters.values.sumOf { it.values.size.toLong() * 4L }
            }
            require(adapter.scaling.isFinite()) { "${resolved.name}: adapter scale is not finite" }
            if (adapter.adapterType.endsWith("-xs")) {
                throw StableAudio3LoraException(
                    "${resolved.name}: ${adapter.adapterType} requires an SVD basis; " +
                        "the Android LiteRT merger supports lora, dora, and bora adapters"
                )
            }
            ResolvedAdapter(lora.strength, adapter)
        }
        val cacheKey = cacheKey(baseDigest, normalized, parsed)
        val cacheDirectory = File(context.applicationContext.cacheDir, CACHE_DIRECTORY)
        require(cacheDirectory.isDirectory || cacheDirectory.mkdirs()) {
            "Stable Audio LoRA cache directory is unavailable"
        }
        val output = File(
            cacheDirectory,
            "dit_${safeName(normalized.kind.family)}_${normalized.ditPrecision.wireValue}_$cacheKey.tflite"
        )
        val manifest = manifestFile(output)

        mergeMutex.withLock {
            checkCancelled(isCancelled)
            readCache(
                output = output,
                manifest = manifest,
                cacheKey = cacheKey,
                baseDigest = baseDigest,
                request = normalized,
                parsed = parsed,
                onProgress = onProgress,
                isCancelled = isCancelled
            )?.let { return@withLock it }

            onProgress(StableAudio3LoraMergeProgress("reading_graph"))
            val weights = StableAudio3TfliteWeights.discover(base)
            require(weights.isNotEmpty()) { "Stable Audio DiT has no patchable fully connected weights" }
            val expectedType = when (normalized.ditPrecision) {
                StableAudio3DitPrecision.FP32 -> StableAudio3TfliteWeightType.FP32
                StableAudio3DitPrecision.W16A32 -> StableAudio3TfliteWeightType.FP16
                else -> error("unsupported LoRA precision")
            }
            require(weights.all { it.type == expectedType }) {
                "Stable Audio DiT buffers do not match requested ${normalized.ditPrecision.wireValue} precision"
            }
            val mapper = StableAudio3LoraMapper(weights)
            val mapped = linkedMapOf<Long, MutableList<MappedLayer>>()
            var matched = 0
            parsed.forEach { adapter ->
                adapter.adapter.layers.forEach { (layerName, layer) ->
                    checkCancelled(isCancelled)
                    val shape = adapterShape(layerName, layer)
                    val weight = mapper.resolve(layerName, shape)
                    validateLayerShape(layerName, weight, layer, adapter.adapter.adapterType)
                    mapped.getOrPut(weight.offset) { mutableListOf() }
                        .add(MappedLayer(adapter, layerName, layer, weight))
                    matched++
                }
            }
            require(matched > 0) { "No Stable Audio LoRA layers matched this DiT" }

            val temporary = File(cacheDirectory, "${output.name}.tmp-${UUID.randomUUID()}")
            var publishedModel = false
            var publishedManifest = false
            try {
                copyFile(base, temporary, onProgress, isCancelled)
                patchFile(
                    base = base,
                    patched = temporary,
                    mapped = mapped,
                    onProgress = onProgress,
                    isCancelled = isCancelled
                )
                val mergedDigest = digestFile(temporary, "hashing_merged", onProgress, isCancelled)
                val provenance = provenance(cacheKey, baseDigest, parsed)
                publishModel(temporary, output, mergedDigest, isCancelled)
                publishedModel = true
                publishManifest(
                    manifest = manifest,
                    json = manifestJson(
                        cacheKey = cacheKey,
                        request = normalized,
                        base = dit,
                        baseDigest = baseDigest,
                        parsed = parsed,
                        output = output,
                        mergedDigest = mergedDigest,
                        provenance = provenance
                    )
                )
                publishedManifest = true
                onProgress(
                    StableAudio3LoraMergeProgress(
                        stage = "complete",
                        completedBytes = output.length(),
                        totalBytes = output.length(),
                        completedTensors = mapped.size,
                        totalTensors = mapped.size
                    )
                )
                normalized.copy(
                    components = normalized.components.copy(
                        dit = dit.copy(
                            path = output.absolutePath,
                            sha256 = mergedDigest,
                            sizeBytes = output.length(),
                            sourceIdentity = provenance
                        )
                    ),
                    loras = emptyList()
                )
            } catch (error: Throwable) {
                temporary.delete()
                // The cache has no other reader until prepare returns; remove an unpublished pair.
                if (publishedModel && !publishedManifest) {
                    output.delete()
                    manifest.delete()
                }
                throw error
            }
        }
    }

    private data class ResolvedAdapter(
        val strength: Float,
        val adapter: StableAudio3ParsedAdapter
    )

    private data class MappedLayer(
        val adapter: ResolvedAdapter,
        val name: String,
        val layer: StableAudio3LoraLayer,
        val weight: StableAudio3TfliteWeight
    )

    private fun adapterShape(layerName: String, layer: StableAudio3LoraLayer): IntArray {
        val a = layer.parameters["lora_A"]
            ?: throw StableAudio3LoraException("$layerName: adapter is missing lora_A")
        val b = layer.parameters["lora_B"]
            ?: throw StableAudio3LoraException("$layerName: adapter is missing lora_B")
        require(a.shape.size == 2 && b.shape.size == 2) {
            "$layerName: lora_A and lora_B must be rank-2 matrices"
        }
        return intArrayOf(b.shape[0], a.shape[1])
    }

    private fun validateLayerShape(
        layerName: String,
        weight: StableAudio3TfliteWeight,
        layer: StableAudio3LoraLayer,
        adapterType: String
    ) {
        val a = layer.parameters["lora_A"]
            ?: throw StableAudio3LoraException("$layerName: adapter is missing lora_A")
        val b = layer.parameters["lora_B"]
            ?: throw StableAudio3LoraException("$layerName: adapter is missing lora_B")
        require(a.shape.size == 2 && b.shape.size == 2 &&
            b.shape[0] == weight.shape[0] && a.shape[1] == weight.shape[1] &&
            a.shape[0] == b.shape[1]) {
            "$layerName: adapter matrices ${b.shape.contentToString()}·${a.shape.contentToString()} " +
                "do not fit base ${weight.shape.contentToString()}"
        }
        when (adapterType) {
            "lora" -> Unit
            "dora-rows" -> requireMagnitude(layerName, layer.parameters["magnitude"], weight.shape[0])
            "dora-cols" -> requireMagnitude(layerName, layer.parameters["magnitude"], weight.shape[1])
            "bora" -> {
                requireMagnitude(layerName, layer.parameters["magnitude_r"], weight.shape[0])
                requireMagnitude(layerName, layer.parameters["magnitude_c"], weight.shape[1])
            }
            else -> throw StableAudio3LoraException("$layerName: unsupported adapter type $adapterType")
        }
    }

    private fun requireMagnitude(layerName: String, tensor: StableAudio3LoraTensor?, expected: Int) {
        require(tensor != null && tensor.values.size == expected) {
            "$layerName: adapter magnitude must contain $expected values"
        }
    }

    private suspend fun patchFile(
        base: File,
        patched: File,
        mapped: Map<Long, List<MappedLayer>>,
        onProgress: StableAudio3LoraProgressSink,
        isCancelled: () -> Boolean
    ) {
        val total = mapped.size
        var completed = 0
        val mergeContext = coroutineContext
        val checkpoint = { mergeContext.ensureActive(); if (isCancelled()) throw CancellationException("LoRA cancelled") }
        RandomAccessFile(base, "r").use { original ->
            RandomAccessFile(patched, "rw").use { output ->
                mapped.values.forEach { layers ->
                    checkCancelled(isCancelled)
                    val first = layers.first().weight
                    val elements = first.shape.fold(1L) { count, dimension -> Math.multiplyExact(count, dimension.toLong()) }
                    requireMergeHeap(elements * 32L)
                    val baseValues = readWeight(original, first)
                    val merged = baseValues.copyOf()
                    layers.forEach { mappedLayer ->
                        require(mappedLayer.weight.type == first.type &&
                            mappedLayer.weight.shapeKey == first.shapeKey) {
                            "${mappedLayer.name}: adapters resolve to incompatible shared weight buffers"
                        }
                        val delta = StableAudio3LoraMath.validateAndMergeDelta(
                            layerName = mappedLayer.name,
                            base = baseValues,
                            rows = first.shape[0],
                            columns = first.shape[1],
                            layer = mappedLayer.layer,
                            adapterType = mappedLayer.adapter.adapter.adapterType,
                            scaling = mappedLayer.adapter.adapter.scaling,
                            checkpoint = checkpoint
                        )
                        for (index in merged.indices) {
                            merged[index] += mappedLayer.adapter.strength * delta[index]
                        }
                    }
                    require(merged.all { it.isFinite() }) {
                        "${first.name}: LoRA merge produced a non-finite weight"
                    }
                    output.seek(first.offset)
                    output.write(encodeWeight(merged, first.type))
                    completed++
                    onProgress(
                        StableAudio3LoraMergeProgress(
                            stage = "writing_weights",
                            completedTensors = completed,
                            totalTensors = total
                        )
                    )
                }
                output.fd.sync()
            }
        }
    }

    private fun readWeight(file: RandomAccessFile, weight: StableAudio3TfliteWeight): FloatArray {
        val bytes = ByteArray(weight.byteSize)
        file.seek(weight.offset)
        file.readFully(bytes)
        val result = FloatArray(weight.shape.fold(1) { product, dimension -> product * dimension })
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        when (weight.type) {
            StableAudio3TfliteWeightType.FP32 -> buffer.asFloatBuffer().get(result)
            StableAudio3TfliteWeightType.FP16 -> for (index in result.indices) {
                result[index] = halfToFloat(buffer.getShort().toInt() and 0xffff)
            }
        }
        require(result.all { it.isFinite() }) { "${weight.name}: base weight contains non-finite values" }
        return result
    }

    private fun encodeWeight(values: FloatArray, type: StableAudio3TfliteWeightType): ByteArray {
        val bytes = ByteArray(values.size * type.bytesPerValue)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        when (type) {
            StableAudio3TfliteWeightType.FP32 -> buffer.asFloatBuffer().put(values)
            StableAudio3TfliteWeightType.FP16 -> values.forEach { buffer.putShort(floatToHalf(it).toShort()) }
        }
        return bytes
    }

    private suspend fun copyFile(
        source: File,
        destination: File,
        onProgress: StableAudio3LoraProgressSink,
        isCancelled: () -> Boolean
    ) {
        val total = source.length()
        var completed = 0L
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    checkCancelled(isCancelled)
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    completed += count
                    onProgress(StableAudio3LoraMergeProgress("copying_base", completed, total))
                }
                output.fd.sync()
            }
        }
    }

    private suspend fun digestFile(
        file: File,
        stage: String,
        onProgress: StableAudio3LoraProgressSink,
        isCancelled: () -> Boolean
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var completed = 0L
        val total = file.length()
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                checkCancelled(isCancelled)
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                completed += count
                onProgress(StableAudio3LoraMergeProgress(stage, completed, total))
            }
        }
        return digest.digest().toHex()
    }

    private suspend fun readCache(
        output: File,
        manifest: File,
        cacheKey: String,
        baseDigest: String,
        request: StableAudio3Request,
        parsed: List<ResolvedAdapter>,
        onProgress: StableAudio3LoraProgressSink,
        isCancelled: () -> Boolean
    ): StableAudio3Request? {
        if (!output.isFile || !manifest.isFile) return null
        val json = runCatching { JSONObject(manifest.readText()) }.getOrNull() ?: return null
        if (json.optInt("schemaVersion", -1) != MANIFEST_VERSION ||
            json.optString("cacheKey") != cacheKey ||
            json.optString("baseDigest") != baseDigest ||
            json.optString("mergedDigest").length != 64
        ) return null
        val expectedSize = json.optLong("mergedSizeBytes", -1L)
        if (expectedSize != output.length()) return null
        val actualDigest = digestFile(output, "verifying_cache", onProgress, isCancelled)
        if (!actualDigest.equals(json.optString("mergedDigest"), ignoreCase = true)) return null
        val provenance = json.optString("provenance").takeIf { it.isNotBlank() }
            ?: provenance(cacheKey, baseDigest, parsed)
        return request.copy(
            components = request.components.copy(
                dit = request.components.dit.copy(
                    path = output.absolutePath,
                    sha256 = actualDigest,
                    sizeBytes = output.length(),
                    sourceIdentity = provenance
                )
            ),
            loras = emptyList()
        )
    }

    private fun manifestJson(
        cacheKey: String,
        request: StableAudio3Request,
        base: StableAudio3ComponentRef,
        baseDigest: String,
        parsed: List<ResolvedAdapter>,
        output: File,
        mergedDigest: String,
        provenance: String
    ): JSONObject = JSONObject().apply {
        put("schemaVersion", MANIFEST_VERSION)
        put("cacheKey", cacheKey)
        put("family", request.kind.family)
        put("precision", request.ditPrecision.wireValue)
        put("basePath", base.path)
        put("baseDigest", baseDigest)
        put("baseSizeBytes", base.sizeBytes ?: JSONObject.NULL)
        put("mergedPath", output.absolutePath)
        put("mergedDigest", mergedDigest)
        put("mergedSizeBytes", output.length())
        put("provenance", provenance)
        put("adapters", JSONArray().apply {
            parsed.forEach { adapter ->
                put(JSONObject().apply {
                    put("path", adapter.adapter.file.absolutePath)
                    put("digest", adapter.adapter.digest)
                    put("type", adapter.adapter.adapterType)
                    put("scaling", adapter.adapter.scaling.toDouble())
                    put("strength", adapter.strength.toDouble())
                })
            }
        })
    }

    private suspend fun publishModel(
        temporary: File,
        output: File,
        mergedDigest: String,
        isCancelled: () -> Boolean
    ) {
        checkCancelled(isCancelled)
        if (output.isFile) {
            val existing = digestFile(output, "verifying_cache", {}, isCancelled)
            if (existing.equals(mergedDigest, ignoreCase = true)) {
                temporary.delete()
                return
            }
            require(output.delete()) { "Unable to replace stale Stable Audio LoRA cache entry" }
        }
        require(temporary.renameTo(output)) {
            if (output.isFile) {
                "Stable Audio LoRA cache publication raced with another writer"
            } else {
                "Unable to publish merged Stable Audio DiT cache entry"
            }
        }
    }

    private fun publishManifest(manifest: File, json: JSONObject) {
        val temporary = File(manifest.parentFile, "${manifest.name}.tmp-${UUID.randomUUID()}")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(json.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (manifest.isFile) manifest.delete()
            require(temporary.renameTo(manifest)) { "Unable to publish Stable Audio LoRA cache manifest" }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun cacheKey(
        baseDigest: String,
        request: StableAudio3Request,
        parsed: List<ResolvedAdapter>
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(bytes.size).array())
            digest.update(bytes)
        }
        add(MERGE_VERSION)
        add(request.kind.family)
        add(request.ditPrecision.wireValue)
        add(baseDigest)
        parsed.forEach { adapter ->
            add(adapter.adapter.digest)
            add(adapter.adapter.adapterType)
            add(java.lang.Float.floatToIntBits(adapter.adapter.scaling).toString())
            add(java.lang.Float.floatToIntBits(adapter.strength).toString())
        }
        return digest.digest().toHex()
    }

    private fun provenance(
        cacheKey: String,
        baseDigest: String,
        parsed: List<ResolvedAdapter>
    ): String = buildString {
        append("stable-audio3-lora:").append(cacheKey)
        append(";base=").append(baseDigest)
        parsed.forEach { adapter ->
            append(";adapter=").append(adapter.adapter.digest)
            append("@").append(java.lang.Float.floatToIntBits(adapter.strength).toUInt().toString(16))
        }
    }

    private fun manifestFile(output: File): File =
        File(output.parentFile, output.nameWithoutExtension + ".json")

    private fun safeName(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('.', '_')
        .ifBlank { "stable_audio" }

    private suspend fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw CancellationException("Stable Audio LoRA merge cancelled")
        coroutineContext.ensureActive()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun halfToFloat(bits: Int): Float {
        val sign = (bits ushr 15) and 1
        val exponent = (bits ushr 10) and 0x1f
        val fraction = bits and 0x3ff
        val result = when (exponent) {
            0 -> if (fraction == 0) sign shl 31 else {
                var mantissa = fraction
                var exp = -14
                while ((mantissa and 0x400) == 0) {
                    mantissa = mantissa shl 1
                    exp--
                }
                (sign shl 31) or ((exp + 127) shl 23) or ((mantissa and 0x3ff) shl 13)
            }
            0x1f -> (sign shl 31) or 0x7f800000 or (fraction shl 13)
            else -> (sign shl 31) or ((exponent - 15 + 127) shl 23) or (fraction shl 13)
        }
        return Float.fromBits(result)
    }

    internal fun floatToHalf(value: Float): Int {
        require(value.isFinite()) { "Non-finite FP16 weight" }
        val bits = value.toRawBits()
        val sign = (bits ushr 16) and 0x8000
        val exponent = ((bits ushr 23) and 0xff) - 127 + 15
        val fraction = bits and 0x7fffff
        if (exponent < -10) return sign
        require(exponent < 31) { "LoRA weight exceeds FP16 range" }
        val shift = if (exponent <= 0) 14 - exponent else 13
        val mantissa = if (exponent <= 0) fraction or 0x800000 else fraction
        var rounded = mantissa ushr shift
        val remainder = mantissa and ((1 shl shift) - 1)
        val halfway = 1 shl (shift - 1)
        if (remainder > halfway || (remainder == halfway && (rounded and 1) != 0)) rounded++
        // Addition propagates a rounded mantissa carry into the exponent.
        val magnitude = (if (exponent <= 0) 0 else exponent shl 10) + rounded
        require(magnitude < 0x7c00) { "LoRA weight exceeds FP16 range" }
        return sign or magnitude
    }

}

/** Strict checkpoint-name mapper copied from the pinned upstream exporter. */
private class StableAudio3LoraMapper(private val weights: List<StableAudio3TfliteWeight>) {
    private data class BlockSpec(val module: String, val leaf: String, val glu: Boolean)

    private val block = linkedMapOf<List<Any>, StableAudio3TfliteWeight>()
    private val local = mapOf("0" to mutableListOf<StableAudio3TfliteWeight>(), "2" to mutableListOf())
    private val seconds = mutableListOf<StableAudio3TfliteWeight>()

    init {
        weights.forEach { weight ->
            val module = moduleTag(weight.name)
            val leaf = LEAF.find(weight.name)?.groupValues?.get(1)
            val blockIndex = BLOCK.find(weight.name)?.groupValues?.get(1)?.toIntOrNull()
            if (module == "local_embed" && leaf != null && leaf in setOf("0", "2")) {
                local.getValue(leaf).add(weight)
            } else if (module != null && module in setOf("self_attn", "cross_attn", "ff") &&
                blockIndex != null && leaf != null
            ) {
                val key = listOf<Any>(blockIndex, module, leaf, "_GLUWrap_0" in weight.name)
                require(block.put(key, weight) == null) {
                    "Stable Audio graph has ambiguous FC mapping for ${weight.name}"
                }
            }
            if (weight.shapeKey == listOf(768, 256)) seconds += weight
        }
    }

    fun resolve(layer: String, shape: IntArray): StableAudio3TfliteWeight {
        if (layer == SECONDS_LAYER) {
            return one(layer, shape, seconds, "seconds conditioner")
        }
        val stripped = layer.removePrefix("model.")
        val match = TRANSFORMER_LAYER.matchEntire(stripped)
        if (match != null) {
            val blockIndex = match.groupValues[1].toInt()
            val suffix = match.groupValues[2]
            val spec = blockSpecs[suffix]
                ?: throw StableAudio3LoraException("$layer: unsupported Stable Audio block sub-module $suffix")
            if (spec.module == "local_embed") {
                val group = local.getValue(spec.leaf)
                require(blockIndex in group.indices) {
                    "$layer: local embedding block $blockIndex is outside the exported graph"
                }
                val candidate = group[blockIndex]
                require(candidate.shapeKey == shape.toList()) {
                    "$layer: base ${candidate.shape.contentToString()} does not match adapter ${shape.contentToString()}"
                }
                return candidate
            }
            val candidate = block[listOf<Any>(blockIndex, spec.module, spec.leaf, spec.glu)]
            return one(layer, shape, listOfNotNull(candidate), "block linear")
        }
        val needle = singleton[stripped]
            ?: throw StableAudio3LoraException("$layer: no TFLite tensor mapping is defined")
        return one(layer, shape, weights.filter { needle in it.name && it.shapeKey == shape.toList() }, "singleton")
    }

    private fun one(
        layer: String,
        shape: IntArray,
        candidates: List<StableAudio3TfliteWeight>,
        tier: String
    ): StableAudio3TfliteWeight {
        if (candidates.size == 1) return candidates.single()
        throw StableAudio3LoraException(
            "$layer: expected exactly one $tier FC for shape ${shape.contentToString()}, found ${candidates.size}"
        )
    }

    private fun moduleTag(name: String): String? = when {
        "SelfAttention_self_attn" in name -> "self_attn"
        "CrossAttention_cross_attn" in name -> "cross_attn"
        "Seq_to_local_embed" in name || "Sequential_seq" in name -> "local_embed"
        "FeedForward_ff" in name -> "ff"
        else -> null
    }

    private companion object {
        const val SECONDS_LAYER = "conditioners.seconds_total.embedder.embedding.1"
        val LEAF = Regex("torch\\.nn\\.modules\\.linear\\.Linear_([A-Za-z0-9_]+)")
        val BLOCK = Regex("TransformerBlock_(\\d+)/")
        val TRANSFORMER_LAYER = Regex("transformer\\.layers\\.(\\d+)\\.(.+)")
        val blockSpecs = mapOf(
            "self_attn.to_qkv" to BlockSpec("self_attn", "to_qkv", false),
            "self_attn.to_out" to BlockSpec("self_attn", "to_out", false),
            "cross_attn.to_q" to BlockSpec("cross_attn", "to_q", false),
            "cross_attn.to_kv" to BlockSpec("cross_attn", "to_kv", false),
            "cross_attn.to_out" to BlockSpec("cross_attn", "to_out", false),
            "ff.ff.0.proj" to BlockSpec("ff", "proj", true),
            "ff.ff.2" to BlockSpec("ff", "2", false),
            "to_local_embed.0" to BlockSpec("local_embed", "0", false),
            "to_local_embed.2" to BlockSpec("local_embed", "2", false),
            "to_local_embed.seq.0" to BlockSpec("local_embed", "0", false),
            "to_local_embed.seq.2" to BlockSpec("local_embed", "2", false)
        )
        val singleton = mapOf(
            "transformer.project_in" to "Linear_project_in",
            "transformer.project_out" to "Linear_project_out",
            "to_timestep_embed.0" to "Sequential_to_timestep_embed/torch.nn.modules.linear.Linear_0",
            "to_timestep_embed.2" to "Sequential_to_timestep_embed/torch.nn.modules.linear.Linear_2",
            "to_cond_embed.0" to "Sequential_to_cond_embed/torch.nn.modules.linear.Linear_0",
            "to_cond_embed.2" to "Sequential_to_cond_embed/torch.nn.modules.linear.Linear_2",
            "to_global_embed.0" to "Sequential_to_global_embed/torch.nn.modules.linear.Linear_0",
            "to_global_embed.2" to "Sequential_to_global_embed/torch.nn.modules.linear.Linear_2",
            "transformer.global_cond_embedder.0" to "Sequential_global_cond_embedder/torch.nn.modules.linear.Linear_0",
            "transformer.global_cond_embedder.2" to "Sequential_global_cond_embedder/torch.nn.modules.linear.Linear_2"
        )
    }
}
