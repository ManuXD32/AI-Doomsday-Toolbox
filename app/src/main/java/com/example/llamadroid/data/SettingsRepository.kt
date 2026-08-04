package com.example.llamadroid.data

import android.content.Context
import android.content.SharedPreferences
import com.example.llamadroid.data.model.LITERT_BACKEND_AUTO
import com.example.llamadroid.data.model.normalizeLiteRtBackend
import com.example.llamadroid.onnx.OnnxBackendOverride
import com.example.llamadroid.onnx.OnnxCatalogProvider
import com.example.llamadroid.onnx.OnnxExecutionMode
import com.example.llamadroid.onnx.OnnxGraphOptimizationLevel
import com.example.llamadroid.onnx.OnnxRuntimeBackend
import com.example.llamadroid.service.LlamaSpeculativeMode
import com.example.llamadroid.service.AgentPromptComparisonStore
import com.example.llamadroid.tama.data.TamaPicGenDefaults
import com.example.llamadroid.util.AIConstants
import com.example.llamadroid.util.PromptUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.Locale

data class RemoteSummarySettingsSnapshot(
    val backend: String,
    val ollamaUrl: String,
    val llamaServerUrl: String,
    val llamaSwapUrl: String,
    val ollamaModel: String?,
    val llamaSwapModel: String?,
    val liteRtModelId: Long? = null,
    val liteRtBackend: String = LITERT_BACKEND_AUTO,
    val liteRtMtpEnabled: Boolean = false,
    val thinkingEnabled: Boolean,
    val llamaServerModelLabel: String?,
    val llamaServerContextTokens: Int,
    val llamaServerContextLabel: String?,
    val chunkContext: Int,
    val chunkMaxTokens: Int,
    val mergeContext: Int,
    val mergeMaxTokens: Int,
    val temperature: Float,
    val timeoutMinutes: Int,
    val targetLanguage: String,
    val summaryPrompt: String?,
    val mergePrompt: String?
)

data class PdfTranslationOptionsSnapshot(
    val usePageScreenshotContext: Boolean,
    val screenshotMaxSide: Int,
    val screenshotJpegQuality: Int,
    val textOnlyFallbackEnabled: Boolean,
    val qualityMode: PdfTranslationQualityMode,
    val ocrProvider: PdfOcrProvider,
    val bubbleGuidedOcrEnabled: Boolean,
    val llamaOcr: LlamaOcrSettingsSnapshot
)

enum class PdfTranslationQualityMode {
    BEST_QUALITY,
    BALANCED,
    FASTER
}

enum class PdfOcrProvider {
    ML_KIT,
    LLAMA_CPP_GGUF
}

enum class LlamaOcrPromptPreset(
    val label: String,
    val prompt: String,
    val recommendedFlags: String
) {
    UNLIMITED_OCR(
        label = "Unlimited-OCR",
        prompt = "document parsing.",
        recommendedFlags = "--chat-template deepseek-ocr --flash-attn off"
    ),
    GLM_OCR(
        label = "GLM-OCR",
        prompt = "Extract all text from this image. Preserve reading order and line breaks.",
        recommendedFlags = "--flash-attn off -fit off"
    ),
    DEEPSEEK_OCR(
        label = "DeepSeek-OCR",
        prompt = "<|grounding|>Convert the document to markdown.",
        recommendedFlags = "--chat-template deepseek-ocr --flash-attn off --no-warmup"
    ),
    HUNYUAN_OCR(
        label = "HunyuanOCR",
        prompt = "Recognize all readable text in this image. Preserve reading order and line breaks.",
        recommendedFlags = "--flash-attn off"
    ),
    PADDLEOCR_VL(
        label = "PaddleOCR-VL",
        prompt = "Extract all document text from the image. Return only OCR text.",
        recommendedFlags = "--flash-attn off"
    ),
    DOTS_OCR(
        label = "Dots.OCR",
        prompt = "Extract the text from this image. Keep line breaks and reading order.",
        recommendedFlags = "--flash-attn off"
    ),
    LIGHTON_OCR(
        label = "LightOnOCR",
        prompt = "OCR this page image. Return only the recognized text.",
        recommendedFlags = "--flash-attn off"
    ),
    QIANFAN_OCR(
        label = "Qianfan-OCR",
        prompt = "Recognize the text in this image. Preserve natural reading order.",
        recommendedFlags = "--flash-attn off"
    ),
    GENERIC_OCR(
        label = "Generic OCR",
        prompt = "OCR this image. Return only the recognized text in reading order.",
        recommendedFlags = "--flash-attn off"
    );

    companion object {
        fun fromName(value: String?): LlamaOcrPromptPreset =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: UNLIMITED_OCR
    }
}

data class LlamaOcrSettingsSnapshot(
    val modelPath: String?,
    val mmprojPath: String?,
    val promptPreset: LlamaOcrPromptPreset,
    val customPrompt: String?,
    val contextSize: Int,
    val maxTokens: Int,
    val port: Int,
    val flashAttention: Boolean,
    val cacheRam: Int,
    val parallel: Int,
    val customFlags: String?,
    val commandTemplate: String?,
    val temporarilyReplaceRunningServer: Boolean
) {
    val prompt: String get() = customPrompt?.takeIf { it.isNotBlank() } ?: promptPreset.prompt
    val baseUrl: String get() = "http://127.0.0.1:$port"
}

