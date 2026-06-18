package com.example.llamadroid.onnx

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import java.io.File
import java.util.EnumSet
import kotlin.math.max
import kotlin.math.roundToInt

enum class OnnxGraphOptimizationLevel {
    DISABLE_ALL,
    BASIC,
    EXTENDED,
    ALL
}

enum class OnnxExecutionMode {
    SEQUENTIAL,
    PARALLEL
}

enum class OnnxBackendOverride {
    DEFAULT,
    CPU,
    NNAPI
}

enum class OnnxRamProfile {
    LOW,
    MEDIUM,
    HIGH,
    EXTREME
}

@Parcelize
@Serializable
data class OnnxRuntimeOptions(
    val runtimeThreadCount: Int? = null,
    val graphOptimizationLevel: OnnxGraphOptimizationLevel = OnnxGraphOptimizationLevel.ALL,
    val unetBackendOverride: OnnxBackendOverride = OnnxBackendOverride.DEFAULT,
    val vaeDecoderBackendOverride: OnnxBackendOverride = OnnxBackendOverride.DEFAULT,
    val vaeEncoderBackendOverride: OnnxBackendOverride = OnnxBackendOverride.DEFAULT,
    val intraOpThreads: Int? = null,
    val interOpThreads: Int? = null,
    val executionMode: OnnxExecutionMode = OnnxExecutionMode.SEQUENTIAL,
    val memoryPatternOptimization: Boolean = true,
    val cpuArenaAllocator: Boolean = true,
    val nnapiCpuDisabled: Boolean = true,
    val nnapiUseFp16: Boolean = false
) : Parcelable

@Serializable
data class OnnxRuntimeComponentSummary(
    val component: String,
    val requestedBackend: String,
    val resolvedBackend: String,
    val warningMessage: String? = null
)

@Serializable
data class OnnxRuntimeExecutionSummary(
    val textEncoder: OnnxRuntimeComponentSummary,
    val unet: OnnxRuntimeComponentSummary,
    val vaeDecoder: OnnxRuntimeComponentSummary,
    val vaeEncoder: OnnxRuntimeComponentSummary? = null,
    val options: OnnxRuntimeOptions = OnnxRuntimeOptions()
)

data class OnnxSessionLoadResult(
    val session: OrtSession,
    val summary: OnnxRuntimeComponentSummary
) : AutoCloseable {
    override fun close() {
        session.close()
    }
}

data class OnnxNormalizedCanvasSize(
    val requestedWidth: Int,
    val requestedHeight: Int,
    val normalizedWidth: Int,
    val normalizedHeight: Int
) {
    val wasAdjusted: Boolean
        get() = requestedWidth != normalizedWidth || requestedHeight != normalizedHeight
}

data class OnnxRuntimeFeatureSupport(
    val graphOptimizationLevel: Boolean,
    val intraOpThreads: Boolean,
    val interOpThreads: Boolean,
    val executionMode: Boolean,
    val memoryPatternOptimization: Boolean,
    val cpuArenaAllocator: Boolean,
    val nnapi: Boolean,
    val nnapiCpuDisabled: Boolean,
    val nnapiUseFp16: Boolean
)

private const val ONNX_MIN_DIMENSION = 64
private const val ONNX_DIMENSION_GRID = 8

fun normalizeOnnxCanvasSize(requestedWidth: Int, requestedHeight: Int): OnnxNormalizedCanvasSize {
    val safeWidth = requestedWidth.coerceAtLeast(ONNX_MIN_DIMENSION)
    val safeHeight = requestedHeight.coerceAtLeast(ONNX_MIN_DIMENSION)
    return OnnxNormalizedCanvasSize(
        requestedWidth = requestedWidth,
        requestedHeight = requestedHeight,
        normalizedWidth = nearestGridSize(safeWidth),
        normalizedHeight = nearestGridSize(safeHeight)
    )
}

