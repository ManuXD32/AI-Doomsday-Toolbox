package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessRpcError
import com.example.llamadroid.harness.client.HarnessRpcResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

data class HarnessProviderAuthMethodUi(
    val id: String,
    val label: String
)

data class HarnessProviderAuthOptionUi(
    val id: String,
    val label: String,
    val description: String? = null
)

data class HarnessProviderAuthPromptUi(
    val kind: String,
    val message: String,
    val placeholder: String? = null,
    val options: List<HarnessProviderAuthOptionUi> = emptyList()
)

data class HarnessProviderAuthNoticeUi(
    val message: String,
    val url: String? = null,
    val code: String? = null
)

/** Redacted provider login state returned by the official authorization broker. */
data class HarnessProviderAuthUi(
    val methods: List<HarnessProviderAuthMethodUi> = emptyList(),
    val configured: Boolean = false,
    val busy: Boolean = false,
    val requestId: String? = null,
    val status: String? = null,
    val notices: List<HarnessProviderAuthNoticeUi> = emptyList(),
    val prompt: HarnessProviderAuthPromptUi? = null,
    val errorCode: String? = null
)

/**
 * Adapter for the release authorization seam. All provider-specific work stays
 * in DSH's authorization registry; Android only renders bounded notices and
 * submits answers to the request id returned by that registry.
 */
data class NativeHarnessProviderAuthHooks(
    val describe: suspend (providerIds: List<String>) -> Map<String, HarnessProviderAuthUi> =
        { _: List<String> -> emptyMap() },
    val login: suspend (providerId: String, method: String) -> HarnessRpcResult =
        { _: String, _: String -> unavailableProviderAuth() },
    val status: suspend (providerId: String, requestId: String) -> HarnessRpcResult =
        { _: String, _: String -> unavailableProviderAuth() },
    val answer: suspend (
        providerId: String,
        requestId: String,
        answer: String?,
        declined: Boolean
    ) -> HarnessRpcResult = { _: String, _: String, _: String?, _: Boolean -> unavailableProviderAuth() },
    val cancel: suspend (providerId: String, requestId: String) -> HarnessRpcResult =
        { _: String, _: String -> unavailableProviderAuth() },
    val logout: suspend (providerId: String) -> HarnessRpcResult =
        { _: String -> unavailableProviderAuth() },
    /** Opens a broker supplied authorization URL after the route validates it. */
    val openAuthorizationUrl: suspend (url: String) -> Unit = { _: String -> }
)

private fun unavailableProviderAuth(): HarnessRpcResult = HarnessRpcResult.Failure(
    HarnessRpcError("PROVIDER_AUTH_UNAVAILABLE", "The provider authorization flow is not connected")
)

internal fun parseHarnessProviderAuthView(
    value: JsonElement,
    previous: HarnessProviderAuthUi = HarnessProviderAuthUi()
): HarnessProviderAuthUi? {
    val objectValue = value.jsonObjectOrNull() ?: return null
    val methods = objectValue["methods"]?.jsonArrayOrNull()?.mapNotNull { method ->
        val item = method.jsonObjectOrNull() ?: return@mapNotNull null
        val id = item.string("id") ?: return@mapNotNull null
        HarnessProviderAuthMethodUi(id, item.string("label") ?: id)
    } ?: previous.methods
    val notices = objectValue["notices"]?.jsonArrayOrNull()?.mapNotNull { notice ->
        val item = notice.jsonObjectOrNull() ?: return@mapNotNull null
        val message = item.string("message") ?: return@mapNotNull null
        HarnessProviderAuthNoticeUi(message, item.string("url"), item.string("code"))
    } ?: previous.notices
    val prompt = objectValue["prompt"]?.jsonObjectOrNull()?.let { item ->
        val message = item.string("message") ?: return@let null
        HarnessProviderAuthPromptUi(
            kind = item.string("kind") ?: "text",
            message = message,
            placeholder = item.string("placeholder"),
            options = item["options"]?.jsonArrayOrNull()?.mapNotNull { option ->
                val optionObject = option.jsonObjectOrNull() ?: return@mapNotNull null
                val id = optionObject.string("id") ?: return@mapNotNull null
                HarnessProviderAuthOptionUi(
                    id = id,
                    label = optionObject.string("label") ?: id,
                    description = optionObject.string("description")
                )
            }.orEmpty()
        )
    } ?: if ("prompt" in objectValue) null else previous.prompt
    return previous.copy(
        methods = methods,
        configured = objectValue.boolean("configured") ?: previous.configured,
        busy = objectValue.boolean("busy") ?: previous.busy,
        requestId = objectValue.string("requestId") ?: if ("requestId" in objectValue) null else previous.requestId,
        status = objectValue.string("status") ?: if ("status" in objectValue) null else previous.status,
        notices = notices,
        prompt = prompt,
        errorCode = objectValue.string("errorCode") ?: if ("errorCode" in objectValue) null else previous.errorCode
    )
}

internal fun parseHarnessProviderAuthDescribe(
    value: JsonElement
): Map<String, HarnessProviderAuthUi> = value.jsonObjectOrNull()?.mapNotNull { (providerId, item) ->
    parseHarnessProviderAuthView(item)?.let { providerId to it }
}.orEmpty().toMap()

