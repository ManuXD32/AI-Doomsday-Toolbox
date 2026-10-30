package com.example.llamadroid.data.easy

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.llamadroid.data.AppThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Own preferences for Easy mode (prefs file `easy_mode`). [enabledFlow] is app-wide: every instance
 * shares the same flow, so the shell and the settings screens react to one toggle.
 */
class EasyModeSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        // First instance seeds the shared flow from disk; later instances keep the live value.
        synchronized(lock) {
            if (!seeded) {
                enabledState.value = prefs.getBoolean(KEY_ENABLED, false)
                chatModelState.value = prefs.getString(KEY_CHAT_MODEL, "").orEmpty()
                imageModelState.value = prefs.getString(KEY_IMAGE_MODEL, "").orEmpty()
                themeState.value = AppThemeMode.fromStorage(prefs.getString(KEY_THEME, null))
                seeded = true
            }
        }
    }

    /** App-wide Easy mode switch state. */
    val enabledFlow: StateFlow<Boolean> get() = enabledState.asStateFlow()

    var enabled: Boolean
        get() = enabledState.value
        set(value) {
            prefs.edit { putBoolean(KEY_ENABLED, value) }
            enabledState.value = value
        }

    var introSeen: Boolean
        get() = prefs.getBoolean(KEY_INTRO_SEEN, false)
        set(value) = prefs.edit { putBoolean(KEY_INTRO_SEEN, value) }

    fun shouldShowIntro(): Boolean = !introSeen

    fun markIntroSeen() {
        introSeen = true
    }

    /** Absolute path of the last chat model picked in Easy mode; empty = none. */
    var chatModelPath: String
        get() = chatModelState.value
        set(value) {
            prefs.edit { putString(KEY_CHAT_MODEL, value) }
            chatModelState.value = value
        }

    /** Live [chatModelPath], so open screens follow a change made in the Models screen. */
    val chatModelPathFlow: StateFlow<String> get() = chatModelState.asStateFlow()

    /** Id of the image model picked in Easy mode; empty = none. */
    var imageModelId: String
        get() = imageModelState.value
        set(value) {
            prefs.edit { putString(KEY_IMAGE_MODEL, value) }
            imageModelState.value = value
        }

    /** Live [imageModelId], so the Images tab follows a change made in the Models screen. */
    val imageModelIdFlow: StateFlow<String> get() = imageModelState.asStateFlow()

    /**
     * Easy mode's own light/dark choice. It is applied only while Easy mode is on and never
     * touches the advanced theme setting.
     */
    var themeMode: AppThemeMode
        get() = themeState.value
        set(value) {
            prefs.edit { putString(KEY_THEME, value.storageValue) }
            themeState.value = value
        }

    val themeModeFlow: StateFlow<AppThemeMode> get() = themeState.asStateFlow()

    /** Absolute path of the Whisper model used by the chat mic; empty = none. */
    var whisperModelPath: String
        get() = prefs.getString(KEY_WHISPER_MODEL, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_WHISPER_MODEL, value) }

    /** User-chosen context size for [modelPath]; 0 = automatic. */
    fun contextOverrideFor(modelPath: String): Int =
        if (modelPath.isBlank()) 0 else prefs.getInt(contextKey(modelPath), 0)

    /** Saves the per-model context choice; 0 (or less) clears it back to automatic. */
    fun setContextOverrideFor(modelPath: String, ctx: Int) {
        if (modelPath.isBlank()) return
        prefs.edit {
            if (ctx > 0) putInt(contextKey(modelPath), ctx) else remove(contextKey(modelPath))
        }
    }

    private companion object {
        const val PREFS = "easy_mode"
        const val KEY_ENABLED = "enabled"
        const val KEY_INTRO_SEEN = "intro_seen"
        const val KEY_CHAT_MODEL = "chat_model_path"
        const val KEY_IMAGE_MODEL = "image_model_id"
        const val KEY_WHISPER_MODEL = "whisper_model_path"
        const val KEY_CONTEXT_PREFIX = "context_override:"
        const val KEY_THEME = "theme_mode"

        fun contextKey(modelPath: String) = KEY_CONTEXT_PREFIX + modelPath

        val lock = Any()
        val enabledState = MutableStateFlow(false)
        val chatModelState = MutableStateFlow("")
        val imageModelState = MutableStateFlow("")
        val themeState = MutableStateFlow(AppThemeMode.SYSTEM)
        var seeded = false
    }
}
