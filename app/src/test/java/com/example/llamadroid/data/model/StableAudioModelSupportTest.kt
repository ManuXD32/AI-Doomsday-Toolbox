package com.example.llamadroid.data.model

import com.example.llamadroid.data.db.ModelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StableAudioModelSupportTest {
    @Test
    fun `manifest role aliases resolve to appended runtime types`() {
        assertEquals(ModelType.LITERT_AUDIO_DIT, StableAudioModelSupport.typeForRole("dit"))
        assertEquals(ModelType.LITERT_AUDIO_COMPONENT, StableAudioModelSupport.typeForRole("textEncoder"))
        assertEquals(ModelType.LITERT_AUDIO_COMPONENT, StableAudioModelSupport.typeForRole("codec_encoder"))
        assertEquals(ModelType.LITERT_AUDIO_COMPONENT, StableAudioModelSupport.typeForRole("stable_audio_tokenizer"))
        assertEquals(StableAudioModelSupport.ROLE_CODEC_DECODER,
            StableAudioModelSupport.canonicalRole("codec-decoder"))
    }

    @Test
    fun `shared family and role metadata remain distinct from chat`() {
        assertTrue(StableAudioModelSupport.isFamily(StableAudioModelSupport.FAMILY_SHARED))
        assertEquals(ModelType.LITERT_AUDIO_DIT,
            StableAudioModelSupport.typeForRole(StableAudioModelSupport.ROLE_DIT))
        assertEquals(ModelType.LITERT_AUDIO_COMPONENT,
            StableAudioModelSupport.typeForRole(StableAudioModelSupport.ROLE_CODEC_DECODER))
    }
}