/** Owns polling so the main controller remains focused on session/stream state. */
internal class NativeHarnessProviderAuthActions(
    private val scope: CoroutineScope,
    private val hooks: NativeHarnessProviderAuthHooks,
    private val refresh: suspend () -> Unit,
    private val update: suspend (providerId: String, auth: HarnessProviderAuthUi) -> Unit,
    private val reportFailure: suspend (String, String) -> Unit
) {
    private val activeViews = mutableMapOf<String, HarnessProviderAuthUi>()
    private val pollJobs = mutableMapOf<String, Job>()
    private val openedUrls = mutableMapOf<String, MutableSet<String>>()
    private val mapLock = Any()

    suspend fun describe(providerIds: List<String>): Map<String, HarnessProviderAuthUi> {
        val described = hooks.describe(providerIds)
        val active = synchronized(mapLock) { activeViews.toMap() }
        return described.mapValues { (providerId, auth) ->
            active[providerId]?.let { current ->
                auth.copy(
                    requestId = current.requestId,
                    status = current.status,
                    notices = current.notices,
                    prompt = current.prompt,
                    errorCode = current.errorCode,
                    busy = auth.busy || current.status == "pending"
                )
            } ?: auth
        }
    }

    suspend fun login(providerId: String, method: String) {
        when (val result = hooks.login(providerId, method)) {
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> {
                val view = parseHarnessProviderAuthView(result.value)
                if (view != null) {
                    apply(providerId, view)
                    view.requestId?.let { startPolling(providerId, it) }
                }
                refresh()
            }
        }
    }

    suspend fun answer(providerId: String, requestId: String, answer: String, declined: Boolean) {
        when (val result = hooks.answer(providerId, requestId, answer.take(16_384), declined)) {
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> applyResult(providerId, requestId, result)
        }
    }

    suspend fun cancel(providerId: String, requestId: String) {
        when (val result = hooks.cancel(providerId, requestId)) {
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> applyResult(providerId, requestId, result)
        }
    }

    suspend fun logout(providerId: String) {
        cancelPolling(providerId)
        when (val result = hooks.logout(providerId)) {
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> {
                synchronized(mapLock) { activeViews.remove(providerId) }
                refresh()
            }
        }
    }

    fun close() {
        val jobs = synchronized(mapLock) {
            val current = pollJobs.values.toList()
            pollJobs.clear()
            activeViews.clear()
            openedUrls.clear()
            current
        }
        jobs.forEach { it.cancel() }
    }

    private suspend fun applyResult(
        providerId: String,
        requestId: String,
        result: HarnessRpcResult.Success
    ) {
        val previous = synchronized(mapLock) { activeViews[providerId] }
            ?.takeIf { it.requestId == requestId }
            ?: HarnessProviderAuthUi(requestId = requestId)
        val view = parseHarnessProviderAuthView(result.value, previous) ?: previous
        apply(providerId, view)
        if (view.status == "pending") startPolling(providerId, requestId) else cancelPolling(providerId)
        if (view.status != "pending") refresh()
    }

    private suspend fun apply(providerId: String, view: HarnessProviderAuthUi) {
        val urls = synchronized(mapLock) {
            activeViews[providerId] = view
            view.requestId?.let { requestId ->
                val seen = openedUrls.getOrPut(requestId) { mutableSetOf() }
                view.notices.mapNotNull { it.url }.filter { seen.add(it) }
            }.orEmpty()
        }
        urls.forEach { url ->
            try {
                hooks.openAuthorizationUrl(url)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                reportFailure("PROVIDER_AUTH_URL_FAILED", "Authorization browser could not be opened")
            }
        }
        update(providerId, view)
    }

    private fun startPolling(providerId: String, requestId: String) {
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                while (isActive) {
                    delay(350L)
                    val result = try {
                        hooks.status(providerId, requestId)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        reportFailure(
                            "PROVIDER_AUTH_STATUS_FAILED",
                            error.message ?: "Provider authorization status could not be read"
                        )
                        break
                    }
                    when (result) {
                        is HarnessRpcResult.Failure -> {
                            reportFailure(result.error.code, result.error.message)
                            break
                        }
                        is HarnessRpcResult.Success -> {
                            val previous = synchronized(mapLock) { activeViews[providerId] }
                                ?.takeIf { it.requestId == requestId }
                                ?: HarnessProviderAuthUi(requestId = requestId)
                            val view = parseHarnessProviderAuthView(result.value, previous) ?: previous
                            apply(providerId, view)
                            if (view.status != "pending") {
                                refresh()
                                break
                            }
                        }
                    }
                }
            } finally {
                synchronized(mapLock) {
                    if (pollJobs[providerId] === coroutineContext[Job]) pollJobs.remove(providerId)
                }
            }
        }
        val previous = synchronized(mapLock) { pollJobs.put(providerId, job) }
        previous?.cancel()
        job.start()
    }

    private fun cancelPolling(providerId: String) {
        val job = synchronized(mapLock) { pollJobs.remove(providerId) }
        job?.cancel()
    }
}
