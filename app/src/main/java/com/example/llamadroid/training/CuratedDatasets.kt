package com.example.llamadroid.training

enum class DatasetGroup { CHAT, GENERAL_KNOWLEDGE, CODING, TOOL_USE, STORIES, SPANISH }

/** One downloadable file of a curated dataset. */
data class CuratedFile(val path: String, val sizeBytes: Long)

/**
 * A curated Hugging Face dataset. Ids, licences, paths and sizes were checked against the Hub on
 * 2026-11-17. [revision] is "main" until pinned to a commit. [config]/[split] are informational for
 * the datasets-server; the download uses [files]. [defaultCapMb] is the default amount of text the
 * prepare step reads from the dataset (the full files are always downloaded).
 */
data class CuratedDataset(
    val id: String,
    val title: String,
    val group: DatasetGroup,
    val repo: String,
    val revision: String = "main",
    val config: String? = null,
    val split: String? = "train",
    val files: List<CuratedFile>,
    val format: DatasetFormat,
    /** `prepare --columns` pairs; see [DatasetHelpers.columnMapJson]. */
    val columnMap: Map<String, String>,
    val defaultCapMb: Int,
    val licence: String,
    /** True when the Hub requires an accepted agreement plus an HF token. */
    val gated: Boolean = false,
    /** True when the data carries a non-commercial or restrictive provider-terms caveat. */
    val nonCommercial: Boolean = false
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
}

object CuratedDatasets {
    private val messages = mapOf("messages" to "messages", "role" to "role", "content" to "content")
    private val shareGpt = mapOf(
        "messages" to "conversations", "role" to "from", "content" to "value",
        "role_alias" to "human:user|gpt:assistant|system:system|tool:tool"
    )
    private val alpaca = mapOf("instruction" to "instruction", "input" to "input", "output" to "output")
    private val text = mapOf("text" to "text")

