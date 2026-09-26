package com.example.llamadroid.audio

import com.example.llamadroid.audio.library.AudioLibrarySelection
import com.example.llamadroid.audio.library.AudioLibrarySort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLibraryModelsTest {
    @Test
    fun `selection survives page changes and supports excluded matches`() {
        var selection = AudioLibrarySelection().toggle("first").toggle("second")
        assertTrue(selection.contains("first"))
        assertFalse(selection.contains("third"))

        selection = selection.copy(allMatching = true, excludedIds = setOf("second"))
        assertTrue(selection.contains("first"))
        assertFalse(selection.contains("second"))
        assertTrue(selection.contains("page-two"))
        assertEquals(AudioLibrarySelection(), selection.clear())
    }

    @Test
    fun `invalid persisted sort falls back to newest`() {
        assertEquals(AudioLibrarySort.NAME_ASC, AudioLibrarySort.fromStored("name_asc"))
        assertEquals(AudioLibrarySort.SIZE_DESC, AudioLibrarySort.fromStored("size_desc"))
        assertEquals(AudioLibrarySort.NEWEST, AudioLibrarySort.fromStored("removed_sort"))
        assertEquals(AudioLibrarySort.NEWEST, AudioLibrarySort.fromStored(null))
    }
}
