package com.example.llamadroid.data.model

import com.example.llamadroid.data.db.ModelType

/** Shared by installed lists, download counters and transfer management. */
object ModelManagerModelTypes {
    val llama: List<ModelType> = listOf(
        ModelType.LLM,
        ModelType.LLM_DRAFT,
        ModelType.LORA,
        ModelType.EMBEDDING,
        ModelType.VISION,
        ModelType.VISION_PROJECTOR,
        ModelType.MMPROJ,
        ModelType.LLAMA_TTS,
        ModelType.LLAMA_TTS_COMPANION,
    )

    val liteRtAudio: List<ModelType> = listOf(
        ModelType.LITERT_AUDIO_DIT,
        ModelType.LITERT_AUDIO_COMPONENT,
    )
}
