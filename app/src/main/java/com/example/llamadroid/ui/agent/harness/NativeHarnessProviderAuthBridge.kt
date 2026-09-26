package com.example.llamadroid.ui.agent.harness

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.example.llamadroid.harness.client.HarnessCallPolicy
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URI

/** Android adapter for the authenticated `adt/providerAuth` broker route. */
internal fun nativeHarnessProviderAuthHooks(
    clientProvider: () -> HarnessClient?,
    context: Context
): NativeHarnessProviderAuthHooks = NativeHarnessProviderAuthHooks(
    describe = { providerIds ->
        val client = clientProvider()
        if (client == null) {
            providerIds.associateWith {
                unavailableProviderAuthUi(
                    "HARNESS_NOT_RUNNING",
                    "The shared Harness runtime is not running",
                )
            }
        } else {
            when (val result = client.call("adt", "providerAuth", buildJsonObject {
                put("operation", "describe")
                putJsonArray("providerIds") { providerIds.forEach { add(JsonPrimitive(it)) } }
            }, HarnessCallPolicy.SafeRead)) {
                is HarnessRpcResult.Success -> parseHarnessProviderAuthDescribe(result.value)
                is HarnessRpcResult.Failure -> providerIds.associateWith {
                    unavailableProviderAuthUi(result.error.code, result.error.message)
                }
            }
        }
    },
    login = { providerId, method ->
        callProviderAuth(clientProvider, buildJsonObject {
            put("operation", "login")
            put("providerId", providerId)
            put("method", method)
        })
    },
    status = { providerId, requestId ->
        callProviderAuth(clientProvider, buildJsonObject {
            put("operation", "status")
            put("providerId", providerId)
            put("requestId", requestId)
        })
    },
    answer = { providerId, requestId, answer, declined ->
        callProviderAuth(clientProvider, buildJsonObject {
            put("operation", "answer")
            put("providerId", providerId)
            put("requestId", requestId)
            if (declined) put("decline", true) else put("answer", answer.orEmpty())
        })
    },
    cancel = { providerId, requestId ->
        callProviderAuth(clientProvider, buildJsonObject {
            put("operation", "cancel")
            put("providerId", providerId)
            put("requestId", requestId)
        })
    },
    logout = { providerId ->
        callProviderAuth(clientProvider, buildJsonObject {
            put("operation", "logout")
            put("providerId", providerId)
        })
    },
    openAuthorizationUrl = { rawUrl -> openHarnessAuthorizationUrl(context, rawUrl) }
)

private fun unavailableProviderAuthUi(code: String, message: String): HarnessProviderAuthUi =
    HarnessProviderAuthUi(
        errorCode = code,
        notices = listOf(HarnessProviderAuthNoticeUi(message = message, code = code)),
    )

private suspend fun callProviderAuth(
    clientProvider: () -> HarnessClient?,
    args: kotlinx.serialization.json.JsonObject
): HarnessRpcResult = clientProvider()?.call("adt", "providerAuth", args, HarnessCallPolicy.NoRetry)
    ?: HarnessRpcResult.Failure(
        com.example.llamadroid.harness.client.HarnessRpcError(
            "HARNESS_NOT_RUNNING",
            "The shared Harness runtime is not running"
        )
    )

private suspend fun openHarnessAuthorizationUrl(context: Context, rawUrl: String) {
    val parsed = runCatching { URI(rawUrl) }.getOrNull() ?: error("PROVIDER_AUTH_URL_INVALID")
    val scheme = parsed.scheme?.lowercase()
    val host = parsed.host?.lowercase()
    val loopback = host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
    require(parsed.userInfo == null && (scheme == "https" || (scheme == "http" && loopback))) {
        "PROVIDER_AUTH_URL_UNSAFE"
    }
    withContext(Dispatchers.Main) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, rawUrl.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
