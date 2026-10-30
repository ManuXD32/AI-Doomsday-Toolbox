package com.example.llamadroid.service.chat

import android.content.Context
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.service.LlamaServerSessionStatus
import com.example.llamadroid.service.ManagedLlamaServerCoordinator
import com.example.llamadroid.service.normalizePhysicalEndpoint
import com.example.llamadroid.service.ram.LlamaRamGate
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Turns "this chat wants to generate on this server" into an [AdmissionTarget]: which resource it
 * shares, how many sessions that resource runs at once, and (for a managed local server that is
 * not running yet) the RAM it would take to start.
 */
internal class ChatAdmissionPlanner(
    private val context: Context,
    private val database: AppDatabase,
    val ramGate: LlamaRamGate = LlamaRamGate(context)
) {
    private val slots = SlotCapacityCache(fetch = ::fetchTotalSlots)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    suspend fun targetFor(chatId: Long, server: LlamaServerEntity): AdmissionTarget {
        if (server.isLiteRtEngine()) {
            return AdmissionTarget(chatId, AdmissionKind.ENGINE, LITERT_KEY, server.name.ifBlank { "LiteRT" })
        }
        if (!server.isLlamaServerEngine()) {
            return AdmissionTarget(
                chatId, AdmissionKind.UNLIMITED, "other:${server.id}", server.name,
                capacity = Int.MAX_VALUE
            )
        }
        val cardId = server.managedServerCardId
        if (cardId != null) {
            return managedTarget(chatId, server, cardId)
        }
        val baseUrl = server.baseUrl()
        val key = normalizePhysicalEndpoint(baseUrl)
        val capacity = slots.capacity(key, baseUrl) ?: 1
        return AdmissionTarget(chatId, AdmissionKind.ENDPOINT, key, server.name, capacity)
    }

    private suspend fun managedTarget(chatId: Long, server: LlamaServerEntity, cardId: Long): AdmissionTarget {
        val key = "card:$cardId"
        return try {
            val info = ManagedLlamaServerCoordinator(context, database).catalog().firstOrNull { it.card.id == cardId }
            val profile = info?.profile
            val configuredSlots = (profile?.parallel ?: 1).coerceAtLeast(1)
            val running = info?.status == LlamaServerSessionStatus.RUNNING
            if (running) {
                val url = info?.baseUrl
                val capacity = url?.let { slots.capacity(normalizePhysicalEndpoint(it), it) } ?: configuredSlots
                AdmissionTarget(chatId, AdmissionKind.ENDPOINT, key, server.name, capacity)
            } else if (info?.status == LlamaServerSessionStatus.STARTING || info?.status == LlamaServerSessionStatus.LOADING) {
                // Already coming up (its memory is being claimed): no extra RAM gate.
                AdmissionTarget(chatId, AdmissionKind.ENDPOINT, key, server.name, configuredSlots)
            } else {
                val estimate = if (profile != null && profile.modelPath.isNotBlank() && File(profile.modelPath).isFile) {
                    withContext(Dispatchers.IO) { ramGate.estimate(profile) }
                } else {
                    null
                }
                AdmissionTarget(chatId, AdmissionKind.ENDPOINT, key, server.name, configuredSlots, estimate)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DebugLog.log("ChatAdmissionPlanner: managed card $cardId planning failed (${error.javaClass.simpleName})")
            AdmissionTarget(chatId, AdmissionKind.ENDPOINT, key, server.name, 1)
        }
    }

    private suspend fun fetchTotalSlots(baseUrl: String): Int? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(baseUrl.trimEnd('/') + "/props").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            SlotCapacityCache.parseTotalSlots(response.body?.string().orEmpty())
        }
    }

    companion object {
        const val LITERT_KEY = "litert"
    }
}
