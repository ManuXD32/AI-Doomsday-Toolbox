package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessCallPolicy
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcResult
import kotlinx.serialization.json.JsonElement

/** Native bridge for the bundled permission-presets Remote and its command. */
internal class NativeHarnessPermissionActions(
    private val clientProvider: () -> HarnessClient?,
    private val selectedSessionProvider: () -> String?,
    private val mutate: suspend ((NativeHarnessUiState) -> NativeHarnessUiState) -> Unit,
    private val reportFailure: suspend (String, String) -> Unit
) {
    suspend fun refresh(report: Boolean) {
        val client = clientProvider()
        if (client == null) {
            mutate { current -> current.copy(permission = current.permission.copy(isLoading = false, available = false)) }
            return
        }
        mutate { current -> current.copy(permission = current.permission.copy(isLoading = true)) }
        when (val result = client.call("permissionPresets", "catalog", policy = HarnessCallPolicy.SafeRead)) {
            is HarnessRpcResult.Failure -> {
                mutate { current -> current.copy(permission = HarnessPermissionUiState()) }
                if (report) reportFailure(result.error.code, result.error.message)
            }
            is HarnessRpcResult.Success -> {
                val options = result.value.objectArray("options").mapNotNull option@{ row ->
                    val value = row.string("value") ?: return@option null
                    HarnessPermissionOptionUi(
                        value = value,
                        name = row.string("name") ?: value,
                        description = row.string("description")
                    )
                }
                mutate { current ->
                    current.copy(permission = current.permission.copy(
                        options = options,
                        available = true,
                        isLoading = false
                    ))
                }
            }
        }
    }

    suspend fun select(value: String) {
        val sessionId = selectedSessionProvider()?.takeIf(String::isNotBlank)
            ?: return reportFailure("PERMISSION_SESSION_MISSING", "Select a Harness session before changing permissions")
        val client = clientProvider() ?: return
        val reply = executeNativeHarnessCommand(client, sessionId, "/permission ${value.trim()}", emptyList())
        if (!reply.accepted) {
            reportFailure("PERMISSION_COMMAND_FAILED", reply.text ?: "Harness rejected the permission preset")
            return
        }
        mutate { current ->
            current.copy(permission = current.permission.copy(currentValue = value.trim()))
        }
        refresh(report = false)
    }

    /** Feed the `permissions` Session projection from follow/control baselines. */
    suspend fun applyProjection(value: JsonElement?) {
        val currentValue = value?.jsonObjectOrNull()?.string("currentValue") ?: return
        mutate { current -> current.copy(permission = current.permission.copy(currentValue = currentValue, available = true)) }
    }
}
