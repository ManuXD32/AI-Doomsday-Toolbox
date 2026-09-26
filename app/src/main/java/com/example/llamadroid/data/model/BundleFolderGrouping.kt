package com.example.llamadroid.data.model

import com.example.llamadroid.R
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SD_CAPABILITY_VID_GEN
import com.example.llamadroid.data.db.isAudioTtsComponentType
import com.example.llamadroid.data.db.isStableAudioComponentType

enum class BundleCategory(val titleRes: Int) {
    NORMAL(R.string.bundle_folder_normal),
    VISION(R.string.bundle_folder_vision),
    SPEECH(R.string.bundle_folder_speech),
    MUSIC(R.string.bundle_folder_music),
    VIDEO(R.string.bundle_folder_video),
    UPSCALING(R.string.bundle_folder_upscaling),
    EDITING(R.string.bundle_folder_editing),
    DETECTORS(R.string.bundle_folder_detectors)
}

fun CuratedModelBundle.folderCategory(): BundleCategory = when {
    videoPolicy != null -> BundleCategory.VIDEO
    files.any { it.type.isStableAudioComponentType() } -> BundleCategory.MUSIC
    files.any { it.type.isAudioTtsComponentType() || it.type == ModelType.WHISPER || it.type == ModelType.ONNX_TTS } -> BundleCategory.SPEECH
    files.any { it.type == ModelType.SD_ADETAILER } -> BundleCategory.DETECTORS
    files.any { it.type == ModelType.VISION_PROJECTOR || it.type == ModelType.MMPROJ || it.type == ModelType.VISION } -> BundleCategory.VISION
    else -> BundleCategory.NORMAL
}

fun SdCuratedBundle.folderCategory(): BundleCategory = when {
    files.any { SD_CAPABILITY_VID_GEN in it.sdCapabilities.orEmpty().split(',') } -> BundleCategory.VIDEO
    files.all { it.modelType == ModelType.SD_UPSCALER } -> BundleCategory.UPSCALING
    SdWorkflowPresetCatalog.bundles.any { it.id == id } ||
        files.any { it.modelType == ModelType.SD_IP_ADAPTER } -> BundleCategory.EDITING
    else -> BundleCategory.NORMAL
}
