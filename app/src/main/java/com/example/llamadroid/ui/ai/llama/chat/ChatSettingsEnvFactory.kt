package com.example.llamadroid.ui.ai.llama.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.repository.KnowledgeBaseRepository
import com.example.llamadroid.onnx.isOnnxBackgroundRemovalModel
import com.example.llamadroid.onnx.isOnnxTxt2ImgBundle
import com.example.llamadroid.onnx.resolveSupertonicVoices
import com.example.llamadroid.service.supportsSdTxt2Img
import java.io.File

/**
 * Builds the [ChatSettingsEnv] lookup lists (models, voices, knowledge bases) for hosts that edit
 * chat options outside a chat, namely the server editors' "defaults for new chats" section. It is
 * always in defaults mode, which hides the actions that navigate away or need a saved chat.
 */
@Composable
internal fun rememberDefaultsSettingsEnv(): ChatSettingsEnv {
    val context = LocalContext.current
    val database = remember(context) { AppDatabase.getDatabase(context) }
    val knowledgeBaseRepository = remember(database) { KnowledgeBaseRepository(context, database) }
    val onnxImageModels by remember(database) {
        database.modelDao().getModelsByType(ModelType.ONNX_IMAGE_GEN)
    }.collectAsState(initial = emptyList())
    val sdImageMainModels by remember(database) {
        database.modelDao().getModelsByTypes(listOf(ModelType.SD_CHECKPOINT, ModelType.SD_DIFFUSION))
    }.collectAsState(initial = emptyList())
    val sdImageSupportModels by remember(database) {
        database.modelDao().getModelsByTypes(
            listOf(
                ModelType.SD_VAE,
                ModelType.SD_TAE,
                ModelType.SD_CLIP_L,
                ModelType.SD_CLIP_G,
                ModelType.SD_T5XXL,
                ModelType.LLM,
                ModelType.VISION_PROJECTOR,
                ModelType.SD_PHOTOMAKER
            )
        )
    }.collectAsState(initial = emptyList())
    val onnxBackgroundRemovalModels by remember(database) {
        database.modelDao().getModelsByType(ModelType.ONNX_BACKGROUND_REMOVAL)
    }.collectAsState(initial = emptyList())
    val onnxTtsModels by remember(database) {
        database.modelDao().getModelsByType(ModelType.ONNX_TTS)
    }.collectAsState(initial = emptyList())
    val knowledgeBases by remember(knowledgeBaseRepository) { knowledgeBaseRepository.observeKnowledgeBases() }
        .collectAsState(initial = emptyList())
    val imageModelOptions = remember(onnxImageModels) {
        onnxImageModels.filter { it.isOnnxTxt2ImgBundle() }.map { it.filename }.distinct()
    }
    val sdImageModelOptions = remember(sdImageMainModels) {
        sdImageMainModels.filter { it.supportsSdTxt2Img() }.map { it.filename }.distinct()
    }
    val bgrModelOptions = remember(onnxBackgroundRemovalModels) {
        onnxBackgroundRemovalModels.filter { it.isOnnxBackgroundRemovalModel() }.map { it.filename }.distinct()
    }
    val ttsVoiceOptions = remember(onnxTtsModels) {
        onnxTtsModels.firstOrNull()
            ?.let { runCatching { resolveSupertonicVoices(File(it.path)) }.getOrDefault(emptyList()) }
            .orEmpty()
    }
    return ChatSettingsEnv(
        onnxTtsModels = onnxTtsModels,
        ttsVoiceOptions = ttsVoiceOptions,
        knowledgeBases = knowledgeBases,
        imageModelOptions = imageModelOptions,
        sdImageMainModels = sdImageMainModels,
        sdImageModelOptions = sdImageModelOptions,
        sdImageSupportModels = sdImageSupportModels,
        bgrModelOptions = bgrModelOptions,
        liteRtContextCap = LITERT_CHAT_FALLBACK_CONTEXT_MAX,
        onOpenKnowledgeBase = {},
        onManageCustomTools = {},
        defaultsMode = true
    )
}
