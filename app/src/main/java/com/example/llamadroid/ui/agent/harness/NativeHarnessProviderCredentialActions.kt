package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/** Implements the provider editor's ordered profile-reference and secret writes. */
internal class NativeHarnessProviderCredentialActions(
    private val clientProvider: suspend () -> HarnessClient?,
    private val bindingProvider: (String) -> NativeHarnessProviderBinding?,
    private val refresh: suspend () -> Unit,
    private val reportFailure: suspend (String, String) -> Unit
) {
    suspend fun set(providerId: String, value: String) {
        val binding = bindingProvider(providerId) ?: return reportFailure(
            "PROVIDER_NOT_DECLARED",
            "The selected provider is not declared by Harness"
        )
        if (value.isBlank()) return reportFailure(
            "CREDENTIAL_VALUE_EMPTY",
            "Enter a provider credential before saving"
        )
        if (!binding.writable) return reportFailure(
            "SETTINGS_READ_ONLY",
            "Harness settings are read-only in this environment"
        )
        val client = clientProvider() ?: return
        val reference = nativeHarnessProviderCredentialReference(binding)
        if (binding.settingsNamespace == NATIVE_CUSTOM_PROVIDER_SETTINGS_NAMESPACE) {
            when (val result = NativeHarnessProviderGateway.updateCredential(client, binding, reference, value)) {
                is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
                is HarnessRpcResult.Success -> refresh()
            }
            return
        }
        var profileReferenceRevision: Int? = null
        if (
            binding.credentialReferenceDerived &&
            binding.settingsNamespace == NATIVE_CUSTOM_PROVIDER_SETTINGS_NAMESPACE
        ) {
            when (val result = NativeHarnessCapabilities.mutateSettings(
                client,
                binding.settingsNamespace,
                buildJsonArray {
                    add(
                        NativeHarnessCapabilities.setOperation(
                            binding.settingsPath + "apiKeyEnv",
                            JsonPrimitive(reference)
                        )
                    )
                },
                binding.revision
            )) {
                is HarnessRpcResult.Failure -> return reportFailure(result.error.code, result.error.message)
                is HarnessRpcResult.Success -> {
                    profileReferenceRevision = result.value.jsonObjectOrNull()?.int("revision")
                }
            }
        }
        when (val result = NativeHarnessCapabilities.setCredential(client, reference, value)) {
            is HarnessRpcResult.Failure -> {
                // Keep the profile reference and secret write atomic from the user's
                // perspective. Roll back only with the committed revision so a concurrent
                // settings edit cannot be deleted by an unguarded compensation.
                if (profileReferenceRevision != null) {
                    when (val rollback = NativeHarnessCapabilities.mutateSettings(
                        client,
                        NATIVE_CUSTOM_PROVIDER_SETTINGS_NAMESPACE,
                        buildJsonArray {
                            add(NativeHarnessCapabilities.unsetOperation(binding.settingsPath + "apiKeyEnv"))
                        },
                        profileReferenceRevision,
                    )) {
                        is HarnessRpcResult.Failure -> reportFailure(
                            "CREDENTIAL_REFERENCE_ROLLBACK_FAILED",
                            rollback.error.message,
                        )
                        is HarnessRpcResult.Success -> Unit
                    }
                }
                refresh()
                reportFailure(result.error.code, result.error.message)
            }
            is HarnessRpcResult.Success -> refresh()
        }
    }

    suspend fun unset(providerId: String) {
        val binding = bindingProvider(providerId) ?: return reportFailure(
            "PROVIDER_NOT_DECLARED",
            "The selected provider is not declared by Harness"
        )
        if (!binding.writable) return reportFailure(
            "SETTINGS_READ_ONLY",
            "Harness settings are read-only in this environment"
        )
        val client = clientProvider() ?: return
        val reference = nativeHarnessProviderCredentialReference(binding)
        if (binding.settingsNamespace == NATIVE_CUSTOM_PROVIDER_SETTINGS_NAMESPACE) {
            when (val result = NativeHarnessProviderGateway.updateCredential(client, binding, reference, null)) {
                is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
                is HarnessRpcResult.Success -> refresh()
            }
            return
        }
        when (val result = NativeHarnessCapabilities.unsetCredential(client, reference)) {
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> {
                // apiKeyEnv is user settings state for custom providers. Clear it after the
                // secret succeeds so keyless auth does not retain a stale reference.
                if (
                    binding.settingsNamespace == NATIVE_CUSTOM_PROVIDER_SETTINGS_NAMESPACE &&
                    binding.apiKeyReference != null
                ) {
                    when (val cleanup = NativeHarnessCapabilities.mutateSettings(
                        client,
                        binding.settingsNamespace,
                        buildJsonArray {
                            add(NativeHarnessCapabilities.unsetOperation(binding.settingsPath + "apiKeyEnv"))
                        },
                        binding.revision,
                    )) {
                        is HarnessRpcResult.Failure -> reportFailure(
                            "CREDENTIAL_REFERENCE_CLEAR_FAILED",
                            cleanup.error.message,
                        )
                        is HarnessRpcResult.Success -> Unit
                    }
                }
                refresh()
            }
        }
    }
}
