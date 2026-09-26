package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessCallPolicy
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Canonical command/skill catalogs, refreshed only into the Session that requested them. */
internal class NativeHarnessReferenceCatalogs(
    private val clientProvider: suspend () -> HarnessClient?,
    private val selectedSession: () -> String?,
    private val isClientCurrent: (HarnessClient) -> Boolean,
    private val update: suspend ((NativeHarnessUiState) -> NativeHarnessUiState) -> Unit,
    private val report: suspend (String, String) -> Unit,
) {
    suspend fun skills(reportFailure: Boolean) {
        val sessionId = selectedSession() ?: return
        val client = clientProvider() ?: return
        when (val result = client.call("skills", "list", buildJsonObject {
            putJsonObject("request") { put("sessionId", sessionId) }
        }, HarnessCallPolicy.SafeRead)) {
            is HarnessRpcResult.Failure -> if (reportFailure && selectedSession() == sessionId && isClientCurrent(client)) report(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> update { current ->
                if (current.selectedSessionId != sessionId || !isClientCurrent(client)) current else current.copy(
                    extensions = current.extensions.copy(skills = result.value.objectArray("skills").mapNotNull { skill ->
                        val name = skill.string("name") ?: return@mapNotNull null
                        HarnessSkillUi(id = name, name = "/" + name, summary = skill.string("description").orEmpty(),
                            enabled = true, installed = true, canRemove = false)
                    }),
                )
            }
        }
    }

    suspend fun commands(reportFailure: Boolean) {
        val sessionId = selectedSession() ?: return
        val client = clientProvider() ?: return
        when (val result = client.call("commands", "list", buildJsonObject {
            put("agentId", sessionId)
        }, HarnessCallPolicy.SafeRead)) {
            is HarnessRpcResult.Failure -> if (reportFailure && selectedSession() == sessionId && isClientCurrent(client)) report(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> update { current ->
                if (current.selectedSessionId != sessionId || !isClientCurrent(client)) current else current.copy(
                    commands = (result.value as? JsonArray).orEmpty().mapNotNull { descriptor ->
                        val item = descriptor as? JsonObject ?: return@mapNotNull null
                        val name = item.string("name") ?: return@mapNotNull null
                        HarnessCommandUi(name = "/" + name, description = item.string("description"))
                    },
                )
            }
        }
    }
}