fun estimateOnnxRamProfile(normalizedWidth: Int, normalizedHeight: Int): OnnxRamProfile {
    val pixelArea = normalizedWidth.toLong() * normalizedHeight.toLong()
    return when {
        pixelArea <= 512L * 512L -> OnnxRamProfile.LOW
        pixelArea <= 768L * 768L -> OnnxRamProfile.MEDIUM
        pixelArea <= 1024L * 1024L -> OnnxRamProfile.HIGH
        else -> OnnxRamProfile.EXTREME
    }
}

fun detectOnnxRuntimeFeatureSupport(): OnnxRuntimeFeatureSupport {
    val methods = OrtSession.SessionOptions::class.java.methods
    val methodNames = methods.map { it.name }.toSet()
    val nnapiFlagsClass = OrtSession.SessionOptions::class.java.declaredClasses.firstOrNull {
        it.simpleName.equals("NNAPIFlags", ignoreCase = true)
    }
    val nnapiFlagNames = nnapiFlagsClass
        ?.enumConstants
        ?.mapNotNull { (it as? Enum<*>)?.name }
        ?.toSet()
        ?: emptySet()
    return OnnxRuntimeFeatureSupport(
        graphOptimizationLevel = methodNames.contains("setOptimizationLevel"),
        intraOpThreads = methodNames.contains("setIntraOpNumThreads"),
        interOpThreads = methodNames.contains("setInterOpNumThreads"),
        executionMode = methodNames.contains("setExecutionMode"),
        memoryPatternOptimization = methodNames.contains("setMemoryPatternOptimization"),
        cpuArenaAllocator = methodNames.contains("setCPUArenaAllocator"),
        nnapi = methodNames.contains("addNnapi"),
        nnapiCpuDisabled = nnapiFlagNames.contains("CPU_DISABLED"),
        nnapiUseFp16 = nnapiFlagNames.contains("USE_FP16")
    )
}

fun OnnxRuntimeOptions.backendFor(
    component: String,
    defaultBackend: OnnxRuntimeBackend
): OnnxRuntimeBackend {
    val override = when (component) {
        "unet" -> unetBackendOverride
        "vae_decoder" -> vaeDecoderBackendOverride
        "vae_encoder" -> vaeEncoderBackendOverride
        else -> OnnxBackendOverride.DEFAULT
    }
    return when (override) {
        OnnxBackendOverride.DEFAULT -> defaultBackend
        OnnxBackendOverride.CPU -> OnnxRuntimeBackend.CPU
        OnnxBackendOverride.NNAPI -> OnnxRuntimeBackend.NNAPI
    }
}

fun OnnxRuntimeOptions.toDisplayLines(): List<String> {
    val lines = mutableListOf<String>()
    runtimeThreadCount?.let { lines += "threads=$it" }
    lines += "graph_opt=${graphOptimizationLevel.name}"
    intraOpThreads?.let { lines += "intra_op=$it" }
    interOpThreads?.let { lines += "inter_op=$it" }
    lines += "execution_mode=${executionMode.name}"
    lines += "memory_pattern=${if (memoryPatternOptimization) "on" else "off"}"
    lines += "cpu_arena=${if (cpuArenaAllocator) "on" else "off"}"
    lines += "nnapi_cpu_disabled=${if (nnapiCpuDisabled) "on" else "off"}"
    lines += "nnapi_fp16=${if (nnapiUseFp16) "on" else "off"}"
    if (unetBackendOverride != OnnxBackendOverride.DEFAULT) {
        lines += "unet_backend=${unetBackendOverride.name}"
    }
    if (vaeDecoderBackendOverride != OnnxBackendOverride.DEFAULT) {
        lines += "vae_decoder_backend=${vaeDecoderBackendOverride.name}"
    }
    if (vaeEncoderBackendOverride != OnnxBackendOverride.DEFAULT) {
        lines += "vae_encoder_backend=${vaeEncoderBackendOverride.name}"
    }
    return lines
}

