package com.example.llamadroid.service.vision

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.data.model.LlamaMessageImageDescriptionEntity
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.model.LlamaServerSessionIds
import com.example.llamadroid.service.ManagedLlamaServerCoordinator
import com.example.llamadroid.service.ephemeral.EphemeralLlamaServer
import com.example.llamadroid.service.fileToDataUrl
import com.example.llamadroid.service.inferImageMimeType
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Describes chat images in text so a model without vision can still use them. A temporary
 * low-impact server (the user's chosen vision card) is started only when images are pending and
 * free RAM allows it, handles all pending images in one start and is always stopped afterwards.
 * Only metadata is logged; descriptions are stored in Room, never logged.
 */
object VisionDescriptionService {
    private const val TAG = "VisionHelper"
    private const val CONTEXT_TOKENS = 4096

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .callTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    /**
     * Returns messageId to description for every image message of [chatId] that has one, creating
     * missing ones when the helper is enabled and [server] cannot see images. Never throws for helper
     * problems (the turn then just runs without descriptions).
     */
    suspend fun ensureDescriptions(
        context: Context,
        chatId: Long,
        server: LlamaServerEntity,
        onStatus: (String) -> Unit = {}
    ): Map<Long, String> {
        val app = context.applicationContext
        val db = AppDatabase.getDatabase(app)
        val dao = db.llamaMessageImageDescriptionDao()
        val messages = db.llamaMessageDao().getMessagesOnce(chatId).filter { !it.isError && ImageFallbackLogic.hasImage(it) }
        if (messages.isEmpty()) return emptyMap()
        var stored = dao.getMany(messages.map { it.id }).associateBy { it.messageId }
        fun current() = stored.filterValues { row -> messages.any { it.id == row.messageId && it.imagePath == row.imagePath } }
            .mapValues { it.value.description }

        val settings = VisionHelperSettings(app)
        if (server.supportsVision || !settings.enabled || settings.serverRowId <= 0L) return current()
        val pending = ImageFallbackLogic.pending(messages, stored.mapValues { it.value.imagePath })
            .filter { File(it.imagePath.orEmpty()).isFile }
        if (pending.isEmpty()) return current()

        try {
            val info = ManagedLlamaServerCoordinator(app, db).catalog().firstOrNull { it.card.id == settings.serverRowId }
            val profile = info?.profile
            val modelPath = profile?.modelPath?.takeIf { File(it).isFile }
            val mmprojPath = profile?.mmprojPath?.takeIf { it.isNotBlank() && File(it).isFile }
            if (info == null || info.errorCode != null || profile == null || modelPath == null || mmprojPath == null) {
                DebugLog.log("$TAG: helper card unavailable")
                return current()
            }
            val ram = EphemeralLlamaServer.ramDecision(app, File(modelPath).length(), File(mmprojPath).length())
            if (!ram.ok) {
                onStatus(app.getString(R.string.vision_helper_status_waiting_ram))
                return current()
            }
            onStatus(app.getString(R.string.vision_helper_status_describing))
            val launch = EphemeralLlamaServer.lowImpactProfile(profile, modelPath, mmprojPath, CONTEXT_TOKENS)
            val created = EphemeralLlamaServer.run(app, LlamaServerSessionIds.VISION_HELPER, launch) { baseUrl ->
                buildList {
                    for (message in pending) {
                        val row = describeOne(baseUrl, message, info.card.name, settings.thinking) ?: continue
                        dao.insert(row)
                        add(row)
                    }
                }
            }
            stored = stored + created.associateBy { it.messageId }
        } catch (error: Exception) {
            if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
            DebugLog.log("$TAG: helper failed (${error.javaClass.simpleName})")
        }
        return current()
    }

    private suspend fun describeOne(
        baseUrl: String,
        message: LlamaMessageEntity,
        modelName: String,
        thinking: Boolean
    ): LlamaMessageImageDescriptionEntity? {
        val path = message.imagePath ?: return null
        val dataUrl = withContext(Dispatchers.IO) { fileToDataUrl(path, inferImageMimeType(path)) }
        val description = describeDataUrl(baseUrl, dataUrl, thinking) ?: return null
        return LlamaMessageImageDescriptionEntity(
            messageId = message.id,
            imagePath = path,
            description = description,
            model = modelName
        )
    }

    /** Shared by chats and the harness: one image request to a running helper server. Null on failure. */
    private suspend fun describeDataUrl(baseUrl: String, dataUrl: String, thinking: Boolean): String? {
        val body = withContext(Dispatchers.IO) { ImageFallbackLogic.buildRequestBody(dataUrl, thinking) }
        val response = try {
            post(baseUrl, body)
        } catch (error: java.io.IOException) {
            DebugLog.log("$TAG: image request failed (${error.javaClass.simpleName})")
            return null
        }
        return ImageFallbackLogic.parseDescription(response)
    }

    /** Whether the helper is switched on and a card is chosen (cheap; no file or RAM checks). */
    fun isConfigured(context: Context): Boolean {
        val settings = VisionHelperSettings(context.applicationContext)
        return settings.enabled && settings.serverRowId > 0L
    }

    /** Outcome of describing raw data URLs outside any chat. */
    internal sealed interface DataUrlOutcome {
        /** One entry per input URL, null where that image got no usable description. */
        data class Done(val texts: List<String?>) : DataUrlOutcome
        /** [reason] is a short English reason fit for model-facing text. */
        data class Unavailable(val reason: String) : DataUrlOutcome
    }

    internal const val REASON_NOT_SET_UP = "no vision helper is set up"
    internal const val REASON_NO_RAM = "not enough free RAM next to the loaded model"
    internal const val REASON_FAILED = "the helper failed"

    /**
     * Runs the helper once (a single temporary server start) over [dataUrls]. Never throws for helper
     * problems; cancellation propagates.
     */
    internal suspend fun describeDataUrls(context: Context, dataUrls: List<String>): DataUrlOutcome {
        if (dataUrls.isEmpty()) return DataUrlOutcome.Done(emptyList())
        val app = context.applicationContext
        val settings = VisionHelperSettings(app)
        if (!settings.enabled || settings.serverRowId <= 0L) return DataUrlOutcome.Unavailable(REASON_NOT_SET_UP)
        return try {
            val db = AppDatabase.getDatabase(app)
            val info = ManagedLlamaServerCoordinator(app, db).catalog().firstOrNull { it.card.id == settings.serverRowId }
            val profile = info?.profile
            val modelPath = profile?.modelPath?.takeIf { File(it).isFile }
            val mmprojPath = profile?.mmprojPath?.takeIf { it.isNotBlank() && File(it).isFile }
            if (info == null || info.errorCode != null || profile == null || modelPath == null || mmprojPath == null) {
                DebugLog.log("$TAG: helper card unavailable")
                return DataUrlOutcome.Unavailable(REASON_NOT_SET_UP)
            }
            val ram = EphemeralLlamaServer.ramDecision(app, File(modelPath).length(), File(mmprojPath).length())
            if (!ram.ok) return DataUrlOutcome.Unavailable(REASON_NO_RAM)
            val launch = EphemeralLlamaServer.lowImpactProfile(profile, modelPath, mmprojPath, CONTEXT_TOKENS)
            val texts = EphemeralLlamaServer.run(app, LlamaServerSessionIds.VISION_HELPER, launch) { baseUrl ->
                dataUrls.map { describeDataUrl(baseUrl, it, settings.thinking) }
            }
            DataUrlOutcome.Done(texts)
        } catch (error: Exception) {
            if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
            DebugLog.log("$TAG: helper failed (${error.javaClass.simpleName})")
            DataUrlOutcome.Unavailable(REASON_FAILED)
        }
    }

    private suspend fun post(baseUrl: String, body: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/v1/chat/completions")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }
}
