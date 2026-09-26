package com.example.llamadroid.ui.ai

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType

/**
 * SD Models also exposes shared video/image-LLM records. Their compatibility
 * metadata is optional, so a blank value must not hide a usable installed row.
 */
internal fun isAdditionalSdModel(model: ModelEntity): Boolean = model.type in setOf(
    ModelType.SD_AUDIO_VAE,
    ModelType.SD_EMBEDDINGS_CONNECTORS,
    ModelType.SD_MOTION_MODULE,
    ModelType.SD_TEXTUAL_INVERSION,
    ModelType.LLM,
    ModelType.SD_LLM,
    ModelType.VISION_PROJECTOR,
    ModelType.MMPROJ
)