fun createOnnxCpuSession(
    environment: OrtEnvironment,
    modelFile: File,
    runtimeOptions: OnnxRuntimeOptions,
    loadOrtFormat: Boolean = true
): OrtSession {
    val options = createOnnxSessionOptions(runtimeOptions, loadOrtFormat)
    return environment.createSession(modelFile.absolutePath, options)
}

fun createOnnxSessionWithBackend(
    environment: OrtEnvironment,
    modelFile: File,
    requestedBackend: OnnxRuntimeBackend,
    runtimeOptions: OnnxRuntimeOptions,
    componentLabel: String,
    loadOrtFormat: Boolean = true
): OnnxSessionLoadResult {
    if (requestedBackend == OnnxRuntimeBackend.CPU) {
        return OnnxSessionLoadResult(
            session = createOnnxCpuSession(environment, modelFile, runtimeOptions, loadOrtFormat),
            summary = OnnxRuntimeComponentSummary(
                component = componentLabel,
                requestedBackend = requestedBackend.name,
                resolvedBackend = OnnxRuntimeBackend.CPU.name
            )
        )
    }

    return try {
        val nnapiOptions = createOnnxSessionOptions(runtimeOptions, loadOrtFormat)
        val enabled = tryEnableNnapi(nnapiOptions, runtimeOptions)
        require(enabled) { "NNAPI provider is not available in this ONNX Runtime build" }
        OnnxSessionLoadResult(
            session = environment.createSession(modelFile.absolutePath, nnapiOptions),
            summary = OnnxRuntimeComponentSummary(
                component = componentLabel,
                requestedBackend = requestedBackend.name,
                resolvedBackend = OnnxRuntimeBackend.NNAPI.name
            )
        )
    } catch (e: Exception) {
        val resolution = resolveOnnxBackend(
            requestedBackend = requestedBackend,
            componentLabel = componentLabel,
            nnapiErrorMessage = e.message
        )
        OnnxSessionLoadResult(
            session = createOnnxCpuSession(environment, modelFile, runtimeOptions, loadOrtFormat),
            summary = OnnxRuntimeComponentSummary(
                component = componentLabel,
                requestedBackend = requestedBackend.name,
                resolvedBackend = resolution.resolvedBackend.name,
                warningMessage = resolution.warningMessage
            )
        )
    }
}

private fun createOnnxSessionOptions(
    runtimeOptions: OnnxRuntimeOptions,
    loadOrtFormat: Boolean
): OrtSession.SessionOptions {
    return OrtSession.SessionOptions().apply {
        if (loadOrtFormat) {
            runCatching {
                javaClass.methods
                    .firstOrNull { it.name == "addConfigEntry" && it.parameterTypes.size == 2 }
                    ?.invoke(this, "session.load_model_format", "ORT")
            }
        }
        runtimeOptions.runtimeThreadCount?.let { threadCount ->
            if (runtimeOptions.intraOpThreads == null) {
                invokeSingleIntArg("setIntraOpNumThreads", threadCount)
            }
        }
        runtimeOptions.intraOpThreads?.let { invokeSingleIntArg("setIntraOpNumThreads", it) }
        runtimeOptions.interOpThreads?.let { invokeSingleIntArg("setInterOpNumThreads", it) }
        invokeBooleanArg("setMemoryPatternOptimization", runtimeOptions.memoryPatternOptimization)
        invokeBooleanArg("setCPUArenaAllocator", runtimeOptions.cpuArenaAllocator)
        applyExecutionMode(runtimeOptions.executionMode)
        applyGraphOptimizationLevel(runtimeOptions.graphOptimizationLevel)
    }
}

private fun OrtSession.SessionOptions.invokeSingleIntArg(methodName: String, value: Int) {
    runCatching {
        javaClass.methods
            .firstOrNull { it.name == methodName && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) }
            ?.invoke(this, value)
    }
}

