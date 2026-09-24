package com.example.llamadroid.harness

import android.annotation.SuppressLint
import android.content.Context
import org.json.JSONObject

/** A durable, path-scoped Android rename waiting for the DSH group to confirm its title. */
internal data class HarnessPendingWorkspaceTitle(
    val guestPath: String,
    val title: String,
)

// A rename receipt must be durably committed before the project title changes,
// and the caller must be able to detect a failed commit. KTX edit() hides that result.
@SuppressLint("UseKtx")
internal class HarnessWorkspaceTitleSyncStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun pending(groupId: String, guestPath: String): HarnessPendingWorkspaceTitle? {
        val raw = preferences.getString(groupId, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val path = normalizeHarnessGuestPath(json.getString("guestPath"))
            val title = HarnessProjectManagementRules.normalizeTitle(json.getString("title"))
            HarnessPendingWorkspaceTitle(path, title).takeIf {
                path == normalizeHarnessGuestPath(guestPath)
            }
        }.getOrNull()
    }

    fun mark(groupId: String, guestPath: String, title: String) {
        require(groupId.isNotBlank() && groupId.length <= 256) { "WORKSPACE_IDENTITY_MISMATCH" }
        val payload = JSONObject()
            .put("guestPath", normalizeHarnessGuestPath(guestPath))
            .put("title", HarnessProjectManagementRules.normalizeTitle(title))
            .toString()
        check(preferences.edit().putString(groupId, payload).commit()) {
            "WORKSPACE_TITLE_SYNC_SAVE_FAILED"
        }
    }

    fun clear(groupId: String) {
        check(preferences.edit().remove(groupId).commit()) { "WORKSPACE_TITLE_SYNC_CLEAR_FAILED" }
    }

    fun clearAll() {
        check(preferences.edit().clear().commit()) { "WORKSPACE_TITLE_SYNC_CLEAR_FAILED" }
    }

    private companion object {
        const val PREFERENCES = "harness_workspace_title_sync"
    }
}
