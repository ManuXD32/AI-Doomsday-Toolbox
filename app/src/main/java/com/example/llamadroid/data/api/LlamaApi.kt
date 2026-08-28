package com.example.llamadroid.data.api

import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Streaming
import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

interface LlamaApi {
    @Streaming
    @POST("v1/chat/completions")
    fun chatCompletion(@Body request: LlamaChatRequest): Call<ResponseBody>

    @GET("v1/models")
    fun getModels(): Call<LlamaModelsResponse>
}

@Keep
data class LlamaChatRequest(
    @SerializedName("model") val model: String,
    @SerializedName("messages") val messages: List<LlamaChatMessage>,
    @SerializedName("stream") val stream: Boolean = true,
    @SerializedName("stream_options") val streamOptions: LlamaStreamOptions? = null,
    @SerializedName("temperature") val temperature: Float? = null,
    @SerializedName("top_p") val top_p: Float? = null,
    @SerializedName("top_k") val top_k: Int? = null,
    @SerializedName("min_p") val min_p: Float? = null,
    @SerializedName("seed") val seed: Int? = null,
    @SerializedName("repeat_penalty") val repeat_penalty: Float? = null,
    @SerializedName("frequency_penalty") val frequency_penalty: Float? = null,
    @SerializedName("presence_penalty") val presence_penalty: Float? = null,
    @SerializedName("max_tokens") val max_tokens: Int? = null,
    @SerializedName("n_predict") val n_predict: Int? = null,
    @SerializedName("return_progress") val returnProgress: Boolean = true,
    @SerializedName("sse_ping_interval") val ssePingInterval: Int = 2,
    @SerializedName("stop") val stop: List<String>? = null,
    @SerializedName("chat_template_kwargs") val chat_template_kwargs: Map<String, Any>? = null
)

@Keep
data class LlamaStreamOptions(
    @SerializedName("include_usage") val includeUsage: Boolean = true
)

@Keep
data class LlamaChatMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: Any // String or List<Map> for multimodal turns
)

@Keep
data class LlamaChatResponse(
    @SerializedName("choices") val choices: List<LlamaChatChoice>,
    @SerializedName("usage") val usage: LlamaChatUsage? = null,
    @SerializedName("prompt_progress") val promptProgress: LlamaPromptProgress? = null
)

@Keep
data class LlamaPromptProgress(
    @SerializedName("total") val total: Int = 0,
    @SerializedName("cache") val cached: Int = 0,
    @SerializedName("processed") val processed: Int = 0,
    @SerializedName("time_ms") val timeMs: Long = 0L
)

@Keep
data class LlamaChatChoice(
    @SerializedName("delta") val delta: LlamaChatDelta? = null,
    @SerializedName("message") val message: LlamaChatDelta? = null,
    @SerializedName("finish_reason") val finish_reason: String? = null
)

@Keep
data class LlamaChatDelta(
    @SerializedName("role") val role: String? = null,
    @SerializedName("content") val content: String? = null,
    @SerializedName("reasoning_content") val reasoningContent: String? = null,
    @SerializedName("thinking") val thinking: String? = null
)

@Keep
data class LlamaChatUsage(
    @SerializedName("prompt_tokens") val promptTokens: Int = 0,
    @SerializedName("completion_tokens") val completionTokens: Int = 0,
    @SerializedName("total_tokens") val totalTokens: Int = 0
)

@Keep
data class LlamaModelsResponse(
    @SerializedName("data") val data: List<LlamaModelInfo> = emptyList()
)

@Keep
data class LlamaModelInfo(
    @SerializedName("id") val id: String,
    @SerializedName("object") val objectType: String? = null
)
