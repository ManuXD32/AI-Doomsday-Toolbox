package com.example.llamadroid.ui.settings.chat

import androidx.annotation.StringRes
import androidx.compose.runtime.staticCompositionLocalOf
import com.example.llamadroid.R

/** Sections of the global "Chat settings" screen, in display order. [arg] is the optional `section` route argument. */
enum class ChatSettingsSection(val arg: String, @StringRes val titleRes: Int) {
    DOCUMENTS("documents", R.string.chat_global_documents_title),
    HELPERS("helpers", R.string.chat_global_helpers_title),
    CARDS("cards", R.string.chat_global_cards_title),
    WEB("web", R.string.chat_global_web_title),
    CACHES("caches", R.string.chat_global_caches_title),
    NEW_CHATS("new_chats", R.string.chat_global_new_chats_title),
    DELEGATION("delegation", R.string.chat_global_delegation_title),
    WEAR("wear", R.string.chat_global_wear_title);

    companion object {
        const val ROUTE = "settings_chat"
        const val ROUTE_PATTERN = "settings_chat?section={section}"

        /** Route that opens the screen, optionally scrolled to [section]. */
        fun route(section: ChatSettingsSection? = null): String =
            if (section == null) ROUTE else "$ROUTE?section=${section.arg}"

        /** Parses the `section` argument; unknown or blank values mean "no scroll target". */
        fun fromArg(arg: String?): ChatSettingsSection? =
            entries.firstOrNull { it.arg == arg?.trim() }
    }
}

/** Opens the global Chat settings screen (optionally at a section) from deep call sites such as the per-chat sheet. */
val LocalChatGlobalSettingsOpener = staticCompositionLocalOf<(ChatSettingsSection?) -> Unit> { {} }
