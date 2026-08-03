package com.example.llamadroid.wear

import android.content.Context

object PinnedOrganizerNoteStore {
    private const val PREFS = "adt_pinned_organizer_note_v1"
    private const val KEY_NOTE_ID = "note_id"

    fun get(context: Context): Int? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_NOTE_ID, -1)
            .takeIf { it > 0 }

    fun set(context: Context, noteId: Int?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (noteId == null || noteId <= 0) remove(KEY_NOTE_ID) else putInt(KEY_NOTE_ID, noteId)
            }
            .apply()
    }
}
