package com.example.llamadroid.data

import android.annotation.SuppressLint
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Durable state for the guided walkthrough.
 *
 * The automatic presentation is a one-time opportunity. Chapter progress is
 * kept separately so an interrupted walkthrough can resume at its last stable
 * step without storing any user-entered content.
 */
class WalkthroughPreferences(private val prefs: SharedPreferences) {
    init {
        initializeAutomaticEligibility()
    }

    /**
     * Seed the first-run marker durably before any setup UI can write the welcome flag.
     * Existing values, including a previously consumed or deferred claim, are left alone.
     */
    @SuppressLint("ApplySharedPref")
    private fun initializeAutomaticEligibility() = synchronized(lock) {
        if (!prefs.contains(PREF_AUTOMATIC_ELIGIBLE)) {
            prefs.edit(commit = true) {
                putBoolean(
                    PREF_AUTOMATIC_ELIGIBLE,
                    !prefs.getBoolean(PREF_HAS_COMPLETED_WELCOME, false)
                )
            }
        }
    }

    /** True while the one-time automatic presentation has not been claimed. */
    val automaticEligible: Boolean
        get() = synchronized(lock) {
            prefs.getBoolean(PREF_AUTOMATIC_ELIGIBLE, false)
        }

    /**
     * Atomically claim the automatic presentation and report durable success.
     *
     * The KTX edit block discards commit's Boolean result, so this operation
     * keeps the direct editor call to preserve the durable-write contract.
     */
    @SuppressLint("ApplySharedPref", "UseKtx")
    fun claimAutomaticPresentation(): Boolean = synchronized(lock) {
        if (!prefs.getBoolean(PREF_AUTOMATIC_ELIGIBLE, false)) {
            false
        } else {
            val editor = prefs.edit().putBoolean(PREF_AUTOMATIC_ELIGIBLE, false)
            if (!prefs.contains(progressKey(CORE_CHAPTER_ID))) {
                editor.putString(progressKey(CORE_CHAPTER_ID), CORE_FIRST_STEP_ID)
            }
            editor.commit()
        }
    }

    /**
     * Re-arm an automatic presentation that was claimed but never shown; call from IO.
     * The KTX edit block discards commit's Boolean result, so the direct editor
     * call preserves the durable-write contract returned to the caller.
     */
    @SuppressLint("ApplySharedPref", "UseKtx")
    fun deferAutomaticPresentation(): Boolean = synchronized(lock) {
        prefs.edit().putBoolean(PREF_AUTOMATIC_ELIGIBLE, true).commit()
    }

    /**
     * Consume eligibility when a user explicitly opens the guide during a launch.
     * The KTX edit block discards commit's Boolean result, so the direct editor
     * call preserves the durable-write contract returned to the caller.
     */
    @SuppressLint("ApplySharedPref", "UseKtx")
    fun consumeManualPresentation(): Boolean = synchronized(lock) {
        if (!prefs.getBoolean(PREF_AUTOMATIC_ELIGIBLE, false)) {
            false
        } else {
            prefs.edit().putBoolean(PREF_AUTOMATIC_ELIGIBLE, false).commit()
        }
    }

    fun progress(chapterId: String): String? =
        prefs.getString(progressKey(chapterId), null)

    fun saveProgress(chapterId: String, stepId: String) {
        prefs.edit {
            putString(progressKey(chapterId), stepId)
        }
    }

    fun isCompleted(chapterId: String): Boolean =
        prefs.getBoolean(completedKey(chapterId), false)

    fun complete(chapterId: String) {
        prefs.edit {
            putBoolean(completedKey(chapterId), true)
        }
    }

    fun reset(chapterId: String) {
        prefs.edit {
            remove(progressKey(chapterId))
            remove(completedKey(chapterId))
        }
    }

    private fun progressKey(chapterId: String): String =
        "$PREF_PROGRESS_PREFIX$chapterId"

    private fun completedKey(chapterId: String): String =
        "$PREF_COMPLETED_PREFIX$chapterId"

    private companion object {
        private const val PREF_HAS_COMPLETED_WELCOME = "has_completed_welcome"
        private const val PREF_AUTOMATIC_ELIGIBLE = "walkthrough_automatic_eligible"
        private const val PREF_PROGRESS_PREFIX = "walkthrough_progress:"
        private const val PREF_COMPLETED_PREFIX = "walkthrough_completed:"
        private const val CORE_CHAPTER_ID = "core"
        private const val CORE_FIRST_STEP_ID = "home"
        private val lock = Any()
    }
}
