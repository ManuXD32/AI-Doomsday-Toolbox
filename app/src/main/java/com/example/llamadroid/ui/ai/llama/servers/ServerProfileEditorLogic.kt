package com.example.llamadroid.ui.ai.llama.servers

import com.example.llamadroid.service.LlamaServerLaunchProfile
import com.google.gson.Gson

/** Draft preferences file backing the full-screen server profile editor (isolated from global settings). */
internal const val SERVER_PROFILE_DRAFT_PREFS = "llm_server_profile_draft"

/** Reasons the server profile editor cannot be saved yet; each maps to one inline message. */
enum class ProfileEditorError {
    MODEL_NOT_SELECTED,
    MODEL_FILE_MISSING,
    MMPROJ_FILE_MISSING,
    DRAFT_FILE_MISSING,
    PORT_INVALID,
    PORT_IN_USE,
    FLAGS_INVALID
}

/**
 * @param portText blank means "pick a free port"
 * @param usedPorts ports of the other local servers
 * @param fileExists readable-file check, injected so tests need no filesystem
 */
fun validateProfileEditor(
    profile: LlamaServerLaunchProfile,
    portText: String,
    usedPorts: Set<Int>,
    fileExists: (String) -> Boolean,
    argumentsValid: (flags: String, template: String) -> Boolean
): Set<ProfileEditorError> {
    val errors = linkedSetOf<ProfileEditorError>()
    if (profile.modelPath.isBlank()) errors += ProfileEditorError.MODEL_NOT_SELECTED
    else if (!fileExists(profile.modelPath)) errors += ProfileEditorError.MODEL_FILE_MISSING
    if ((profile.visionEnabled || profile.videoEnabled) && profile.mmprojPath?.let(fileExists) != true) {
        errors += ProfileEditorError.MMPROJ_FILE_MISSING
    }
    if (!profile.draftModelPath.isNullOrBlank() && !fileExists(profile.draftModelPath)) {
        errors += ProfileEditorError.DRAFT_FILE_MISSING
    }
    if (portText.isNotBlank()) {
        val port = portText.trim().toIntOrNull()
        if (port == null || port !in 1..65535) errors += ProfileEditorError.PORT_INVALID
        else if (port in usedPorts) errors += ProfileEditorError.PORT_IN_USE
    }
    if (!argumentsValid(profile.customFlags.orEmpty(), profile.commandTemplate.orEmpty())) {
        errors += ProfileEditorError.FLAGS_INVALID
    }
    return errors
}

/** A managed server always listens on loopback; the chosen port (or null for auto) is applied on save. */
fun profileForSave(captured: LlamaServerLaunchProfile, requestedPort: Int?): LlamaServerLaunchProfile =
    captured.copy(host = "127.0.0.1", serverPort = requestedPort ?: captured.serverPort)

/** Stable text form of a profile, used to detect unsaved edits. */
fun profileFingerprint(profile: LlamaServerLaunchProfile): String = Gson().toJson(profile)

/** Name suggested for a new server: the model file name unless the user typed their own. */
fun suggestedServerName(currentName: String, nameTouched: Boolean, modelPath: String): String =
    if (nameTouched && currentName.isNotBlank()) currentName else defaultServerName(modelPath)

/** Route used to open the editor: [cardId] -1 means a new server, [modelPath] pre-selects a model. */
fun serverProfileEditorRoute(cardId: Long?, modelPath: String?): String =
    "server_profile_editor?cardId=${cardId ?: -1L}&modelPath=${android.net.Uri.encode(modelPath.orEmpty())}"

/**
 * Opens the full-screen server profile editor (`cardId` null = new server, `modelPath` pre-selects a
 * model). Provided by the app NavHost so every server sheet can reach the route without a NavController.
 */
val LocalServerProfileEditorOpener =
    androidx.compose.runtime.staticCompositionLocalOf<(cardId: Long?, modelPath: String?) -> Unit> { { _, _ -> } }
