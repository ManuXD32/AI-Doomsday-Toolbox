package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.R

/** Maps app-authored failures to localized copy; backend diagnostics stay visible separately. */
internal fun localizedHarnessNoticeMessage(code: String): Int? = when (code) {
    "PROJECT_REMOVED" -> R.string.harness_project_action_removed
    "HarnessTransportException", "transport/ws-failure", "transport/ws-closed" -> R.string.harness_connection_interrupted
    "ATTACHMENT_RECEIPT_MISSING" -> R.string.harness_notice_attachment_receipt_missing
    "ATTACHMENT_SESSION_MISSING" -> R.string.harness_notice_attachment_session_missing
    "ATTACHMENT_SESSION_CHANGED" -> R.string.harness_notice_attachment_session_changed
    "ATTACHMENT_VALUE_INVALID" -> R.string.harness_notice_attachment_invalid
    "CREDENTIAL_VALUE_EMPTY" -> R.string.harness_notice_credential_empty
    "PROVIDER_ALREADY_CONFIGURED" -> R.string.harness_notice_provider_already_configured
    "PROVIDER_CREDENTIAL_UNAVAILABLE" -> R.string.harness_notice_provider_credential_unavailable
    "PROVIDER_FIELD_UNAVAILABLE" -> R.string.harness_notice_provider_field_unavailable
    "PROVIDER_FORM_UNAVAILABLE" -> R.string.harness_notice_provider_form_unavailable
    "PROVIDER_ROUTE_FAILED" -> R.string.harness_provider_save_failed
    "PROVIDER_ROUTE_INVALID" -> R.string.harness_provider_response_invalid
    "settings/conflict" -> R.string.harness_settings_revision_conflict
    "PROVIDER_NOT_DECLARED" -> R.string.harness_notice_provider_not_declared
    "PROVIDER_NOT_REMOVABLE" -> R.string.harness_notice_provider_not_removable
    "PROVIDER_DISCOVERY_EMPTY" -> R.string.harness_provider_discovery_empty
    "PROVIDER_DISCOVERY_INVALID" -> R.string.harness_notice_provider_discovery_invalid
    "PROVIDER_DISCOVERY_FAILED" -> R.string.harness_notice_provider_discovery_failed
    "PROVIDER_DISCOVERY_NETWORK" -> R.string.harness_notice_provider_discovery_network
    "PROVIDER_DISCOVERY_UNAUTHORIZED" -> R.string.harness_notice_provider_discovery_unauthorized
    "PROVIDER_DISCOVERY_HTTP_FAILED" -> R.string.harness_notice_provider_discovery_http_failed
    "PROVIDER_DISCOVERY_UNSUPPORTED" -> R.string.harness_notice_provider_discovery_unsupported
    "PROVIDER_DISCOVERY_INVALID_RESPONSE" -> R.string.harness_notice_provider_discovery_invalid_response
    "PROVIDER_DISCOVERY_TOO_LARGE" -> R.string.harness_notice_provider_discovery_too_large
    "PROVIDER_MODELS_UNAVAILABLE" -> R.string.harness_notice_provider_models_unavailable
    "PROVIDER_MODELS_INVALID" -> R.string.harness_notice_provider_models_invalid
    "CUSTOM_PROVIDER_ROUTE_INVALID" -> R.string.harness_notice_custom_provider_route_invalid
    "CUSTOM_PROVIDER_ROUTE_TAKEN" -> R.string.harness_notice_custom_provider_route_taken
    "CUSTOM_PROVIDER_PROTOCOL_INVALID" -> R.string.harness_notice_custom_provider_protocol_invalid
    "CUSTOM_PROVIDER_BASE_URL_INVALID" -> R.string.harness_notice_custom_provider_base_url_invalid
    "CUSTOM_PROVIDER_MODELS_REQUIRED" -> R.string.harness_notice_custom_provider_models_required
    "CUSTOM_PROVIDER_MODEL_INVALID" -> R.string.harness_notice_custom_provider_model_invalid
    "CUSTOM_PROVIDER_KEY_INVALID" -> R.string.harness_notice_custom_provider_key_invalid
    "CUSTOM_PROVIDER_NAMESPACE_UNAVAILABLE" -> R.string.harness_notice_custom_provider_namespace_unavailable
    "SESSION_ID_MISSING" -> R.string.harness_notice_session_id_missing
    "SETTINGS_FIELD_INVALID" -> R.string.harness_notice_settings_field_invalid
    "SETTINGS_FIELD_UNAVAILABLE" -> R.string.harness_notice_settings_field_unavailable
    "SETTINGS_JSON_INVALID" -> R.string.harness_notice_settings_json_invalid
    "SETTINGS_FIELD_NOT_OVERRIDDEN" -> R.string.harness_notice_settings_field_not_overridden
    "SETTINGS_FIELD_READ_ONLY" -> R.string.harness_notice_settings_field_read_only
    "SETTINGS_READ_ONLY" -> R.string.harness_notice_settings_read_only
    "SETTINGS_DOCUMENT_UNAVAILABLE" -> R.string.harness_notice_settings_document_unavailable
    "SETTINGS_DOCUMENT_INVALID" -> R.string.harness_notice_settings_document_invalid
    "SETTINGS_DOCUMENT_TOO_LARGE" -> R.string.harness_notice_settings_document_too_large
    "SETTINGS_DOCUMENT_INVALID_RESULT" -> R.string.harness_notice_settings_document_invalid_result
    "GOAL_INVALID_OBJECTIVE" -> R.string.harness_notice_goal_objective_invalid
    "GOAL_INVALID_MAX_ROUNDS" -> R.string.harness_notice_goal_max_rounds_invalid
    "GOAL_INVALID_EDIT" -> R.string.harness_notice_goal_edit_invalid
    "GOAL_NOT_FOUND" -> R.string.harness_notice_goal_missing
    "PLUGIN_INVALID_SPEC" -> R.string.harness_notice_plugin_spec_invalid
    "PLUGIN_INSTALL_CANCEL_UNAVAILABLE" -> R.string.harness_notice_plugin_cancel_unavailable
    "PROVIDER_AUTH_UNAVAILABLE" -> R.string.harness_notice_provider_auth_unavailable
    "CREDENTIAL_REFERENCE_ROLLBACK_FAILED",
    "CREDENTIAL_REFERENCE_CLEAR_FAILED" -> R.string.harness_notice_provider_credential_unavailable
    "PROVIDER_AUTH_URL_FAILED" -> R.string.harness_notice_provider_auth_url_failed
    "PROVIDER_AUTH_STATUS_FAILED" -> R.string.harness_notice_provider_auth_status_failed
    "OFFLINE_SESSION_READ_FAILED" -> R.string.harness_notice_offline_session_read_failed
    "CAPABILITY_UNAVAILABLE" -> R.string.harness_notice_capability_unavailable
    "INTERACTION_BRIDGE_UNAVAILABLE" -> R.string.harness_notice_interaction_unavailable
    "JOB_READ_UNAVAILABLE" -> R.string.harness_notice_job_read_unavailable
    "JOB_KILL_UNAVAILABLE" -> R.string.harness_notice_job_kill_unavailable
    "SUBAGENTS_READ_ONLY" -> R.string.harness_notice_subagents_read_only
    "SUBAGENT_ADDRESS_UNAVAILABLE" -> R.string.harness_notice_subagent_address_unavailable
    "PLUGIN_INSTALL_FAILED" -> R.string.harness_notice_plugin_install_failed
    "PLUGIN_INSTALL_INVALID_RESULT" -> R.string.harness_notice_plugin_invalid_result
    "PLUGIN_INSTALL_CANCELLED" -> R.string.harness_notice_plugin_install_cancelled
    "MODEL_CATALOG_INVALID" -> R.string.harness_notice_model_catalog_invalid
    "MODEL_REASONING_EFFORT_INVALID" -> R.string.harness_notice_model_reasoning_invalid
    "MODEL_CONTEXT_UNKNOWN" -> R.string.harness_notice_model_context_unknown
    "PERMISSION_SESSION_MISSING" -> R.string.harness_notice_permission_session_missing
    "PERMISSION_COMMAND_FAILED" -> R.string.harness_notice_permission_command_failed
    "HARNESS_UNAVAILABLE" -> R.string.harness_notice_unavailable
    "QUESTION_EVENT_NOT_FOUND" -> R.string.harness_notice_interaction_expired
    "APPROVAL_EVENT_NOT_FOUND" -> R.string.harness_notice_interaction_expired
    "PLAN_EVENT_NOT_FOUND" -> R.string.harness_notice_interaction_expired
    "SKILLS_READ_ONLY" -> R.string.harness_notice_skills_read_only
    "WORKSPACE_STREAM_FAILED" -> R.string.harness_notice_workspace_stream_failed
    "FEEDBACK_SESSION_MISSING" -> R.string.harness_notice_feedback_session_missing
    "FEEDBACK_RPC_FAILED" -> R.string.harness_notice_feedback_rpc_failed
    "FEEDBACK_INVALID_RESULT" -> R.string.harness_notice_feedback_invalid_result
    "FEEDBACK_REJECTED" -> R.string.harness_notice_feedback_rejected
    "FEEDBACK_CONFLICT" -> R.string.harness_notice_feedback_conflict
    "TRANSCRIPT_DETAIL_PAGE_INVALID" -> R.string.harness_notice_transcript_detail_page_invalid
    "TRANSCRIPT_DETAIL_ITEM_MISSING" -> R.string.harness_notice_transcript_detail_item_missing
    "TRANSCRIPT_DETAIL_REFERENCE_INVALID" -> R.string.harness_notice_transcript_detail_reference_invalid
    "TRANSCRIPT_DETAIL_REQUEST_FAILED" -> R.string.harness_notice_transcript_detail_request_failed
    "TRANSCRIPT_COPY_TOO_LARGE" -> R.string.harness_transcript_copy_too_large
    else -> when {
        code.startsWith("gateway/") -> R.string.harness_gateway_operation_failed
        code.startsWith("TRANSCRIPT_COPY_") || code.startsWith("TRANSCRIPT_FORK_") ||
            code == "TRANSCRIPT_ACTION_FAILED" -> R.string.harness_transcript_action_failed
        else -> null
    }
}
