package com.example.llamadroid.ui.audio.music

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MusicWorkspaceDraftTest {
    @Test fun `music and sound effects start with separate duration defaults`() {
        assertEquals("30", MusicWorkspaceDraft("music")["durationSeconds"])
        assertEquals("5", MusicWorkspaceDraft("sfx")["durationSeconds"])
    }

    @Test fun `persistent draft retains invalid values custom components and stacked adapters`() {
        val draft = MusicWorkspaceDraft("music")
            .withValue("seed", "999999999999999999999999")
            .withValue("steps", "-")
            .withValue("prompt", "Piano\nwith rain")
            .withValue("operation", "inpaint")
            .copy(components = mapOf("dit" to "/custom/base.tflite"),
                loras = listOf(MusicLoraDraft("/custom/a.safetensors", "0.4"), MusicLoraDraft("/custom/b.safetensors", "1.2")))
        assertEquals(draft, MusicWorkspaceDraft.fromJson("music", draft.toJson()))
    }

    @Test fun `switching operation preserves input region and generation settings`() {
        val draft = MusicWorkspaceDraft("sfx").withValue("operation", "inpaint")
            .withValue("initAudio", "content://audio/clip")
            .withValue("maskStartSeconds", "2.5").withValue("cfg", "3")
        val switched = draft.withValue("operation", "generate").withValue("operation", "inpaint")
        assertEquals(draft, switched)
    }
}
