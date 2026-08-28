package com.example.llamadroid.data.model

import com.example.llamadroid.R
import com.example.llamadroid.data.db.ModelType

object LlamaCuratedBundleCatalog {
    private fun file(
        id: String,
        repo: String,
        path: String,
        local: String,
        type: ModelType,
        bytes: Long,
        sha: String,
        license: String,
        note: String = ""
    ) = CuratedBundleFile(
        id = id,
        repoId = repo,
        revision = "main",
        remotePath = path,
        localFilename = local,
        type = type,
        sizeBytes = bytes,
        sha256 = sha,
        license = license,
        note = note
    )

    val bundles: List<CuratedModelBundle> = listOf(
        CuratedModelBundle(
            id = "gemma4-e2b-complete",
            titleRes = R.string.llama_bundle_gemma4_e2b_title,
            descriptionRes = R.string.llama_bundle_gemma4_e2b_desc,
            defaultPrefix = "Gemma4-E2B",
            capabilityRes = listOf(
                R.string.curated_bundle_capability_vision,
                R.string.curated_bundle_capability_mtp
            ),
            files = listOf(
                file("gemma4-e2b-main", "unsloth/gemma-4-E2B-it-GGUF", "gemma-4-E2B-it-Q4_K_M.gguf", "gemma-4-E2B-it-Q4_K_M.gguf", ModelType.LLM, 3_110_000_000L, "740185b21d22ceb83a11c3aa62ad5842ef32c70f6096d756bbee85a1e4ec34b8", "Apache-2.0"),
                file("gemma4-e2b-mmproj", "unsloth/gemma-4-E2B-it-GGUF", "mmproj-BF16.gguf", "mmproj-gemma-4-E2B-it-BF16.gguf", ModelType.VISION_PROJECTOR, 987_000_000L, "a402f10fb5780bf91d03a10cd89061139f522bee2e679b1291bbfdcd71d9547d", "Apache-2.0"),
                file("gemma4-e2b-mtp", "unsloth/gemma-4-E2B-it-GGUF", "mtp-gemma-4-E2B-it.gguf", "mtp-gemma-4-E2B-it.gguf", ModelType.LLM_DRAFT, 97_800_000L, "9eba819938efccfd6044f8af84e3bbfddc639a2bcf32ebc36420e6a649191919", "Apache-2.0")
            )
        ),
        CuratedModelBundle(
            id = "gemma4-e4b-complete",
            titleRes = R.string.llama_bundle_gemma4_e4b_title,
            descriptionRes = R.string.llama_bundle_gemma4_e4b_desc,
            defaultPrefix = "Gemma4-E4B",
            capabilityRes = listOf(
                R.string.curated_bundle_capability_vision,
                R.string.curated_bundle_capability_mtp
            ),
            files = listOf(
                file("gemma4-e4b-main", "unsloth/gemma-4-E4B-it-GGUF", "gemma-4-E4B-it-Q4_K_M.gguf", "gemma-4-E4B-it-Q4_K_M.gguf", ModelType.LLM, 4_980_000_000L, "85a896a047553e842f25297ee5b031d64ff30147d9c4af17b1e4b394cd1fab87", "Apache-2.0"),
                file("gemma4-e4b-mmproj", "unsloth/gemma-4-E4B-it-GGUF", "mmproj-F32.gguf", "mmproj-gemma-4-E4B-it-F32.gguf", ModelType.VISION_PROJECTOR, 1_910_000_000L, "343cdea7775835ebdd1caa6c42ec3ec3e711d082835c72253d4e87c4b7e303d0", "Apache-2.0"),
                file("gemma4-e4b-mtp", "unsloth/gemma-4-E4B-it-GGUF", "mtp-gemma-4-E4B-it.gguf", "mtp-gemma-4-E4B-it.gguf", ModelType.LLM_DRAFT, 98_700_000L, "b6a723115efa510d3b3215db1e26790dae84cd08c2134a764f3d194f1f0c3376", "Apache-2.0")
            )
        ),
        CuratedModelBundle(
            id = "qwen35-08b", titleRes = R.string.llama_bundle_qwen35_08b_title, descriptionRes = R.string.llama_bundle_qwen35_08b_desc, defaultPrefix = "Qwen3.5-0.8B",
            files = listOf(file("qwen35-08b-main", "lmstudio-community/Qwen3.5-0.8B-GGUF", "Qwen3.5-0.8B-Q4_K_M.gguf", "Qwen3.5-0.8B-Q4_K_M.gguf", ModelType.LLM, 528_000_000L, "f5b14da98939b60bbe1019a964eba656407e1e0b64f1fe3003ff6d650e93bfec", "Apache-2.0"))
        ),
        CuratedModelBundle(
            id = "qwen35-2b", titleRes = R.string.llama_bundle_qwen35_2b_title, descriptionRes = R.string.llama_bundle_qwen35_2b_desc, defaultPrefix = "Qwen3.5-2B",
            files = listOf(file("qwen35-2b-main", "unsloth/Qwen3.5-2B-GGUF", "Qwen3.5-2B-Q4_K_M.gguf", "Qwen3.5-2B-Q4_K_M.gguf", ModelType.LLM, 1_280_000_000L, "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223", "Apache-2.0"))
        ),
        CuratedModelBundle(
            id = "qwen35-4b", titleRes = R.string.llama_bundle_qwen35_4b_title, descriptionRes = R.string.llama_bundle_qwen35_4b_desc, defaultPrefix = "Qwen3.5-4B",
            files = listOf(file("qwen35-4b-main", "lmstudio-community/Qwen3.5-4B-GGUF", "Qwen3.5-4B-Q4_K_M.gguf", "Qwen3.5-4B-Q4_K_M.gguf", ModelType.LLM, 2_710_000_000L, "25082a7dd3776cc3c741c6347d3bd04523f05796607b3fbc32fa3a25dfa1418c", "Apache-2.0"))
        ),
        CuratedModelBundle(
            id = "qwen35-9b", titleRes = R.string.llama_bundle_qwen35_9b_title, descriptionRes = R.string.llama_bundle_qwen35_9b_desc, defaultPrefix = "Qwen3.5-9B",
            files = listOf(file("qwen35-9b-main", "lmstudio-community/Qwen3.5-9B-GGUF", "Qwen3.5-9B-Q4_K_M.gguf", "Qwen3.5-9B-Q4_K_M.gguf", ModelType.LLM, 5_630_000_000L, "cd76ec205963b3b33350093e6904d9de16c4e666fd104e1f632d25c7f15f2a13", "Apache-2.0"))
        ),
        CuratedModelBundle(id = "lfm25-230m", titleRes = R.string.llama_bundle_lfm25_230m_title, descriptionRes = R.string.llama_bundle_lfm25_230m_desc, defaultPrefix = "LFM2.5-230M", files = listOf(file("lfm25-230m-main", "LiquidAI/LFM2.5-230M-GGUF", "LFM2.5-230M-Q4_K_M.gguf", "LFM2.5-230M-Q4_K_M.gguf", ModelType.LLM, 153_000_000L, "7bbd90384d3deffe4c646ec9643b212802d32d4ce417c90a1ec9282100650062", "LFM-1.0"))),
        CuratedModelBundle(id = "lfm25-350m", titleRes = R.string.llama_bundle_lfm25_350m_title, descriptionRes = R.string.llama_bundle_lfm25_350m_desc, defaultPrefix = "LFM2.5-350M", files = listOf(file("lfm25-350m-main", "LiquidAI/LFM2.5-350M-GGUF", "LFM2.5-350M-Q4_K_M.gguf", "LFM2.5-350M-Q4_K_M.gguf", ModelType.LLM, 229_000_000L, "7e6f72643caafc9a68256686638c4d7916f2cec76d1df478d4c3ddcd95a6aed4", "LFM-1.0"))),
        CuratedModelBundle(id = "lfm25-12b-instruct", titleRes = R.string.llama_bundle_lfm25_12b_instruct_title, descriptionRes = R.string.llama_bundle_lfm25_12b_instruct_desc, defaultPrefix = "LFM2.5-1.2B-Instruct", files = listOf(file("lfm25-12b-instruct-main", "LiquidAI/LFM2.5-1.2B-Instruct-GGUF", "LFM2.5-1.2B-Instruct-Q4_K_M.gguf", "LFM2.5-1.2B-Instruct-Q4_K_M.gguf", ModelType.LLM, 730_895_168L, "b1b3de114215d9507409a662a501a631095a479a419584e8a2ded6304b19b4f5", "LFM-1.0"))),
        CuratedModelBundle(id = "lfm25-12b-thinking", titleRes = R.string.llama_bundle_lfm25_12b_thinking_title, descriptionRes = R.string.llama_bundle_lfm25_12b_thinking_desc, defaultPrefix = "LFM2.5-1.2B-Thinking", files = listOf(file("lfm25-12b-thinking-main", "LiquidAI/LFM2.5-1.2B-Thinking-GGUF", "LFM2.5-1.2B-Thinking-Q4_K_M.gguf", "LFM2.5-1.2B-Thinking-Q4_K_M.gguf", ModelType.LLM, 731_000_000L, "7223a2202405b02e8e1e6c5baa543c43dc98c1d9741a5c2a0ee1583212e1231b", "LFM-1.0"))),
        CuratedModelBundle(id = "lfm2-26b", titleRes = R.string.llama_bundle_lfm2_26b_title, descriptionRes = R.string.llama_bundle_lfm2_26b_desc, defaultPrefix = "LFM2-2.6B", files = listOf(file("lfm2-26b-main", "LiquidAI/LFM2-2.6B-GGUF", "LFM2-2.6B-Q4_K_M.gguf", "LFM2-2.6B-Q4_K_M.gguf", ModelType.LLM, 1_560_000_000L, "384bc877b6c37064982f96885bef69e4475919f5969218ed4e3b9399ae0340df", "LFM-1.0"))),
        CuratedModelBundle(id = "lfm25-8b-a1b", titleRes = R.string.llama_bundle_lfm25_8b_a1b_title, descriptionRes = R.string.llama_bundle_lfm25_8b_a1b_desc, defaultPrefix = "LFM2.5-8B-A1B", files = listOf(file("lfm25-8b-a1b-main", "LiquidAI/LFM2.5-8B-A1B-GGUF", "LFM2.5-8B-A1B-Q4_K_M.gguf", "LFM2.5-8B-A1B-Q4_K_M.gguf", ModelType.LLM, 5_160_000_000L, "4923ec14f06b968b74d663e5949867d2d9c3bf13a20b8be1a9f9af39989b2bb0", "LFM-1.0")))
    )
}