private fun OrtSession.SessionOptions.invokeBooleanArg(methodName: String, value: Boolean) {
    runCatching {
        javaClass.methods
            .firstOrNull { it.name == methodName && it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) }
            ?.invoke(this, value)
    }
}

private fun OrtSession.SessionOptions.applyExecutionMode(mode: OnnxExecutionMode) {
    runCatching {
        val executionModeClass = javaClass.declaredClasses.firstOrNull {
            it.simpleName.equals("ExecutionMode", ignoreCase = true)
        } ?: return
        val enumName = when (mode) {
            OnnxExecutionMode.SEQUENTIAL -> "SEQUENTIAL"
            OnnxExecutionMode.PARALLEL -> "PARALLEL"
        }
        val enumValue = executionModeClass.enumConstants.firstOrNull {
            (it as? Enum<*>)?.name == enumName
        } ?: return
        javaClass.methods
            .firstOrNull { it.name == "setExecutionMode" && it.parameterTypes.size == 1 }
            ?.invoke(this, enumValue)
    }
}

private fun OrtSession.SessionOptions.applyGraphOptimizationLevel(level: OnnxGraphOptimizationLevel) {
    runCatching {
        val optLevelClass = javaClass.declaredClasses.firstOrNull {
            it.simpleName.equals("OptLevel", ignoreCase = true)
        } ?: return
        val candidates = when (level) {
            OnnxGraphOptimizationLevel.DISABLE_ALL -> listOf("NO_OPT", "DISABLE_ALL")
            OnnxGraphOptimizationLevel.BASIC -> listOf("BASIC_OPT", "BASIC")
            OnnxGraphOptimizationLevel.EXTENDED -> listOf("EXTENDED_OPT", "EXTENDED")
            OnnxGraphOptimizationLevel.ALL -> listOf("ALL_OPT", "ALL")
        }
        val enumValue = optLevelClass.enumConstants.firstOrNull { enumConstant ->
            candidates.contains((enumConstant as? Enum<*>)?.name)
        } ?: return
        javaClass.methods
            .firstOrNull { it.name == "setOptimizationLevel" && it.parameterTypes.size == 1 }
            ?.invoke(this, enumValue)
    }
}

private fun tryEnableNnapi(
    options: OrtSession.SessionOptions,
    runtimeOptions: OnnxRuntimeOptions
): Boolean {
    val method = options.javaClass.methods.firstOrNull { it.name == "addNnapi" } ?: return false
    val parameterType = method.parameterTypes.firstOrNull()
    if (parameterType == null || parameterType == Void.TYPE) {
        method.invoke(options)
        return true
    }
    if (parameterType.name != "java.util.EnumSet") {
        return false
    }
    val flagsClass = options.javaClass.declaredClasses.firstOrNull {
        it.simpleName.equals("NNAPIFlags", ignoreCase = true)
    } ?: return false
    @Suppress("UNCHECKED_CAST")
    val enumSet = EnumSet::class.java
        .getMethod("noneOf", Class::class.java)
        .invoke(null, flagsClass) as MutableSet<Any?>
    if (runtimeOptions.nnapiCpuDisabled) {
        flagsClass.enumConstants.firstOrNull { (it as? Enum<*>)?.name == "CPU_DISABLED" }?.let {
            enumSet.add(it)
        }
    }
    if (runtimeOptions.nnapiUseFp16) {
        flagsClass.enumConstants.firstOrNull { (it as? Enum<*>)?.name == "USE_FP16" }?.let {
            enumSet.add(it)
        }
    }
    method.invoke(options, enumSet)
    return true
}

private fun nearestGridSize(value: Int, grid: Int = ONNX_DIMENSION_GRID): Int {
    val rounded = max(grid, (value.toFloat() / grid.toFloat()).roundToInt() * grid)
    return rounded.coerceAtLeast(ONNX_MIN_DIMENSION)
}