class SettingsRepository(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("llamadroid_settings", Context.MODE_PRIVATE)

    init {
        if (prefs.contains("model_storage_uri")) {
            prefs.edit().remove("model_storage_uri").apply()
        }
    }

    private inline fun <reified T : Enum<T>> enumPref(key: String, defaultValue: T): T {
        val stored = prefs.getString(key, defaultValue.name)
        return runCatching { enumValueOf<T>(stored ?: defaultValue.name) }.getOrDefault(defaultValue)
    }

    private fun optionalIntPref(key: String): Int? {
        if (!prefs.contains(key)) return null
        val value = prefs.getInt(key, 0)
        return value.takeIf { it > 0 }
    }

    private fun setOptionalIntPref(key: String, value: Int?) {
        prefs.edit().apply {
            if (value == null || value <= 0) {
                remove(key)
            } else {
                putInt(key, value)
            }
        }.apply()
    }

    inner class RemoteSummarySettingsGroup(
        private val keyPrefix: String,
        private val defaultSummaryPrompt: String?,
        private val defaultMergePrompt: String?,
        private val defaultOllamaUrl: String = AIConstants.Urls.OLLAMA_DEFAULT,
        private val defaultLlamaServerUrl: String = PDF_LLAMA_SERVER_DEFAULT_URL,
        private val defaultLlamaSwapUrl: String = PDF_LLAMA_SWAP_DEFAULT_URL,
        private val fallbackChunkContextKey: String? = null,
        private val fallbackChunkMaxTokensKey: String? = null,
        private val fallbackTemperatureKey: String? = null,
        private val fallbackTimeoutKey: String? = null,
        private val fallbackThinkingEnabledKey: String? = null,
        private val fallbackSummaryPromptKey: String? = null,
        private val fallbackMergePromptKey: String? = null,
        private val fallbackTargetLanguageKey: String? = null,
        private val defaultTargetLanguage: String = DEFAULT_SUMMARY_TARGET_LANGUAGE
    ) {
        private fun stringFlow(suffix: String, default: String? = null, fallbackKey: String? = null): MutableStateFlow<String?> {
            val key = "${keyPrefix}_$suffix"
            val value = when {
                prefs.contains(key) -> prefs.getString(key, default)
                fallbackKey != null -> prefs.getString(fallbackKey, default)
                else -> prefs.getString(key, default)
            }
            return MutableStateFlow(value)
        }

        private fun intFlow(
            suffix: String,
            default: Int,
            range: IntRange,
            fallbackKey: String? = null
        ): MutableStateFlow<Int> {
            val key = "${keyPrefix}_$suffix"
            val value = when {
                prefs.contains(key) -> prefs.getInt(key, default)
                fallbackKey != null -> prefs.getInt(fallbackKey, default)
                else -> prefs.getInt(key, default)
            }.coerceIn(range)
            return MutableStateFlow(value)
        }

        private fun intMinOnlyFlow(
            suffix: String,
            default: Int,
            minValue: Int,
            fallbackKey: String? = null
        ): MutableStateFlow<Int> {
            val key = "${keyPrefix}_$suffix"
            val value = when {
                prefs.contains(key) -> prefs.getInt(key, default)
                fallbackKey != null -> prefs.getInt(fallbackKey, default)
                else -> prefs.getInt(key, default)
            }.coerceAtLeast(minValue)
            return MutableStateFlow(value)
        }

        private fun floatFlow(
            suffix: String,
            default: Float,
            min: Float,
            max: Float,
            fallbackKey: String? = null
        ): MutableStateFlow<Float> {
            val key = "${keyPrefix}_$suffix"
            val value = when {
                prefs.contains(key) -> prefs.getFloat(key, default)
                fallbackKey != null -> prefs.getFloat(fallbackKey, default)
                else -> prefs.getFloat(key, default)
            }.coerceIn(min, max)
            return MutableStateFlow(value)
        }

        private fun boolFlow(suffix: String, default: Boolean, fallbackKey: String? = null): MutableStateFlow<Boolean> {
            val key = "${keyPrefix}_$suffix"
            val value = when {
                prefs.contains(key) -> prefs.getBoolean(key, default)
                fallbackKey != null -> prefs.getBoolean(fallbackKey, default)
                else -> prefs.getBoolean(key, default)
            }
            return MutableStateFlow(value)
        }

        private fun putStringValue(suffix: String, value: String?) {
            prefs.edit().putString("${keyPrefix}_$suffix", value).apply()
        }

        private fun putIntValue(suffix: String, value: Int) {
            prefs.edit().putInt("${keyPrefix}_$suffix", value).apply()
        }

        private fun putFloatValue(suffix: String, value: Float) {
            prefs.edit().putFloat("${keyPrefix}_$suffix", value).apply()
        }

        private fun putBooleanValue(suffix: String, value: Boolean) {
            prefs.edit().putBoolean("${keyPrefix}_$suffix", value).apply()
        }

        private fun longFlow(suffix: String, default: Long = -1L): MutableStateFlow<Long> {
            val key = "${keyPrefix}_$suffix"
            return MutableStateFlow(prefs.getLong(key, default))
        }

        private fun putLongValue(suffix: String, value: Long?) {
            prefs.edit().apply {
                if (value == null || value <= 0L) {
                    remove("${keyPrefix}_$suffix")
                } else {
                    putLong("${keyPrefix}_$suffix", value)
                }
            }.apply()
        }

        private val _backend = stringFlow("backend", PDF_BACKEND_OLLAMA).let {
            MutableStateFlow(normalizeOllamaOrLlamaBackend(it.value))
        }
        val backend = _backend.asStateFlow()
        fun setBackend(value: String) {
            val normalized = normalizeOllamaOrLlamaBackend(value)
            putStringValue("backend", normalized)
            _backend.value = normalized
        }

        private val _ollamaUrl = stringFlow("ollama_url", defaultOllamaUrl).let {
            MutableStateFlow(it.value ?: defaultOllamaUrl)
        }
        val ollamaUrl = _ollamaUrl.asStateFlow()
        fun setOllamaUrl(value: String) {
            putStringValue("ollama_url", value)
            _ollamaUrl.value = value
        }

        private val _llamaServerUrl = stringFlow("llama_server_url", defaultLlamaServerUrl).let {
            MutableStateFlow(it.value ?: defaultLlamaServerUrl)
        }
        val llamaServerUrl = _llamaServerUrl.asStateFlow()
        fun setLlamaServerUrl(value: String) {
            putStringValue("llama_server_url", value)
            _llamaServerUrl.value = value
        }

        private val _llamaSwapUrl = stringFlow("llama_swap_url", defaultLlamaSwapUrl).let {
            MutableStateFlow(it.value ?: defaultLlamaSwapUrl)
        }
        val llamaSwapUrl = _llamaSwapUrl.asStateFlow()
        fun setLlamaSwapUrl(value: String) {
            putStringValue("llama_swap_url", value)
            _llamaSwapUrl.value = value
        }

        private val _ollamaModel = stringFlow("ollama_model")
        val ollamaModel = _ollamaModel.asStateFlow()
        fun setOllamaModel(value: String?) {
            putStringValue("ollama_model", value)
            _ollamaModel.value = value
        }

        private val _llamaSwapModel = stringFlow("llama_swap_model")
        val llamaSwapModel = _llamaSwapModel.asStateFlow()
        fun setLlamaSwapModel(value: String?) {
            putStringValue("llama_swap_model", value)
            _llamaSwapModel.value = value
        }

        private val _liteRtModelId = longFlow("litert_model_id")
        val liteRtModelId = _liteRtModelId.asStateFlow()
        fun setLiteRtModelId(value: Long?) {
            putLongValue("litert_model_id", value)
            _liteRtModelId.value = value?.takeIf { it > 0L } ?: -1L
        }

        private val _liteRtBackend = stringFlow("litert_backend", LITERT_BACKEND_AUTO).let {
            MutableStateFlow(normalizeLiteRtBackend(it.value))
        }
        val liteRtBackend = _liteRtBackend.asStateFlow()
        fun setLiteRtBackend(value: String) {
            val normalized = normalizeLiteRtBackend(value)
            putStringValue("litert_backend", normalized)
            _liteRtBackend.value = normalized
        }

        private val _liteRtMtpEnabled = boolFlow("litert_mtp_enabled", false)
        val liteRtMtpEnabled = _liteRtMtpEnabled.asStateFlow()
        fun setLiteRtMtpEnabled(value: Boolean) {
            putBooleanValue("litert_mtp_enabled", value)
            _liteRtMtpEnabled.value = value
        }

        private val _thinkingEnabled = boolFlow("thinking_enabled", false, fallbackThinkingEnabledKey)
        val thinkingEnabled = _thinkingEnabled.asStateFlow()
        fun setThinkingEnabled(value: Boolean) {
            putBooleanValue("thinking_enabled", value)
            _thinkingEnabled.value = value
        }

        private val _llamaServerModelLabel = stringFlow("llama_server_model_label")
        val llamaServerModelLabel = _llamaServerModelLabel.asStateFlow()
        fun setLlamaServerModelLabel(value: String?) {
            putStringValue("llama_server_model_label", value)
            _llamaServerModelLabel.value = value
        }

        private val _llamaServerContextTokens = intFlow("llama_server_context_tokens", -1, -1..SUMMARY_CONTEXT_RANGE.last)
        val llamaServerContextTokens = _llamaServerContextTokens.asStateFlow()
        fun setLlamaServerContextTokens(value: Int?) {
            val normalized = value ?: -1
            putIntValue("llama_server_context_tokens", normalized)
            _llamaServerContextTokens.value = normalized
        }

        private val _llamaServerContextLabel = stringFlow("llama_server_context_label")
        val llamaServerContextLabel = _llamaServerContextLabel.asStateFlow()
        fun setLlamaServerContextLabel(value: String?) {
            putStringValue("llama_server_context_label", value)
            _llamaServerContextLabel.value = value
        }

        private val _chunkContext = intMinOnlyFlow(
            suffix = "chunk_context",
            default = AIConstants.Defaults.CONTEXT_SIZE_PDF,
            minValue = SUMMARY_CONTEXT_MIN,
            fallbackKey = fallbackChunkContextKey
        )
        val chunkContext = _chunkContext.asStateFlow()
        fun setChunkContext(value: Int) {
            val clamped = value.coerceAtLeast(SUMMARY_CONTEXT_MIN)
            putIntValue("chunk_context", clamped)
            _chunkContext.value = clamped
        }

        private val _chunkMaxTokens = intMinOnlyFlow(
            suffix = "chunk_max_tokens",
            default = AIConstants.Defaults.MAX_TOKENS_PDF,
            minValue = SUMMARY_MAX_TOKENS_MIN,
            fallbackKey = fallbackChunkMaxTokensKey
        )
        val chunkMaxTokens = _chunkMaxTokens.asStateFlow()
        fun setChunkMaxTokens(value: Int) {
            val clamped = value.coerceAtLeast(SUMMARY_MAX_TOKENS_MIN)
            putIntValue("chunk_max_tokens", clamped)
            _chunkMaxTokens.value = clamped
        }

        private val _mergeContext = intMinOnlyFlow(
            suffix = "merge_context",
            default = _chunkContext.value,
            minValue = SUMMARY_CONTEXT_MIN
        )
        val mergeContext = _mergeContext.asStateFlow()
        fun setMergeContext(value: Int) {
            val clamped = value.coerceAtLeast(SUMMARY_CONTEXT_MIN)
            putIntValue("merge_context", clamped)
            _mergeContext.value = clamped
        }

        private val _mergeMaxTokens = intMinOnlyFlow(
            suffix = "merge_max_tokens",
            default = _chunkMaxTokens.value,
            minValue = SUMMARY_MAX_TOKENS_MIN
        )
        val mergeMaxTokens = _mergeMaxTokens.asStateFlow()
        fun setMergeMaxTokens(value: Int) {
            val clamped = value.coerceAtLeast(SUMMARY_MAX_TOKENS_MIN)
            putIntValue("merge_max_tokens", clamped)
            _mergeMaxTokens.value = clamped
        }

        private val _temperature = floatFlow(
            suffix = "temperature",
            default = AIConstants.Defaults.TEMPERATURE_PDF,
            min = SUMMARY_TEMPERATURE_MIN,
            max = SUMMARY_TEMPERATURE_MAX,
            fallbackKey = fallbackTemperatureKey
        )
        val temperature = _temperature.asStateFlow()
        fun setTemperature(value: Float) {
            val clamped = value.coerceIn(SUMMARY_TEMPERATURE_MIN, SUMMARY_TEMPERATURE_MAX)
            putFloatValue("temperature", clamped)
            _temperature.value = clamped
        }

        private val _timeoutMinutes = intFlow(
            suffix = "timeout_minutes",
            default = SUMMARY_TIMEOUT_DISABLED,
            range = SUMMARY_TIMEOUT_MINUTES_RANGE,
            fallbackKey = fallbackTimeoutKey
        )
        val timeoutMinutes = _timeoutMinutes.asStateFlow()
        fun setTimeoutMinutes(value: Int) {
            val clamped = value.coerceIn(SUMMARY_TIMEOUT_MINUTES_RANGE)
            putIntValue("timeout_minutes", clamped)
            _timeoutMinutes.value = clamped
        }

        private val _targetLanguage = stringFlow(
            suffix = "target_language",
            default = defaultTargetLanguage,
            fallbackKey = fallbackTargetLanguageKey
        ).let { MutableStateFlow((it.value ?: defaultTargetLanguage).ifBlank { defaultTargetLanguage }) }
        val targetLanguage = _targetLanguage.asStateFlow()
        fun setTargetLanguage(value: String) {
            val normalized = value.ifBlank { defaultTargetLanguage }
            putStringValue("target_language", normalized)
            _targetLanguage.value = normalized
        }

        private val _summaryPrompt = stringFlow("prompt", defaultSummaryPrompt, fallbackSummaryPromptKey)
        val summaryPrompt = _summaryPrompt.asStateFlow()
        fun setSummaryPrompt(value: String?) {
            putStringValue("prompt", value)
            _summaryPrompt.value = value
        }

        private val _mergePrompt = stringFlow("merge_prompt", defaultMergePrompt, fallbackMergePromptKey)
        val mergePrompt = _mergePrompt.asStateFlow()
        fun setMergePrompt(value: String?) {
            putStringValue("merge_prompt", value)
            _mergePrompt.value = value
        }

        fun snapshot(): RemoteSummarySettingsSnapshot {
            return RemoteSummarySettingsSnapshot(
                backend = backend.value,
                ollamaUrl = ollamaUrl.value,
                llamaServerUrl = llamaServerUrl.value,
                llamaSwapUrl = llamaSwapUrl.value,
                ollamaModel = ollamaModel.value,
                llamaSwapModel = llamaSwapModel.value,
                liteRtModelId = liteRtModelId.value.takeIf { it > 0L },
                liteRtBackend = liteRtBackend.value,
                liteRtMtpEnabled = liteRtMtpEnabled.value,
                thinkingEnabled = thinkingEnabled.value,
                llamaServerModelLabel = llamaServerModelLabel.value,
                llamaServerContextTokens = llamaServerContextTokens.value,
                llamaServerContextLabel = llamaServerContextLabel.value,
                chunkContext = chunkContext.value,
                chunkMaxTokens = chunkMaxTokens.value,
                mergeContext = mergeContext.value,
                mergeMaxTokens = mergeMaxTokens.value,
                temperature = temperature.value,
                timeoutMinutes = timeoutMinutes.value,
                targetLanguage = targetLanguage.value,
                summaryPrompt = summaryPrompt.value ?: defaultSummaryPrompt,
                mergePrompt = mergePrompt.value ?: defaultMergePrompt
            )
        }

        fun applySnapshot(value: RemoteSummarySettingsSnapshot) {
            setBackend(value.backend)
            setOllamaUrl(value.ollamaUrl)
            setLlamaServerUrl(value.llamaServerUrl)
            setLlamaSwapUrl(value.llamaSwapUrl)
            setOllamaModel(value.ollamaModel)
            setLlamaSwapModel(value.llamaSwapModel)
            setLiteRtModelId(value.liteRtModelId)
            setLiteRtBackend(value.liteRtBackend)
            setLiteRtMtpEnabled(value.liteRtMtpEnabled)
            setThinkingEnabled(value.thinkingEnabled)
            setLlamaServerModelLabel(value.llamaServerModelLabel)
            setLlamaServerContextTokens(value.llamaServerContextTokens.takeIf { it > 0 })
            setLlamaServerContextLabel(value.llamaServerContextLabel)
            setChunkContext(value.chunkContext)
            setChunkMaxTokens(value.chunkMaxTokens)
            setMergeContext(value.mergeContext)
            setMergeMaxTokens(value.mergeMaxTokens)
            setTemperature(value.temperature)
            setTimeoutMinutes(value.timeoutMinutes)
            setTargetLanguage(value.targetLanguage)
            setSummaryPrompt(value.summaryPrompt)
            setMergePrompt(value.mergePrompt)
        }
    }
    
    // Selected LLM Model Path
    private val _selectedModelPath = MutableStateFlow(prefs.getString("selected_model_path", null))
    val selectedModelPath = _selectedModelPath.asStateFlow()
    
    fun setSelectedModelPath(path: String?) {
        prefs.edit().putString("selected_model_path", path).apply()
        _selectedModelPath.value = path
    }

    private val _selectedLlmLoraPath = MutableStateFlow(
        prefs.getString("selected_llm_lora_path", null)
    )
    val selectedLlmLoraPath = _selectedLlmLoraPath.asStateFlow()

    fun setSelectedLlmLoraPath(path: String?) {
        prefs.edit().putString("selected_llm_lora_path", path).apply()
        _selectedLlmLoraPath.value = path
    }
    
    // Selected Embedding Model Path
    private val _selectedEmbeddingModelPath = MutableStateFlow(prefs.getString("selected_embedding_model_path", null))
    val selectedEmbeddingModelPath = _selectedEmbeddingModelPath.asStateFlow()
    
    fun setSelectedEmbeddingModelPath(path: String?) {
        prefs.edit().putString("selected_embedding_model_path", path).apply()
        _selectedEmbeddingModelPath.value = path
    }

    private val _knowledgeBaseChunkSize = MutableStateFlow(
        normalizeKnowledgeBaseChunkSize(
            prefs.getInt("knowledge_base_chunk_size", KB_DEFAULT_CHUNK_SIZE)
        )
    )
    val knowledgeBaseChunkSize = _knowledgeBaseChunkSize.asStateFlow()

    fun setKnowledgeBaseChunkSize(size: Int) {
        val normalized = normalizeKnowledgeBaseChunkSize(size)
        prefs.edit().putInt("knowledge_base_chunk_size", normalized).apply()
        _knowledgeBaseChunkSize.value = normalized
    }

    private val _knowledgeEmbeddingBatchSize = MutableStateFlow(
        normalizeKnowledgeEmbeddingBatchSize(
            prefs.getInt(
                "knowledge_embedding_batch_size",
                knowledgeEmbeddingBatchSizeForChunkSize(_knowledgeBaseChunkSize.value)
            )
        )
    )
    val knowledgeEmbeddingBatchSize = _knowledgeEmbeddingBatchSize.asStateFlow()

    fun setKnowledgeEmbeddingBatchSize(size: Int) {
        val normalized = normalizeKnowledgeEmbeddingBatchSize(size)
        prefs.edit().putInt("knowledge_embedding_batch_size", normalized).apply()
        _knowledgeEmbeddingBatchSize.value = normalized
    }

    private val _knowledgeEmbeddingThreads = MutableStateFlow(
        normalizeKnowledgeEmbeddingThreads(
            prefs.getInt("knowledge_embedding_threads", KB_DEFAULT_EMBED_THREADS)
        )
    )
    val knowledgeEmbeddingThreads = _knowledgeEmbeddingThreads.asStateFlow()

    fun setKnowledgeEmbeddingThreads(count: Int) {
        val normalized = normalizeKnowledgeEmbeddingThreads(count)
        prefs.edit().putInt("knowledge_embedding_threads", normalized).apply()
        _knowledgeEmbeddingThreads.value = normalized
    }

    private val _knowledgeEmbeddingNetworkVisible = MutableStateFlow(
        prefs.getBoolean("knowledge_embedding_network_visible", false)
    )
    val knowledgeEmbeddingNetworkVisible = _knowledgeEmbeddingNetworkVisible.asStateFlow()

    fun setKnowledgeEmbeddingNetworkVisible(enabled: Boolean) {
        prefs.edit().putBoolean("knowledge_embedding_network_visible", enabled).apply()
        _knowledgeEmbeddingNetworkVisible.value = enabled
    }

    private val _knowledgeEmbeddingBackend = MutableStateFlow(
        normalizeKnowledgeEmbeddingBackend(
            prefs.getString("knowledge_embedding_backend", KB_EMBED_BACKEND_LOCAL)
        )
    )
    val knowledgeEmbeddingBackend = _knowledgeEmbeddingBackend.asStateFlow()

    fun setKnowledgeEmbeddingBackend(backend: String) {
        val normalized = normalizeKnowledgeEmbeddingBackend(backend)
        prefs.edit().putString("knowledge_embedding_backend", normalized).apply()
        _knowledgeEmbeddingBackend.value = normalized
    }

    private val _knowledgeEmbeddingLlamaServerUrl = MutableStateFlow(
        prefs.getString("knowledge_embedding_llama_server_url", "http://127.0.0.1:8081")
            ?: "http://127.0.0.1:8081"
    )
    val knowledgeEmbeddingLlamaServerUrl = _knowledgeEmbeddingLlamaServerUrl.asStateFlow()

    fun setKnowledgeEmbeddingLlamaServerUrl(url: String) {
        prefs.edit().putString("knowledge_embedding_llama_server_url", url).apply()
        _knowledgeEmbeddingLlamaServerUrl.value = url
    }

    private val _knowledgeEmbeddingOllamaUrl = MutableStateFlow(
        prefs.getString("knowledge_embedding_ollama_url", AIConstants.Urls.OLLAMA_DEFAULT)
            ?: AIConstants.Urls.OLLAMA_DEFAULT
    )
    val knowledgeEmbeddingOllamaUrl = _knowledgeEmbeddingOllamaUrl.asStateFlow()

    fun setKnowledgeEmbeddingOllamaUrl(url: String) {
        prefs.edit().putString("knowledge_embedding_ollama_url", url).apply()
        _knowledgeEmbeddingOllamaUrl.value = url
    }

    private val _knowledgeEmbeddingOllamaModel = MutableStateFlow(
        prefs.getString("knowledge_embedding_ollama_model", null)
    )
    val knowledgeEmbeddingOllamaModel = _knowledgeEmbeddingOllamaModel.asStateFlow()

    fun setKnowledgeEmbeddingOllamaModel(model: String?) {
        prefs.edit().putString("knowledge_embedding_ollama_model", model).apply()
        _knowledgeEmbeddingOllamaModel.value = model
    }

    private val _knowledgeEmbeddingLiteRtModelId = MutableStateFlow(
        prefs.getLong("knowledge_embedding_litert_model_id", -1L)
    )
    val knowledgeEmbeddingLiteRtModelId = _knowledgeEmbeddingLiteRtModelId.asStateFlow()

    private val _knowledgeEmbeddingLiteRtModelLabel = MutableStateFlow(
        prefs.getString("knowledge_embedding_litert_model_label", null)
    )
    val knowledgeEmbeddingLiteRtModelLabel = _knowledgeEmbeddingLiteRtModelLabel.asStateFlow()

    fun setKnowledgeEmbeddingLiteRtModelId(modelId: Long?, modelLabel: String? = null) {
        prefs.edit().apply {
            if (modelId == null || modelId <= 0L) {
                remove("knowledge_embedding_litert_model_id")
                remove("knowledge_embedding_litert_model_label")
            } else {
                putLong("knowledge_embedding_litert_model_id", modelId)
                putString("knowledge_embedding_litert_model_label", modelLabel?.trim()?.takeIf { it.isNotEmpty() })
            }
        }.apply()
        _knowledgeEmbeddingLiteRtModelId.value = modelId?.takeIf { it > 0L } ?: -1L
        _knowledgeEmbeddingLiteRtModelLabel.value =
            modelLabel?.trim()?.takeIf { modelId != null && modelId > 0L && it.isNotEmpty() }
    }

    private val _knowledgeEmbeddingLlamaSwapUrl = MutableStateFlow(
        prefs.getString("knowledge_embedding_llama_swap_url", PDF_LLAMA_SWAP_DEFAULT_URL)
            ?: PDF_LLAMA_SWAP_DEFAULT_URL
    )
    val knowledgeEmbeddingLlamaSwapUrl = _knowledgeEmbeddingLlamaSwapUrl.asStateFlow()

    fun setKnowledgeEmbeddingLlamaSwapUrl(url: String) {
        prefs.edit().putString("knowledge_embedding_llama_swap_url", url).apply()
        _knowledgeEmbeddingLlamaSwapUrl.value = url
    }

    private val _knowledgeEmbeddingLlamaSwapModel = MutableStateFlow(
        prefs.getString("knowledge_embedding_llama_swap_model", null)
    )
    val knowledgeEmbeddingLlamaSwapModel = _knowledgeEmbeddingLlamaSwapModel.asStateFlow()

    fun setKnowledgeEmbeddingLlamaSwapModel(model: String?) {
        prefs.edit().putString("knowledge_embedding_llama_swap_model", model).apply()
        _knowledgeEmbeddingLlamaSwapModel.value = model
    }

    private val _llmAccelerationMode = MutableStateFlow(
        normalizeAccelerationMode(prefs.getString("llm_acceleration_mode", ACCELERATION_AUTO))
    )
    val llmAccelerationMode = _llmAccelerationMode.asStateFlow()

    fun setLlmAccelerationMode(mode: String) {
        val normalized = normalizeAccelerationMode(mode)
        prefs.edit().putString("llm_acceleration_mode", normalized).apply()
        _llmAccelerationMode.value = normalized
    }

    private val _stableDiffusionAccelerationMode = MutableStateFlow(
        normalizeStableDiffusionAccelerationMode(
            prefs.getString("stable_diffusion_acceleration_mode", ACCELERATION_AUTO)
        )
    )
    val stableDiffusionAccelerationMode = _stableDiffusionAccelerationMode.asStateFlow()

    fun setStableDiffusionAccelerationMode(mode: String) {
        val normalized = normalizeStableDiffusionAccelerationMode(mode)
        prefs.edit().putString("stable_diffusion_acceleration_mode", normalized).apply()
        _stableDiffusionAccelerationMode.value = normalized
    }

    private val _llmNativeBinarySelection = MutableStateFlow(
        normalizeLlmNativeBinarySelection(
            prefs.getString(
                "llm_native_binary_selection",
                legacyLlmBinarySelectionDefault(prefs.getString("llm_acceleration_mode", ACCELERATION_AUTO))
            )
        )
    )
    val llmNativeBinarySelection = _llmNativeBinarySelection.asStateFlow()

    fun setLlmNativeBinarySelection(selection: String) {
        val normalized = normalizeLlmNativeBinarySelection(selection)
        prefs.edit().putString("llm_native_binary_selection", normalized).apply()
        _llmNativeBinarySelection.value = normalized
    }

    private val _stableDiffusionNativeBinarySelection = MutableStateFlow(
        normalizeStableDiffusionNativeBinarySelection(
            prefs.getString(
                "stable_diffusion_native_binary_selection",
                legacyStableDiffusionBinarySelectionDefault(
                    prefs.getString("stable_diffusion_acceleration_mode", ACCELERATION_AUTO)
                )
            )
        )
    )
    val stableDiffusionNativeBinarySelection = _stableDiffusionNativeBinarySelection.asStateFlow()

    fun setStableDiffusionNativeBinarySelection(selection: String) {
        val normalized = normalizeStableDiffusionNativeBinarySelection(selection)
        prefs.edit().putString("stable_diffusion_native_binary_selection", normalized).apply()
        _stableDiffusionNativeBinarySelection.value = normalized
    }
    
    // Context Size
    private val _contextSize = MutableStateFlow(prefs.getInt("context_size", AIConstants.Defaults.CONTEXT_SIZE_LLM))
    val contextSize = _contextSize.asStateFlow()
    
    fun setContextSize(size: Int) {
        prefs.edit().putInt("context_size", size).apply()
        _contextSize.value = size
    }
    
    // Threads
    private val _threads = MutableStateFlow(prefs.getInt("threads", 4))
    val threads = _threads.asStateFlow()
    
    fun setThreads(count: Int) {
        prefs.edit().putInt("threads", count).apply()
        _threads.value = count
    }
    
    // Temperature
    private val _temperature = MutableStateFlow(prefs.getFloat("temperature", 0.7f))
    val temperature = _temperature.asStateFlow()
    
    fun setTemperature(temp: Float) {
        prefs.edit().putFloat("temperature", temp).apply()
        _temperature.value = temp
    }

    // Auto Mode (Auto-approve plans and file writes)
    private val _autoMode = MutableStateFlow(prefs.getBoolean("agent_auto_mode", false))
    val autoMode = _autoMode.asStateFlow()

    fun setAutoMode(enabled: Boolean) {
        prefs.edit().putBoolean("agent_auto_mode", enabled).apply()
        _autoMode.value = enabled
    }
    
    // Command Auto-Accept (Auto-approve run_command specifically)
    private val _commandAutoAccept = MutableStateFlow(prefs.getBoolean("agent_command_auto_accept", false))
    val commandAutoAccept = _commandAutoAccept.asStateFlow()
    
    fun setCommandAutoAccept(enabled: Boolean) {
        prefs.edit().putBoolean("agent_command_auto_accept", enabled).apply()
        _commandAutoAccept.value = enabled
    }
    
    // Agent backend: "ollama", "llama-server", or "llama-swap"
    private val _agentBackend = MutableStateFlow(normalizeOllamaOrLlamaBackend(prefs.getString("agent_backend", PDF_BACKEND_OLLAMA)))
    val agentBackend = _agentBackend.asStateFlow()
    fun setAgentBackend(backend: String) {
        val normalized = normalizeOllamaOrLlamaBackend(backend)
        prefs.edit().putString("agent_backend", normalized).apply()
        _agentBackend.value = normalized
    }
    
    // llama-server URL (used when backend is "llama-server")
    private val _llamaServerUrl = MutableStateFlow(prefs.getString("llama_server_url", "http://localhost:8080") ?: "http://localhost:8080")
    val llamaServerUrl = _llamaServerUrl.asStateFlow()
    fun setLlamaServerUrl(url: String) {
        prefs.edit().putString("llama_server_url", url).apply()
        _llamaServerUrl.value = url
    }

    // llama-swap URL (used when backend is "llama-swap")
    private val _agentLlamaSwapUrl = MutableStateFlow(
        prefs.getString("agent_llama_swap_url", PDF_LLAMA_SWAP_DEFAULT_URL) ?: PDF_LLAMA_SWAP_DEFAULT_URL
    )
    val agentLlamaSwapUrl = _agentLlamaSwapUrl.asStateFlow()
    fun setAgentLlamaSwapUrl(url: String) {
        prefs.edit().putString("agent_llama_swap_url", url).apply()
        _agentLlamaSwapUrl.value = url
    }

    // Last known llama-server metadata for the agent runtime UI
    private val _agentLlamaServerModelLabel = MutableStateFlow(prefs.getString("agent_llama_server_model_label", null))
    val agentLlamaServerModelLabel = _agentLlamaServerModelLabel.asStateFlow()
    fun setAgentLlamaServerModelLabel(value: String?) {
        prefs.edit().putString("agent_llama_server_model_label", value).apply()
        _agentLlamaServerModelLabel.value = value
    }

    private val _agentLlamaServerContextTokens = MutableStateFlow(prefs.getInt("agent_llama_server_context_tokens", -1))
    val agentLlamaServerContextTokens = _agentLlamaServerContextTokens.asStateFlow()
    fun setAgentLlamaServerContextTokens(value: Int?) {
        val normalized = value ?: -1
        prefs.edit().putInt("agent_llama_server_context_tokens", normalized).apply()
        _agentLlamaServerContextTokens.value = normalized
    }

    private val _agentLlamaServerContextLabel = MutableStateFlow(prefs.getString("agent_llama_server_context_label", null))
    val agentLlamaServerContextLabel = _agentLlamaServerContextLabel.asStateFlow()
    fun setAgentLlamaServerContextLabel(value: String?) {
        prefs.edit().putString("agent_llama_server_context_label", value).apply()
        _agentLlamaServerContextLabel.value = value
    }

    private val _agentLiteRtModelId = MutableStateFlow(prefs.getLong("agent_litert_model_id", -1L))
    val agentLiteRtModelId = _agentLiteRtModelId.asStateFlow()
    fun setAgentLiteRtModelId(modelId: Long?) {
        prefs.edit().apply {
            if (modelId == null || modelId <= 0L) remove("agent_litert_model_id") else putLong("agent_litert_model_id", modelId)
        }.apply()
        _agentLiteRtModelId.value = modelId?.takeIf { it > 0L } ?: -1L
    }

    private val _agentLiteRtBackend = MutableStateFlow(normalizeLiteRtBackend(prefs.getString("agent_litert_backend", LITERT_BACKEND_AUTO)))
    val agentLiteRtBackend = _agentLiteRtBackend.asStateFlow()
    fun setAgentLiteRtBackend(backend: String) {
        val normalized = normalizeLiteRtBackend(backend)
        prefs.edit().putString("agent_litert_backend", normalized).apply()
        _agentLiteRtBackend.value = normalized
    }

    private val _agentLiteRtMtpEnabled = MutableStateFlow(prefs.getBoolean("agent_litert_mtp_enabled", false))
    val agentLiteRtMtpEnabled = _agentLiteRtMtpEnabled.asStateFlow()
    fun setAgentLiteRtMtpEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("agent_litert_mtp_enabled", enabled).apply()
        _agentLiteRtMtpEnabled.value = enabled
    }

    private val _agentLiteRtContextTokens = MutableStateFlow(prefs.getInt("agent_litert_context_tokens", -1))
    val agentLiteRtContextTokens = _agentLiteRtContextTokens.asStateFlow()
    fun setAgentLiteRtContextTokens(value: Int?) {
        val normalized = value?.takeIf { it > 0 } ?: -1
        prefs.edit().putInt("agent_litert_context_tokens", normalized).apply()
        _agentLiteRtContextTokens.value = normalized
    }

    private val _agentLiteRtMaxOutputTokens = MutableStateFlow(prefs.getInt("agent_litert_max_output_tokens", -1))
    val agentLiteRtMaxOutputTokens = _agentLiteRtMaxOutputTokens.asStateFlow()
    fun setAgentLiteRtMaxOutputTokens(value: Int?) {
        val normalized = value?.takeIf { it > 0 } ?: -1
        prefs.edit().putInt("agent_litert_max_output_tokens", normalized).apply()
        _agentLiteRtMaxOutputTokens.value = normalized
    }

    private val _agentLiteRtThinkingEnabled = MutableStateFlow(prefs.getBoolean("agent_litert_thinking_enabled", true))
    val agentLiteRtThinkingEnabled = _agentLiteRtThinkingEnabled.asStateFlow()
    fun setAgentLiteRtThinkingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("agent_litert_thinking_enabled", enabled).apply()
        _agentLiteRtThinkingEnabled.value = enabled
    }

    // Show Extra Output (thinking, tool calls, etc.)
    private val _showExtraOutput = MutableStateFlow(prefs.getBoolean("agent_show_extra_output", true))
    val showExtraOutput = _showExtraOutput.asStateFlow()

    fun setShowExtraOutput(enabled: Boolean) {
        prefs.edit().putBoolean("agent_show_extra_output", enabled).apply()
        _showExtraOutput.value = enabled
    }
    
    // Remote Access (allow connections from other devices)
    private val _remoteAccess = MutableStateFlow(prefs.getBoolean("remote_access", false))
    val remoteAccess = _remoteAccess.asStateFlow()
    
    fun setRemoteAccess(enabled: Boolean) {
        prefs.edit().putBoolean("remote_access", enabled).apply()
        _remoteAccess.value = enabled
    }
    
    // Enable Vision (load mmproj for vision models)
    private val _enableVision = MutableStateFlow(prefs.getBoolean("enable_vision", true))
    val enableVision = _enableVision.asStateFlow()
    
    fun setEnableVision(enabled: Boolean) {
        prefs.edit().putBoolean("enable_vision", enabled).apply()
        _enableVision.value = enabled
    }
    
    // Selected mmproj (vision projector) path
    private val _selectedMmprojPath = MutableStateFlow(prefs.getString("selected_mmproj_path", null))
    val selectedMmprojPath = _selectedMmprojPath.asStateFlow()
    
    fun setSelectedMmprojPath(path: String?) {
        prefs.edit().putString("selected_mmproj_path", path).apply()
        _selectedMmprojPath.value = path
    }
    
    // Selected system prompt ID (for saved prompts feature)
    private val _selectedPromptId = MutableStateFlow(prefs.getLong("selected_prompt_id", -1L))
    val selectedPromptId = _selectedPromptId.asStateFlow()
    
    fun setSelectedPromptId(id: Long) {
        prefs.edit().putLong("selected_prompt_id", id).apply()
        _selectedPromptId.value = id
    }
    
    // Output folder for generated images (null = default app folder)
    private val _outputFolderUri = MutableStateFlow(prefs.getString("output_folder_uri", null))
    val outputFolderUri = _outputFolderUri.asStateFlow()
    
    fun setOutputFolderUri(uri: String?) {
        prefs.edit().putString("output_folder_uri", uri).apply()
        _outputFolderUri.value = uri
    }

    // Quadtrix WebUI/worker workspace. The URI points at the shared sync/export
    // folder the user picked. The stored path is the app-managed runtime
    // workspace used by the native process.
    private val _quadtrixWorkspaceUri = MutableStateFlow(prefs.getString("quadtrix_workspace_uri", null))
    val quadtrixWorkspaceUri = _quadtrixWorkspaceUri.asStateFlow()

    private val _quadtrixWorkspacePath = MutableStateFlow(prefs.getString("quadtrix_workspace_path", null))
    val quadtrixWorkspacePath = _quadtrixWorkspacePath.asStateFlow()

    fun setQuadtrixWorkspace(uri: String?, directPath: String?) {
        prefs.edit().apply {
            if (uri.isNullOrBlank()) remove("quadtrix_workspace_uri") else putString("quadtrix_workspace_uri", uri)
            if (directPath.isNullOrBlank()) remove("quadtrix_workspace_path") else putString("quadtrix_workspace_path", directPath)
        }.apply()
        _quadtrixWorkspaceUri.value = uri
        _quadtrixWorkspacePath.value = directPath
    }

    private val _quadtrixWebHost = MutableStateFlow(prefs.getString("quadtrix_web_host", "127.0.0.1") ?: "127.0.0.1")
    val quadtrixWebHost = _quadtrixWebHost.asStateFlow()

    private val _quadtrixWebPort = MutableStateFlow(prefs.getInt("quadtrix_web_port", 8080))
    val quadtrixWebPort = _quadtrixWebPort.asStateFlow()

    fun setQuadtrixWebEndpoint(host: String, port: Int) {
        prefs.edit()
            .putString("quadtrix_web_host", host)
            .putInt("quadtrix_web_port", port)
            .apply()
        _quadtrixWebHost.value = host
        _quadtrixWebPort.value = port
    }

    private val _quadtrixWorkerHost = MutableStateFlow(prefs.getString("quadtrix_worker_host", "0.0.0.0") ?: "0.0.0.0")
    val quadtrixWorkerHost = _quadtrixWorkerHost.asStateFlow()

    private val _quadtrixWorkerPort = MutableStateFlow(prefs.getInt("quadtrix_worker_port", 9091))
    val quadtrixWorkerPort = _quadtrixWorkerPort.asStateFlow()

    private val _quadtrixWorkerToken = MutableStateFlow(prefs.getString("quadtrix_worker_token", "") ?: "")
    val quadtrixWorkerToken = _quadtrixWorkerToken.asStateFlow()

    private val _quadtrixWorkerThreads = MutableStateFlow(prefs.getInt("quadtrix_worker_threads", 4))
    val quadtrixWorkerThreads = _quadtrixWorkerThreads.asStateFlow()

    fun setQuadtrixWorkerEndpoint(host: String, port: Int, token: String, threads: Int) {
        prefs.edit()
            .putString("quadtrix_worker_host", host)
            .putInt("quadtrix_worker_port", port)
            .putString("quadtrix_worker_token", token)
            .putInt("quadtrix_worker_threads", threads)
            .apply()
        _quadtrixWorkerHost.value = host
        _quadtrixWorkerPort.value = port
        _quadtrixWorkerToken.value = token
        _quadtrixWorkerThreads.value = threads
    }

    private val _onnxCatalogProvider = MutableStateFlow(
        OnnxCatalogProvider.fromId(prefs.getString("onnx_catalog_provider", OnnxCatalogProvider.SDAI.id))
            ?: OnnxCatalogProvider.SDAI
    )
    val onnxCatalogProvider = _onnxCatalogProvider.asStateFlow()

    fun setOnnxCatalogProvider(provider: OnnxCatalogProvider) {
        prefs.edit().putString("onnx_catalog_provider", provider.id).apply()
        _onnxCatalogProvider.value = provider
    }

    private val _tamaNormalDreamingEnabled = MutableStateFlow(
        when {
            prefs.contains("tama_normal_dreaming_enabled") -> prefs.getBoolean("tama_normal_dreaming_enabled", true)
            prefs.contains("tama_dreaming_enabled") -> prefs.getBoolean("tama_dreaming_enabled", true)
            else -> true
        }
    )
    val tamaNormalDreamingEnabled = _tamaNormalDreamingEnabled.asStateFlow()

    fun setTamaNormalDreamingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("tama_normal_dreaming_enabled", enabled).apply()
        _tamaNormalDreamingEnabled.value = enabled
    }

    private val _tamaDeepDreamingEnabled = MutableStateFlow(
        when {
            prefs.contains("tama_deep_dreaming_enabled") -> prefs.getBoolean("tama_deep_dreaming_enabled", true)
            prefs.contains("tama_dreaming_enabled") -> prefs.getBoolean("tama_dreaming_enabled", true)
            else -> true
        }
    )
    val tamaDeepDreamingEnabled = _tamaDeepDreamingEnabled.asStateFlow()

    fun setTamaDeepDreamingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("tama_deep_dreaming_enabled", enabled).apply()
        _tamaDeepDreamingEnabled.value = enabled
    }

    private val _tamaDeepDreamRetryCount = MutableStateFlow(
        prefs.getInt("tama_deep_dream_retry_count", 3).coerceAtLeast(1)
    )
    val tamaDeepDreamRetryCount = _tamaDeepDreamRetryCount.asStateFlow()

    fun setTamaDeepDreamRetryCount(count: Int) {
        val normalized = count.coerceAtLeast(1)
        prefs.edit().putInt("tama_deep_dream_retry_count", normalized).apply()
        _tamaDeepDreamRetryCount.value = normalized
    }

    private val _tamaDeepDreamDesiredLanguage = MutableStateFlow(
        prefs.getString(
            "tama_deep_dream_desired_language",
            Locale.getDefault().displayLanguage.takeIf { it.isNotBlank() } ?: "English"
        )?.trim().orEmpty().ifBlank {
            Locale.getDefault().displayLanguage.takeIf { it.isNotBlank() } ?: "English"
        }
    )
    val tamaDeepDreamDesiredLanguage = _tamaDeepDreamDesiredLanguage.asStateFlow()

    fun setTamaDeepDreamDesiredLanguage(language: String) {
        val normalized = language.trim().ifBlank {
            Locale.getDefault().displayLanguage.takeIf { it.isNotBlank() } ?: "English"
        }
        prefs.edit().putString("tama_deep_dream_desired_language", normalized).apply()
        _tamaDeepDreamDesiredLanguage.value = normalized
    }

    private val _tamaSchoolPaintingEnabled = MutableStateFlow(
        prefs.getBoolean("tama_school_painting_enabled", true)
    )
    val tamaSchoolPaintingEnabled = _tamaSchoolPaintingEnabled.asStateFlow()

    fun setTamaSchoolPaintingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("tama_school_painting_enabled", enabled).apply()
        _tamaSchoolPaintingEnabled.value = enabled
    }

    private val _tamaPicGenModelFilename = MutableStateFlow(
        prefs.getString("tama_pic_gen_model_filename", null)
    )
    val tamaPicGenModelFilename = _tamaPicGenModelFilename.asStateFlow()

    fun setTamaPicGenModelFilename(filename: String?) {
        prefs.edit().putString("tama_pic_gen_model_filename", filename).apply()
        _tamaPicGenModelFilename.value = filename
    }

    private val _tamaPicGenResolution = MutableStateFlow(
        prefs.getInt("tama_pic_gen_resolution", TamaPicGenDefaults.DEFAULT_RESOLUTION)
            .let { saved ->
                TamaPicGenDefaults.RESOLUTION_PRESETS.firstOrNull { it == saved }
                    ?: TamaPicGenDefaults.DEFAULT_RESOLUTION
            }
    )
    val tamaPicGenResolution = _tamaPicGenResolution.asStateFlow()

    fun setTamaPicGenResolution(resolution: Int) {
        val normalized = TamaPicGenDefaults.RESOLUTION_PRESETS.firstOrNull { it == resolution }
            ?: TamaPicGenDefaults.DEFAULT_RESOLUTION
        prefs.edit().putInt("tama_pic_gen_resolution", normalized).apply()
        _tamaPicGenResolution.value = normalized
    }

    private val _keepScreenAwakeDuringGeneration =
        MutableStateFlow(prefs.getBoolean("keep_screen_awake_during_generation", false))
    val keepScreenAwakeDuringGeneration = _keepScreenAwakeDuringGeneration.asStateFlow()

    fun setKeepScreenAwakeDuringGeneration(enabled: Boolean) {
        prefs.edit().putBoolean("keep_screen_awake_during_generation", enabled).apply()
        _keepScreenAwakeDuringGeneration.value = enabled
    }

    // Ollama URL (API endpoint)
    private val _ollamaUrl = MutableStateFlow(prefs.getString("ollama_url", AIConstants.Urls.OLLAMA_DEFAULT) ?: AIConstants.Urls.OLLAMA_DEFAULT)
    val ollamaUrl = _ollamaUrl.asStateFlow()
    
    fun setOllamaUrl(url: String) {
        prefs.edit().putString("ollama_url", url).apply()
        _ollamaUrl.value = url
    }
    
    // Ollama use_mmap option (memory mapping for model loading)
    private val _ollamaMmap = MutableStateFlow(prefs.getBoolean("ollama_mmap", false))
    val ollamaMmap = _ollamaMmap.asStateFlow()
    
    fun setOllamaMmap(enabled: Boolean) {
        prefs.edit().putBoolean("ollama_mmap", enabled).apply()
        _ollamaMmap.value = enabled
    }
    
    // Custom Flags for Llama.cpp (General Selector)
    private val _customFlags = MutableStateFlow(prefs.getString("custom_flags", "") ?: "")
    val customFlags = _customFlags.asStateFlow()
    
    fun setCustomFlags(flags: String) {
        prefs.edit().putString("custom_flags", flags).apply()
        _customFlags.value = flags
    }
    
    // Track currently loaded Custom Command (ID) in General Selector
    private val _loadedCommandId = MutableStateFlow(prefs.getLong("loaded_command_id", -1L))
    val loadedCommandId = _loadedCommandId.asStateFlow()
    
    fun setLoadedCommandId(id: Long) {
        prefs.edit().putLong("loaded_command_id", id).apply()
        _loadedCommandId.value = id
    }

    // Custom command template for the general llama.cpp launcher.
    private val _customCommandTemplate = MutableStateFlow(
        prefs.getString("custom_command_template", "") ?: ""
    )
    val customCommandTemplate = _customCommandTemplate.asStateFlow()

    fun setCustomCommandTemplate(template: String) {
        prefs.edit().putString("custom_command_template", template).apply()
        _customCommandTemplate.value = template
    }

    // Generated llama-server runtime parameters for the general launcher.
    private val _serverPort = MutableStateFlow(prefs.getInt("server_port", 8080).coerceIn(1, 65535))
    val serverPort = _serverPort.asStateFlow()

    fun setServerPort(port: Int) {
        val normalized = port.coerceIn(1, 65535)
        prefs.edit().putInt("server_port", normalized).commit()
        _serverPort.value = normalized
    }

    private val _serverBatchSize = MutableStateFlow(prefs.getInt("server_batch_size", 512).coerceIn(1, 131072))
    val serverBatchSize = _serverBatchSize.asStateFlow()

    fun setServerBatchSize(size: Int) {
        val normalized = size.coerceIn(1, 131072)
        prefs.edit().putInt("server_batch_size", normalized).apply()
        _serverBatchSize.value = normalized
    }

    private val _serverPhysicalBatchSize = MutableStateFlow(
        if (prefs.contains("server_physical_batch_size")) {
            prefs.getInt("server_physical_batch_size", 512).coerceIn(1, 131072)
        } else {
            null
        }
    )
    val serverPhysicalBatchSize = _serverPhysicalBatchSize.asStateFlow()

    fun setServerPhysicalBatchSize(size: Int?) {
        val normalized = size?.coerceIn(1, 131072)
        prefs.edit().apply {
            if (normalized == null) remove("server_physical_batch_size") else putInt("server_physical_batch_size", normalized)
        }.apply()
        _serverPhysicalBatchSize.value = normalized
    }

    private val _serverParallel = MutableStateFlow(
        if (prefs.contains("server_parallel")) {
            prefs.getInt("server_parallel", 1).coerceIn(1, 512)
        } else {
            null
        }
    )
    val serverParallel = _serverParallel.asStateFlow()

    fun setServerParallel(parallel: Int?) {
        val normalized = parallel?.coerceIn(1, 512)
        prefs.edit().apply {
            if (normalized == null) remove("server_parallel") else putInt("server_parallel", normalized)
        }.apply()
        _serverParallel.value = normalized
    }

    private val _serverCacheRam = MutableStateFlow(
        if (prefs.contains("server_cache_ram")) {
            prefs.getInt("server_cache_ram", 0).coerceIn(0, 262144)
        } else {
            null
        }
    )
    val serverCacheRam = _serverCacheRam.asStateFlow()

    fun setServerCacheRam(cacheRamMb: Int?) {
        val normalized = cacheRamMb?.coerceIn(0, 262144)
        prefs.edit().apply {
            if (normalized == null) remove("server_cache_ram") else putInt("server_cache_ram", normalized)
        }.apply()
        _serverCacheRam.value = normalized
    }

    private val _serverContextCheckpoints = MutableStateFlow(
        prefs.getInt("server_context_checkpoints", -1).takeIf { it >= 0 }
    )
    val serverContextCheckpoints = _serverContextCheckpoints.asStateFlow()
    fun setServerContextCheckpoints(value: Int?) {
        val normalized = value?.coerceIn(0, 4096)
        prefs.edit().apply {
            if (normalized == null) remove("server_context_checkpoints")
            else putInt("server_context_checkpoints", normalized)
        }.apply()
        _serverContextCheckpoints.value = normalized
    }

    private val _serverCheckpointMinStep = MutableStateFlow(
        prefs.getInt("server_checkpoint_min_step", -1).takeIf { it >= 0 }
    )
    val serverCheckpointMinStep = _serverCheckpointMinStep.asStateFlow()
    fun setServerCheckpointMinStep(value: Int?) {
        val normalized = value?.coerceIn(0, 1_048_576)
        prefs.edit().apply {
            if (normalized == null) remove("server_checkpoint_min_step")
            else putInt("server_checkpoint_min_step", normalized)
        }.apply()
        _serverCheckpointMinStep.value = normalized
    }

    private val _serverCachePrompt = MutableStateFlow(prefs.getBoolean("server_cache_prompt", true))
    val serverCachePrompt = _serverCachePrompt.asStateFlow()
    fun setServerCachePrompt(enabled: Boolean) {
        prefs.edit().putBoolean("server_cache_prompt", enabled).apply()
        _serverCachePrompt.value = enabled
    }

    private val _serverCacheIdleSlots = MutableStateFlow(prefs.getBoolean("server_cache_idle_slots", true))
    val serverCacheIdleSlots = _serverCacheIdleSlots.asStateFlow()
    fun setServerCacheIdleSlots(enabled: Boolean) {
        prefs.edit().putBoolean("server_cache_idle_slots", enabled).apply()
        _serverCacheIdleSlots.value = enabled
    }

    private val _serverKvUnifiedMode = MutableStateFlow(
        com.example.llamadroid.service.LlamaKvUnifiedMode.fromValue(
            prefs.getString("server_kv_unified_mode", "auto")
        ).value
    )
    val serverKvUnifiedMode = _serverKvUnifiedMode.asStateFlow()
    fun setServerKvUnifiedMode(value: String) {
        val normalized = com.example.llamadroid.service.LlamaKvUnifiedMode.fromValue(value).value
        prefs.edit().putString("server_kv_unified_mode", normalized).apply()
        _serverKvUnifiedMode.value = normalized
    }

    private val _serverSwaFull = MutableStateFlow(prefs.getBoolean("server_swa_full", false))
    val serverSwaFull = _serverSwaFull.asStateFlow()
    fun setServerSwaFull(enabled: Boolean) {
        prefs.edit().putBoolean("server_swa_full", enabled).apply()
        _serverSwaFull.value = enabled
    }

    private val _serverSleepIdleSeconds = MutableStateFlow(
        prefs.getInt("server_sleep_idle_seconds", 1800).coerceIn(-1, 604_800)
    )
    val serverSleepIdleSeconds = _serverSleepIdleSeconds.asStateFlow()
    fun setServerSleepIdleSeconds(value: Int?) {
        val normalized = value?.coerceIn(-1, 604_800) ?: -1
        prefs.edit().putInt("server_sleep_idle_seconds", normalized).apply()
        _serverSleepIdleSeconds.value = normalized
    }

    private val _agentLlamaSlotAffinityMode = MutableStateFlow(
        com.example.llamadroid.service.LlamaSlotAffinityMode.fromValue(
            prefs.getString("agent_llama_slot_affinity_mode", "automatic")
        ).value
    )
    val agentLlamaSlotAffinityMode = _agentLlamaSlotAffinityMode.asStateFlow()
    fun setAgentLlamaSlotAffinityMode(value: String) {
        val normalized = com.example.llamadroid.service.LlamaSlotAffinityMode.fromValue(value).value
        prefs.edit().putString("agent_llama_slot_affinity_mode", normalized).apply()
        _agentLlamaSlotAffinityMode.value = normalized
    }

    private val _agentPromptCacheDiagnostics = MutableStateFlow(
        prefs.getBoolean("agent_prompt_cache_diagnostics", false)
    )
    val agentPromptCacheDiagnostics = _agentPromptCacheDiagnostics.asStateFlow()
    fun setAgentPromptCacheDiagnostics(enabled: Boolean) {
        prefs.edit().putBoolean("agent_prompt_cache_diagnostics", enabled).apply()
        _agentPromptCacheDiagnostics.value = enabled
    }

    private val _agentDeveloperPromptComparison = MutableStateFlow(
        prefs.getBoolean("agent_developer_prompt_comparison", false)
    )
    val agentDeveloperPromptComparison = _agentDeveloperPromptComparison.asStateFlow()
    fun setAgentDeveloperPromptComparison(enabled: Boolean) {
        prefs.edit().putBoolean("agent_developer_prompt_comparison", enabled).apply()
        _agentDeveloperPromptComparison.value = enabled
        if (!enabled) {
            AgentPromptComparisonStore.sync(context, false, "", "")
        }
    }
    
    // Ollama num_thread option (number of threads for inference)
    private val _ollamaThreads = MutableStateFlow(prefs.getInt("ollama_threads", 4))
    val ollamaThreads = _ollamaThreads.asStateFlow()
    
    fun setOllamaThreads(count: Int) {
        prefs.edit().putInt("ollama_threads", count).apply()
        _ollamaThreads.value = count
    }
    
    // Ollama num_ctx option (context length in tokens)
    private val _ollamaNumCtx = MutableStateFlow(prefs.getInt("ollama_num_ctx", AIConstants.Defaults.CONTEXT_SIZE_OLLAMA))
    val ollamaNumCtx = _ollamaNumCtx.asStateFlow()
    
    fun setOllamaNumCtx(count: Int) {
        prefs.edit().putInt("ollama_num_ctx", count).apply()
        _ollamaNumCtx.value = count
    }

    
    // ========== Stable Diffusion Thread Settings ==========
    
    // SD txt2img threads
    private val _sdTxt2imgThreads = MutableStateFlow(prefs.getInt("sd_txt2img_threads", 4))
    val sdTxt2imgThreads = _sdTxt2imgThreads.asStateFlow()
    
    fun setSdTxt2imgThreads(count: Int) {
        prefs.edit().putInt("sd_txt2img_threads", count).apply()
        _sdTxt2imgThreads.value = count
    }
    
    // SD img2img threads
    private val _sdImg2imgThreads = MutableStateFlow(prefs.getInt("sd_img2img_threads", 4))
    val sdImg2imgThreads = _sdImg2imgThreads.asStateFlow()
    
    fun setSdImg2imgThreads(count: Int) {
        prefs.edit().putInt("sd_img2img_threads", count).apply()
        _sdImg2imgThreads.value = count
    }
    
    // SD upscale threads  
    private val _sdUpscaleThreads = MutableStateFlow(prefs.getInt("sd_upscale_threads", 4))
    val sdUpscaleThreads = _sdUpscaleThreads.asStateFlow()
    
    fun setSdUpscaleThreads(count: Int) {
        prefs.edit().putInt("sd_upscale_threads", count).apply()
        _sdUpscaleThreads.value = count
    }
    
    // ========== WhisperCPP Settings ==========
    
    private val _whisperThreads = MutableStateFlow(prefs.getInt("whisper_threads", 4))
    val whisperThreads = _whisperThreads.asStateFlow()
    
    fun setWhisperThreads(count: Int) {
        prefs.edit().putInt("whisper_threads", count).apply()
        _whisperThreads.value = count
    }
    
    private val _whisperOutputFolder = MutableStateFlow(prefs.getString("whisper_output_folder", null))
    val whisperOutputFolder = _whisperOutputFolder.asStateFlow()
    
    fun setWhisperOutputFolder(uri: String?) {
        prefs.edit().putString("whisper_output_folder", uri).apply()
        _whisperOutputFolder.value = uri
    }

    private val _tamaWhisperModelPath = MutableStateFlow(prefs.getString("tama_whisper_model_path", null))
    val tamaWhisperModelPath = _tamaWhisperModelPath.asStateFlow()

    fun setTamaWhisperModelPath(path: String?) {
        prefs.edit().putString("tama_whisper_model_path", path).apply()
        _tamaWhisperModelPath.value = path
    }

    private val _tamaWhisperLanguage = MutableStateFlow(
        prefs.getString("tama_whisper_language", "auto") ?: "auto"
    )
    val tamaWhisperLanguage = _tamaWhisperLanguage.asStateFlow()

    fun setTamaWhisperLanguage(lang: String) {
        prefs.edit().putString("tama_whisper_language", lang).apply()
        _tamaWhisperLanguage.value = lang
    }

    private val _tamaChatImageInputEnabled = MutableStateFlow(
        prefs.getBoolean("tama_chat_image_input_enabled", false)
    )
    val tamaChatImageInputEnabled = _tamaChatImageInputEnabled.asStateFlow()

    fun setTamaChatImageInputEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("tama_chat_image_input_enabled", enabled).apply()
        _tamaChatImageInputEnabled.value = enabled
    }
    
    // ========== Video Upscaler Settings ==========
    
    // Load threads for loading frames (default 1 for less lag)
    private val _upscalerLoadThreads = MutableStateFlow(prefs.getInt("upscaler_load_threads", 1))
    val upscalerLoadThreads = _upscalerLoadThreads.asStateFlow()
    fun setUpscalerLoadThreads(count: Int) {
        prefs.edit().putInt("upscaler_load_threads", count).apply()
        _upscalerLoadThreads.value = count
    }
    
    // Process threads for upscaling (default 1 for less lag)
    private val _upscalerProcThreads = MutableStateFlow(prefs.getInt("upscaler_proc_threads", 1))
    val upscalerProcThreads = _upscalerProcThreads.asStateFlow()
    fun setUpscalerProcThreads(count: Int) {
        prefs.edit().putInt("upscaler_proc_threads", count).apply()
        _upscalerProcThreads.value = count
    }
    
    // Save threads for saving frames (default 1 for less lag)
    private val _upscalerSaveThreads = MutableStateFlow(prefs.getInt("upscaler_save_threads", 1))
    val upscalerSaveThreads = _upscalerSaveThreads.asStateFlow()
    fun setUpscalerSaveThreads(count: Int) {
        prefs.edit().putInt("upscaler_save_threads", count).apply()
        _upscalerSaveThreads.value = count
    }
    
    private val _upscalerOutputFolder = MutableStateFlow(prefs.getString("upscaler_output_folder", null))
    val upscalerOutputFolder = _upscalerOutputFolder.asStateFlow()
    
    fun setUpscalerOutputFolder(uri: String?) {
        prefs.edit().putString("upscaler_output_folder", uri).apply()
        _upscalerOutputFolder.value = uri
    }
    
    // ========== PDF Summary Settings ==========

    val pdfSummarySettings = RemoteSummarySettingsGroup(
        keyPrefix = "pdf_summary",
        defaultSummaryPrompt = null,
        defaultMergePrompt = null,
        defaultLlamaServerUrl = PDF_LLAMA_SERVER_DEFAULT_URL,
        fallbackChunkContextKey = "pdf_context_size",
        fallbackChunkMaxTokensKey = "pdf_max_tokens",
        fallbackTemperatureKey = "pdf_temperature",
        fallbackTimeoutKey = "pdf_summary_timeout_minutes",
        fallbackThinkingEnabledKey = "pdf_summary_thinking_enabled",
        fallbackSummaryPromptKey = "pdf_summary_prompt",
        fallbackMergePromptKey = "pdf_unification_prompt"
    )

    val pdfSummaryBackend = pdfSummarySettings.backend
    fun setPdfSummaryBackend(backend: String) = pdfSummarySettings.setBackend(backend)

    val pdfSummaryOllamaUrl = pdfSummarySettings.ollamaUrl
    fun setPdfSummaryOllamaUrl(url: String) = pdfSummarySettings.setOllamaUrl(url)

    val pdfSummaryLlamaServerUrl = pdfSummarySettings.llamaServerUrl
    fun setPdfSummaryLlamaServerUrl(url: String) = pdfSummarySettings.setLlamaServerUrl(url)

    val pdfSummaryLlamaSwapUrl = pdfSummarySettings.llamaSwapUrl
    fun setPdfSummaryLlamaSwapUrl(url: String) = pdfSummarySettings.setLlamaSwapUrl(url)

    val pdfSummaryOllamaModel = pdfSummarySettings.ollamaModel
    fun setPdfSummaryOllamaModel(model: String?) = pdfSummarySettings.setOllamaModel(model)

    val pdfSummaryLlamaSwapModel = pdfSummarySettings.llamaSwapModel
    fun setPdfSummaryLlamaSwapModel(model: String?) = pdfSummarySettings.setLlamaSwapModel(model)

    val pdfSummaryLiteRtModelId = pdfSummarySettings.liteRtModelId
    fun setPdfSummaryLiteRtModelId(modelId: Long?) = pdfSummarySettings.setLiteRtModelId(modelId)

    val pdfSummaryLiteRtBackend = pdfSummarySettings.liteRtBackend
    fun setPdfSummaryLiteRtBackend(backend: String) = pdfSummarySettings.setLiteRtBackend(backend)

    val pdfSummaryLiteRtMtpEnabled = pdfSummarySettings.liteRtMtpEnabled
    fun setPdfSummaryLiteRtMtpEnabled(enabled: Boolean) = pdfSummarySettings.setLiteRtMtpEnabled(enabled)

    val pdfSummaryThinkingEnabled = pdfSummarySettings.thinkingEnabled
    fun setPdfSummaryThinkingEnabled(enabled: Boolean) = pdfSummarySettings.setThinkingEnabled(enabled)

    val pdfSummaryLlamaServerModelLabel = pdfSummarySettings.llamaServerModelLabel
    fun setPdfSummaryLlamaServerModelLabel(label: String?) = pdfSummarySettings.setLlamaServerModelLabel(label)

    val pdfSummaryLlamaServerContextTokens = pdfSummarySettings.llamaServerContextTokens
    fun setPdfSummaryLlamaServerContextTokens(tokens: Int?) = pdfSummarySettings.setLlamaServerContextTokens(tokens)

    val pdfSummaryLlamaServerContextLabel = pdfSummarySettings.llamaServerContextLabel
    fun setPdfSummaryLlamaServerContextLabel(label: String?) = pdfSummarySettings.setLlamaServerContextLabel(label)
    
    // PDF Summary model path (separate from chat model)
    private val _pdfModelPath = MutableStateFlow(prefs.getString("pdf_model_path", null))
    val pdfModelPath = _pdfModelPath.asStateFlow()
    fun setPdfModelPath(path: String?) {
        prefs.edit().putString("pdf_model_path", path).apply()
        _pdfModelPath.value = path
    }
    
    // PDF Summary context size
    val pdfContextSize = pdfSummarySettings.chunkContext
    fun setPdfContextSize(size: Int) = pdfSummarySettings.setChunkContext(size)
    
    // PDF Summary threads
    private val _pdfThreads = MutableStateFlow(
        prefs.getInt("pdf_threads", AIConstants.Defaults.THREAD_COUNT)
            .coerceIn(PDF_THREADS_RANGE)
    )
    val pdfThreads = _pdfThreads.asStateFlow()
    fun setPdfThreads(count: Int) {
        val clamped = count.coerceIn(PDF_THREADS_RANGE)
        prefs.edit().putInt("pdf_threads", clamped).apply()
        _pdfThreads.value = clamped
    }
    
    // PDF Summary temperature
    val pdfTemperature = pdfSummarySettings.temperature
    fun setPdfTemperature(temp: Float) = pdfSummarySettings.setTemperature(temp)
    
    // PDF Summary max tokens
    val pdfMaxTokens = pdfSummarySettings.chunkMaxTokens
    fun setPdfMaxTokens(tokens: Int) = pdfSummarySettings.setChunkMaxTokens(tokens)

    val pdfMergeContextSize = pdfSummarySettings.mergeContext
    fun setPdfMergeContextSize(size: Int) = pdfSummarySettings.setMergeContext(size)

    val pdfMergeMaxTokens = pdfSummarySettings.mergeMaxTokens
    fun setPdfMergeMaxTokens(tokens: Int) = pdfSummarySettings.setMergeMaxTokens(tokens)

    val pdfSummaryTimeoutMinutes = pdfSummarySettings.timeoutMinutes
    fun setPdfSummaryTimeoutMinutes(minutes: Int) = pdfSummarySettings.setTimeoutMinutes(minutes)

    val pdfSummaryTargetLanguage = pdfSummarySettings.targetLanguage
    fun setPdfSummaryTargetLanguage(value: String) = pdfSummarySettings.setTargetLanguage(value)

    private fun defaultPdfTranslationLanguage(): String {
        return com.example.llamadroid.service.PDFTranslationLogic.defaultTranslationLanguageForAppLanguage(
            prefs.getString("selected_language", "system")
        )
    }

    val pdfTranslationSettings = RemoteSummarySettingsGroup(
        keyPrefix = "pdf_translation",
        defaultSummaryPrompt = com.example.llamadroid.service.PDFTranslationLogic.DEFAULT_PAGE_TRANSLATION_SYSTEM_PROMPT,
        defaultMergePrompt = null,
        defaultLlamaServerUrl = PDF_LLAMA_SERVER_DEFAULT_URL,
        fallbackChunkContextKey = "pdf_context_size",
        fallbackChunkMaxTokensKey = "pdf_max_tokens",
        fallbackTemperatureKey = "pdf_temperature",
        fallbackTimeoutKey = "pdf_summary_timeout_minutes",
        defaultTargetLanguage = defaultPdfTranslationLanguage()
    )

    val mangaTranslationSettings = RemoteSummarySettingsGroup(
        keyPrefix = "manga_translation",
        defaultSummaryPrompt = com.example.llamadroid.service.PDFTranslationLogic.DEFAULT_PAGE_TRANSLATION_SYSTEM_PROMPT,
        defaultMergePrompt = null,
        defaultLlamaServerUrl = PDF_LLAMA_SERVER_DEFAULT_URL,
        defaultTargetLanguage = defaultPdfTranslationLanguage()
    )

    val pdfTranslationBackend = pdfTranslationSettings.backend
    fun setPdfTranslationBackend(backend: String) = pdfTranslationSettings.setBackend(backend)

    val pdfTranslationOllamaUrl = pdfTranslationSettings.ollamaUrl
    fun setPdfTranslationOllamaUrl(url: String) = pdfTranslationSettings.setOllamaUrl(url)

    val pdfTranslationLlamaServerUrl = pdfTranslationSettings.llamaServerUrl
    fun setPdfTranslationLlamaServerUrl(url: String) = pdfTranslationSettings.setLlamaServerUrl(url)

    val pdfTranslationLlamaSwapUrl = pdfTranslationSettings.llamaSwapUrl
    fun setPdfTranslationLlamaSwapUrl(url: String) = pdfTranslationSettings.setLlamaSwapUrl(url)

    val pdfTranslationOllamaModel = pdfTranslationSettings.ollamaModel
    fun setPdfTranslationOllamaModel(model: String?) = pdfTranslationSettings.setOllamaModel(model)

    val pdfTranslationLlamaSwapModel = pdfTranslationSettings.llamaSwapModel
    fun setPdfTranslationLlamaSwapModel(model: String?) = pdfTranslationSettings.setLlamaSwapModel(model)

    val pdfTranslationLiteRtModelId = pdfTranslationSettings.liteRtModelId
    fun setPdfTranslationLiteRtModelId(modelId: Long?) = pdfTranslationSettings.setLiteRtModelId(modelId)

    val pdfTranslationLiteRtBackend = pdfTranslationSettings.liteRtBackend
    fun setPdfTranslationLiteRtBackend(backend: String) = pdfTranslationSettings.setLiteRtBackend(backend)

    val pdfTranslationLiteRtMtpEnabled = pdfTranslationSettings.liteRtMtpEnabled
    fun setPdfTranslationLiteRtMtpEnabled(enabled: Boolean) = pdfTranslationSettings.setLiteRtMtpEnabled(enabled)

    val pdfTranslationLlamaServerModelLabel = pdfTranslationSettings.llamaServerModelLabel
    fun setPdfTranslationLlamaServerModelLabel(label: String?) = pdfTranslationSettings.setLlamaServerModelLabel(label)

    val pdfTranslationLlamaServerContextTokens = pdfTranslationSettings.llamaServerContextTokens
    fun setPdfTranslationLlamaServerContextTokens(tokens: Int?) = pdfTranslationSettings.setLlamaServerContextTokens(tokens)

    val pdfTranslationLlamaServerContextLabel = pdfTranslationSettings.llamaServerContextLabel
    fun setPdfTranslationLlamaServerContextLabel(label: String?) = pdfTranslationSettings.setLlamaServerContextLabel(label)

    val pdfTranslationThinkingEnabled = pdfTranslationSettings.thinkingEnabled
    fun setPdfTranslationThinkingEnabled(enabled: Boolean) = pdfTranslationSettings.setThinkingEnabled(enabled)

    val pdfTranslationContextSize = pdfTranslationSettings.chunkContext
    fun setPdfTranslationContextSize(size: Int) = pdfTranslationSettings.setChunkContext(size)

    val pdfTranslationMaxTokens = pdfTranslationSettings.chunkMaxTokens
    fun setPdfTranslationMaxTokens(tokens: Int) = pdfTranslationSettings.setChunkMaxTokens(tokens)

    val pdfTranslationTemperature = pdfTranslationSettings.temperature
    fun setPdfTranslationTemperature(temp: Float) = pdfTranslationSettings.setTemperature(temp)

    val pdfTranslationTimeoutMinutes = pdfTranslationSettings.timeoutMinutes
    fun setPdfTranslationTimeoutMinutes(minutes: Int) = pdfTranslationSettings.setTimeoutMinutes(minutes)

    val pdfTranslationTargetLanguage = pdfTranslationSettings.targetLanguage
    fun setPdfTranslationTargetLanguage(value: String) = pdfTranslationSettings.setTargetLanguage(
        value.trim().ifBlank { defaultPdfTranslationLanguage() }
    )

    val pdfTranslationPrompt = pdfTranslationSettings.summaryPrompt
    fun setPdfTranslationPrompt(prompt: String?) = pdfTranslationSettings.setSummaryPrompt(prompt)

    private val _pdfTranslationScreenshotContext = MutableStateFlow(
        prefs.getBoolean("pdf_translation_screenshot_context", true)
    )
    val pdfTranslationScreenshotContext = _pdfTranslationScreenshotContext.asStateFlow()
    fun setPdfTranslationScreenshotContext(enabled: Boolean) {
        prefs.edit().putBoolean("pdf_translation_screenshot_context", enabled).apply()
        _pdfTranslationScreenshotContext.value = enabled
    }

    private val _pdfTranslationScreenshotMaxSide = MutableStateFlow(
        prefs.getInt("pdf_translation_screenshot_max_side", 1400).coerceIn(480, 2400)
    )
    val pdfTranslationScreenshotMaxSide = _pdfTranslationScreenshotMaxSide.asStateFlow()
    fun setPdfTranslationScreenshotMaxSide(value: Int) {
        val normalized = value.coerceIn(480, 2400)
        prefs.edit().putInt("pdf_translation_screenshot_max_side", normalized).apply()
        _pdfTranslationScreenshotMaxSide.value = normalized
    }

    private val _pdfTranslationScreenshotJpegQuality = MutableStateFlow(
        prefs.getInt("pdf_translation_screenshot_jpeg_quality", 82).coerceIn(40, 95)
    )
    val pdfTranslationScreenshotJpegQuality = _pdfTranslationScreenshotJpegQuality.asStateFlow()
    fun setPdfTranslationScreenshotJpegQuality(value: Int) {
        val normalized = value.coerceIn(40, 95)
        prefs.edit().putInt("pdf_translation_screenshot_jpeg_quality", normalized).apply()
        _pdfTranslationScreenshotJpegQuality.value = normalized
    }

    private val _pdfTranslationTextFallback = MutableStateFlow(
        prefs.getBoolean("pdf_translation_text_fallback", true)
    )
    val pdfTranslationTextFallback = _pdfTranslationTextFallback.asStateFlow()
    fun setPdfTranslationTextFallback(enabled: Boolean) {
        prefs.edit().putBoolean("pdf_translation_text_fallback", enabled).apply()
        _pdfTranslationTextFallback.value = enabled
    }

    private val _pdfTranslationQualityMode = MutableStateFlow(
        enumPref("pdf_translation_quality_mode", PdfTranslationQualityMode.BEST_QUALITY)
    )
    val pdfTranslationQualityMode = _pdfTranslationQualityMode.asStateFlow()
    fun setPdfTranslationQualityMode(mode: PdfTranslationQualityMode) {
        prefs.edit().putString("pdf_translation_quality_mode", mode.name).apply()
        _pdfTranslationQualityMode.value = mode
    }

    private val _pdfOcrProvider = MutableStateFlow(
        enumPref("pdf_ocr_provider", PdfOcrProvider.ML_KIT)
    )
    val pdfOcrProvider = _pdfOcrProvider.asStateFlow()
    fun setPdfOcrProvider(provider: PdfOcrProvider) {
        prefs.edit().putString("pdf_ocr_provider", provider.name).apply()
        _pdfOcrProvider.value = provider
    }

    private val _pdfOcrBubbleGuided = MutableStateFlow(
        prefs.getBoolean("pdf_ocr_bubble_guided", false)
    )
    val pdfOcrBubbleGuided = _pdfOcrBubbleGuided.asStateFlow()
    fun setPdfOcrBubbleGuided(enabled: Boolean) {
        prefs.edit().putBoolean("pdf_ocr_bubble_guided", enabled).apply()
        _pdfOcrBubbleGuided.value = enabled
    }

    private val _pdfOcrLlamaModelPath = MutableStateFlow(prefs.getString("pdf_ocr_llama_model_path", null))
    val pdfOcrLlamaModelPath = _pdfOcrLlamaModelPath.asStateFlow()
    fun setPdfOcrLlamaModelPath(path: String?) {
        prefs.edit().putString("pdf_ocr_llama_model_path", path?.trim()?.ifBlank { null }).apply()
        _pdfOcrLlamaModelPath.value = path?.trim()?.ifBlank { null }
    }

    private val _pdfOcrLlamaMmprojPath = MutableStateFlow(prefs.getString("pdf_ocr_llama_mmproj_path", null))
    val pdfOcrLlamaMmprojPath = _pdfOcrLlamaMmprojPath.asStateFlow()
    fun setPdfOcrLlamaMmprojPath(path: String?) {
        prefs.edit().putString("pdf_ocr_llama_mmproj_path", path?.trim()?.ifBlank { null }).apply()
        _pdfOcrLlamaMmprojPath.value = path?.trim()?.ifBlank { null }
    }

    private val _pdfOcrLlamaPromptPreset = MutableStateFlow(
        LlamaOcrPromptPreset.fromName(prefs.getString("pdf_ocr_llama_prompt_preset", null))
    )
    val pdfOcrLlamaPromptPreset = _pdfOcrLlamaPromptPreset.asStateFlow()
    fun setPdfOcrLlamaPromptPreset(preset: LlamaOcrPromptPreset) {
        prefs.edit().putString("pdf_ocr_llama_prompt_preset", preset.name).apply()
        _pdfOcrLlamaPromptPreset.value = preset
    }

    private val _pdfOcrLlamaCustomPrompt = MutableStateFlow(prefs.getString("pdf_ocr_llama_custom_prompt", null))
    val pdfOcrLlamaCustomPrompt = _pdfOcrLlamaCustomPrompt.asStateFlow()
    fun setPdfOcrLlamaCustomPrompt(prompt: String?) {
        prefs.edit().putString("pdf_ocr_llama_custom_prompt", prompt?.trim()?.ifBlank { null }).apply()
        _pdfOcrLlamaCustomPrompt.value = prompt?.trim()?.ifBlank { null }
    }

    private val _pdfOcrLlamaContextSize = MutableStateFlow(
        prefs.getInt("pdf_ocr_llama_context_size", 16384).coerceIn(2048, 65536)
    )
    val pdfOcrLlamaContextSize = _pdfOcrLlamaContextSize.asStateFlow()
    fun setPdfOcrLlamaContextSize(value: Int) {
        val normalized = value.coerceIn(2048, 65536)
        prefs.edit().putInt("pdf_ocr_llama_context_size", normalized).apply()
        _pdfOcrLlamaContextSize.value = normalized
    }

    private val _pdfOcrLlamaMaxTokens = MutableStateFlow(
        prefs.getInt("pdf_ocr_llama_max_tokens", 2600).coerceIn(128, 32768)
    )
    val pdfOcrLlamaMaxTokens = _pdfOcrLlamaMaxTokens.asStateFlow()
    fun setPdfOcrLlamaMaxTokens(value: Int) {
        val normalized = value.coerceIn(128, 32768)
        prefs.edit().putInt("pdf_ocr_llama_max_tokens", normalized).apply()
        _pdfOcrLlamaMaxTokens.value = normalized
    }

    private val _pdfOcrLlamaPort = MutableStateFlow(
        prefs.getInt("pdf_ocr_llama_port", 8087).coerceIn(1024, 65535)
    )
    val pdfOcrLlamaPort = _pdfOcrLlamaPort.asStateFlow()
    fun setPdfOcrLlamaPort(value: Int) {
        val normalized = value.coerceIn(1024, 65535)
        prefs.edit().putInt("pdf_ocr_llama_port", normalized).apply()
        _pdfOcrLlamaPort.value = normalized
    }

    private val _pdfOcrLlamaFlashAttention = MutableStateFlow(
        prefs.getBoolean("pdf_ocr_llama_flash_attention", false)
    )
    val pdfOcrLlamaFlashAttention = _pdfOcrLlamaFlashAttention.asStateFlow()
    fun setPdfOcrLlamaFlashAttention(enabled: Boolean) {
        prefs.edit().putBoolean("pdf_ocr_llama_flash_attention", enabled).apply()
        _pdfOcrLlamaFlashAttention.value = enabled
    }

    private val _pdfOcrLlamaCacheRam = MutableStateFlow(
        prefs.getInt("pdf_ocr_llama_cache_ram", 0).coerceIn(0, 131072)
    )
    val pdfOcrLlamaCacheRam = _pdfOcrLlamaCacheRam.asStateFlow()
    fun setPdfOcrLlamaCacheRam(value: Int) {
        val normalized = value.coerceIn(0, 131072)
        prefs.edit().putInt("pdf_ocr_llama_cache_ram", normalized).apply()
        _pdfOcrLlamaCacheRam.value = normalized
    }

    private val _pdfOcrLlamaParallel = MutableStateFlow(
        prefs.getInt("pdf_ocr_llama_parallel", 1).coerceIn(1, 8)
    )
    val pdfOcrLlamaParallel = _pdfOcrLlamaParallel.asStateFlow()
    fun setPdfOcrLlamaParallel(value: Int) {
        val normalized = value.coerceIn(1, 8)
        prefs.edit().putInt("pdf_ocr_llama_parallel", normalized).apply()
        _pdfOcrLlamaParallel.value = normalized
    }

    private val _pdfOcrLlamaCustomFlags = MutableStateFlow(prefs.getString("pdf_ocr_llama_custom_flags", null))
    val pdfOcrLlamaCustomFlags = _pdfOcrLlamaCustomFlags.asStateFlow()
    fun setPdfOcrLlamaCustomFlags(flags: String?) {
        prefs.edit().putString("pdf_ocr_llama_custom_flags", flags?.trim()?.ifBlank { null }).apply()
        _pdfOcrLlamaCustomFlags.value = flags?.trim()?.ifBlank { null }
    }

    private val _pdfOcrLlamaCommandTemplate = MutableStateFlow(prefs.getString("pdf_ocr_llama_command_template", null))
    val pdfOcrLlamaCommandTemplate = _pdfOcrLlamaCommandTemplate.asStateFlow()
    fun setPdfOcrLlamaCommandTemplate(template: String?) {
        prefs.edit().putString("pdf_ocr_llama_command_template", template?.trim()?.ifBlank { null }).apply()
        _pdfOcrLlamaCommandTemplate.value = template?.trim()?.ifBlank { null }
    }

    private val _pdfOcrLlamaReplaceRunningServer = MutableStateFlow(
        prefs.getBoolean("pdf_ocr_llama_replace_running_server", true)
    )
    val pdfOcrLlamaReplaceRunningServer = _pdfOcrLlamaReplaceRunningServer.asStateFlow()
    fun setPdfOcrLlamaReplaceRunningServer(enabled: Boolean) {
        prefs.edit().putBoolean("pdf_ocr_llama_replace_running_server", enabled).apply()
        _pdfOcrLlamaReplaceRunningServer.value = enabled
    }

    fun pdfTranslationOptionsSnapshot(): PdfTranslationOptionsSnapshot {
        return PdfTranslationOptionsSnapshot(
            usePageScreenshotContext = pdfTranslationScreenshotContext.value,
            screenshotMaxSide = pdfTranslationScreenshotMaxSide.value,
            screenshotJpegQuality = pdfTranslationScreenshotJpegQuality.value,
            textOnlyFallbackEnabled = pdfTranslationTextFallback.value,
            qualityMode = pdfTranslationQualityMode.value,
            ocrProvider = pdfOcrProvider.value,
            bubbleGuidedOcrEnabled = pdfOcrBubbleGuided.value,
            llamaOcr = LlamaOcrSettingsSnapshot(
                modelPath = pdfOcrLlamaModelPath.value,
                mmprojPath = pdfOcrLlamaMmprojPath.value,
                promptPreset = pdfOcrLlamaPromptPreset.value,
                customPrompt = pdfOcrLlamaCustomPrompt.value,
                contextSize = pdfOcrLlamaContextSize.value,
                maxTokens = pdfOcrLlamaMaxTokens.value,
                port = pdfOcrLlamaPort.value,
                flashAttention = pdfOcrLlamaFlashAttention.value,
                cacheRam = pdfOcrLlamaCacheRam.value,
                parallel = pdfOcrLlamaParallel.value,
                customFlags = pdfOcrLlamaCustomFlags.value,
                commandTemplate = pdfOcrLlamaCommandTemplate.value,
                temporarilyReplaceRunningServer = pdfOcrLlamaReplaceRunningServer.value
            )
        )
    }

    fun defaultMangaTranslationRunConfig(): com.example.llamadroid.service.MangaTranslationRunConfig {
        val profile = com.example.llamadroid.service.MangaTranslationProfile.BEST_READING
        val settings = mangaTranslationSettings.snapshot()
        val llamaOcr = LlamaOcrSettingsSnapshot(
            modelPath = null,
            mmprojPath = null,
            promptPreset = LlamaOcrPromptPreset.GENERIC_OCR,
            customPrompt = null,
            contextSize = 16384,
            maxTokens = 2600,
            port = 8087,
            flashAttention = false,
            cacheRam = 0,
            parallel = 1,
            customFlags = LlamaOcrPromptPreset.GENERIC_OCR.recommendedFlags,
            commandTemplate = null,
            temporarilyReplaceRunningServer = true
        )
        val translationConfig = com.example.llamadroid.service.DocumentTranslationRunConfig(
            settings = settings,
            usePageImageContext = true,
            pageImageMaxSide = 1400,
            pageImageJpegQuality = 82,
            textOnlyFallbackEnabled = true,
            qualityMode = PdfTranslationQualityMode.BEST_QUALITY
        )
        return com.example.llamadroid.service.MangaTranslationRunConfig(
            profile = profile,
            targetLanguage = settings.targetLanguage,
            readingDirection = com.example.llamadroid.service.MangaReadingDirection.AUTO,
            translationConfig = translationConfig,
            ocrConfig = com.example.llamadroid.service.DocumentOcrRunConfig(
                provider = PdfOcrProvider.ML_KIT,
                strategy = com.example.llamadroid.service.MangaOcrStrategy.HYBRID,
                llamaOcr = llamaOcr
            ),
            behavior = com.example.llamadroid.service.MangaTranslationSupport.defaultBehavior(profile),
            pageImageContextAvailable = true,
            pageImageContextReason = null
        )
    }

    fun mangaTranslationRunConfigSnapshot(): com.example.llamadroid.service.MangaTranslationRunConfig {
        val fallback = defaultMangaTranslationRunConfig()
        val stored = prefs.getString(MANGA_TRANSLATION_CONFIG_KEY, null) ?: return fallback
        return runCatching {
            com.example.llamadroid.service.MangaTranslationSupport.runConfigFromJson(
                JSONObject(stored),
                fallback
            )
        }.getOrDefault(fallback)
    }

    fun saveMangaTranslationRunConfig(config: com.example.llamadroid.service.MangaTranslationRunConfig) {
        mangaTranslationSettings.applySnapshot(config.translationSettings)
        prefs.edit()
            .putString(
                MANGA_TRANSLATION_CONFIG_KEY,
                com.example.llamadroid.service.MangaTranslationSupport.runConfigToJson(config).toString()
            )
            .apply()
    }
    
    // PDF Summary system prompt (for summarizing each chunk)
    val pdfSummaryPrompt = pdfSummarySettings.summaryPrompt
    fun setPdfSummaryPrompt(prompt: String?) = pdfSummarySettings.setSummaryPrompt(prompt)
    
    // PDF Unification system prompt (for combining summaries)
    val pdfUnificationPrompt = pdfSummarySettings.mergePrompt
    fun setPdfUnificationPrompt(prompt: String?) = pdfSummarySettings.setMergePrompt(prompt)
    
    // Welcome screen completion flag
    private val _hasCompletedWelcome = MutableStateFlow(prefs.getBoolean("has_completed_welcome", false))
    val hasCompletedWelcome = _hasCompletedWelcome.asStateFlow()
    
    fun setHasCompletedWelcome(completed: Boolean) {
        prefs.edit().putBoolean("has_completed_welcome", completed).apply()
        _hasCompletedWelcome.value = completed
    }
    
    // Selected language (system, en, es)
    private val _selectedLanguage = MutableStateFlow(prefs.getString("selected_language", "system") ?: "system")
    val selectedLanguage = _selectedLanguage.asStateFlow()
    
    fun setSelectedLanguage(lang: String) {
        prefs.edit().putString("selected_language", lang).apply()
        _selectedLanguage.value = lang
        if (!prefs.contains("pdf_translation_target_language")) {
            pdfTranslationSettings.setTargetLanguage(defaultPdfTranslationLanguage())
        }
    }
    
    // Legacy model library folder URI (deprecated/no-op for Play-safe storage)
    private val _modelStorageUri = MutableStateFlow<String?>(null)
    val modelStorageUri = _modelStorageUri.asStateFlow()
    
    fun setModelStorageUri(@Suppress("UNUSED_PARAMETER") uri: String?) {
        prefs.edit().remove("model_storage_uri").apply()
        _modelStorageUri.value = null
    }
    
    // ZIM Files Storage URI (SAF) for Kiwix
    private val _zimFolderUri = MutableStateFlow(prefs.getString("zim_folder_uri", null))
    val zimFolderUri = _zimFolderUri.asStateFlow()
    
    fun setZimFolderUri(uri: String?) {
        prefs.edit().putString("zim_folder_uri", uri).apply()
        _zimFolderUri.value = uri
    }
    
    // Kiwix Server LAN Visibility (like LLM remote access)
    private val _kiwixRemoteAccess = MutableStateFlow(prefs.getBoolean("kiwix_remote_access", false))
    val kiwixRemoteAccess = _kiwixRemoteAccess.asStateFlow()
    
    fun setKiwixRemoteAccess(enabled: Boolean) {
        prefs.edit().putBoolean("kiwix_remote_access", enabled).apply()
        _kiwixRemoteAccess.value = enabled
    }
    
    // ========== KV Cache Quantization Settings ==========
    
    // LLM Server (llama-server) KV Cache settings
    private val _serverKvCacheEnabled = MutableStateFlow(prefs.getBoolean("server_kv_cache_enabled", false))
    val serverKvCacheEnabled = _serverKvCacheEnabled.asStateFlow()
    fun setServerKvCacheEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("server_kv_cache_enabled", enabled).apply()
        _serverKvCacheEnabled.value = enabled
    }
    
    private val _serverKvCacheTypeK = MutableStateFlow(prefs.getString("server_kv_cache_type_k", "f16") ?: "f16")
    val serverKvCacheTypeK = _serverKvCacheTypeK.asStateFlow()
    fun setServerKvCacheTypeK(type: String) {
        prefs.edit().putString("server_kv_cache_type_k", type).apply()
        _serverKvCacheTypeK.value = type
    }
    
    private val _serverKvCacheTypeV = MutableStateFlow(prefs.getString("server_kv_cache_type_v", "f16") ?: "f16")
    val serverKvCacheTypeV = _serverKvCacheTypeV.asStateFlow()
    fun setServerKvCacheTypeV(type: String) {
        prefs.edit().putString("server_kv_cache_type_v", type).apply()
        _serverKvCacheTypeV.value = type
    }
    
    private val _serverKvCacheReuse = MutableStateFlow(prefs.getInt("server_kv_cache_reuse", 0))
    val serverKvCacheReuse = _serverKvCacheReuse.asStateFlow()
    fun setServerKvCacheReuse(tokens: Int) {
        prefs.edit().putInt("server_kv_cache_reuse", tokens).apply()
        _serverKvCacheReuse.value = tokens
    }

    private val _llamaKvOffloadMode = MutableStateFlow(
        normalizeLlamaKvOffloadMode(prefs.getString("llama_kv_offload_mode", LLAMA_KV_OFFLOAD_AUTO))
    )
    val llamaKvOffloadMode = _llamaKvOffloadMode.asStateFlow()
    fun setLlamaKvOffloadMode(mode: String) {
        val normalized = normalizeLlamaKvOffloadMode(mode)
        prefs.edit().putString("llama_kv_offload_mode", normalized).apply()
        _llamaKvOffloadMode.value = normalized
    }
    
    // ========== Speculative Decoding Settings ==========
    
    // Enable speculative decoding globally
    private val _speculativeEnabled = MutableStateFlow(prefs.getBoolean("speculative_enabled", false))
    val speculativeEnabled = _speculativeEnabled.asStateFlow()
    fun setSpeculativeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("speculative_enabled", enabled).apply()
        _speculativeEnabled.value = enabled
    }

    private val _speculativeMode = MutableStateFlow(
        if (prefs.contains("speculative_mode")) {
            LlamaSpeculativeMode.fromFlagValue(prefs.getString("speculative_mode", null))
        } else if (prefs.getBoolean("mtp_decoding_enabled", false)) {
            LlamaSpeculativeMode.DRAFT_MTP
        } else {
            LlamaSpeculativeMode.DRAFT_SIMPLE
        }
    )
    val speculativeMode = _speculativeMode.asStateFlow()
    fun setSpeculativeMode(mode: LlamaSpeculativeMode) {
        prefs.edit().putString("speculative_mode", mode.flagValue).apply()
        _speculativeMode.value = mode
    }
    
    // Draft model path for global setting
    private val _draftModelPath = MutableStateFlow(prefs.getString("draft_model_path", null))
    val draftModelPath = _draftModelPath.asStateFlow()
    fun setDraftModelPath(path: String?) {
        prefs.edit().putString("draft_model_path", path).apply()
        _draftModelPath.value = path
    }
    
    // Draft max tokens
    private val _draftMaxTokens = MutableStateFlow(prefs.getInt("draft_max_tokens", 3))
    val draftMaxTokens = _draftMaxTokens.asStateFlow()
    fun setDraftMaxTokens(max: Int) {
        prefs.edit().putInt("draft_max_tokens", max).apply()
        _draftMaxTokens.value = max
    }
    
    // Draft min tokens
    private val _draftMinTokens = MutableStateFlow(prefs.getInt("draft_min_tokens", 0))
    val draftMinTokens = _draftMinTokens.asStateFlow()
    fun setDraftMinTokens(min: Int) {
        prefs.edit().putInt("draft_min_tokens", min).apply()
        _draftMinTokens.value = min
    }
    
    // Draft p-min threshold
    private val _draftPMin = MutableStateFlow(prefs.getFloat("draft_p_min", 0.0f))
    val draftPMin = _draftPMin.asStateFlow()
    fun setDraftPMin(pMin: Float) {
        val normalized = pMin.coerceIn(0f, 1f)
        prefs.edit().putFloat("draft_p_min", normalized).apply()
        _draftPMin.value = normalized
    }

    private val _draftThreads = MutableStateFlow(prefs.getInt("draft_threads", 4).coerceIn(1, 16))
    val draftThreads = _draftThreads.asStateFlow()
    fun setDraftThreads(threads: Int) {
        val normalized = threads.coerceIn(1, 16)
        prefs.edit().putInt("draft_threads", normalized).apply()
        _draftThreads.value = normalized
    }

    private val _draftThreadsBatch = MutableStateFlow(prefs.getInt("draft_threads_batch", 4).coerceIn(1, 16))
    val draftThreadsBatch = _draftThreadsBatch.asStateFlow()
    fun setDraftThreadsBatch(threads: Int) {
        val normalized = threads.coerceIn(1, 16)
        prefs.edit().putInt("draft_threads_batch", normalized).apply()
        _draftThreadsBatch.value = normalized
    }

    // Enable embedded MTP decoding for llama.cpp models with MTP heads
    private val _mtpDecodingEnabled = MutableStateFlow(prefs.getBoolean("mtp_decoding_enabled", false))
    val mtpDecodingEnabled = _mtpDecodingEnabled.asStateFlow()
    fun setMtpDecodingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("mtp_decoding_enabled", enabled).apply()
        _mtpDecodingEnabled.value = enabled
    }

    private val _mtpDraftMaxTokens = MutableStateFlow(prefs.getInt("mtp_draft_max_tokens", 3).coerceIn(1, 16))
    val mtpDraftMaxTokens = _mtpDraftMaxTokens.asStateFlow()
    fun setMtpDraftMaxTokens(max: Int) {
        val normalized = max.coerceIn(1, 16)
        prefs.edit().putInt("mtp_draft_max_tokens", normalized).apply()
        _mtpDraftMaxTokens.value = normalized
    }

    private val _mtpDraftMinTokens = MutableStateFlow(prefs.getInt("mtp_draft_min_tokens", 0).coerceAtLeast(0))
    val mtpDraftMinTokens = _mtpDraftMinTokens.asStateFlow()
    fun setMtpDraftMinTokens(min: Int) {
        val normalized = min.coerceAtLeast(0)
        prefs.edit().putInt("mtp_draft_min_tokens", normalized).apply()
        _mtpDraftMinTokens.value = normalized
    }

    private val _mtpDraftPMin = MutableStateFlow(prefs.getFloat("mtp_draft_p_min", 0.0f).coerceIn(0f, 1f))
    val mtpDraftPMin = _mtpDraftPMin.asStateFlow()
    fun setMtpDraftPMin(pMin: Float) {
        val normalized = pMin.coerceIn(0f, 1f)
        prefs.edit().putFloat("mtp_draft_p_min", normalized).apply()
        _mtpDraftPMin.value = normalized
    }

    private val _mtpUseDraftModel = MutableStateFlow(prefs.getBoolean("mtp_use_draft_model", false))
    val mtpUseDraftModel = _mtpUseDraftModel.asStateFlow()
    fun setMtpUseDraftModel(enabled: Boolean) {
        prefs.edit().putBoolean("mtp_use_draft_model", enabled).apply()
        _mtpUseDraftModel.value = enabled
    }

    private val _llamaDraftDeviceMode = MutableStateFlow(
        normalizeLlamaDraftDeviceMode(prefs.getString("llama_draft_device_mode", LLAMA_DRAFT_DEVICE_AUTO))
    )
    val llamaDraftDeviceMode = _llamaDraftDeviceMode.asStateFlow()
    fun setLlamaDraftDeviceMode(mode: String) {
        val normalized = normalizeLlamaDraftDeviceMode(mode)
        prefs.edit().putString("llama_draft_device_mode", normalized).apply()
        _llamaDraftDeviceMode.value = normalized
    }

    /**
     * General local-LLM OpenCL placement experiment. Distributed profiles deliberately do not
     * read this preference; their worker placement remains owned by DistributedService.
     */
    private val _llamaOpenClCpuTargetGpuDraft = MutableStateFlow(
        prefs.getBoolean("llama_opencl_cpu_target_gpu_draft", false)
    )
    val llamaOpenClCpuTargetGpuDraft = _llamaOpenClCpuTargetGpuDraft.asStateFlow()

    fun setLlamaOpenClCpuTargetGpuDraft(enabled: Boolean) {
        prefs.edit().putBoolean("llama_opencl_cpu_target_gpu_draft", enabled).apply()
        _llamaOpenClCpuTargetGpuDraft.value = enabled
    }

    private val _ngramModNMatch = MutableStateFlow(prefs.getInt("spec_ngram_mod_n_match", 24).coerceAtLeast(1))
    val ngramModNMatch = _ngramModNMatch.asStateFlow()
    fun setNgramModNMatch(value: Int) {
        val normalized = value.coerceAtLeast(1)
        prefs.edit().putInt("spec_ngram_mod_n_match", normalized).apply()
        _ngramModNMatch.value = normalized
    }

    private val _ngramModNMin = MutableStateFlow(prefs.getInt("spec_ngram_mod_n_min", 48).coerceAtLeast(1))
    val ngramModNMin = _ngramModNMin.asStateFlow()
    fun setNgramModNMin(value: Int) {
        val normalized = value.coerceAtLeast(1)
        prefs.edit().putInt("spec_ngram_mod_n_min", normalized).apply()
        _ngramModNMin.value = normalized
        if (_ngramModNMax.value < normalized) setNgramModNMax(normalized)
    }

    private val _ngramModNMax = MutableStateFlow(prefs.getInt("spec_ngram_mod_n_max", 64).coerceAtLeast(1))
    val ngramModNMax = _ngramModNMax.asStateFlow()
    fun setNgramModNMax(value: Int) {
        val normalized = value.coerceAtLeast(_ngramModNMin.value.coerceAtLeast(1))
        prefs.edit().putInt("spec_ngram_mod_n_max", normalized).apply()
        _ngramModNMax.value = normalized
    }

    private val _ngramSimpleSizeN = MutableStateFlow(prefs.getInt("spec_ngram_simple_size_n", 12).coerceAtLeast(1))
    val ngramSimpleSizeN = _ngramSimpleSizeN.asStateFlow()
    fun setNgramSimpleSizeN(value: Int) {
        val normalized = value.coerceAtLeast(1)
        prefs.edit().putInt("spec_ngram_simple_size_n", normalized).apply()
        _ngramSimpleSizeN.value = normalized
    }

    private val _ngramSimpleSizeM = MutableStateFlow(prefs.getInt("spec_ngram_simple_size_m", 48).coerceAtLeast(1))
    val ngramSimpleSizeM = _ngramSimpleSizeM.asStateFlow()
    fun setNgramSimpleSizeM(value: Int) {
        val normalized = value.coerceAtLeast(1)
        prefs.edit().putInt("spec_ngram_simple_size_m", normalized).apply()
        _ngramSimpleSizeM.value = normalized
    }

    private val _ngramSimpleMinHits = MutableStateFlow(prefs.getInt("spec_ngram_simple_min_hits", 1).coerceAtLeast(1))
    val ngramSimpleMinHits = _ngramSimpleMinHits.asStateFlow()
    fun setNgramSimpleMinHits(value: Int) {
        val normalized = value.coerceAtLeast(1)
        prefs.edit().putInt("spec_ngram_simple_min_hits", normalized).apply()
        _ngramSimpleMinHits.value = normalized
    }

    private val _ngramMapKSizeN = MutableStateFlow(prefs.getInt("spec_ngram_map_k_size_n", 12).coerceAtLeast(1))
    val ngramMapKSizeN = _ngramMapKSizeN.asStateFlow()
    fun setNgramMapKSizeN(value: Int) {
        val normalized = value.coerceAtLeast(1)
        prefs.edit().putInt("spec_ngram_map_k_size_n", normalized).apply()
        _ngramMapKSizeN.value = normalized
    }

    private val _ngramMapKSizeM = MutableStateFlow(prefs.getInt("spec_ngram_map_k_size_m", 48).coerceAtLeast(1))
    val ngramMapKSizeM = _ngramMapKSizeM.asStateFlow()
    fun setNgramMapKSizeM(value: Int) {
        val normalized = value.coerceAtLeast(1)
        prefs.edit().putInt("spec_ngram_map_k_size_m", normalized).apply()
        _ngramMapKSizeM.value = normalized
    }

    private val _ngramMapKMinHits = MutableStateFlow(prefs.getInt("spec_ngram_map_k_min_hits", 1).coerceAtLeast(1))
    val ngramMapKMinHits = _ngramMapKMinHits.asStateFlow()
    fun setNgramMapKMinHits(value: Int) {
        val normalized = value.coerceAtLeast(1)
        prefs.edit().putInt("spec_ngram_map_k_min_hits", normalized).apply()
        _ngramMapKMinHits.value = normalized
    }

    private val _ngramMapK4VSizeN = MutableStateFlow(prefs.getInt("spec_ngram_map_k4v_size_n", 12).coerceAtLeast(1))
    val ngramMapK4VSizeN = _ngramMapK4VSizeN.asStateFlow()
    fun setNgramMapK4VSizeN(value: Int) {
        val normalized = value.coerceAtLeast(1)
        prefs.edit().putInt("spec_ngram_map_k4v_size_n", normalized).apply()
        _ngramMapK4VSizeN.value = normalized
    }

    private val _ngramMapK4VSizeM = MutableStateFlow(prefs.getInt("spec_ngram_map_k4v_size_m", 48).coerceAtLeast(1))
    val ngramMapK4VSizeM = _ngramMapK4VSizeM.asStateFlow()
    fun setNgramMapK4VSizeM(value: Int) {
        val normalized = value.coerceAtLeast(1)
        prefs.edit().putInt("spec_ngram_map_k4v_size_m", normalized).apply()
        _ngramMapK4VSizeM.value = normalized
    }

    private val _ngramMapK4VMinHits = MutableStateFlow(prefs.getInt("spec_ngram_map_k4v_min_hits", 1).coerceAtLeast(1))
    val ngramMapK4VMinHits = _ngramMapK4VMinHits.asStateFlow()
    fun setNgramMapK4VMinHits(value: Int) {
        val normalized = value.coerceAtLeast(1)
        prefs.edit().putInt("spec_ngram_map_k4v_min_hits", normalized).apply()
        _ngramMapK4VMinHits.value = normalized
    }

    private val _llamaNativeToolsEnabled = MutableStateFlow(prefs.getBoolean("llama_native_tools_enabled", false))
    val llamaNativeToolsEnabled = _llamaNativeToolsEnabled.asStateFlow()
    fun setLlamaNativeToolsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("llama_native_tools_enabled", enabled).apply()
        _llamaNativeToolsEnabled.value = enabled
    }
    
    // Flash Attention global flag
    private val _flashAttentionEnabled = MutableStateFlow(prefs.getBoolean("flash_attention_enabled", false))
    val flashAttentionEnabled = _flashAttentionEnabled.asStateFlow()
    fun setFlashAttentionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("flash_attention_enabled", enabled).apply()
        _flashAttentionEnabled.value = enabled
    }
    
    // PDF AI remote summary KV Cache settings
    private val _pdfKvCacheEnabled = MutableStateFlow(prefs.getBoolean("pdf_kv_cache_enabled", false))
    val pdfKvCacheEnabled = _pdfKvCacheEnabled.asStateFlow()
    fun setPdfKvCacheEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("pdf_kv_cache_enabled", enabled).apply()
        _pdfKvCacheEnabled.value = enabled
    }
    
    private val _pdfKvCacheTypeK = MutableStateFlow(prefs.getString("pdf_kv_cache_type_k", "f16") ?: "f16")
    val pdfKvCacheTypeK = _pdfKvCacheTypeK.asStateFlow()
    fun setPdfKvCacheTypeK(type: String) {
        prefs.edit().putString("pdf_kv_cache_type_k", type).apply()
        _pdfKvCacheTypeK.value = type
    }
    
    private val _pdfKvCacheTypeV = MutableStateFlow(prefs.getString("pdf_kv_cache_type_v", "f16") ?: "f16")
    val pdfKvCacheTypeV = _pdfKvCacheTypeV.asStateFlow()
    fun setPdfKvCacheTypeV(type: String) {
        prefs.edit().putString("pdf_kv_cache_type_v", type).apply()
        _pdfKvCacheTypeV.value = type
    }
    
    // Video Sumup remote summary KV Cache settings
    private val _videoKvCacheEnabled = MutableStateFlow(prefs.getBoolean("video_kv_cache_enabled", false))
    val videoKvCacheEnabled = _videoKvCacheEnabled.asStateFlow()
    fun setVideoKvCacheEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("video_kv_cache_enabled", enabled).apply()
        _videoKvCacheEnabled.value = enabled
    }
    
    private val _videoKvCacheTypeK = MutableStateFlow(prefs.getString("video_kv_cache_type_k", "f16") ?: "f16")
    val videoKvCacheTypeK = _videoKvCacheTypeK.asStateFlow()
    fun setVideoKvCacheTypeK(type: String) {
        prefs.edit().putString("video_kv_cache_type_k", type).apply()
        _videoKvCacheTypeK.value = type
    }
    
    private val _videoKvCacheTypeV = MutableStateFlow(prefs.getString("video_kv_cache_type_v", "f16") ?: "f16")
    val videoKvCacheTypeV = _videoKvCacheTypeV.asStateFlow()
    fun setVideoKvCacheTypeV(type: String) {
        prefs.edit().putString("video_kv_cache_type_v", type).apply()
        _videoKvCacheTypeV.value = type
    }
    
    // File Server settings
    private val _fileServerFolderUri = MutableStateFlow(prefs.getString("file_server_folder_uri", null))
    val fileServerFolderUri = _fileServerFolderUri.asStateFlow()
    fun setFileServerFolderUri(uri: String?) {
        prefs.edit().putString("file_server_folder_uri", uri).apply()
        _fileServerFolderUri.value = uri
    }
    
    private val _fileServerPort = MutableStateFlow(prefs.getInt("file_server_port", AIConstants.Ports.FILE_SERVER))
    val fileServerPort = _fileServerPort.asStateFlow()
    fun setFileServerPort(port: Int) {
        prefs.edit().putInt("file_server_port", port).apply()
        _fileServerPort.value = port
    }

    // ========== Stable Diffusion Memory Settings ==========

    private val _sdMaxCpuRamEnabled = MutableStateFlow(prefs.getBoolean("sd_max_cpu_ram_enabled", false))
    val sdMaxCpuRamEnabled = _sdMaxCpuRamEnabled.asStateFlow()
    fun setSdMaxCpuRamEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("sd_max_cpu_ram_enabled", enabled).apply()
        _sdMaxCpuRamEnabled.value = enabled
    }

    private val _sdMaxCpuRamGiB = MutableStateFlow(prefs.getString("sd_max_cpu_ram_gib", "") ?: "")
    val sdMaxCpuRamGiB = _sdMaxCpuRamGiB.asStateFlow()
    fun setSdMaxCpuRamGiB(value: String) {
        val normalized = value.trim().take(12)
        prefs.edit().putString("sd_max_cpu_ram_gib", normalized).apply()
        _sdMaxCpuRamGiB.value = normalized
    }
    
    private val _sdVaeTiling = MutableStateFlow(prefs.getBoolean("sd_vae_tiling", false))
    val sdVaeTiling = _sdVaeTiling.asStateFlow()
    fun setSdVaeTiling(enabled: Boolean) {
        prefs.edit().putBoolean("sd_vae_tiling", enabled).apply()
        _sdVaeTiling.value = enabled
    }

    private val _sdVaeTileOverlap = MutableStateFlow(prefs.getFloat("sd_vae_tile_overlap", 0.5f))
    val sdVaeTileOverlap = _sdVaeTileOverlap.asStateFlow()
    fun setSdVaeTileOverlap(overlap: Float) {
        prefs.edit().putFloat("sd_vae_tile_overlap", overlap).apply()
        _sdVaeTileOverlap.value = overlap
    }

    private val _sdVaeTileSize = MutableStateFlow(prefs.getString("sd_vae_tile_size", "32x32") ?: "32x32")
    val sdVaeTileSize = _sdVaeTileSize.asStateFlow()
    fun setSdVaeTileSize(size: String) {
        prefs.edit().putString("sd_vae_tile_size", size).apply()
        _sdVaeTileSize.value = size
    }

    private val _sdVaeRelativeTileSize = MutableStateFlow(prefs.getString("sd_vae_relative_tile_size", "") ?: "")
    val sdVaeRelativeTileSize = _sdVaeRelativeTileSize.asStateFlow()
    fun setSdVaeRelativeTileSize(size: String) {
        prefs.edit().putString("sd_vae_relative_tile_size", size).apply()
        _sdVaeRelativeTileSize.value = size
    }

    private val _sdTensorTypeRules = MutableStateFlow(prefs.getString("sd_tensor_type_rules", "") ?: "")
    val sdTensorTypeRules = _sdTensorTypeRules.asStateFlow()
    fun setSdTensorTypeRules(rules: String) {
        prefs.edit().putString("sd_tensor_type_rules", rules).apply()
        _sdTensorTypeRules.value = rules
    }

    // FLUX Reminder persistence
    private val _showFluxReminderPending = MutableStateFlow(prefs.getBoolean("show_flux_reminder_pending", false))
    val showFluxReminderPending = _showFluxReminderPending.asStateFlow()
    fun setShowFluxReminderPending(pending: Boolean) {
        prefs.edit().putBoolean("show_flux_reminder_pending", pending).apply()
        _showFluxReminderPending.value = pending
    }

    /**
     * Check if external model storage is configured
     */
    fun isExternalStorageConfigured(): Boolean = false
    
    /**
     * Model type enum for folder organization
     */
    enum class ModelType {
        LLM,
        SD_CHECKPOINT,
        SD_VAE,
        SD_LORA,
        WHISPER
    }
    
    /**
     * Get the subfolder name for each model type
     */
    fun getSubfolderForType(type: ModelType): String = when (type) {
        ModelType.LLM -> "llm"
        ModelType.SD_CHECKPOINT -> "sd/checkpoints"
        ModelType.SD_VAE -> "sd/vae"
        ModelType.SD_LORA -> "sd/lora"
        ModelType.WHISPER -> "whisper"
    }
    
    // ========== Distributed Inference Layer Distribution Memory ==========
    
    /**
     * Store the last layer distribution for a model (JSON: {workerId: layerProportion})
     * This allows reusing the same distribution to take advantage of worker caches.
     * Format: JSON object like {"worker1:50052": 0.6, "worker2:50052": 0.4}
     */
    private val _lastLayerDistribution = MutableStateFlow(prefs.getString("last_layer_distribution", null))
    val lastLayerDistribution = _lastLayerDistribution.asStateFlow()
    
    fun setLastLayerDistribution(distribution: String?) {
        prefs.edit().putString("last_layer_distribution", distribution).apply()
        _lastLayerDistribution.value = distribution
    }
    
    /**
     * Store the model path that the layer distribution was calculated for.
     * If the model changes, the distribution should be recalculated.
     */
    private val _lastDistributionModelPath = MutableStateFlow(prefs.getString("last_distribution_model_path", null))
    val lastDistributionModelPath = _lastDistributionModelPath.asStateFlow()
    
    fun setLastDistributionModelPath(path: String?) {
        prefs.edit().putString("last_distribution_model_path", path).apply()
        _lastDistributionModelPath.value = path
    }
    
    /**
     * Clear distribution memory (forces recalculation on next run)
     */
    fun clearLayerDistributionMemory() {
        setLastLayerDistribution(null)
        setLastDistributionModelPath(null)
    }

    // ========== Video Summary Settings ==========

    val videoSummarySettings = RemoteSummarySettingsGroup(
        keyPrefix = "video_summary",
        defaultSummaryPrompt = DEFAULT_TRANSCRIPT_SUMMARY_PROMPT,
        defaultMergePrompt = DEFAULT_TRANSCRIPT_MERGE_PROMPT,
        defaultLlamaServerUrl = PDF_LLAMA_SERVER_DEFAULT_URL
    )

    val videoSummaryBackend = videoSummarySettings.backend
    fun setVideoSummaryBackend(backend: String) = videoSummarySettings.setBackend(backend)

    val videoSummaryOllamaUrl = videoSummarySettings.ollamaUrl
    fun setVideoSummaryOllamaUrl(url: String) = videoSummarySettings.setOllamaUrl(url)

    val videoSummaryLlamaServerUrl = videoSummarySettings.llamaServerUrl
    fun setVideoSummaryLlamaServerUrl(url: String) = videoSummarySettings.setLlamaServerUrl(url)

    val videoSummaryLlamaSwapUrl = videoSummarySettings.llamaSwapUrl
    fun setVideoSummaryLlamaSwapUrl(url: String) = videoSummarySettings.setLlamaSwapUrl(url)

    val videoSummaryOllamaModel = videoSummarySettings.ollamaModel
    fun setVideoSummaryOllamaModel(model: String?) = videoSummarySettings.setOllamaModel(model)

    val videoSummaryLlamaSwapModel = videoSummarySettings.llamaSwapModel
    fun setVideoSummaryLlamaSwapModel(model: String?) = videoSummarySettings.setLlamaSwapModel(model)

    val videoSummaryLiteRtModelId = videoSummarySettings.liteRtModelId
    fun setVideoSummaryLiteRtModelId(modelId: Long?) = videoSummarySettings.setLiteRtModelId(modelId)

    val videoSummaryLiteRtBackend = videoSummarySettings.liteRtBackend
    fun setVideoSummaryLiteRtBackend(backend: String) = videoSummarySettings.setLiteRtBackend(backend)

    val videoSummaryLiteRtMtpEnabled = videoSummarySettings.liteRtMtpEnabled
    fun setVideoSummaryLiteRtMtpEnabled(enabled: Boolean) = videoSummarySettings.setLiteRtMtpEnabled(enabled)

    val videoSummaryThinkingEnabled = videoSummarySettings.thinkingEnabled
    fun setVideoSummaryThinkingEnabled(enabled: Boolean) = videoSummarySettings.setThinkingEnabled(enabled)

    val videoSummaryLlamaServerModelLabel = videoSummarySettings.llamaServerModelLabel
    fun setVideoSummaryLlamaServerModelLabel(label: String?) = videoSummarySettings.setLlamaServerModelLabel(label)

    val videoSummaryLlamaServerContextTokens = videoSummarySettings.llamaServerContextTokens
    fun setVideoSummaryLlamaServerContextTokens(tokens: Int?) = videoSummarySettings.setLlamaServerContextTokens(tokens)

    val videoSummaryLlamaServerContextLabel = videoSummarySettings.llamaServerContextLabel
    fun setVideoSummaryLlamaServerContextLabel(label: String?) = videoSummarySettings.setLlamaServerContextLabel(label)

    val videoSummaryChunkContext = videoSummarySettings.chunkContext
    fun setVideoSummaryChunkContext(size: Int) = videoSummarySettings.setChunkContext(size)

    val videoSummaryChunkMaxTokens = videoSummarySettings.chunkMaxTokens
    fun setVideoSummaryChunkMaxTokens(tokens: Int) = videoSummarySettings.setChunkMaxTokens(tokens)

    val videoSummaryMergeContext = videoSummarySettings.mergeContext
    fun setVideoSummaryMergeContext(size: Int) = videoSummarySettings.setMergeContext(size)

    val videoSummaryMergeMaxTokens = videoSummarySettings.mergeMaxTokens
    fun setVideoSummaryMergeMaxTokens(tokens: Int) = videoSummarySettings.setMergeMaxTokens(tokens)

    val videoSummaryTemperature = videoSummarySettings.temperature
    fun setVideoSummaryTemperature(temp: Float) = videoSummarySettings.setTemperature(temp)

    val videoSummaryTimeoutMinutes = videoSummarySettings.timeoutMinutes
    fun setVideoSummaryTimeoutMinutes(minutes: Int) = videoSummarySettings.setTimeoutMinutes(minutes)

    val videoSummaryTargetLanguage = videoSummarySettings.targetLanguage
    fun setVideoSummaryTargetLanguage(value: String) = videoSummarySettings.setTargetLanguage(value)

    val videoSummaryPrompt = videoSummarySettings.summaryPrompt
    fun setVideoSummaryPrompt(prompt: String?) = videoSummarySettings.setSummaryPrompt(prompt)

    val videoSummaryMergePrompt = videoSummarySettings.mergePrompt
    fun setVideoSummaryMergePrompt(prompt: String?) = videoSummarySettings.setMergePrompt(prompt)

    private val _videoSummaryWhisperThreads = MutableStateFlow(prefs.getInt("video_summary_whisper_threads", 4))
    val videoSummaryWhisperThreads = _videoSummaryWhisperThreads.asStateFlow()

    fun setVideoSummaryWhisperThreads(count: Int) {
        prefs.edit().putInt("video_summary_whisper_threads", count).apply()
        _videoSummaryWhisperThreads.value = count
    }

    private val _videoSummaryWhisperLanguage = MutableStateFlow(
        prefs.getString("video_summary_whisper_language", "auto") ?: "auto"
    )
    val videoSummaryWhisperLanguage = _videoSummaryWhisperLanguage.asStateFlow()

    fun setVideoSummaryWhisperLanguage(lang: String) {
        prefs.edit().putString("video_summary_whisper_language", lang).apply()
        _videoSummaryWhisperLanguage.value = lang
    }

    // ========== Workflow Settings (Transcribe+Summary) ==========
    
    private val _workflowWhisperModelPath = MutableStateFlow(prefs.getString("workflow_whisper_model", null))
    val workflowWhisperModelPath = _workflowWhisperModelPath.asStateFlow()
    
    fun setWorkflowWhisperModelPath(path: String?) {
        prefs.edit().putString("workflow_whisper_model", path).apply()
        _workflowWhisperModelPath.value = path
    }
    
    private val _workflowLlmModelPath = MutableStateFlow(prefs.getString("workflow_llm_model", null))
    val workflowLlmModelPath = _workflowLlmModelPath.asStateFlow()
    
    fun setWorkflowLlmModelPath(path: String?) {
        prefs.edit().putString("workflow_llm_model", path).apply()
        _workflowLlmModelPath.value = path
    }
    
    private val _workflowWhisperThreads = MutableStateFlow(prefs.getInt("workflow_whisper_threads", 4))
    val workflowWhisperThreads = _workflowWhisperThreads.asStateFlow()
    
    fun setWorkflowWhisperThreads(count: Int) {
        prefs.edit().putInt("workflow_whisper_threads", count).apply()
        _workflowWhisperThreads.value = count
    }
    
    private val _workflowLlmThreads = MutableStateFlow(prefs.getInt("workflow_llm_threads", 4))
    val workflowLlmThreads = _workflowLlmThreads.asStateFlow()
    
    fun setWorkflowLlmThreads(count: Int) {
        prefs.edit().putInt("workflow_llm_threads", count).apply()
        _workflowLlmThreads.value = count
    }
    
    private val _workflowWhisperLanguage = MutableStateFlow(prefs.getString("workflow_whisper_language", "auto") ?: "auto")
    val workflowWhisperLanguage = _workflowWhisperLanguage.asStateFlow()
    
    fun setWorkflowWhisperLanguage(lang: String) {
        prefs.edit().putString("workflow_whisper_language", lang).apply()
        _workflowWhisperLanguage.value = lang
    }

    val workflowSummarySettings = RemoteSummarySettingsGroup(
        keyPrefix = "workflow_summary",
        defaultSummaryPrompt = DEFAULT_TRANSCRIPT_SUMMARY_PROMPT,
        defaultMergePrompt = DEFAULT_TRANSCRIPT_MERGE_PROMPT,
        defaultLlamaServerUrl = PDF_LLAMA_SERVER_DEFAULT_URL,
        fallbackChunkContextKey = "workflow_context",
        fallbackChunkMaxTokensKey = "workflow_max_tokens",
        fallbackTemperatureKey = "workflow_temperature"
    )

    val workflowSummaryBackend = workflowSummarySettings.backend
    fun setWorkflowSummaryBackend(backend: String) = workflowSummarySettings.setBackend(backend)

    val workflowSummaryOllamaUrl = workflowSummarySettings.ollamaUrl
    fun setWorkflowSummaryOllamaUrl(url: String) = workflowSummarySettings.setOllamaUrl(url)

    val workflowSummaryLlamaServerUrl = workflowSummarySettings.llamaServerUrl
    fun setWorkflowSummaryLlamaServerUrl(url: String) = workflowSummarySettings.setLlamaServerUrl(url)

    val workflowSummaryLlamaSwapUrl = workflowSummarySettings.llamaSwapUrl
    fun setWorkflowSummaryLlamaSwapUrl(url: String) = workflowSummarySettings.setLlamaSwapUrl(url)

    val workflowSummaryOllamaModel = workflowSummarySettings.ollamaModel
    fun setWorkflowSummaryOllamaModel(model: String?) = workflowSummarySettings.setOllamaModel(model)

    val workflowSummaryLlamaSwapModel = workflowSummarySettings.llamaSwapModel
    fun setWorkflowSummaryLlamaSwapModel(model: String?) = workflowSummarySettings.setLlamaSwapModel(model)

    val workflowSummaryLiteRtModelId = workflowSummarySettings.liteRtModelId
    fun setWorkflowSummaryLiteRtModelId(modelId: Long?) = workflowSummarySettings.setLiteRtModelId(modelId)

    val workflowSummaryLiteRtBackend = workflowSummarySettings.liteRtBackend
    fun setWorkflowSummaryLiteRtBackend(backend: String) = workflowSummarySettings.setLiteRtBackend(backend)

    val workflowSummaryLiteRtMtpEnabled = workflowSummarySettings.liteRtMtpEnabled
    fun setWorkflowSummaryLiteRtMtpEnabled(enabled: Boolean) = workflowSummarySettings.setLiteRtMtpEnabled(enabled)

    val workflowSummaryThinkingEnabled = workflowSummarySettings.thinkingEnabled
    fun setWorkflowSummaryThinkingEnabled(enabled: Boolean) = workflowSummarySettings.setThinkingEnabled(enabled)

    val workflowSummaryLlamaServerModelLabel = workflowSummarySettings.llamaServerModelLabel
    fun setWorkflowSummaryLlamaServerModelLabel(label: String?) = workflowSummarySettings.setLlamaServerModelLabel(label)

    val workflowSummaryLlamaServerContextTokens = workflowSummarySettings.llamaServerContextTokens
    fun setWorkflowSummaryLlamaServerContextTokens(tokens: Int?) = workflowSummarySettings.setLlamaServerContextTokens(tokens)

    val workflowSummaryLlamaServerContextLabel = workflowSummarySettings.llamaServerContextLabel
    fun setWorkflowSummaryLlamaServerContextLabel(label: String?) = workflowSummarySettings.setLlamaServerContextLabel(label)

    val workflowContext = workflowSummarySettings.chunkContext
    fun setWorkflowContext(size: Int) = workflowSummarySettings.setChunkContext(size)

    val workflowTemperature = workflowSummarySettings.temperature
    fun setWorkflowTemperature(temp: Float) = workflowSummarySettings.setTemperature(temp)

    val workflowMaxTokens = workflowSummarySettings.chunkMaxTokens
    fun setWorkflowMaxTokens(tokens: Int) = workflowSummarySettings.setChunkMaxTokens(tokens)

    val workflowMergeContext = workflowSummarySettings.mergeContext
    fun setWorkflowMergeContext(size: Int) = workflowSummarySettings.setMergeContext(size)

    val workflowMergeMaxTokens = workflowSummarySettings.mergeMaxTokens
    fun setWorkflowMergeMaxTokens(tokens: Int) = workflowSummarySettings.setMergeMaxTokens(tokens)

    val workflowSummaryTimeoutMinutes = workflowSummarySettings.timeoutMinutes
    fun setWorkflowSummaryTimeoutMinutes(minutes: Int) = workflowSummarySettings.setTimeoutMinutes(minutes)

    val workflowSummaryTargetLanguage = workflowSummarySettings.targetLanguage
    fun setWorkflowSummaryTargetLanguage(value: String) = workflowSummarySettings.setTargetLanguage(value)

    val workflowSummaryPrompt = workflowSummarySettings.summaryPrompt
    fun setWorkflowSummaryPrompt(prompt: String?) = workflowSummarySettings.setSummaryPrompt(prompt)

    val workflowSummaryMergePrompt = workflowSummarySettings.mergePrompt
    fun setWorkflowSummaryMergePrompt(prompt: String?) = workflowSummarySettings.setMergePrompt(prompt)
    
    // ========== Low Memory Mode (disable mmap for large models) ==========
    
    /**
     * When enabled, passes --no-mmap to llama.cpp, loading the entire model into RAM.
     * This is SLOWER to start but can help on devices with limited virtual memory.
     * Most users should keep this OFF since mmap allows running larger models by paging.
     */
    private val _lowMemoryMode = MutableStateFlow(prefs.getBoolean("low_memory_mode", false))
    val lowMemoryMode = _lowMemoryMode.asStateFlow()
    
    fun setLowMemoryMode(enabled: Boolean) {
        prefs.edit().putBoolean("low_memory_mode", enabled).apply()
        _lowMemoryMode.value = enabled
    }
    
    // ========== Termux Tool Network Visibility Settings ==========
    // When enabled (true), tools bind to 0.0.0.0 (accessible from network)
    // When disabled (false/default), tools bind to 127.0.0.1 (localhost only)
    
    private val _ollamaNetworkVisible = MutableStateFlow(prefs.getBoolean("ollama_network_visible", false))
    val ollamaNetworkVisible = _ollamaNetworkVisible.asStateFlow()
    fun setOllamaNetworkVisible(enabled: Boolean) {
        prefs.edit().putBoolean("ollama_network_visible", enabled).apply()
        _ollamaNetworkVisible.value = enabled
    }
    
    private val _openWebUINetworkVisible = MutableStateFlow(prefs.getBoolean("open_webui_network_visible", false))
    val openWebUINetworkVisible = _openWebUINetworkVisible.asStateFlow()
    fun setOpenWebUINetworkVisible(enabled: Boolean) {
        prefs.edit().putBoolean("open_webui_network_visible", enabled).apply()
        _openWebUINetworkVisible.value = enabled
    }
    
    private val _bigAGINetworkVisible = MutableStateFlow(prefs.getBoolean("big_agi_network_visible", false))
    val bigAGINetworkVisible = _bigAGINetworkVisible.asStateFlow()
    fun setBigAGINetworkVisible(enabled: Boolean) {
        prefs.edit().putBoolean("big_agi_network_visible", enabled).apply()
        _bigAGINetworkVisible.value = enabled
    }
    
    private val _oobaboogaNetworkVisible = MutableStateFlow(prefs.getBoolean("oobabooga_network_visible", false))
    val oobaboogaNetworkVisible = _oobaboogaNetworkVisible.asStateFlow()
    fun setOobaboogaNetworkVisible(enabled: Boolean) {
        prefs.edit().putBoolean("oobabooga_network_visible", enabled).apply()
        _oobaboogaNetworkVisible.value = enabled
    }
    
    private val _fastsdcpuNetworkVisible = MutableStateFlow(prefs.getBoolean("fastsdcpu_network_visible", false))
    val fastsdcpuNetworkVisible = _fastsdcpuNetworkVisible.asStateFlow()
    fun setFastsdcpuNetworkVisible(enabled: Boolean) {
        prefs.edit().putBoolean("fastsdcpu_network_visible", enabled).apply()
        _fastsdcpuNetworkVisible.value = enabled
    }

    private val _fastsdcpuMcpNetworkVisible = MutableStateFlow(prefs.getBoolean("fastsdcpu_mcp_network_visible", false))
    val fastsdcpuMcpNetworkVisible = _fastsdcpuMcpNetworkVisible.asStateFlow()
    fun setFastsdcpuMcpNetworkVisible(enabled: Boolean) {
        prefs.edit().putBoolean("fastsdcpu_mcp_network_visible", enabled).apply()
        _fastsdcpuMcpNetworkVisible.value = enabled
    }
    
    private val _a1111NetworkVisible = MutableStateFlow(prefs.getBoolean("a1111_network_visible", false))
    val a1111NetworkVisible = _a1111NetworkVisible.asStateFlow()
    fun setA1111NetworkVisible(enabled: Boolean) {
        prefs.edit().putBoolean("a1111_network_visible", enabled).apply()
        _a1111NetworkVisible.value = enabled
    }
    
    /**
     * Get network visibility for a tool by ID
     */
    fun getToolNetworkVisible(toolId: String): Boolean {
        return when (toolId) {
            "ollama" -> _ollamaNetworkVisible.value
            "open_webui" -> _openWebUINetworkVisible.value
            "big_agi" -> _bigAGINetworkVisible.value
            "oobabooga" -> _oobaboogaNetworkVisible.value
            "fastsdcpu" -> _fastsdcpuNetworkVisible.value
            "fastsdcpu_mcp" -> _fastsdcpuMcpNetworkVisible.value
            "a1111" -> _a1111NetworkVisible.value
            else -> false
        }
    }
    
    /**
     * Set network visibility for a tool by ID
     */
    fun setToolNetworkVisible(toolId: String, enabled: Boolean) {
        when (toolId) {
            "ollama" -> setOllamaNetworkVisible(enabled)
            "open_webui" -> setOpenWebUINetworkVisible(enabled)
            "big_agi" -> setBigAGINetworkVisible(enabled)
            "oobabooga" -> setOobaboogaNetworkVisible(enabled)
            "fastsdcpu" -> setFastsdcpuNetworkVisible(enabled)
            "fastsdcpu_mcp" -> setFastsdcpuMcpNetworkVisible(enabled)
            "a1111" -> setA1111NetworkVisible(enabled)
        }
    }
    
    // ========== AI Agent Per-Role Model Settings ==========
    
    // Orchestrator agent model (main agent that delegates)
    private val _agentOrchestratorModel = MutableStateFlow(prefs.getString("agent_orchestrator_model", "qwen3.5:9b") ?: "qwen3.5:9b")
    val agentOrchestratorModel = _agentOrchestratorModel.asStateFlow()
    fun setAgentOrchestratorModel(model: String) {
        prefs.edit().putString("agent_orchestrator_model", model).apply()
        _agentOrchestratorModel.value = model
    }
    
    // Coder agent model (writes/edits code)
    private val _agentCoderModel = MutableStateFlow(prefs.getString("agent_coder_model", "qwen3.5:9b") ?: "qwen3.5:9b")
    val agentCoderModel = _agentCoderModel.asStateFlow()
    fun setAgentCoderModel(model: String) {
        prefs.edit().putString("agent_coder_model", model).apply()
        _agentCoderModel.value = model
    }
    
    // Reviewer agent model (reviews code)
    private val _agentReviewerModel = MutableStateFlow(prefs.getString("agent_reviewer_model", "qwen3.5:9b") ?: "qwen3.5:9b")
    val agentReviewerModel = _agentReviewerModel.asStateFlow()
    fun setAgentReviewerModel(model: String) {
        prefs.edit().putString("agent_reviewer_model", model).apply()
        _agentReviewerModel.value = model
    }
    
    // Executor agent model (runs commands)
    private val _agentExecutorModel = MutableStateFlow(prefs.getString("agent_executor_model", "qwen3.5:9b") ?: "qwen3.5:9b")
    val agentExecutorModel = _agentExecutorModel.asStateFlow()
    fun setAgentExecutorModel(model: String) {
        prefs.edit().putString("agent_executor_model", model).apply()
        _agentExecutorModel.value = model
    }
    
    // Summarizer agent model
    private val _agentSummarizerModel = MutableStateFlow(prefs.getString("agent_summarizer_model", "qwen3.5:9b") ?: "qwen3.5:9b")
    val agentSummarizerModel = _agentSummarizerModel.asStateFlow()
    fun setAgentSummarizerModel(model: String) {
        prefs.edit().putString("agent_summarizer_model", model).apply()
        _agentSummarizerModel.value = model
    }

    // Visual tester agent model (observes/interacts with local WebUI previews only)
    private val _agentVisualTesterModel = MutableStateFlow(prefs.getString("agent_visual_tester_model", "qwen3.5:9b") ?: "qwen3.5:9b")
    val agentVisualTesterModel = _agentVisualTesterModel.asStateFlow()
    fun setAgentVisualTesterModel(model: String) {
        prefs.edit().putString("agent_visual_tester_model", model).apply()
        _agentVisualTesterModel.value = model
    }

    private val _agentVisualTestingEnabled = MutableStateFlow(prefs.getBoolean("agent_visual_testing_enabled", true))
    val agentVisualTestingEnabled = _agentVisualTestingEnabled.asStateFlow()
    fun setAgentVisualTestingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("agent_visual_testing_enabled", enabled).apply()
        _agentVisualTestingEnabled.value = enabled
    }

    // Summarizer Thinking
    private val _agentSummarizerThinkingEnabled = MutableStateFlow(prefs.getBoolean("agent_summarizer_thinking_enabled", true))
    val agentSummarizerThinkingEnabled = _agentSummarizerThinkingEnabled.asStateFlow()
    fun setAgentSummarizerThinkingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("agent_summarizer_thinking_enabled", enabled).apply()
        _agentSummarizerThinkingEnabled.value = enabled
    }

    // Per-agent vision toggles
    private val _agentOrchestratorVisionEnabled = MutableStateFlow(prefs.getBoolean("agent_orchestrator_vision_enabled", true))
    val agentOrchestratorVisionEnabled = _agentOrchestratorVisionEnabled.asStateFlow()
    fun setAgentOrchestratorVisionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("agent_orchestrator_vision_enabled", enabled).apply()
        _agentOrchestratorVisionEnabled.value = enabled
    }

    private val _agentCoderVisionEnabled = MutableStateFlow(prefs.getBoolean("agent_coder_vision_enabled", false))
    val agentCoderVisionEnabled = _agentCoderVisionEnabled.asStateFlow()
    fun setAgentCoderVisionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("agent_coder_vision_enabled", enabled).apply()
        _agentCoderVisionEnabled.value = enabled
    }

    private val _agentReviewerVisionEnabled = MutableStateFlow(prefs.getBoolean("agent_reviewer_vision_enabled", false))
    val agentReviewerVisionEnabled = _agentReviewerVisionEnabled.asStateFlow()
    fun setAgentReviewerVisionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("agent_reviewer_vision_enabled", enabled).apply()
        _agentReviewerVisionEnabled.value = enabled
    }

    private val _agentExecutorVisionEnabled = MutableStateFlow(prefs.getBoolean("agent_executor_vision_enabled", false))
    val agentExecutorVisionEnabled = _agentExecutorVisionEnabled.asStateFlow()
    fun setAgentExecutorVisionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("agent_executor_vision_enabled", enabled).apply()
        _agentExecutorVisionEnabled.value = enabled
    }

    private val _agentSummarizerVisionEnabled = MutableStateFlow(prefs.getBoolean("agent_summarizer_vision_enabled", false))
    val agentSummarizerVisionEnabled = _agentSummarizerVisionEnabled.asStateFlow()
    fun setAgentSummarizerVisionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("agent_summarizer_vision_enabled", enabled).apply()
        _agentSummarizerVisionEnabled.value = enabled
    }

    private val _agentVisualTesterVisionEnabled = MutableStateFlow(prefs.getBoolean("agent_visual_tester_vision_enabled", true))
    val agentVisualTesterVisionEnabled = _agentVisualTesterVisionEnabled.asStateFlow()
    fun setAgentVisualTesterVisionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("agent_visual_tester_vision_enabled", enabled).apply()
        _agentVisualTesterVisionEnabled.value = enabled
    }

    // Shared agent image-generation tool settings
    private val _agentImageGenerationToolEnabled = MutableStateFlow(prefs.getBoolean("agent_image_generation_tool_enabled", true))
    val agentImageGenerationToolEnabled = _agentImageGenerationToolEnabled.asStateFlow()
    fun setAgentImageGenerationToolEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("agent_image_generation_tool_enabled", enabled).apply()
        _agentImageGenerationToolEnabled.value = enabled
    }

    private val _agentImageGenerationEngine = MutableStateFlow(
        prefs.getString("agent_image_generation_engine", "ONNX") ?: "ONNX"
    )
    val agentImageGenerationEngine = _agentImageGenerationEngine.asStateFlow()
    fun setAgentImageGenerationEngine(engine: String) {
        val normalized = when (engine.trim().uppercase(Locale.US)) {
            "SD" -> "SD"
            else -> "ONNX"
        }
        prefs.edit().putString("agent_image_generation_engine", normalized).apply()
        _agentImageGenerationEngine.value = normalized
    }

    private val _agentImageGenerationModel = MutableStateFlow(prefs.getString("agent_image_generation_model", null))
    val agentImageGenerationModel = _agentImageGenerationModel.asStateFlow()
    fun setAgentImageGenerationModel(model: String?) {
        prefs.edit().putString("agent_image_generation_model", model).apply()
        _agentImageGenerationModel.value = model
    }

    private val _agentImageGenerationSteps = MutableStateFlow(prefs.getInt("agent_image_generation_steps", 20))
    val agentImageGenerationSteps = _agentImageGenerationSteps.asStateFlow()
    fun setAgentImageGenerationSteps(steps: Int) {
        val normalized = steps.coerceAtLeast(1)
        prefs.edit().putInt("agent_image_generation_steps", normalized).apply()
        _agentImageGenerationSteps.value = normalized
    }

    private val _agentImageGenerationCfg = MutableStateFlow(prefs.getFloat("agent_image_generation_cfg", 6.5f))
    val agentImageGenerationCfg = _agentImageGenerationCfg.asStateFlow()
    fun setAgentImageGenerationCfg(cfg: Float) {
        val normalized = cfg.coerceAtLeast(0.1f)
        prefs.edit().putFloat("agent_image_generation_cfg", normalized).apply()
        _agentImageGenerationCfg.value = normalized
    }

    private val _agentImageGenerationResolution = MutableStateFlow(
        prefs.getString("agent_image_generation_resolution", "512x512") ?: "512x512"
    )
    val agentImageGenerationResolution = _agentImageGenerationResolution.asStateFlow()
    fun setAgentImageGenerationResolution(resolution: String) {
        val normalized = resolution.ifBlank { "512x512" }
        prefs.edit().putString("agent_image_generation_resolution", normalized).apply()
        _agentImageGenerationResolution.value = normalized
    }

    private val _agentSdImageGenerationModel = MutableStateFlow(prefs.getString("agent_sd_image_generation_model", null))
    val agentSdImageGenerationModel = _agentSdImageGenerationModel.asStateFlow()
    fun setAgentSdImageGenerationModel(model: String?) {
        prefs.edit().putString("agent_sd_image_generation_model", model?.takeIf { it.isNotBlank() }).apply()
        _agentSdImageGenerationModel.value = model?.takeIf { it.isNotBlank() }
    }

    private val _agentSdImageGenerationVae = MutableStateFlow(prefs.getString("agent_sd_image_generation_vae", null))
    val agentSdImageGenerationVae = _agentSdImageGenerationVae.asStateFlow()
    fun setAgentSdImageGenerationVae(model: String?) {
        prefs.edit().putString("agent_sd_image_generation_vae", model?.takeIf { it.isNotBlank() }).apply()
        _agentSdImageGenerationVae.value = model?.takeIf { it.isNotBlank() }
    }

    private val _agentSdImageGenerationTae = MutableStateFlow(prefs.getString("agent_sd_image_generation_tae", null))
    val agentSdImageGenerationTae = _agentSdImageGenerationTae.asStateFlow()
    fun setAgentSdImageGenerationTae(model: String?) {
        prefs.edit().putString("agent_sd_image_generation_tae", model?.takeIf { it.isNotBlank() }).apply()
        _agentSdImageGenerationTae.value = model?.takeIf { it.isNotBlank() }
    }

    private val _agentSdImageGenerationClipL = MutableStateFlow(prefs.getString("agent_sd_image_generation_clip_l", null))
    val agentSdImageGenerationClipL = _agentSdImageGenerationClipL.asStateFlow()
    fun setAgentSdImageGenerationClipL(model: String?) {
        prefs.edit().putString("agent_sd_image_generation_clip_l", model?.takeIf { it.isNotBlank() }).apply()
        _agentSdImageGenerationClipL.value = model?.takeIf { it.isNotBlank() }
    }

    private val _agentSdImageGenerationClipG = MutableStateFlow(prefs.getString("agent_sd_image_generation_clip_g", null))
    val agentSdImageGenerationClipG = _agentSdImageGenerationClipG.asStateFlow()
    fun setAgentSdImageGenerationClipG(model: String?) {
        prefs.edit().putString("agent_sd_image_generation_clip_g", model?.takeIf { it.isNotBlank() }).apply()
        _agentSdImageGenerationClipG.value = model?.takeIf { it.isNotBlank() }
    }

    private val _agentSdImageGenerationT5xxl = MutableStateFlow(prefs.getString("agent_sd_image_generation_t5xxl", null))
    val agentSdImageGenerationT5xxl = _agentSdImageGenerationT5xxl.asStateFlow()
    fun setAgentSdImageGenerationT5xxl(model: String?) {
        prefs.edit().putString("agent_sd_image_generation_t5xxl", model?.takeIf { it.isNotBlank() }).apply()
        _agentSdImageGenerationT5xxl.value = model?.takeIf { it.isNotBlank() }
    }

    private val _agentSdImageGenerationLlm = MutableStateFlow(prefs.getString("agent_sd_image_generation_llm", null))
    val agentSdImageGenerationLlm = _agentSdImageGenerationLlm.asStateFlow()
    fun setAgentSdImageGenerationLlm(model: String?) {
        prefs.edit().putString("agent_sd_image_generation_llm", model?.takeIf { it.isNotBlank() }).apply()
        _agentSdImageGenerationLlm.value = model?.takeIf { it.isNotBlank() }
    }

    private val _agentSdImageGenerationLlmVision = MutableStateFlow(prefs.getString("agent_sd_image_generation_llm_vision", null))
    val agentSdImageGenerationLlmVision = _agentSdImageGenerationLlmVision.asStateFlow()
    fun setAgentSdImageGenerationLlmVision(model: String?) {
        prefs.edit().putString("agent_sd_image_generation_llm_vision", model?.takeIf { it.isNotBlank() }).apply()
        _agentSdImageGenerationLlmVision.value = model?.takeIf { it.isNotBlank() }
    }

    private val _agentSdImageGenerationPhotoMaker = MutableStateFlow(prefs.getString("agent_sd_image_generation_photomaker", null))
    val agentSdImageGenerationPhotoMaker = _agentSdImageGenerationPhotoMaker.asStateFlow()
    fun setAgentSdImageGenerationPhotoMaker(model: String?) {
        prefs.edit().putString("agent_sd_image_generation_photomaker", model?.takeIf { it.isNotBlank() }).apply()
        _agentSdImageGenerationPhotoMaker.value = model?.takeIf { it.isNotBlank() }
    }

    private val _agentSdImageGenerationWidth = MutableStateFlow(prefs.getInt("agent_sd_image_generation_width", 512))
    val agentSdImageGenerationWidth = _agentSdImageGenerationWidth.asStateFlow()
    fun setAgentSdImageGenerationWidth(width: Int) {
        val normalized = width.coerceIn(256, 1024)
        prefs.edit().putInt("agent_sd_image_generation_width", normalized).apply()
        _agentSdImageGenerationWidth.value = normalized
    }

    private val _agentSdImageGenerationHeight = MutableStateFlow(prefs.getInt("agent_sd_image_generation_height", 512))
    val agentSdImageGenerationHeight = _agentSdImageGenerationHeight.asStateFlow()
    fun setAgentSdImageGenerationHeight(height: Int) {
        val normalized = height.coerceIn(256, 1024)
        prefs.edit().putInt("agent_sd_image_generation_height", normalized).apply()
        _agentSdImageGenerationHeight.value = normalized
    }

    private val _agentSdImageGenerationSteps = MutableStateFlow(prefs.getInt("agent_sd_image_generation_steps", 20))
    val agentSdImageGenerationSteps = _agentSdImageGenerationSteps.asStateFlow()
    fun setAgentSdImageGenerationSteps(steps: Int) {
        val normalized = steps.coerceIn(1, 50)
        prefs.edit().putInt("agent_sd_image_generation_steps", normalized).apply()
        _agentSdImageGenerationSteps.value = normalized
    }

    private val _agentSdImageGenerationCfg = MutableStateFlow(prefs.getFloat("agent_sd_image_generation_cfg", 7.0f))
    val agentSdImageGenerationCfg = _agentSdImageGenerationCfg.asStateFlow()
    fun setAgentSdImageGenerationCfg(cfg: Float) {
        val normalized = cfg.coerceIn(1f, 20f)
        prefs.edit().putFloat("agent_sd_image_generation_cfg", normalized).apply()
        _agentSdImageGenerationCfg.value = normalized
    }

    private val _agentSdImageGenerationSampler = MutableStateFlow(
        prefs.getString("agent_sd_image_generation_sampler", "EULER_A") ?: "EULER_A"
    )
    val agentSdImageGenerationSampler = _agentSdImageGenerationSampler.asStateFlow()
    fun setAgentSdImageGenerationSampler(sampler: String) {
        val normalized = sampler.trim().ifBlank { "EULER_A" }
        prefs.edit().putString("agent_sd_image_generation_sampler", normalized).apply()
        _agentSdImageGenerationSampler.value = normalized
    }

    private val _agentSdImageGenerationSeed = MutableStateFlow(
        prefs.getString("agent_sd_image_generation_seed", "") ?: ""
    )
    val agentSdImageGenerationSeed = _agentSdImageGenerationSeed.asStateFlow()
    fun setAgentSdImageGenerationSeed(seed: String) {
        val normalized = seed.trim()
        prefs.edit().putString("agent_sd_image_generation_seed", normalized).apply()
        _agentSdImageGenerationSeed.value = normalized
    }

    private val _agentSdImageGenerationNegativePrompt = MutableStateFlow(
        prefs.getString("agent_sd_image_generation_negative_prompt", "") ?: ""
    )
    val agentSdImageGenerationNegativePrompt = _agentSdImageGenerationNegativePrompt.asStateFlow()
    fun setAgentSdImageGenerationNegativePrompt(prompt: String) {
        prefs.edit().putString("agent_sd_image_generation_negative_prompt", prompt).apply()
        _agentSdImageGenerationNegativePrompt.value = prompt
    }

    private val _agentSdImageGenerationThreads = MutableStateFlow(prefs.getInt("agent_sd_image_generation_threads", -1))
    val agentSdImageGenerationThreads = _agentSdImageGenerationThreads.asStateFlow()
    fun setAgentSdImageGenerationThreads(threads: Int) {
        val normalized = threads.coerceIn(-1, 16)
        prefs.edit().putInt("agent_sd_image_generation_threads", normalized).apply()
        _agentSdImageGenerationThreads.value = normalized
    }

    private val _agentSdImageGenerationFlowShift = MutableStateFlow(
        prefs.getString("agent_sd_image_generation_flow_shift", "") ?: ""
    )
    val agentSdImageGenerationFlowShift = _agentSdImageGenerationFlowShift.asStateFlow()
    fun setAgentSdImageGenerationFlowShift(value: String) {
        prefs.edit().putString("agent_sd_image_generation_flow_shift", value.trim()).apply()
        _agentSdImageGenerationFlowShift.value = value.trim()
    }

    private val _agentSdImageGenerationDiffusionFa = MutableStateFlow(
        prefs.getBoolean("agent_sd_image_generation_diffusion_fa", false)
    )
    val agentSdImageGenerationDiffusionFa = _agentSdImageGenerationDiffusionFa.asStateFlow()
    fun setAgentSdImageGenerationDiffusionFa(enabled: Boolean) {
        prefs.edit().putBoolean("agent_sd_image_generation_diffusion_fa", enabled).apply()
        _agentSdImageGenerationDiffusionFa.value = enabled
    }

    private val _agentSdImageGenerationMmap = MutableStateFlow(
        prefs.getBoolean("agent_sd_image_generation_mmap", false)
    )
    val agentSdImageGenerationMmap = _agentSdImageGenerationMmap.asStateFlow()
    fun setAgentSdImageGenerationMmap(enabled: Boolean) {
        prefs.edit().putBoolean("agent_sd_image_generation_mmap", enabled).apply()
        _agentSdImageGenerationMmap.value = enabled
    }

    private val _agentSdImageGenerationVaeConvDirect = MutableStateFlow(
        prefs.getBoolean("agent_sd_image_generation_vae_conv_direct", false)
    )
    val agentSdImageGenerationVaeConvDirect = _agentSdImageGenerationVaeConvDirect.asStateFlow()
    fun setAgentSdImageGenerationVaeConvDirect(enabled: Boolean) {
        prefs.edit().putBoolean("agent_sd_image_generation_vae_conv_direct", enabled).apply()
        _agentSdImageGenerationVaeConvDirect.value = enabled
    }

    private val _agentSdImageGenerationQwenZeroCondT = MutableStateFlow(
        prefs.getBoolean("agent_sd_image_generation_qwen_zero_cond_t", false)
    )
    val agentSdImageGenerationQwenZeroCondT = _agentSdImageGenerationQwenZeroCondT.asStateFlow()
    fun setAgentSdImageGenerationQwenZeroCondT(enabled: Boolean) {
        prefs.edit().putBoolean("agent_sd_image_generation_qwen_zero_cond_t", enabled).apply()
        _agentSdImageGenerationQwenZeroCondT.value = enabled
    }

    private val _agentSdImageGenerationChromaDisableDitMask = MutableStateFlow(
        prefs.getBoolean("agent_sd_image_generation_chroma_disable_dit_mask", false)
    )
    val agentSdImageGenerationChromaDisableDitMask = _agentSdImageGenerationChromaDisableDitMask.asStateFlow()
    fun setAgentSdImageGenerationChromaDisableDitMask(enabled: Boolean) {
        prefs.edit().putBoolean("agent_sd_image_generation_chroma_disable_dit_mask", enabled).apply()
        _agentSdImageGenerationChromaDisableDitMask.value = enabled
    }

    // Shared agent background-removal tool settings
    private val _agentBackgroundRemovalToolEnabled = MutableStateFlow(prefs.getBoolean("agent_background_removal_tool_enabled", true))
    val agentBackgroundRemovalToolEnabled = _agentBackgroundRemovalToolEnabled.asStateFlow()
    fun setAgentBackgroundRemovalToolEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("agent_background_removal_tool_enabled", enabled).apply()
        _agentBackgroundRemovalToolEnabled.value = enabled
    }

    private val _agentBackgroundRemovalModel = MutableStateFlow(prefs.getString("agent_background_removal_model", null))
    val agentBackgroundRemovalModel = _agentBackgroundRemovalModel.asStateFlow()
    fun setAgentBackgroundRemovalModel(model: String?) {
        prefs.edit().putString("agent_background_removal_model", model?.takeIf { it.isNotBlank() }).apply()
        _agentBackgroundRemovalModel.value = model?.takeIf { it.isNotBlank() }
    }

    private val _agentBackgroundRemovalBackend = MutableStateFlow(
        prefs.getString("agent_background_removal_backend", "CPU") ?: "CPU"
    )
    val agentBackgroundRemovalBackend = _agentBackgroundRemovalBackend.asStateFlow()
    fun setAgentBackgroundRemovalBackend(backend: String) {
        val normalized = backend.ifBlank { "CPU" }
        prefs.edit().putString("agent_background_removal_backend", normalized).apply()
        _agentBackgroundRemovalBackend.value = normalized
    }

    private val _agentBackgroundRemovalRuntimeThreads = MutableStateFlow(
        prefs.getInt("agent_background_removal_runtime_threads", 0)
    )
    val agentBackgroundRemovalRuntimeThreads = _agentBackgroundRemovalRuntimeThreads.asStateFlow()
    fun setAgentBackgroundRemovalRuntimeThreads(value: Int) {
        val normalized = value.coerceIn(0, 16)
        prefs.edit().putInt("agent_background_removal_runtime_threads", normalized).apply()
        _agentBackgroundRemovalRuntimeThreads.value = normalized
    }

    private val _agentBackgroundRemovalGraphOptimization = MutableStateFlow(
        prefs.getString("agent_background_removal_graph_optimization", "ALL") ?: "ALL"
    )
    val agentBackgroundRemovalGraphOptimization = _agentBackgroundRemovalGraphOptimization.asStateFlow()
    fun setAgentBackgroundRemovalGraphOptimization(value: String) {
        val normalized = value.ifBlank { "ALL" }
        prefs.edit().putString("agent_background_removal_graph_optimization", normalized).apply()
        _agentBackgroundRemovalGraphOptimization.value = normalized
    }

    private val _agentBackgroundRemovalResizeBeforeProcessing = MutableStateFlow(
        prefs.getBoolean("agent_background_removal_resize_before_processing", true)
    )
    val agentBackgroundRemovalResizeBeforeProcessing = _agentBackgroundRemovalResizeBeforeProcessing.asStateFlow()
    fun setAgentBackgroundRemovalResizeBeforeProcessing(enabled: Boolean) {
        prefs.edit().putBoolean("agent_background_removal_resize_before_processing", enabled).apply()
        _agentBackgroundRemovalResizeBeforeProcessing.value = enabled
    }

    private val _agentBackgroundRemovalResizeMaxEdge = MutableStateFlow(
        prefs.getInt("agent_background_removal_resize_max_edge", 512)
    )
    val agentBackgroundRemovalResizeMaxEdge = _agentBackgroundRemovalResizeMaxEdge.asStateFlow()
    fun setAgentBackgroundRemovalResizeMaxEdge(value: Int) {
        val normalized = value.coerceIn(128, 2048)
        prefs.edit().putInt("agent_background_removal_resize_max_edge", normalized).apply()
        _agentBackgroundRemovalResizeMaxEdge.value = normalized
    }

    private val _agentBackgroundRemovalAlphaThreshold = MutableStateFlow(
        prefs.getFloat("agent_background_removal_alpha_threshold", 0.5f)
    )
    val agentBackgroundRemovalAlphaThreshold = _agentBackgroundRemovalAlphaThreshold.asStateFlow()
    fun setAgentBackgroundRemovalAlphaThreshold(value: Float) {
        val normalized = value.coerceIn(0f, 1f)
        prefs.edit().putFloat("agent_background_removal_alpha_threshold", normalized).apply()
        _agentBackgroundRemovalAlphaThreshold.value = normalized
    }

    private val _agentBackgroundRemovalFeatherRadius = MutableStateFlow(
        prefs.getInt("agent_background_removal_feather_radius", 1)
    )
    val agentBackgroundRemovalFeatherRadius = _agentBackgroundRemovalFeatherRadius.asStateFlow()
    fun setAgentBackgroundRemovalFeatherRadius(value: Int) {
        val normalized = value.coerceIn(0, 16)
        prefs.edit().putInt("agent_background_removal_feather_radius", normalized).apply()
        _agentBackgroundRemovalFeatherRadius.value = normalized
    }

    private val _agentBackgroundRemovalMaskSoftness = MutableStateFlow(
        prefs.getFloat("agent_background_removal_mask_softness", 1f)
    )
    val agentBackgroundRemovalMaskSoftness = _agentBackgroundRemovalMaskSoftness.asStateFlow()
    fun setAgentBackgroundRemovalMaskSoftness(value: Float) {
        val normalized = value.coerceIn(0f, 1f)
        prefs.edit().putFloat("agent_background_removal_mask_softness", normalized).apply()
        _agentBackgroundRemovalMaskSoftness.value = normalized
    }

    private val _agentBackgroundRemovalMaskContrast = MutableStateFlow(
        prefs.getFloat("agent_background_removal_mask_contrast", 1f)
    )
    val agentBackgroundRemovalMaskContrast = _agentBackgroundRemovalMaskContrast.asStateFlow()
    fun setAgentBackgroundRemovalMaskContrast(value: Float) {
        val normalized = value.coerceIn(0.25f, 4f)
        prefs.edit().putFloat("agent_background_removal_mask_contrast", normalized).apply()
        _agentBackgroundRemovalMaskContrast.value = normalized
    }

    private val _agentBackgroundRemovalExportMask = MutableStateFlow(
        prefs.getBoolean("agent_background_removal_export_mask", false)
    )
    val agentBackgroundRemovalExportMask = _agentBackgroundRemovalExportMask.asStateFlow()
    fun setAgentBackgroundRemovalExportMask(enabled: Boolean) {
        prefs.edit().putBoolean("agent_background_removal_export_mask", enabled).apply()
        _agentBackgroundRemovalExportMask.value = enabled
    }

    // Native chat image-generation tool settings. These intentionally do not reuse
    // the AI Agent image-generation preset so chat experiments can stay isolated.
    private val _nativeChatImageGenerationModel = MutableStateFlow(prefs.getString("native_chat_image_generation_model", null))
    val nativeChatImageGenerationModel = _nativeChatImageGenerationModel.asStateFlow()
    fun setNativeChatImageGenerationModel(model: String?) {
        prefs.edit().putString("native_chat_image_generation_model", model?.takeIf { it.isNotBlank() }).apply()
        _nativeChatImageGenerationModel.value = model?.takeIf { it.isNotBlank() }
    }

    private val _nativeChatImageGenerationWidth = MutableStateFlow(prefs.getInt("native_chat_image_generation_width", 512))
    val nativeChatImageGenerationWidth = _nativeChatImageGenerationWidth.asStateFlow()
    fun setNativeChatImageGenerationWidth(width: Int) {
        val normalized = width.coerceAtLeast(64)
        prefs.edit().putInt("native_chat_image_generation_width", normalized).apply()
        _nativeChatImageGenerationWidth.value = normalized
    }

    private val _nativeChatImageGenerationHeight = MutableStateFlow(prefs.getInt("native_chat_image_generation_height", 512))
    val nativeChatImageGenerationHeight = _nativeChatImageGenerationHeight.asStateFlow()
    fun setNativeChatImageGenerationHeight(height: Int) {
        val normalized = height.coerceAtLeast(64)
        prefs.edit().putInt("native_chat_image_generation_height", normalized).apply()
        _nativeChatImageGenerationHeight.value = normalized
    }

    private val _nativeChatImageGenerationSteps = MutableStateFlow(prefs.getInt("native_chat_image_generation_steps", 20))
    val nativeChatImageGenerationSteps = _nativeChatImageGenerationSteps.asStateFlow()
    fun setNativeChatImageGenerationSteps(steps: Int) {
        val normalized = steps.coerceIn(1, 150)
        prefs.edit().putInt("native_chat_image_generation_steps", normalized).apply()
        _nativeChatImageGenerationSteps.value = normalized
    }

    private val _nativeChatImageGenerationCfg = MutableStateFlow(prefs.getFloat("native_chat_image_generation_cfg", 6.5f))
    val nativeChatImageGenerationCfg = _nativeChatImageGenerationCfg.asStateFlow()
    fun setNativeChatImageGenerationCfg(cfg: Float) {
        val normalized = cfg.coerceIn(0.1f, 30f)
        prefs.edit().putFloat("native_chat_image_generation_cfg", normalized).apply()
        _nativeChatImageGenerationCfg.value = normalized
    }

    private val _nativeChatImageGenerationSeed = MutableStateFlow(
        prefs.getString("native_chat_image_generation_seed", "") ?: ""
    )
    val nativeChatImageGenerationSeed = _nativeChatImageGenerationSeed.asStateFlow()
    fun setNativeChatImageGenerationSeed(seed: String) {
        val normalized = seed.trim()
        prefs.edit().putString("native_chat_image_generation_seed", normalized).apply()
        _nativeChatImageGenerationSeed.value = normalized
    }

    private val _nativeChatImageGenerationNegativePrompt = MutableStateFlow(
        prefs.getString("native_chat_image_generation_negative_prompt", "") ?: ""
    )
    val nativeChatImageGenerationNegativePrompt = _nativeChatImageGenerationNegativePrompt.asStateFlow()
    fun setNativeChatImageGenerationNegativePrompt(prompt: String) {
        prefs.edit().putString("native_chat_image_generation_negative_prompt", prompt).apply()
        _nativeChatImageGenerationNegativePrompt.value = prompt
    }

    private val _nativeChatImageGenerationBackend = MutableStateFlow(
        enumPref("native_chat_image_generation_backend", OnnxRuntimeBackend.CPU)
    )
    val nativeChatImageGenerationBackend = _nativeChatImageGenerationBackend.asStateFlow()
    fun setNativeChatImageGenerationBackend(backend: OnnxRuntimeBackend) {
        prefs.edit().putString("native_chat_image_generation_backend", backend.name).apply()
        _nativeChatImageGenerationBackend.value = backend
    }

    private val _nativeChatImageGenerationRuntimeThreads = MutableStateFlow(
        optionalIntPref("native_chat_image_generation_runtime_threads")
    )
    val nativeChatImageGenerationRuntimeThreads = _nativeChatImageGenerationRuntimeThreads.asStateFlow()
    fun setNativeChatImageGenerationRuntimeThreads(threads: Int?) {
        setOptionalIntPref("native_chat_image_generation_runtime_threads", threads?.coerceAtLeast(1))
        _nativeChatImageGenerationRuntimeThreads.value = threads?.coerceAtLeast(1)
    }

    private val _nativeChatImageGenerationGraphOptimizationLevel = MutableStateFlow(
        enumPref("native_chat_image_generation_graph_optimization_level", OnnxGraphOptimizationLevel.ALL)
    )
    val nativeChatImageGenerationGraphOptimizationLevel = _nativeChatImageGenerationGraphOptimizationLevel.asStateFlow()
    fun setNativeChatImageGenerationGraphOptimizationLevel(level: OnnxGraphOptimizationLevel) {
        prefs.edit().putString("native_chat_image_generation_graph_optimization_level", level.name).apply()
        _nativeChatImageGenerationGraphOptimizationLevel.value = level
    }

    private val _nativeChatImageGenerationUnetBackendOverride = MutableStateFlow(
        enumPref("native_chat_image_generation_unet_backend_override", OnnxBackendOverride.DEFAULT)
    )
    val nativeChatImageGenerationUnetBackendOverride = _nativeChatImageGenerationUnetBackendOverride.asStateFlow()
    fun setNativeChatImageGenerationUnetBackendOverride(override: OnnxBackendOverride) {
        prefs.edit().putString("native_chat_image_generation_unet_backend_override", override.name).apply()
        _nativeChatImageGenerationUnetBackendOverride.value = override
    }

    private val _nativeChatImageGenerationVaeDecoderBackendOverride = MutableStateFlow(
        enumPref("native_chat_image_generation_vae_decoder_backend_override", OnnxBackendOverride.DEFAULT)
    )
    val nativeChatImageGenerationVaeDecoderBackendOverride = _nativeChatImageGenerationVaeDecoderBackendOverride.asStateFlow()
    fun setNativeChatImageGenerationVaeDecoderBackendOverride(override: OnnxBackendOverride) {
        prefs.edit().putString("native_chat_image_generation_vae_decoder_backend_override", override.name).apply()
        _nativeChatImageGenerationVaeDecoderBackendOverride.value = override
    }

    private val _nativeChatImageGenerationVaeEncoderBackendOverride = MutableStateFlow(
        enumPref("native_chat_image_generation_vae_encoder_backend_override", OnnxBackendOverride.DEFAULT)
    )
    val nativeChatImageGenerationVaeEncoderBackendOverride = _nativeChatImageGenerationVaeEncoderBackendOverride.asStateFlow()
    fun setNativeChatImageGenerationVaeEncoderBackendOverride(override: OnnxBackendOverride) {
        prefs.edit().putString("native_chat_image_generation_vae_encoder_backend_override", override.name).apply()
        _nativeChatImageGenerationVaeEncoderBackendOverride.value = override
    }

    private val _nativeChatImageGenerationIntraOpThreads = MutableStateFlow(
        optionalIntPref("native_chat_image_generation_intra_op_threads")
    )
    val nativeChatImageGenerationIntraOpThreads = _nativeChatImageGenerationIntraOpThreads.asStateFlow()
    fun setNativeChatImageGenerationIntraOpThreads(threads: Int?) {
        setOptionalIntPref("native_chat_image_generation_intra_op_threads", threads?.coerceAtLeast(1))
        _nativeChatImageGenerationIntraOpThreads.value = threads?.coerceAtLeast(1)
    }

    private val _nativeChatImageGenerationInterOpThreads = MutableStateFlow(
        optionalIntPref("native_chat_image_generation_inter_op_threads")
    )
    val nativeChatImageGenerationInterOpThreads = _nativeChatImageGenerationInterOpThreads.asStateFlow()
    fun setNativeChatImageGenerationInterOpThreads(threads: Int?) {
        setOptionalIntPref("native_chat_image_generation_inter_op_threads", threads?.coerceAtLeast(1))
        _nativeChatImageGenerationInterOpThreads.value = threads?.coerceAtLeast(1)
    }

    private val _nativeChatImageGenerationExecutionMode = MutableStateFlow(
        enumPref("native_chat_image_generation_execution_mode", OnnxExecutionMode.SEQUENTIAL)
    )
    val nativeChatImageGenerationExecutionMode = _nativeChatImageGenerationExecutionMode.asStateFlow()
    fun setNativeChatImageGenerationExecutionMode(mode: OnnxExecutionMode) {
        prefs.edit().putString("native_chat_image_generation_execution_mode", mode.name).apply()
        _nativeChatImageGenerationExecutionMode.value = mode
    }

    private val _nativeChatImageGenerationMemoryPatternOptimization = MutableStateFlow(
        prefs.getBoolean("native_chat_image_generation_memory_pattern_optimization", true)
    )
    val nativeChatImageGenerationMemoryPatternOptimization = _nativeChatImageGenerationMemoryPatternOptimization.asStateFlow()
    fun setNativeChatImageGenerationMemoryPatternOptimization(enabled: Boolean) {
        prefs.edit().putBoolean("native_chat_image_generation_memory_pattern_optimization", enabled).apply()
        _nativeChatImageGenerationMemoryPatternOptimization.value = enabled
    }

    private val _nativeChatImageGenerationCpuArenaAllocator = MutableStateFlow(
        prefs.getBoolean("native_chat_image_generation_cpu_arena_allocator", true)
    )
    val nativeChatImageGenerationCpuArenaAllocator = _nativeChatImageGenerationCpuArenaAllocator.asStateFlow()
    fun setNativeChatImageGenerationCpuArenaAllocator(enabled: Boolean) {
        prefs.edit().putBoolean("native_chat_image_generation_cpu_arena_allocator", enabled).apply()
        _nativeChatImageGenerationCpuArenaAllocator.value = enabled
    }

    private val _nativeChatImageGenerationNnapiCpuDisabled = MutableStateFlow(
        prefs.getBoolean("native_chat_image_generation_nnapi_cpu_disabled", true)
    )
    val nativeChatImageGenerationNnapiCpuDisabled = _nativeChatImageGenerationNnapiCpuDisabled.asStateFlow()
    fun setNativeChatImageGenerationNnapiCpuDisabled(enabled: Boolean) {
        prefs.edit().putBoolean("native_chat_image_generation_nnapi_cpu_disabled", enabled).apply()
        _nativeChatImageGenerationNnapiCpuDisabled.value = enabled
    }

    private val _nativeChatImageGenerationNnapiUseFp16 = MutableStateFlow(
        prefs.getBoolean("native_chat_image_generation_nnapi_use_fp16", false)
    )
    val nativeChatImageGenerationNnapiUseFp16 = _nativeChatImageGenerationNnapiUseFp16.asStateFlow()
    fun setNativeChatImageGenerationNnapiUseFp16(enabled: Boolean) {
        prefs.edit().putBoolean("native_chat_image_generation_nnapi_use_fp16", enabled).apply()
        _nativeChatImageGenerationNnapiUseFp16.value = enabled
    }
    
    /**
     * Get model for a specific agent role
     */
    fun getAgentModelForRole(role: String): String {
        return when (role.uppercase()) {
            "ORCHESTRATOR" -> _agentOrchestratorModel.value
            "CODEBASE_SCOUT", "PLANNER" -> _agentOrchestratorModel.value
            "RESEARCHER" -> _agentWebSearchModel.value
                .takeIf { it.isNotBlank() }
                ?: _agentOrchestratorModel.value
            "CODER" -> _agentCoderModel.value
            "REVIEWER" -> _agentReviewerModel.value
            "EXECUTOR" -> _agentExecutorModel.value
            "SUMMARIZER" -> _agentSummarizerModel.value
            "VISUAL_TESTER" -> _agentVisualTesterModel.value
            else -> _agentOrchestratorModel.value
        }
    }
    
    /**
     * Set model for a specific agent role
     */
    fun setAgentModelForRole(role: String, model: String) {
        when (role.uppercase()) {
            "ORCHESTRATOR" -> setAgentOrchestratorModel(model)
            "CODEBASE_SCOUT", "PLANNER" -> setAgentOrchestratorModel(model)
            "RESEARCHER" -> setAgentWebSearchModel(model)
            "CODER" -> setAgentCoderModel(model)
            "REVIEWER" -> setAgentReviewerModel(model)
            "EXECUTOR" -> setAgentExecutorModel(model)
            "SUMMARIZER" -> setAgentSummarizerModel(model)
            "VISUAL_TESTER" -> setAgentVisualTesterModel(model)
        }
    }

    /**
     * Get thinking enabled for a specific agent role
     */
    fun getAgentThinkingEnabledForRole(role: String): Boolean {
        return when (role.uppercase()) {
            "ORCHESTRATOR" -> _agentOrchestratorThinkingEnabled.value
            "CODEBASE_SCOUT", "PLANNER" -> _agentOrchestratorThinkingEnabled.value
            "RESEARCHER" -> _agentWebSearchThinkingEnabled.value
            "CODER" -> _agentCoderThinkingEnabled.value
            "REVIEWER" -> _agentReviewerThinkingEnabled.value
            "EXECUTOR" -> _agentExecutorThinkingEnabled.value
            "SUMMARIZER" -> _agentSummarizerThinkingEnabled.value
            "VISUAL_TESTER" -> false
            "WEB_SEARCH" -> _agentWebSearchThinkingEnabled.value
            "KIWIX" -> _agentKiwixThinkingEnabled.value
            else -> _agentOrchestratorThinkingEnabled.value
        }
    }

    /**
     * Set thinking enabled for a specific agent role
     */
    fun setAgentThinkingEnabledForRole(role: String, enabled: Boolean) {
        when (role.uppercase()) {
            "ORCHESTRATOR" -> setAgentOrchestratorThinkingEnabled(enabled)
            "CODEBASE_SCOUT", "PLANNER" -> setAgentOrchestratorThinkingEnabled(enabled)
            "RESEARCHER" -> setAgentWebSearchThinkingEnabled(enabled)
            "CODER" -> setAgentCoderThinkingEnabled(enabled)
            "REVIEWER" -> setAgentReviewerThinkingEnabled(enabled)
            "EXECUTOR" -> setAgentExecutorThinkingEnabled(enabled)
            "SUMMARIZER" -> setAgentSummarizerThinkingEnabled(enabled)
            "VISUAL_TESTER" -> Unit
            "WEB_SEARCH" -> setAgentWebSearchThinkingEnabled(enabled)
            "KIWIX" -> setAgentKiwixThinkingEnabled(enabled)
        }
    }

    fun getAgentVisionEnabledForRole(role: String): Boolean {
        return when (role.uppercase()) {
            "ORCHESTRATOR" -> _agentOrchestratorVisionEnabled.value
            "CODEBASE_SCOUT", "RESEARCHER", "PLANNER" -> false
            "CODER" -> _agentCoderVisionEnabled.value
            "REVIEWER" -> _agentReviewerVisionEnabled.value
            "EXECUTOR" -> _agentExecutorVisionEnabled.value
            "SUMMARIZER" -> _agentSummarizerVisionEnabled.value
            "VISUAL_TESTER" -> _agentVisualTesterVisionEnabled.value
            else -> _agentOrchestratorVisionEnabled.value
        }
    }

    fun setAgentVisionEnabledForRole(role: String, enabled: Boolean) {
        when (role.uppercase()) {
            "ORCHESTRATOR" -> setAgentOrchestratorVisionEnabled(enabled)
            "CODEBASE_SCOUT", "RESEARCHER", "PLANNER" -> Unit
            "CODER" -> setAgentCoderVisionEnabled(enabled)
            "REVIEWER" -> setAgentReviewerVisionEnabled(enabled)
            "EXECUTOR" -> setAgentExecutorVisionEnabled(enabled)
            "SUMMARIZER" -> setAgentSummarizerVisionEnabled(enabled)
            "VISUAL_TESTER" -> setAgentVisualTesterVisionEnabled(enabled)
        }
    }
    
    // Last active conversation ID for AI Agent
    private val _lastAgentConversationId = MutableStateFlow(prefs.getLong("last_agent_conversation_id", -1L))
    val lastAgentConversationId = _lastAgentConversationId.asStateFlow()
    fun setLastAgentConversationId(id: Long) {
        prefs.edit().putLong("last_agent_conversation_id", id).apply()
        _lastAgentConversationId.value = id
    }
    
    // ========== AI Agent Web Search Settings ==========
    
    // Web Search Enabled
    private val _agentWebSearchEnabled = MutableStateFlow(prefs.getBoolean("agent_web_search_enabled", true))
    val agentWebSearchEnabled = _agentWebSearchEnabled.asStateFlow()
    fun setAgentWebSearchEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("agent_web_search_enabled", enabled).apply()
        _agentWebSearchEnabled.value = enabled
    }
    
    // Web Search summarizer model
    private val _agentWebSearchModel = MutableStateFlow(prefs.getString("agent_web_search_model", "qwen3.5:9b") ?: "qwen3.5:9b")
    val agentWebSearchModel = _agentWebSearchModel.asStateFlow()
    fun setAgentWebSearchModel(model: String) {
        prefs.edit().putString("agent_web_search_model", model).apply()
        _agentWebSearchModel.value = model
    }
    
    // Max results to fetch and summarize (1-8)
    private val _agentWebSearchMaxResults = MutableStateFlow(prefs.getInt("agent_web_search_max_results", 3))
    val agentWebSearchMaxResults = _agentWebSearchMaxResults.asStateFlow()
    fun setAgentWebSearchMaxResults(count: Int) {
        prefs.edit().putInt("agent_web_search_max_results", count.coerceAtLeast(1)).apply()
        _agentWebSearchMaxResults.value = count.coerceAtLeast(1)
    }
    
    // Max content chars to send to summarizer per page
    private val _agentWebSearchMaxChars = MutableStateFlow(prefs.getInt("agent_web_search_max_chars", 2000))
    val agentWebSearchMaxChars = _agentWebSearchMaxChars.asStateFlow()
    fun setAgentWebSearchMaxChars(chars: Int) {
        prefs.edit().putInt("agent_web_search_max_chars", chars.coerceAtLeast(100)).apply()
        _agentWebSearchMaxChars.value = chars.coerceAtLeast(100)
    }
    
    // Context size for web search summarizer LLM
    private val _agentWebSearchNumCtx = MutableStateFlow(prefs.getInt("agent_web_search_num_ctx", 16384))
    val agentWebSearchNumCtx = _agentWebSearchNumCtx.asStateFlow()
    fun setAgentWebSearchNumCtx(size: Int) {
        prefs.edit().putInt("agent_web_search_num_ctx", size).apply()
        _agentWebSearchNumCtx.value = size
    }

    // Web Search Thinking
    private val _agentWebSearchThinkingEnabled = MutableStateFlow(prefs.getBoolean("agent_web_search_thinking_enabled", true))
    val agentWebSearchThinkingEnabled = _agentWebSearchThinkingEnabled.asStateFlow()
    fun setAgentWebSearchThinkingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("agent_web_search_thinking_enabled", enabled).apply()
        _agentWebSearchThinkingEnabled.value = enabled
    }
    
    // ========== AI Agent Kiwix Search Settings ==========
    
    // Kiwix Search Enabled
    private val _agentKiwixEnabled = MutableStateFlow(prefs.getBoolean("agent_kiwix_enabled", false))
    val agentKiwixEnabled = _agentKiwixEnabled.asStateFlow()
    fun setAgentKiwixEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("agent_kiwix_enabled", enabled).apply()
        _agentKiwixEnabled.value = enabled
    }
    
    // Kiwix Server URL
    private val _agentKiwixUrl = MutableStateFlow(prefs.getString("agent_kiwix_url", "http://127.0.0.1:8888") ?: "http://127.0.0.1:8888")
    val agentKiwixUrl = _agentKiwixUrl.asStateFlow()
    fun setAgentKiwixUrl(url: String) {
        prefs.edit().putString("agent_kiwix_url", url).apply()
        _agentKiwixUrl.value = url
    }
    
    // Kiwix Search summarizer model
    private val _agentKiwixModel = MutableStateFlow(prefs.getString("agent_kiwix_model", "qwen3.5:9b") ?: "qwen3.5:9b")
    val agentKiwixModel = _agentKiwixModel.asStateFlow()
    fun setAgentKiwixModel(model: String) {
        prefs.edit().putString("agent_kiwix_model", model).apply()
        _agentKiwixModel.value = model
    }
    
    // Kiwix Max results
    private val _agentKiwixMaxResults = MutableStateFlow(prefs.getInt("agent_kiwix_max_results", 3))
    val agentKiwixMaxResults = _agentKiwixMaxResults.asStateFlow()
    fun setAgentKiwixMaxResults(count: Int) {
        prefs.edit().putInt("agent_kiwix_max_results", count.coerceAtLeast(1)).apply()
        _agentKiwixMaxResults.value = count.coerceAtLeast(1)
    }
    
    // Kiwix Max content chars per page
    private val _agentKiwixMaxChars = MutableStateFlow(prefs.getInt("agent_kiwix_max_chars", 2000))
    val agentKiwixMaxChars = _agentKiwixMaxChars.asStateFlow()
    fun setAgentKiwixMaxChars(chars: Int) {
        prefs.edit().putInt("agent_kiwix_max_chars", chars.coerceAtLeast(100)).apply()
        _agentKiwixMaxChars.value = chars.coerceAtLeast(100)
    }
    
    // Context size for Kiwix summarizer LLM
    private val _agentKiwixNumCtx = MutableStateFlow(prefs.getInt("agent_kiwix_num_ctx", 16384))
    val agentKiwixNumCtx = _agentKiwixNumCtx.asStateFlow()
    fun setAgentKiwixNumCtx(count: Int) {
        prefs.edit().putInt("agent_kiwix_num_ctx", count).apply()
        _agentKiwixNumCtx.value = count
    }

    // Kiwix Thinking
    private val _agentKiwixThinkingEnabled = MutableStateFlow(prefs.getBoolean("agent_kiwix_thinking_enabled", true))
    val agentKiwixThinkingEnabled = _agentKiwixThinkingEnabled.asStateFlow()
    fun setAgentKiwixThinkingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("agent_kiwix_thinking_enabled", enabled).apply()
        _agentKiwixThinkingEnabled.value = enabled
    }
    
    // ========== Per-Agent Context Settings ==========
    
    private val _agentOrchestratorCtx = MutableStateFlow(prefs.getInt("agent_orchestrator_ctx", 16384))
    val agentOrchestratorCtx = _agentOrchestratorCtx.asStateFlow()
    fun setAgentOrchestratorCtx(size: Int) {
        prefs.edit().putInt("agent_orchestrator_ctx", size).apply()
        _agentOrchestratorCtx.value = size
    }

    private val _agentOrchestratorMaxOutputTokens =
        MutableStateFlow(prefs.getInt("agent_orchestrator_max_output_tokens", 8096).coerceAtLeast(1))
    val agentOrchestratorMaxOutputTokens = _agentOrchestratorMaxOutputTokens.asStateFlow()
    fun setAgentOrchestratorMaxOutputTokens(value: Int) {
        val normalized = value.coerceIn(1, 262_144)
        prefs.edit().putInt("agent_orchestrator_max_output_tokens", normalized).apply()
        _agentOrchestratorMaxOutputTokens.value = normalized
    }
    
    // Orchestrator Thinking
    private val _agentOrchestratorThinkingEnabled = MutableStateFlow(prefs.getBoolean("agent_orchestrator_thinking_enabled", true))
    val agentOrchestratorThinkingEnabled = _agentOrchestratorThinkingEnabled.asStateFlow()
    fun setAgentOrchestratorThinkingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("agent_orchestrator_thinking_enabled", enabled).apply()
        _agentOrchestratorThinkingEnabled.value = enabled
    }
    
    private val _agentCoderCtx = MutableStateFlow(prefs.getInt("agent_coder_ctx", 16384))
    val agentCoderCtx = _agentCoderCtx.asStateFlow()
    fun setAgentCoderCtx(size: Int) {
        prefs.edit().putInt("agent_coder_ctx", size).apply()
        _agentCoderCtx.value = size
    }

    private val _agentCoderMaxOutputTokens =
        MutableStateFlow(prefs.getInt("agent_coder_max_output_tokens", 8096).coerceAtLeast(1))
    val agentCoderMaxOutputTokens = _agentCoderMaxOutputTokens.asStateFlow()
    fun setAgentCoderMaxOutputTokens(value: Int) {
        val normalized = value.coerceIn(1, 262_144)
        prefs.edit().putInt("agent_coder_max_output_tokens", normalized).apply()
        _agentCoderMaxOutputTokens.value = normalized
    }
    
    // Coder Thinking
    private val _agentCoderThinkingEnabled = MutableStateFlow(prefs.getBoolean("agent_coder_thinking_enabled", true))
    val agentCoderThinkingEnabled = _agentCoderThinkingEnabled.asStateFlow()
    fun setAgentCoderThinkingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("agent_coder_thinking_enabled", enabled).apply()
        _agentCoderThinkingEnabled.value = enabled
    }
    
    private val _agentReviewerCtx = MutableStateFlow(prefs.getInt("agent_reviewer_ctx", 16384))
    val agentReviewerCtx = _agentReviewerCtx.asStateFlow()
    fun setAgentReviewerCtx(size: Int) {
        prefs.edit().putInt("agent_reviewer_ctx", size).apply()
        _agentReviewerCtx.value = size
    }

    private val _agentReviewerMaxOutputTokens =
        MutableStateFlow(prefs.getInt("agent_reviewer_max_output_tokens", 8096).coerceAtLeast(1))
    val agentReviewerMaxOutputTokens = _agentReviewerMaxOutputTokens.asStateFlow()
    fun setAgentReviewerMaxOutputTokens(value: Int) {
        val normalized = value.coerceIn(1, 262_144)
        prefs.edit().putInt("agent_reviewer_max_output_tokens", normalized).apply()
        _agentReviewerMaxOutputTokens.value = normalized
    }
    
    // Reviewer Thinking
    private val _agentReviewerThinkingEnabled = MutableStateFlow(prefs.getBoolean("agent_reviewer_thinking_enabled", true))
    val agentReviewerThinkingEnabled = _agentReviewerThinkingEnabled.asStateFlow()
    fun setAgentReviewerThinkingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("agent_reviewer_thinking_enabled", enabled).apply()
        _agentReviewerThinkingEnabled.value = enabled
    }
    
    private val _agentExecutorCtx = MutableStateFlow(prefs.getInt("agent_executor_ctx", 16384))
    val agentExecutorCtx = _agentExecutorCtx.asStateFlow()
    fun setAgentExecutorCtx(size: Int) {
        prefs.edit().putInt("agent_executor_ctx", size).apply()
        _agentExecutorCtx.value = size
    }

    private val _agentExecutorMaxOutputTokens =
        MutableStateFlow(prefs.getInt("agent_executor_max_output_tokens", 8096).coerceAtLeast(1))
    val agentExecutorMaxOutputTokens = _agentExecutorMaxOutputTokens.asStateFlow()
    fun setAgentExecutorMaxOutputTokens(value: Int) {
        val normalized = value.coerceIn(1, 262_144)
        prefs.edit().putInt("agent_executor_max_output_tokens", normalized).apply()
        _agentExecutorMaxOutputTokens.value = normalized
    }
    
    // Executor Thinking
    private val _agentExecutorThinkingEnabled = MutableStateFlow(prefs.getBoolean("agent_executor_thinking_enabled", true))
    val agentExecutorThinkingEnabled = _agentExecutorThinkingEnabled.asStateFlow()
    fun setAgentExecutorThinkingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("agent_executor_thinking_enabled", enabled).apply()
        _agentExecutorThinkingEnabled.value = enabled
    }
    
    private val _agentSummarizerCtx = MutableStateFlow(prefs.getInt("agent_summarizer_ctx", 16384))
    val agentSummarizerCtx = _agentSummarizerCtx.asStateFlow()
    fun setAgentSummarizerCtx(size: Int) {
        prefs.edit().putInt("agent_summarizer_ctx", size).apply()
        _agentSummarizerCtx.value = size
    }

    private val _agentSummarizerMaxOutputTokens =
        MutableStateFlow(prefs.getInt("agent_summarizer_max_output_tokens", 8096).coerceAtLeast(1))
    val agentSummarizerMaxOutputTokens = _agentSummarizerMaxOutputTokens.asStateFlow()
    fun setAgentSummarizerMaxOutputTokens(value: Int) {
        val normalized = value.coerceIn(1, 262_144)
        prefs.edit().putInt("agent_summarizer_max_output_tokens", normalized).apply()
        _agentSummarizerMaxOutputTokens.value = normalized
    }
    
    /**
     * Get context size for a specific agent role
     */
    fun getAgentContextForRole(role: String): Int {
        return when (role.uppercase()) {
            "ORCHESTRATOR" -> _agentOrchestratorCtx.value
            "CODEBASE_SCOUT", "PLANNER" -> _agentOrchestratorCtx.value
            "RESEARCHER" -> _agentWebSearchNumCtx.value
            "CODER" -> _agentCoderCtx.value
            "REVIEWER" -> _agentReviewerCtx.value
            "EXECUTOR" -> _agentExecutorCtx.value
            "SUMMARIZER" -> _agentSummarizerCtx.value
            else -> _agentOrchestratorCtx.value
        }
    }

    fun getAgentMaxOutputTokensForRole(role: String): Int {
        return when (role.uppercase()) {
            "ORCHESTRATOR" -> _agentOrchestratorMaxOutputTokens.value
            "CODEBASE_SCOUT", "RESEARCHER", "PLANNER" ->
                _agentOrchestratorMaxOutputTokens.value
            "CODER" -> _agentCoderMaxOutputTokens.value
            "REVIEWER" -> _agentReviewerMaxOutputTokens.value
            "EXECUTOR" -> _agentExecutorMaxOutputTokens.value
            "SUMMARIZER" -> _agentSummarizerMaxOutputTokens.value
            else -> _agentOrchestratorMaxOutputTokens.value
        }
    }

    // ========== AI Agent Per-Role System Prompts ==========
    
    companion object {
        private const val MANGA_TRANSLATION_CONFIG_KEY = "manga_translation_run_config_v4"
        const val PDF_BACKEND_OLLAMA = "ollama"
        const val PDF_BACKEND_LLAMA_SERVER = "llama-server"
        const val PDF_BACKEND_LLAMA_SWAP = "llama-swap"
        const val PDF_BACKEND_LITERT = "litert-lm"
        const val KB_EMBED_BACKEND_LOCAL = "local-llama"
        const val KB_EMBED_BACKEND_LLAMA_SERVER = "llama-server"
        const val KB_EMBED_BACKEND_OLLAMA = "ollama"
        const val KB_EMBED_BACKEND_LLAMA_SWAP = "llama-swap"
        const val KB_EMBED_BACKEND_LITERT = "litert"
        const val PDF_LLAMA_SERVER_DEFAULT_URL = "http://localhost:8080"
        const val PDF_LLAMA_SWAP_DEFAULT_URL = "http://localhost:9292"
        const val KB_DEFAULT_CHUNK_SIZE = 1_000
        val KB_CHUNK_SIZE_RANGE: IntRange = 400..3_200
        const val KB_DEFAULT_EMBED_BATCH_SIZE = 1_024
        val KB_EMBED_BATCH_SIZE_RANGE: IntRange = 512..4_096
        const val KB_DEFAULT_EMBED_THREADS = 4
        val KB_EMBED_THREADS_RANGE: IntRange = 1..16
        const val ACCELERATION_AUTO = "auto"
        const val ACCELERATION_CPU = "cpu"
        const val ACCELERATION_GPU = "gpu"
        const val ACCELERATION_NPU = "npu"
        const val NATIVE_BINARY_AUTO = "auto"
        const val NATIVE_BINARY_CPU_AUTO = "cpu_auto"
        const val NATIVE_BINARY_CPU_BASELINE = "cpu_baseline"
        const val NATIVE_BINARY_CPU_DOTPROD = "cpu_dotprod"
        const val NATIVE_BINARY_CPU_ARMV9 = "cpu_armv9"
        const val NATIVE_BINARY_CPU_I8MM = "cpu_i8mm"
        const val NATIVE_BINARY_LLM_SNAPDRAGON_OPENCL = "llm_snapdragon_opencl"
        const val NATIVE_BINARY_SD_SNAPDRAGON_VULKAN = "sd_snapdragon_vulkan"
        const val NATIVE_BINARY_SD_SNAPDRAGON_OPENCL = "sd_snapdragon_opencl"
        const val LLAMA_KV_OFFLOAD_AUTO = "auto"
        const val LLAMA_KV_OFFLOAD_ACCELERATOR = "accelerator"
        const val LLAMA_KV_OFFLOAD_CPU = "cpu"
        const val LLAMA_DRAFT_DEVICE_AUTO = "auto"
        const val LLAMA_DRAFT_DEVICE_ACCELERATOR = "accelerator"
        const val LLAMA_DRAFT_DEVICE_CPU = "cpu"

        fun normalizeKnowledgeBaseChunkSize(size: Int): Int =
            size.coerceIn(KB_CHUNK_SIZE_RANGE)

        fun normalizeKnowledgeEmbeddingBatchSize(size: Int): Int =
            size.coerceIn(KB_EMBED_BATCH_SIZE_RANGE)

        fun normalizeKnowledgeEmbeddingThreads(count: Int): Int =
            count.coerceIn(KB_EMBED_THREADS_RANGE)

        fun knowledgeEmbeddingBatchSizeForChunkSize(chunkSize: Int): Int {
            val normalized = normalizeKnowledgeBaseChunkSize(chunkSize)
            val estimatedTokens = (normalized / 1.5f).toInt() + 64
            val target = estimatedTokens.coerceAtLeast(KB_EMBED_BATCH_SIZE_RANGE.first)
            return when {
                target <= 512 -> 512
                target <= 1_024 -> 1_024
                target <= 2_048 -> 2_048
                else -> KB_EMBED_BATCH_SIZE_RANGE.last
            }
        }

        fun knowledgeEmbeddingContextSizeForChunkSize(chunkSize: Int): Int =
            knowledgeEmbeddingBatchSizeForChunkSize(chunkSize)
                .coerceAtLeast(2_048)
                .coerceAtMost(4_096)

        fun knowledgeEmbeddingTokenBudgetForChunkSize(chunkSize: Int): Int =
            (knowledgeEmbeddingBatchSizeForChunkSize(chunkSize) - 32).coerceAtLeast(256)

        fun knowledgeEmbeddingContextSizeForBatchSize(batchSize: Int): Int =
            normalizeKnowledgeEmbeddingBatchSize(batchSize)
                .coerceAtLeast(2_048)
                .coerceAtMost(4_096)

        fun knowledgeEmbeddingTokenBudgetForBatchSize(batchSize: Int): Int =
            (normalizeKnowledgeEmbeddingBatchSize(batchSize) - 32).coerceAtLeast(256)

        fun normalizeOllamaOrLlamaBackend(backend: String?): String {
            val normalized = backend
                ?.trim()
                ?.lowercase(Locale.US)
                ?.replace('_', '-')
                ?: return PDF_BACKEND_OLLAMA

            return when (normalized) {
                PDF_BACKEND_LLAMA_SERVER,
                "llama.cpp",
                "llama-cpp",
                "llamacpp" -> PDF_BACKEND_LLAMA_SERVER
                PDF_BACKEND_LLAMA_SWAP,
                "llamaswap" -> PDF_BACKEND_LLAMA_SWAP
                PDF_BACKEND_LITERT,
                "litert",
                "litertlm",
                "lite-rt",
                "lite-rt-lm" -> PDF_BACKEND_LITERT
                else -> PDF_BACKEND_OLLAMA
            }
        }

        fun normalizeKnowledgeEmbeddingBackend(backend: String?): String {
            val normalized = backend
                ?.trim()
                ?.lowercase(Locale.US)
                ?.replace('_', '-')
                ?: return KB_EMBED_BACKEND_LOCAL

            return when (normalized) {
                KB_EMBED_BACKEND_LOCAL,
                "local",
                "managed-llama",
                "local-llamacpp" -> KB_EMBED_BACKEND_LOCAL
                KB_EMBED_BACKEND_LLAMA_SERVER,
                "llama.cpp",
                "llama-cpp",
                "llamacpp",
                "custom-llama-server" -> KB_EMBED_BACKEND_LLAMA_SERVER
                KB_EMBED_BACKEND_OLLAMA -> KB_EMBED_BACKEND_OLLAMA
                KB_EMBED_BACKEND_LITERT,
                "litert-lm",
                "litertlm",
                "lite-rt",
                "lite-rt-lm" -> KB_EMBED_BACKEND_LOCAL
                KB_EMBED_BACKEND_LLAMA_SWAP,
                "llamaswap",
                "openai" -> KB_EMBED_BACKEND_LLAMA_SWAP
                else -> KB_EMBED_BACKEND_LOCAL
            }
        }

        fun normalizeAccelerationMode(mode: String?): String {
            val normalized = mode
                ?.trim()
                ?.lowercase(Locale.US)
                ?.replace('_', '-')
                ?: return ACCELERATION_AUTO

            return when (normalized) {
                ACCELERATION_CPU,
                "cpu-only",
                "cpu-only-mode" -> ACCELERATION_CPU
                ACCELERATION_GPU,
                "opencl",
                "adreno",
                "gpu-only" -> ACCELERATION_GPU
                ACCELERATION_NPU,
                "hexagon",
                "htp",
                "dsp",
                "qnn",
                "npu-only",
                "vulkan" -> ACCELERATION_AUTO
                else -> ACCELERATION_AUTO
            }
        }

        fun normalizeStableDiffusionAccelerationMode(@Suppress("UNUSED_PARAMETER") mode: String?): String = ACCELERATION_CPU

        fun normalizeLlamaKvOffloadMode(mode: String?): String {
            val normalized = mode
                ?.trim()
                ?.lowercase(Locale.US)
                ?.replace('_', '-')
                ?: return LLAMA_KV_OFFLOAD_AUTO
            return when (normalized) {
                LLAMA_KV_OFFLOAD_ACCELERATOR,
                "gpu",
                "on",
                "enabled" -> LLAMA_KV_OFFLOAD_ACCELERATOR
                LLAMA_KV_OFFLOAD_CPU,
                "off",
                "disabled",
                "no-kv-offload" -> LLAMA_KV_OFFLOAD_CPU
                else -> LLAMA_KV_OFFLOAD_AUTO
            }
        }

        fun normalizeLlamaDraftDeviceMode(mode: String?): String {
            val normalized = mode
                ?.trim()
                ?.lowercase(Locale.US)
                ?.replace('_', '-')
                ?: return LLAMA_DRAFT_DEVICE_AUTO
            return when (normalized) {
                LLAMA_DRAFT_DEVICE_ACCELERATOR,
                "gpu",
                "opencl",
                "gpuopencl" -> LLAMA_DRAFT_DEVICE_ACCELERATOR
                LLAMA_DRAFT_DEVICE_CPU,
                "none",
                "off",
                "disabled" -> LLAMA_DRAFT_DEVICE_CPU
                else -> LLAMA_DRAFT_DEVICE_AUTO
            }
        }

        fun normalizeLlmNativeBinarySelection(selection: String?): String {
            CustomBinarySelection.normalize(selection)?.let { return it }
            val normalized = normalizeNativeBinaryToken(selection) ?: return NATIVE_BINARY_AUTO

            return when (normalized) {
                NATIVE_BINARY_AUTO,
                "automatic" -> NATIVE_BINARY_AUTO
                NATIVE_BINARY_CPU_AUTO,
                "cpu" -> NATIVE_BINARY_CPU_AUTO
                NATIVE_BINARY_CPU_BASELINE,
                "baseline",
                "libllama_server_baseline",
                "libllama_server_baseline_so" -> NATIVE_BINARY_CPU_BASELINE
                NATIVE_BINARY_CPU_DOTPROD,
                "dotprod",
                "libllama_server_dotprod",
                "libllama_server_dotprod_so" -> NATIVE_BINARY_CPU_DOTPROD
                NATIVE_BINARY_CPU_ARMV9,
                "armv9",
                "libllama_server_armv9",
                "libllama_server_armv9_so" -> NATIVE_BINARY_CPU_ARMV9
                NATIVE_BINARY_CPU_I8MM,
                "i8mm",
                "libllama_server_i8mm",
                "libllama_server_i8mm_so" -> NATIVE_BINARY_CPU_I8MM
                NATIVE_BINARY_LLM_SNAPDRAGON_OPENCL,
                "snapdragon_opencl",
                "opencl",
                "gpu",
                "adreno",
                "libllama_server_snapdragon_opencl",
                "libllama_server_snapdragon_opencl_so" -> NATIVE_BINARY_LLM_SNAPDRAGON_OPENCL
                "vulkan",
                "hexagon",
                "htp",
                "dsp",
                "qnn",
                "npu" -> NATIVE_BINARY_AUTO
                else -> NATIVE_BINARY_AUTO
            }
        }

        fun normalizeStableDiffusionNativeBinarySelection(selection: String?): String {
            CustomBinarySelection.normalize(selection)?.let { return it }
            val normalized = normalizeNativeBinaryToken(selection) ?: return NATIVE_BINARY_AUTO

            return when (normalized) {
                NATIVE_BINARY_AUTO,
                "automatic" -> NATIVE_BINARY_AUTO
                NATIVE_BINARY_CPU_AUTO,
                "cpu" -> NATIVE_BINARY_CPU_AUTO
                NATIVE_BINARY_CPU_BASELINE,
                "baseline",
                "libsd_baseline",
                "libsd_baseline_so" -> NATIVE_BINARY_CPU_BASELINE
                NATIVE_BINARY_CPU_DOTPROD,
                "dotprod",
                "libsd_dotprod",
                "libsd_dotprod_so" -> NATIVE_BINARY_CPU_DOTPROD
                NATIVE_BINARY_CPU_ARMV9,
                "armv9",
                "libsd_armv9",
                "libsd_armv9_so" -> NATIVE_BINARY_CPU_ARMV9
                NATIVE_BINARY_CPU_I8MM,
                "i8mm",
                "libsd_i8mm",
                "libsd_i8mm_so" -> NATIVE_BINARY_CPU_I8MM
                NATIVE_BINARY_SD_SNAPDRAGON_VULKAN,
                "snapdragon_vulkan",
                "vulkan",
                "gpu",
                "libsd_snapdragon_vulkan",
                "libsd_snapdragon_vulkan_so" -> NATIVE_BINARY_SD_SNAPDRAGON_VULKAN
                NATIVE_BINARY_SD_SNAPDRAGON_OPENCL,
                "snapdragon_opencl",
                "opencl",
                "adreno",
                "libsd_snapdragon_opencl",
                "libsd_snapdragon_opencl_so" -> NATIVE_BINARY_SD_SNAPDRAGON_OPENCL
                "hexagon",
                "htp",
                "dsp",
                "qnn",
                "npu" -> NATIVE_BINARY_AUTO
                else -> NATIVE_BINARY_AUTO
            }
        }

        private fun normalizeNativeBinaryToken(selection: String?): String? =
            selection
                ?.trim()
                ?.lowercase(Locale.US)
                ?.replace('-', '_')
                ?.replace('.', '_')
                ?.takeIf { it.isNotBlank() }

        private object CustomBinarySelection {
            private val pattern = Regex("custom:[a-z0-9][a-z0-9._-]{2,47}")

            fun normalize(selection: String?): String? =
                selection?.trim()?.lowercase(Locale.US)?.takeIf(pattern::matches)
        }

        private fun legacyLlmBinarySelectionDefault(accelerationMode: String?): String =
            when (normalizeAccelerationMode(accelerationMode)) {
                ACCELERATION_CPU -> NATIVE_BINARY_CPU_AUTO
                ACCELERATION_GPU -> NATIVE_BINARY_LLM_SNAPDRAGON_OPENCL
                else -> NATIVE_BINARY_AUTO
            }

        private fun legacyStableDiffusionBinarySelectionDefault(accelerationMode: String?): String =
            when (normalizeAccelerationMode(accelerationMode)) {
                ACCELERATION_CPU -> NATIVE_BINARY_CPU_AUTO
                ACCELERATION_GPU -> NATIVE_BINARY_SD_SNAPDRAGON_VULKAN
                else -> NATIVE_BINARY_AUTO
            }

        fun isLlamaServerBackend(backend: String?): Boolean =
            normalizeOllamaOrLlamaBackend(backend) == PDF_BACKEND_LLAMA_SERVER

        fun isLlamaSwapBackend(backend: String?): Boolean =
            normalizeOllamaOrLlamaBackend(backend) == PDF_BACKEND_LLAMA_SWAP

        fun isLiteRtBackend(backend: String?): Boolean =
            normalizeOllamaOrLlamaBackend(backend) == PDF_BACKEND_LITERT

        fun usesOpenAiChatBackend(backend: String?): Boolean =
            normalizeOllamaOrLlamaBackend(backend).let {
                it == PDF_BACKEND_LLAMA_SERVER || it == PDF_BACKEND_LLAMA_SWAP
            }

        fun requiresSelectedRemoteModel(backend: String?): Boolean =
            normalizeOllamaOrLlamaBackend(backend).let {
                it == PDF_BACKEND_OLLAMA || it == PDF_BACKEND_LLAMA_SWAP
            }

        const val SUMMARY_CONTEXT_MIN = 1
        val SUMMARY_CONTEXT_RANGE: IntRange = 2048..32768
        val PDF_THREADS_RANGE: IntRange = 1..16
        const val SUMMARY_MAX_TOKENS_MIN = 1
        val SUMMARY_MAX_TOKENS_RANGE: IntRange = 64..65536
        val SUMMARY_TIMEOUT_MINUTES_RANGE: IntRange = 0..120
        const val SUMMARY_TEMPERATURE_MIN = 0f
        const val SUMMARY_TEMPERATURE_MAX = 2f
        const val SUMMARY_TIMEOUT_DISABLED = 0
        const val DEFAULT_SUMMARY_TARGET_LANGUAGE = "source language"
        val PDF_CONTEXT_RANGE: IntRange = SUMMARY_CONTEXT_RANGE
        val PDF_MAX_TOKENS_RANGE: IntRange = SUMMARY_MAX_TOKENS_RANGE
        val PDF_TIMEOUT_MINUTES_RANGE: IntRange = SUMMARY_TIMEOUT_MINUTES_RANGE
        const val PDF_TEMPERATURE_MIN = SUMMARY_TEMPERATURE_MIN
        const val PDF_TEMPERATURE_MAX = SUMMARY_TEMPERATURE_MAX
        const val PDF_TIMEOUT_DISABLED = SUMMARY_TIMEOUT_DISABLED
        const val DEFAULT_TRANSCRIPT_SUMMARY_PROMPT = """You are an expert transcription summarizer.

Create a concise summary of the provided transcript excerpt.

Instructions:
- Capture the important facts, decisions, and topics discussed
- Prefer bullet points when it improves clarity
- Do not invent information that is not explicitly present
- Write clearly for someone who has not read the full transcript"""
        const val DEFAULT_TRANSCRIPT_MERGE_PROMPT = """You are an expert at merging transcript summaries.

Combine the provided partial summaries into one coherent final summary.

Instructions:
- Preserve all unique relevant information
- Remove repetition
- Organize the result with a short title and clear sections
- Do not add facts that are not explicitly present in the inputs"""
        const val FALLBACK_ORCHESTRATOR_PROMPT = "You are the Orchestrator, the central brain of a multi-agent AI coding system."
        const val FALLBACK_CODER_PROMPT = "You are the Coder agent. You specialize in software development and file manipulation."
        const val FALLBACK_REVIEWER_PROMPT = "You are the Reviewer agent. Your purpose is to ensure code quality, security, and correctness."
        const val FALLBACK_EXECUTOR_PROMPT = "You are the Executor agent. You are the bridge between the AI and the system shell."
        const val FALLBACK_TAMA_PET_PROMPT = """You are a virtual pet talking to your owner.

Stay fully in character, react to your current mood, growth stage, personality, recent actions, location, and inventory, and sound like a real little companion with opinions and feelings.

Default reply style:
- usually 1-2 short sentences
- keep replies compact enough to fit naturally in a small chat bubble
- only go longer when the owner clearly asks for detail or tells you to explain more
- avoid rambling, speeches, and long lists unless directly requested

Voice:
- playful, warm, alive, and aware of what just happened
- stage-appropriate and personality-aware
- funny in small bursts, not long monologues"""
        const val FALLBACK_TAMA_SUMMARIZER_PROMPT = "You are the Tama Summarizer. Your job is to summarize the recent conversation and events into a concise memory that helps the pet remember its owner and life."
        const val FALLBACK_ADVENTURE_SYSTEM_PROMPT = """You are a master storyteller for dark fantasy text adventures.

When generating a story schematic at the START of an adventure, mark these parameters clearly:
- TOTAL_STAGES: (a number between 5 and 30)
- STORY_THREAD: (the main narrative arc)
- KEY_EVENTS: (major plot points)
- POSSIBLE_ENDINGS: (ways the story could conclude)
- TONE: (horror/mystery/action/etc)
- DIFFICULTY: (how harsh consequences are)

For each story stage:
- Present the situation vividly and immersively
- Describe the environment, atmosphere, and any characters
- Create tension, mystery, and engagement
- At the END of each stage, ALWAYS give the player 2-4 clear options to choose from
- Example: "What do you do? A) Attack the creature B) Try to negotiate C) Flee into the darkness D) Search for another path"
- The player can pick one of your options OR describe their own creative action

The player's choices shape the story and its outcome. React meaningfully to their decisions.
Be descriptive and immersive. Do not rush the story."""
        const val FALLBACK_ADVENTURE_SUMMARIZER_PROMPT = """Summarize the adventure stage concisely. Include:
- Key events that happened
- Player's choice and its consequences
- Current situation
Keep it brief but capture essential details."""
    }
    
    private val _agentOrchestratorPrompt = MutableStateFlow(prefs.getString("agent_orchestrator_prompt", null) ?: PromptUtils.getPrompt(context, PromptUtils.Keys.ORCHESTRATOR, FALLBACK_ORCHESTRATOR_PROMPT))
    val agentOrchestratorPrompt = _agentOrchestratorPrompt.asStateFlow()
    fun setAgentOrchestratorPrompt(prompt: String) {
        prefs.edit().putString("agent_orchestrator_prompt", prompt).apply()
        _agentOrchestratorPrompt.value = prompt
    }
    
    private val _agentCoderPrompt = MutableStateFlow(prefs.getString("agent_coder_prompt", null) ?: PromptUtils.getPrompt(context, PromptUtils.Keys.CODER, FALLBACK_CODER_PROMPT))
    val agentCoderPrompt = _agentCoderPrompt.asStateFlow()
    fun setAgentCoderPrompt(prompt: String) {
        prefs.edit().putString("agent_coder_prompt", prompt).apply()
        _agentCoderPrompt.value = prompt
    }
    
    private val _agentReviewerPrompt = MutableStateFlow(prefs.getString("agent_reviewer_prompt", null) ?: PromptUtils.getPrompt(context, PromptUtils.Keys.REVIEWER, FALLBACK_REVIEWER_PROMPT))
    val agentReviewerPrompt = _agentReviewerPrompt.asStateFlow()
    fun setAgentReviewerPrompt(prompt: String) {
        prefs.edit().putString("agent_reviewer_prompt", prompt).apply()
        _agentReviewerPrompt.value = prompt
    }
    
    private val _agentExecutorPrompt = MutableStateFlow(prefs.getString("agent_executor_prompt", null) ?: PromptUtils.getPrompt(context, PromptUtils.Keys.EXECUTOR, FALLBACK_EXECUTOR_PROMPT))
    val agentExecutorPrompt = _agentExecutorPrompt.asStateFlow()
    fun setAgentExecutorPrompt(prompt: String) {
        prefs.edit().putString("agent_executor_prompt", prompt).apply()
        _agentExecutorPrompt.value = prompt
    }
    
    private val _agentSummarizerPrompt = MutableStateFlow(prefs.getString("agent_summarizer_prompt", null) ?: PromptUtils.getPrompt(context, PromptUtils.Keys.SUMMARIZER, "You are the Summarizer agent."))
    val agentSummarizerPrompt = _agentSummarizerPrompt.asStateFlow()
    fun setAgentSummarizerPrompt(prompt: String) {
        prefs.edit().putString("agent_summarizer_prompt", prompt).apply()
        _agentSummarizerPrompt.value = prompt
    }
    
    /**
     * Get system prompt for a specific agent role
     */
    fun getAgentPromptForRole(role: String): String {
        return when (role.uppercase()) {
            "ORCHESTRATOR" -> _agentOrchestratorPrompt.value
            "CODER" -> _agentCoderPrompt.value
            "REVIEWER" -> _agentReviewerPrompt.value
            "EXECUTOR" -> _agentExecutorPrompt.value
            "SUMMARIZER" -> _agentSummarizerPrompt.value
            else -> _agentOrchestratorPrompt.value
        }
    }
    
    /**
     * Reset prompt to default for a specific role
     */
    fun resetAgentPromptToDefault(role: String) {
        when (role.uppercase()) {
            "ORCHESTRATOR" -> setAgentOrchestratorPrompt(PromptUtils.getPrompt(context, PromptUtils.Keys.ORCHESTRATOR, FALLBACK_ORCHESTRATOR_PROMPT))
            "CODER" -> setAgentCoderPrompt(PromptUtils.getPrompt(context, PromptUtils.Keys.CODER, FALLBACK_CODER_PROMPT))
            "REVIEWER" -> setAgentReviewerPrompt(PromptUtils.getPrompt(context, PromptUtils.Keys.REVIEWER, FALLBACK_REVIEWER_PROMPT))
            "EXECUTOR" -> setAgentExecutorPrompt(PromptUtils.getPrompt(context, PromptUtils.Keys.EXECUTOR, FALLBACK_EXECUTOR_PROMPT))
            "SUMMARIZER" -> setAgentSummarizerPrompt(PromptUtils.getPrompt(context, PromptUtils.Keys.SUMMARIZER, "You are the Summarizer agent."))
            "TAMA_PET" -> setTamaPetPrompt(PromptUtils.getPrompt(context, PromptUtils.Keys.TAMA_PET, FALLBACK_TAMA_PET_PROMPT))
            "TAMA_SUMMARIZER" -> setTamaSummarizerPrompt(PromptUtils.getPrompt(context, PromptUtils.Keys.TAMA_SUMMARIZER, FALLBACK_TAMA_SUMMARIZER_PROMPT))
        }
    }

    // ========== Tama Virtual Pet AI Settings ==========

    private val _tamaBackend = MutableStateFlow(
        normalizeOllamaOrLlamaBackend(prefs.getString("tama_backend", PDF_BACKEND_OLLAMA))
    )
    val tamaBackend = _tamaBackend.asStateFlow()
    fun setTamaBackend(backend: String) {
        val normalized = normalizeOllamaOrLlamaBackend(backend)
        prefs.edit().putString("tama_backend", normalized).apply()
        _tamaBackend.value = normalized
    }

    private val _tamaThinkingEnabled = MutableStateFlow(prefs.getBoolean("tama_thinking_enabled", true))
    val tamaThinkingEnabled = _tamaThinkingEnabled.asStateFlow()
    fun setTamaThinkingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("tama_thinking_enabled", enabled).apply()
        _tamaThinkingEnabled.value = enabled
    }

    private val _tamaLlamaServerUrl = MutableStateFlow(
        prefs.getString("tama_llama_server_url", PDF_LLAMA_SERVER_DEFAULT_URL) ?: PDF_LLAMA_SERVER_DEFAULT_URL
    )
    val tamaLlamaServerUrl = _tamaLlamaServerUrl.asStateFlow()
    fun setTamaLlamaServerUrl(url: String) {
        prefs.edit().putString("tama_llama_server_url", url).apply()
        _tamaLlamaServerUrl.value = url
    }

    private val _tamaLlamaSwapUrl = MutableStateFlow(
        prefs.getString("tama_llama_swap_url", PDF_LLAMA_SWAP_DEFAULT_URL) ?: PDF_LLAMA_SWAP_DEFAULT_URL
    )
    val tamaLlamaSwapUrl = _tamaLlamaSwapUrl.asStateFlow()
    fun setTamaLlamaSwapUrl(url: String) {
        prefs.edit().putString("tama_llama_swap_url", url).apply()
        _tamaLlamaSwapUrl.value = url
    }

    private val _tamaLlamaServerModelLabel = MutableStateFlow(prefs.getString("tama_llama_server_model_label", null))
    val tamaLlamaServerModelLabel = _tamaLlamaServerModelLabel.asStateFlow()
    fun setTamaLlamaServerModelLabel(value: String?) {
        prefs.edit().putString("tama_llama_server_model_label", value).apply()
        _tamaLlamaServerModelLabel.value = value
    }

    private val _tamaLlamaServerContextTokens = MutableStateFlow(prefs.getInt("tama_llama_server_context_tokens", -1))
    val tamaLlamaServerContextTokens = _tamaLlamaServerContextTokens.asStateFlow()
    fun setTamaLlamaServerContextTokens(value: Int?) {
        val normalized = value ?: -1
        prefs.edit().putInt("tama_llama_server_context_tokens", normalized).apply()
        _tamaLlamaServerContextTokens.value = normalized
    }

    private val _tamaLlamaServerContextLabel = MutableStateFlow(prefs.getString("tama_llama_server_context_label", null))
    val tamaLlamaServerContextLabel = _tamaLlamaServerContextLabel.asStateFlow()
    fun setTamaLlamaServerContextLabel(value: String?) {
        prefs.edit().putString("tama_llama_server_context_label", value).apply()
        _tamaLlamaServerContextLabel.value = value
    }

    private val _tamaLiteRtModelId = MutableStateFlow(prefs.getLong("tama_litert_model_id", -1L))
    val tamaLiteRtModelId = _tamaLiteRtModelId.asStateFlow()
    fun setTamaLiteRtModelId(modelId: Long?) {
        prefs.edit().apply {
            if (modelId == null || modelId <= 0L) remove("tama_litert_model_id") else putLong("tama_litert_model_id", modelId)
        }.apply()
        _tamaLiteRtModelId.value = modelId?.takeIf { it > 0L } ?: -1L
    }

    private val _tamaLiteRtBackend = MutableStateFlow(normalizeLiteRtBackend(prefs.getString("tama_litert_backend", LITERT_BACKEND_AUTO)))
    val tamaLiteRtBackend = _tamaLiteRtBackend.asStateFlow()
    fun setTamaLiteRtBackend(backend: String) {
        val normalized = normalizeLiteRtBackend(backend)
        prefs.edit().putString("tama_litert_backend", normalized).apply()
        _tamaLiteRtBackend.value = normalized
    }

    private val _tamaLiteRtMtpEnabled = MutableStateFlow(prefs.getBoolean("tama_litert_mtp_enabled", false))
    val tamaLiteRtMtpEnabled = _tamaLiteRtMtpEnabled.asStateFlow()
    fun setTamaLiteRtMtpEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("tama_litert_mtp_enabled", enabled).apply()
        _tamaLiteRtMtpEnabled.value = enabled
    }

    // Tama Pet LLM Model
    private val _tamaPetModel = MutableStateFlow(prefs.getString("tama_pet_model", "qwen3.5:9b") ?: "qwen3.5:9b")
    val tamaPetModel = _tamaPetModel.asStateFlow()
    fun setTamaPetModel(model: String) {
        prefs.edit().putString("tama_pet_model", model).apply()
        _tamaPetModel.value = model
    }

    // Tama Summarizer LLM Model
    private val _tamaSummarizerModel = MutableStateFlow(prefs.getString("tama_summarizer_model", "qwen3.5:9b") ?: "qwen3.5:9b")
    val tamaSummarizerModel = _tamaSummarizerModel.asStateFlow()
    fun setTamaSummarizerModel(model: String) {
        prefs.edit().putString("tama_summarizer_model", model).apply()
        _tamaSummarizerModel.value = model
    }

    // Tama Pet System Prompt
    private val _tamaPetPrompt = MutableStateFlow(prefs.getString("tama_pet_prompt", null) ?: PromptUtils.getPrompt(context, PromptUtils.Keys.TAMA_PET, FALLBACK_TAMA_PET_PROMPT))
    val tamaPetPrompt = _tamaPetPrompt.asStateFlow()
    fun setTamaPetPrompt(prompt: String) {
        prefs.edit().putString("tama_pet_prompt", prompt).apply()
        _tamaPetPrompt.value = prompt
    }

    // Tama Summarizer System Prompt
    private val _tamaSummarizerPrompt = MutableStateFlow(prefs.getString("tama_summarizer_prompt", null) ?: PromptUtils.getPrompt(context, PromptUtils.Keys.TAMA_SUMMARIZER, FALLBACK_TAMA_SUMMARIZER_PROMPT))
    val tamaSummarizerPrompt = _tamaSummarizerPrompt.asStateFlow()
    fun setTamaSummarizerPrompt(prompt: String) {
        prefs.edit().putString("tama_summarizer_prompt", prompt).apply()
        _tamaSummarizerPrompt.value = prompt
    }

    // ========== Tama Ollama (Isolated) Settings ==========

    private val _tamaOllamaUrl = MutableStateFlow(prefs.getString("tama_ollama_url", AIConstants.Urls.OLLAMA_DEFAULT) ?: AIConstants.Urls.OLLAMA_DEFAULT)
    val tamaOllamaUrl = _tamaOllamaUrl.asStateFlow()
    fun setTamaOllamaUrl(url: String) {
        prefs.edit().putString("tama_ollama_url", url).apply()
        _tamaOllamaUrl.value = url
    }

    private val _tamaOllamaMmap = MutableStateFlow(prefs.getBoolean("tama_ollama_mmap", false))
    val tamaOllamaMmap = _tamaOllamaMmap.asStateFlow()
    fun setTamaOllamaMmap(enabled: Boolean) {
        prefs.edit().putBoolean("tama_ollama_mmap", enabled).apply()
        _tamaOllamaMmap.value = enabled
    }

    private val _tamaOllamaThreads = MutableStateFlow(prefs.getInt("tama_ollama_threads", 4))
    val tamaOllamaThreads = _tamaOllamaThreads.asStateFlow()
    fun setTamaOllamaThreads(count: Int) {
        prefs.edit().putInt("tama_ollama_threads", count).apply()
        _tamaOllamaThreads.value = count
    }

    private val _tamaOllamaNumCtx = MutableStateFlow(prefs.getInt("tama_ollama_num_ctx", 16384))
    val tamaOllamaNumCtx = _tamaOllamaNumCtx.asStateFlow()
    fun setTamaOllamaNumCtx(count: Int) {
        prefs.edit().putInt("tama_ollama_num_ctx", count).apply()
        _tamaOllamaNumCtx.value = count
    }

    // ========== Adventure Dungeon (Isolated) Settings ==========

    // Adventure LLM Model
    private val _adventureModel = MutableStateFlow(prefs.getString("adventure_model", "qwen3.5:9b") ?: "qwen3.5:9b")
    val adventureModel = _adventureModel.asStateFlow()
    fun setAdventureModel(model: String) {
        prefs.edit().putString("adventure_model", model).apply()
        _adventureModel.value = model
    }

    // Adventure Summarizer LLM Model
    private val _adventureSummarizerModel = MutableStateFlow(prefs.getString("adventure_summarizer_model", "qwen3.5:9b") ?: "qwen3.5:9b")
    val adventureSummarizerModel = _adventureSummarizerModel.asStateFlow()
    fun setAdventureSummarizerModel(model: String) {
        prefs.edit().putString("adventure_summarizer_model", model).apply()
        _adventureSummarizerModel.value = model
    }

    // Adventure System Prompt (editable by user)
    private val _adventureSystemPrompt = MutableStateFlow(prefs.getString("adventure_system_prompt", null) ?: FALLBACK_ADVENTURE_SYSTEM_PROMPT)
    val adventureSystemPrompt = _adventureSystemPrompt.asStateFlow()
    fun setAdventureSystemPrompt(prompt: String) {
        prefs.edit().putString("adventure_system_prompt", prompt).apply()
        _adventureSystemPrompt.value = prompt
    }

    // Adventure Summarizer Prompt (editable by user)
    private val _adventureSummarizerPrompt = MutableStateFlow(prefs.getString("adventure_summarizer_prompt", null) ?: FALLBACK_ADVENTURE_SUMMARIZER_PROMPT)
    val adventureSummarizerPrompt = _adventureSummarizerPrompt.asStateFlow()
    fun setAdventureSummarizerPrompt(prompt: String) {
        prefs.edit().putString("adventure_summarizer_prompt", prompt).apply()
        _adventureSummarizerPrompt.value = prompt
    }

    // Adventure Ollama URL (independent from Tama and Agent)
    private val _adventureOllamaUrl = MutableStateFlow(prefs.getString("adventure_ollama_url", AIConstants.Urls.OLLAMA_DEFAULT) ?: AIConstants.Urls.OLLAMA_DEFAULT)
    val adventureOllamaUrl = _adventureOllamaUrl.asStateFlow()
    fun setAdventureOllamaUrl(url: String) {
        prefs.edit().putString("adventure_ollama_url", url).apply()
        _adventureOllamaUrl.value = url
    }

    // Adventure Ollama MMAP
    private val _adventureOllamaMmap = MutableStateFlow(prefs.getBoolean("adventure_ollama_mmap", false))
    val adventureOllamaMmap = _adventureOllamaMmap.asStateFlow()
    fun setAdventureOllamaMmap(enabled: Boolean) {
        prefs.edit().putBoolean("adventure_ollama_mmap", enabled).apply()
        _adventureOllamaMmap.value = enabled
    }

    // Adventure Ollama Threads
    private val _adventureOllamaThreads = MutableStateFlow(prefs.getInt("adventure_ollama_threads", 4))
    val adventureOllamaThreads = _adventureOllamaThreads.asStateFlow()
    fun setAdventureOllamaThreads(count: Int) {
        prefs.edit().putInt("adventure_ollama_threads", count).apply()
        _adventureOllamaThreads.value = count
    }

    // Adventure Ollama Context Size
    private val _adventureOllamaNumCtx = MutableStateFlow(prefs.getInt("adventure_ollama_num_ctx", 16384))
    val adventureOllamaNumCtx = _adventureOllamaNumCtx.asStateFlow()
    fun setAdventureOllamaNumCtx(count: Int) {
        prefs.edit().putInt("adventure_ollama_num_ctx", count).apply()
        _adventureOllamaNumCtx.value = count
    }

    // Adventure Story Language
    private val _adventureLanguage = MutableStateFlow(prefs.getString("adventure_language", "English") ?: "English")
    val adventureLanguage = _adventureLanguage.asStateFlow()
    fun setAdventureLanguage(language: String) {
        prefs.edit().putString("adventure_language", language).apply()
        _adventureLanguage.value = language
    }

    private val _adventureBackend = MutableStateFlow(
        normalizeOllamaOrLlamaBackend(prefs.getString("adventure_backend", PDF_BACKEND_OLLAMA))
    )
    val adventureBackend = _adventureBackend.asStateFlow()
    fun setAdventureBackend(backend: String) {
        val normalized = normalizeOllamaOrLlamaBackend(backend)
        prefs.edit().putString("adventure_backend", normalized).apply()
        _adventureBackend.value = normalized
    }

    private val _adventureLlamaServerUrl = MutableStateFlow(
        prefs.getString("adventure_llama_server_url", PDF_LLAMA_SERVER_DEFAULT_URL) ?: PDF_LLAMA_SERVER_DEFAULT_URL
    )
    val adventureLlamaServerUrl = _adventureLlamaServerUrl.asStateFlow()
    fun setAdventureLlamaServerUrl(url: String) {
        prefs.edit().putString("adventure_llama_server_url", url).apply()
        _adventureLlamaServerUrl.value = url
    }

    private val _adventureLlamaSwapUrl = MutableStateFlow(
        prefs.getString("adventure_llama_swap_url", PDF_LLAMA_SWAP_DEFAULT_URL) ?: PDF_LLAMA_SWAP_DEFAULT_URL
    )
    val adventureLlamaSwapUrl = _adventureLlamaSwapUrl.asStateFlow()
    fun setAdventureLlamaSwapUrl(url: String) {
        prefs.edit().putString("adventure_llama_swap_url", url).apply()
        _adventureLlamaSwapUrl.value = url
    }

    private val _adventureLlamaServerModelLabel = MutableStateFlow(
        prefs.getString("adventure_llama_server_model_label", null)
    )
    val adventureLlamaServerModelLabel = _adventureLlamaServerModelLabel.asStateFlow()
    fun setAdventureLlamaServerModelLabel(label: String?) {
        prefs.edit().putString("adventure_llama_server_model_label", label).apply()
        _adventureLlamaServerModelLabel.value = label
    }

    private val _adventureLlamaServerContextTokens = MutableStateFlow(
        prefs.getInt("adventure_llama_server_context_tokens", -1)
    )
    val adventureLlamaServerContextTokens = _adventureLlamaServerContextTokens.asStateFlow()
    fun setAdventureLlamaServerContextTokens(tokens: Int?) {
        val normalized = tokens ?: -1
        prefs.edit().putInt("adventure_llama_server_context_tokens", normalized).apply()
        _adventureLlamaServerContextTokens.value = normalized
    }

    private val _adventureLlamaServerContextLabel = MutableStateFlow(
        prefs.getString("adventure_llama_server_context_label", null)
    )
    val adventureLlamaServerContextLabel = _adventureLlamaServerContextLabel.asStateFlow()
    fun setAdventureLlamaServerContextLabel(label: String?) {
        prefs.edit().putString("adventure_llama_server_context_label", label).apply()
        _adventureLlamaServerContextLabel.value = label
    }

    private val _adventureLiteRtModelId = MutableStateFlow(prefs.getLong("adventure_litert_model_id", -1L))
    val adventureLiteRtModelId = _adventureLiteRtModelId.asStateFlow()
    fun setAdventureLiteRtModelId(modelId: Long?) {
        prefs.edit().apply {
            if (modelId == null || modelId <= 0L) remove("adventure_litert_model_id") else putLong("adventure_litert_model_id", modelId)
        }.apply()
        _adventureLiteRtModelId.value = modelId?.takeIf { it > 0L } ?: -1L
    }

    private val _adventureLiteRtBackend = MutableStateFlow(normalizeLiteRtBackend(prefs.getString("adventure_litert_backend", LITERT_BACKEND_AUTO)))
    val adventureLiteRtBackend = _adventureLiteRtBackend.asStateFlow()
    fun setAdventureLiteRtBackend(backend: String) {
        val normalized = normalizeLiteRtBackend(backend)
        prefs.edit().putString("adventure_litert_backend", normalized).apply()
        _adventureLiteRtBackend.value = normalized
    }

    private val _adventureLiteRtMtpEnabled = MutableStateFlow(prefs.getBoolean("adventure_litert_mtp_enabled", false))
    val adventureLiteRtMtpEnabled = _adventureLiteRtMtpEnabled.asStateFlow()
    fun setAdventureLiteRtMtpEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("adventure_litert_mtp_enabled", enabled).apply()
        _adventureLiteRtMtpEnabled.value = enabled
    }

    private val _adventureWorldImageEnabled = MutableStateFlow(
        prefs.getBoolean("adventure_world_image_enabled", false)
    )
    val adventureWorldImageEnabled = _adventureWorldImageEnabled.asStateFlow()
    fun setAdventureWorldImageEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("adventure_world_image_enabled", enabled).apply()
        _adventureWorldImageEnabled.value = enabled
    }

    private val _adventureStageImagesEnabled = MutableStateFlow(
        prefs.getBoolean("adventure_stage_images_enabled", false)
    )
    val adventureStageImagesEnabled = _adventureStageImagesEnabled.asStateFlow()
    fun setAdventureStageImagesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("adventure_stage_images_enabled", enabled).apply()
        _adventureStageImagesEnabled.value = enabled
    }

    private val _adventureOnnxModelFilename = MutableStateFlow(
        prefs.getString("adventure_onnx_model_filename", null)
    )
    val adventureOnnxModelFilename = _adventureOnnxModelFilename.asStateFlow()
    fun setAdventureOnnxModelFilename(filename: String?) {
        prefs.edit().putString("adventure_onnx_model_filename", filename).apply()
        _adventureOnnxModelFilename.value = filename
    }

    private val _adventureOnnxSteps = MutableStateFlow(
        prefs.getInt("adventure_onnx_steps", 20).coerceAtLeast(1)
    )
    val adventureOnnxSteps = _adventureOnnxSteps.asStateFlow()
    fun setAdventureOnnxSteps(steps: Int) {
        val normalized = steps.coerceAtLeast(1)
        prefs.edit().putInt("adventure_onnx_steps", normalized).apply()
        _adventureOnnxSteps.value = normalized
    }

    private val _adventureOnnxCfg = MutableStateFlow(
        prefs.getFloat("adventure_onnx_cfg", 6.5f).coerceIn(1f, 20f)
    )
    val adventureOnnxCfg = _adventureOnnxCfg.asStateFlow()
    fun setAdventureOnnxCfg(cfg: Float) {
        val normalized = cfg.coerceIn(1f, 20f)
        prefs.edit().putFloat("adventure_onnx_cfg", normalized).apply()
        _adventureOnnxCfg.value = normalized
    }

    private val _adventureOnnxResolution = MutableStateFlow(
        prefs.getInt("adventure_onnx_resolution", TamaPicGenDefaults.DEFAULT_RESOLUTION).coerceAtLeast(256)
    )
    val adventureOnnxResolution = _adventureOnnxResolution.asStateFlow()
    fun setAdventureOnnxResolution(resolution: Int) {
        val normalized = resolution.coerceAtLeast(256)
        prefs.edit().putInt("adventure_onnx_resolution", normalized).apply()
        _adventureOnnxResolution.value = normalized
    }

    /** Durable local drafts for the native Stable Diffusion image and video forms. */
    fun imageGenerationDraft(): JSONObject? = readJsonDraft("sd_image_generation_draft")
    fun setImageGenerationDraft(draft: JSONObject) = saveJsonDraft("sd_image_generation_draft", draft)
    fun videoGenerationDraft(): JSONObject? = readJsonDraft("sd_video_generation_draft")
    fun setVideoGenerationDraft(draft: JSONObject) = saveJsonDraft("sd_video_generation_draft", draft)

    private fun readJsonDraft(key: String): JSONObject? = prefs.getString(key, null)
        ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }

    private fun saveJsonDraft(key: String, draft: JSONObject) {
        prefs.edit().putString(key, draft.toString()).apply()
    }

}