    val all: List<CuratedDataset> = listOf(
        CuratedDataset(
            id = "smol-smoltalk", title = "SmolTalk (chat, curated)", group = DatasetGroup.CHAT,
            repo = "HuggingFaceTB/smol-smoltalk", config = "default",
            files = listOf(CuratedFile("data/train-00000-of-00004.parquet", 229_710_350)),
            format = DatasetFormat.CHAT, columnMap = messages, defaultCapMb = 200, licence = "apache-2.0"
        ),
        CuratedDataset(
            id = "ultrachat-200k", title = "UltraChat 200k (multi-turn chat)", group = DatasetGroup.CHAT,
            repo = "HuggingFaceH4/ultrachat_200k", config = "default", split = "train_sft",
            files = listOf(CuratedFile("data/train_sft-00000-of-00003-a3ecf92756993583.parquet", 243_999_189)),
            format = DatasetFormat.CHAT, columnMap = messages, defaultCapMb = 200, licence = "mit"
        ),
        CuratedDataset(
            id = "cosmopedia-100k", title = "Cosmopedia 100k (textbook-style text)", group = DatasetGroup.GENERAL_KNOWLEDGE,
            repo = "HuggingFaceTB/cosmopedia-100k", config = "default",
            files = listOf(
                CuratedFile("data/train-00000-of-00002.parquet", 153_421_593),
                CuratedFile("data/train-00001-of-00002.parquet", 153_206_051)
            ),
            format = DatasetFormat.TEXT, columnMap = text, defaultCapMb = 250, licence = "apache-2.0"
        ),
        CuratedDataset(
            id = "wikipedia-en", title = "Wikipedia EN (2023-11-01, one shard)", group = DatasetGroup.GENERAL_KNOWLEDGE,
            repo = "wikimedia/wikipedia", config = "20231101.en",
            files = listOf(CuratedFile("20231101.en/train-00005-of-00041.parquet", 243_974_056)),
            format = DatasetFormat.TEXT, columnMap = text, defaultCapMb = 200, licence = "cc-by-sa-3.0, gfdl"
        ),
        CuratedDataset(
            id = "codealpaca-20k", title = "CodeAlpaca 20k (code instructions)", group = DatasetGroup.CODING,
            repo = "sahil2801/CodeAlpaca-20k",
            files = listOf(CuratedFile("code_alpaca_20k.json", 8_057_896)),
            format = DatasetFormat.INSTRUCTION, columnMap = alpaca, defaultCapMb = 8, licence = "cc-by-4.0",
            nonCommercial = true
        ),
        CuratedDataset(
            id = "code-instructions-120k", title = "Code Instructions 120k", group = DatasetGroup.CODING,
            repo = "TokenBender/code_instructions_122k_alpaca_style",
            files = listOf(CuratedFile("code_instructions_120k.json", 169_499_006)),
            format = DatasetFormat.INSTRUCTION, columnMap = alpaca, defaultCapMb = 150, licence = "apache-2.0",
            nonCommercial = true
        ),
        CuratedDataset(
            id = "hermes-function-calling", title = "Hermes function calling v1", group = DatasetGroup.TOOL_USE,
            repo = "NousResearch/hermes-function-calling-v1", config = "func_calling", split = "train",
            files = listOf(
                CuratedFile("func-calling-singleturn.json", 17_793_686),
                CuratedFile("func-calling.json", 20_554_130)
            ),
            format = DatasetFormat.CHAT, columnMap = shareGpt + ("tools" to "tools"),
            defaultCapMb = 40, licence = "apache-2.0"
        ),
        CuratedDataset(
            id = "glaive-function-calling-v2", title = "Glaive function calling v2", group = DatasetGroup.TOOL_USE,
            repo = "glaiveai/glaive-function-calling-v2",
            files = listOf(CuratedFile("glaive-function-calling-v2.json", 271_190_065)),
            format = DatasetFormat.CHAT,
            // `chat` is one string with "USER:" / "ASSISTANT:" / "FUNCTION RESPONSE:" turns and <functioncall> calls.
            columnMap = mapOf("system" to "system", "text" to "chat", "style" to "glaive"),
            defaultCapMb = 150, licence = "apache-2.0"
        ),
        CuratedDataset(
            id = "xlam-function-calling-60k", title = "xLAM function calling 60k (gated)", group = DatasetGroup.TOOL_USE,
            repo = "Salesforce/xlam-function-calling-60k",
            files = listOf(CuratedFile("xlam_function_calling_60k.json", 96_116_570)),
            format = DatasetFormat.INSTRUCTION,
            columnMap = mapOf("instruction" to "query", "tools" to "tools", "output" to "answers", "style" to "xlam"),
            defaultCapMb = 90, licence = "cc-by-4.0", gated = true
        ),
        CuratedDataset(
            id = "tinystories", title = "TinyStories (short stories)", group = DatasetGroup.STORIES,
            repo = "roneneldan/TinyStories", config = "default",
            files = listOf(CuratedFile("data/train-00000-of-00004-2d5a1467fff1081b.parquet", 248_731_111)),
            format = DatasetFormat.TEXT, columnMap = text, defaultCapMb = 250, licence = "cdla-sharing-1.0"
        ),
        CuratedDataset(
            id = "openhermes-2.5-es", title = "OpenHermes 2.5 (Spanish chat)", group = DatasetGroup.SPANISH,
            repo = "Iker/OpenHermes-2.5-Spanish", config = "default",
            files = listOf(CuratedFile("data/train-00001-of-00004.parquet", 167_514_869)),
            format = DatasetFormat.CHAT, columnMap = shareGpt, defaultCapMb = 150, licence = "apache-2.0",
            nonCommercial = true
        ),
        CuratedDataset(
            id = "alpaca-spanish", title = "Alpaca Spanish (instructions)", group = DatasetGroup.SPANISH,
            repo = "bertin-project/alpaca-spanish", config = "default",
            files = listOf(CuratedFile("data/train-00000-of-00001-3a8ceb2c27df896f.parquet", 13_178_075)),
            format = DatasetFormat.INSTRUCTION, columnMap = alpaca, defaultCapMb = 13, licence = "cc-by-4.0",
            nonCommercial = true
        ),
        CuratedDataset(
            id = "wikipedia-es", title = "Wikipedia ES (2023-11-01, one shard)", group = DatasetGroup.SPANISH,
            repo = "wikimedia/wikipedia", config = "20231101.es",
            files = listOf(CuratedFile("20231101.es/train-00003-of-00013.parquet", 244_961_668)),
            format = DatasetFormat.TEXT, columnMap = text, defaultCapMb = 200, licence = "cc-by-sa-3.0, gfdl"
        )
    )

    fun byId(id: String): CuratedDataset? = all.firstOrNull { it.id == id }

    fun byGroup(): Map<DatasetGroup, List<CuratedDataset>> =
        DatasetGroup.entries.associateWith { group -> all.filter { it.group == group } }
}
