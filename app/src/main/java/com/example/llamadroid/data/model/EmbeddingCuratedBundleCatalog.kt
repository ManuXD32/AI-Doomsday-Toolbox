package com.example.llamadroid.data.model

import com.example.llamadroid.R
import com.example.llamadroid.data.db.ModelType

/**
 * Hash-pinned embedding models for the knowledge base. Files are stored as
 * [ModelType.EMBEDDING] so the KB embedding picker lists them once downloaded.
 * Pooling comes from the GGUF metadata, so llama-server needs no extra flag.
 */
object EmbeddingCuratedBundleCatalog {
    const val RECOMMENDED_ID = "embeddinggemma-300m"

    private fun bundle(
        id: String,
        titleRes: Int,
        descriptionRes: Int,
        prefix: String,
        repo: String,
        revision: String,
        remote: String,
        size: Long,
        sha256: String,
        license: String
    ) = CuratedModelBundle(
        id = id,
        titleRes = titleRes,
        descriptionRes = descriptionRes,
        defaultPrefix = prefix,
        capabilityRes = listOf(R.string.embedding_bundle_capability),
        files = listOf(
            CuratedBundleFile(
                id = "$id-main",
                repoId = repo,
                revision = revision,
                remotePath = remote,
                localFilename = remote,
                type = ModelType.EMBEDDING,
                sizeBytes = size,
                sha256 = sha256,
                license = license,
                strictSize = true
            )
        )
    )

    val bundles: List<CuratedModelBundle> = listOf(
        bundle(
            id = RECOMMENDED_ID,
            titleRes = R.string.embedding_bundle_gemma_title,
            descriptionRes = R.string.embedding_bundle_gemma_desc,
            prefix = "Embedding-Gemma-300M",
            repo = "ggml-org/embeddinggemma-300M-GGUF",
            revision = "0f741b5a6585bd53aeb15cd1372c56f2a0f65e12",
            remote = "embeddinggemma-300M-Q8_0.gguf",
            size = 333_590_944L,
            sha256 = "b5ce9d77a3fc4b3b39ccb5643c36777911cc4eb46a66962eadfa3f5f60490d63",
            license = "Gemma"
        ),
        bundle(
            id = "qwen3-embedding-0-6b",
            titleRes = R.string.embedding_bundle_qwen_title,
            descriptionRes = R.string.embedding_bundle_qwen_desc,
            prefix = "Embedding-Qwen3-0.6B",
            repo = "Qwen/Qwen3-Embedding-0.6B-GGUF",
            revision = "370f27d7550e0def9b39c1f16d3fbaa13aa67728",
            remote = "Qwen3-Embedding-0.6B-Q8_0.gguf",
            size = 639_150_592L,
            sha256 = "06507c7b42688469c4e7298b0a1e16deff06caf291cf0a5b278c308249c3e439",
            license = "Apache-2.0"
        ),
        bundle(
            id = "nomic-embed-text-v1-5",
            titleRes = R.string.embedding_bundle_nomic_title,
            descriptionRes = R.string.embedding_bundle_nomic_desc,
            prefix = "Embedding-Nomic-v1.5",
            repo = "nomic-ai/nomic-embed-text-v1.5-GGUF",
            revision = "0188c9bf409793f810680a5a431e7b899c46104c",
            remote = "nomic-embed-text-v1.5.Q4_K_M.gguf",
            size = 84_106_624L,
            sha256 = "d4e388894e09cf3816e8b0896d81d265b55e7a9fff9ab03fe8bf4ef5e11295ac",
            license = "Apache-2.0"
        )
    )
}
