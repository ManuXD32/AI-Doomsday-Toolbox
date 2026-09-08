package com.example.llamadroid.data.model

import com.example.llamadroid.R
import com.example.llamadroid.data.db.ModelType

/** Hash-pinned launch bundles for llama.cpp's native speech adapter. */
object AudioCuratedBundleCatalog {
    private const val QWEN_REVISION = "ca27d74bc954b73dadab5b71ca265d87fc861a7c"
    private const val POCKET_REVISION = "daf229a0492829811624c34a44b92d7738c158c8"
    private const val QWEN_REPO = "ggml-org/Qwen3-TTS-12Hz-1.7B-Base-GGUF"
    private const val POCKET_REPO = "EryriLabs/pocket-tts-GGUF"

    private fun qwenFile(
        id: String,
        remotePath: String,
        localFilename: String,
        type: ModelType,
        size: Long,
        sha256: String,
        role: String,
        sharedKey: String? = null
    ) = CuratedBundleFile(
        id = id,
        repoId = QWEN_REPO,
        revision = QWEN_REVISION,
        remotePath = remotePath,
        localFilename = localFilename,
        type = type,
        sizeBytes = size,
        sha256 = sha256,
        license = "Apache-2.0",
        strictSize = true,
        componentRole = role,
        audioFamily = AudioModelSupport.FAMILY_QWEN3_TTS,
        sharedArtifactKey = sharedKey,
        note = "Pinned to Qwen3-TTS 1.7B Base GGUF revision $QWEN_REVISION"
    )

    private fun pocketFile(
        id: String,
        remotePath: String,
        localFilename: String,
        type: ModelType,
        size: Long,
        sha256: String,
        role: String,
        language: String
    ) = CuratedBundleFile(
        id = id,
        repoId = POCKET_REPO,
        revision = POCKET_REVISION,
        remotePath = remotePath,
        localFilename = localFilename,
        type = type,
        sizeBytes = size,
        sha256 = sha256,
        license = "CC-BY-4.0",
        strictSize = true,
        componentRole = role,
        audioFamily = AudioModelSupport.FAMILY_POCKET_TTS,
        audioLanguage = language,
        note = "Pocket TTS $language model/companion pair pinned to revision $POCKET_REVISION"
    )

    private val qwenSharedMmproj = qwenFile(
        id = "qwen3-tts-17b-mmproj-q8",
        remotePath = "mmproj-Qwen3-TTS-12Hz-1.7B-Base-Q8_0.gguf",
        localFilename = "mmproj-Qwen3-TTS-12Hz-1.7B-Base-Q8_0.gguf",
        type = ModelType.LLAMA_TTS_COMPANION,
        size = 446_422_912L,
        sha256 = "6fd65188839bcd6ecc91b277ad471e22a0edfada4699a0fe82f1165c18cfcce2",
        role = AudioModelSupport.ROLE_MMProj,
        sharedKey = "sha256:6fd65188839bcd6ecc91b277ad471e22a0edfada4699a0fe82f1165c18cfcce2"
    )

    val bundles: List<CuratedModelBundle> = listOf(
        CuratedModelBundle(
            id = "audio-qwen3-tts-17b-q4-k-m",
            titleRes = R.string.audio_bundle_qwen3_tts_q4_title,
            descriptionRes = R.string.audio_bundle_qwen3_tts_q4_desc,
            defaultPrefix = "Qwen3-TTS-1.7B-Q4_K_M",
            capabilityRes = listOf(R.string.audio_bundle_capability_voice_cloning, R.string.audio_bundle_capability_cpu),
            files = listOf(
                qwenFile("qwen3-tts-17b-q4-main", "Qwen3-TTS-12Hz-1.7B-Base-Q4_K_M.gguf", "Qwen3-TTS-12Hz-1.7B-Base-Q4_K_M.gguf", ModelType.LLAMA_TTS, 1_035_965_280L, "8d18c94acb2addd042f97da63c98be144eafa76d0d9495177eab65130cf85129", AudioModelSupport.ROLE_MAIN),
                qwenSharedMmproj
            )
        ),
        CuratedModelBundle(
            id = "audio-qwen3-tts-17b-q8-0",
            titleRes = R.string.audio_bundle_qwen3_tts_q8_title,
            descriptionRes = R.string.audio_bundle_qwen3_tts_q8_desc,
            defaultPrefix = "Qwen3-TTS-1.7B-Q8_0",
            capabilityRes = listOf(R.string.audio_bundle_capability_voice_cloning, R.string.audio_bundle_capability_cpu),
            files = listOf(
                qwenFile("qwen3-tts-17b-q8-main", "Qwen3-TTS-12Hz-1.7B-Base-Q8_0.gguf", "Qwen3-TTS-12Hz-1.7B-Base-Q8_0.gguf", ModelType.LLAMA_TTS, 1_847_874_400L, "ac7931aeb2e7aad1a6ed6602d353a5679c9d096b18ce8204ac730a8408d572e1", AudioModelSupport.ROLE_MAIN),
                qwenSharedMmproj
            )
        ),
        CuratedModelBundle(
            id = "audio-pocket-tts-english",
            titleRes = R.string.audio_bundle_pocket_tts_en_title,
            descriptionRes = R.string.audio_bundle_pocket_tts_en_desc,
            defaultPrefix = "Pocket-TTS-English",
            capabilityRes = listOf(R.string.audio_bundle_capability_voice_cloning, R.string.audio_bundle_capability_cpu),
            files = listOf(
                pocketFile("pocket-tts-en-main", "pocket-tts-en.gguf", "pocket-tts-en.gguf", ModelType.LLAMA_TTS, 159_390_816L, "9cca37ab8f3da366a1864919214fbd545f45609d8f1167d1fb302cebea515d63", AudioModelSupport.ROLE_MAIN, AudioModelSupport.LANGUAGE_ENGLISH),
                pocketFile("pocket-tts-en-mmproj", "mmproj-pocket-tts-en.gguf", "mmproj-pocket-tts-en.gguf", ModelType.LLAMA_TTS_COMPANION, 59_858_080L, "ccb1b6a55a7df0d4f96adff04ae3b9cd99c070edc98f6c84af5adb6d46a64cfc", AudioModelSupport.ROLE_MMProj, AudioModelSupport.LANGUAGE_ENGLISH)
            )
        ),
        CuratedModelBundle(
            id = "audio-pocket-tts-spanish",
            titleRes = R.string.audio_bundle_pocket_tts_es_title,
            descriptionRes = R.string.audio_bundle_pocket_tts_es_desc,
            defaultPrefix = "Pocket-TTS-Spanish",
            capabilityRes = listOf(R.string.audio_bundle_capability_voice_cloning, R.string.audio_bundle_capability_cpu),
            files = listOf(
                pocketFile("pocket-tts-es-main", "spanish/pocket-tts-spanish.gguf", "pocket-tts-spanish.gguf", ModelType.LLAMA_TTS, 159_392_320L, "aa823005551f4600e3ab7a871aa023cfbb268aaef2bfb812654ad349da351d51", AudioModelSupport.ROLE_MAIN, AudioModelSupport.LANGUAGE_SPANISH),
                pocketFile("pocket-tts-es-mmproj", "spanish/mmproj-pocket-tts-spanish.gguf", "mmproj-pocket-tts-spanish.gguf", ModelType.LLAMA_TTS_COMPANION, 59_858_080L, "3fe3df5e6a569eb0a85cb6adaf6526652f8308ad9c011044777f547891ddb8c5", AudioModelSupport.ROLE_MMProj, AudioModelSupport.LANGUAGE_SPANISH)
            )
        )
    )
}
