package com.example.llamadroid.service

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType

/**
 * Returns the installed model family that can back the selected speculative mode.
 *
 * Standard draft modes use a normal llama.cpp target model as the draft model,
 * while MTP with a separate model uses the dedicated LLM_DRAFT/MTP family. In
 * particular, embeddings, projectors, and LoRA adapters are never draft-model
 * candidates even though they are managed by the broader model library.
 */
internal fun speculativeDraftModelsFor(
    models: List<ModelEntity>,
    mode: LlamaSpeculativeMode
): List<ModelEntity> = models.filter { model ->
    if (mode == LlamaSpeculativeMode.DRAFT_MTP) {
        model.type == ModelType.LLM_DRAFT
    } else {
        model.type == ModelType.LLM || model.type == ModelType.VISION
    }
}

/**
 * Keeps a persisted draft path usable only while it still belongs to the
 * candidate family exposed by the current speculative mode.
 */
internal fun effectiveSpeculativeDraftPath(
    selectedPath: String?,
    candidates: List<ModelEntity>
): String? = selectedPath
    ?.takeIf { it.isNotBlank() }
    ?.takeIf { path -> candidates.any { it.path == path } }
