package com.example.llamadroid.harness

/** A closed operation vocabulary; plugin-provided names cannot become durable content. */
internal fun harnessDiagnosticOperation(value: String): String =
    value.takeIf { it in HARNESS_DIAGNOSTIC_OPERATIONS } ?: "extension"

private val HARNESS_DIAGNOSTIC_OPERATIONS = setOf(
    "action", "connection", "parser", "render", "transcript_action", "workspace_open", "workspace_registration", "interface",
    "session/list", "session/search", "session/create", "session/get", "session/follow", "session/control",
    "session/page", "session/read", "session/rename", "session/archive", "session/fork", "session/prompt",
    "session/cancel", "session/selectModel", "session/attachment", "session/append", "session/steer",
    "session/modelCatalog", "session/setModel", "session/queue", "session/queueEdit", "session/queueRemove", "session/queueSteer",
    "modelSelection/getCatalog", "modelSelection/select", "llm/listModels", "llm/listProviders",
    "llm/listConfigurableProviders", "llm/discoverModels", "settings/describe", "settings/mutate",
    "credentials/describe", "credentials/set", "credentials/unset", "adt/providerAuth", "adt/invoke",
    "adt/shutdown", "workspace/create", "workspace/follow", "workspace/rename", "workspace/remove", "workspace/move",
    "agentPresets/list", "agentPresets/read", "agentPresets/duplicate", "agentPresets/delete",
    "pluginInventory/list", "plugin-manager/list", "plugin-manager/install", "plugin-manager/inspect",
    "plugin-manager/cancel", "plugin-manager/uninstall", "plugin-manager/update", "plugin-manager/setEnabled",
    "\$events", "\$events/result", "cordis/list", "cordis/inspect", "remoteEvents/connect", "remoteEvents/respond", "events/connect",
    "permissions/catalog", "permissions/get", "permissions/set", "skills/list", "commands/list",
    "commands/execute", "subagents/list", "goal/get", "goal/create", "goal/update", "messageFeedback/list",
    "messageFeedback/put", "messageFeedback/retract", "terminal/list", "terminal/open", "terminal/follow",
    "/api/session.export", "/api/changes.summary", "/api/changes.diff", "/api/files.list", "/api/files.read",
)
