package com.example.llamadroid.service

enum class LlamaSpeculativeMode(val flagValue: String) {
    DRAFT_SIMPLE("draft-simple"),
    DRAFT_MTP("draft-mtp"),
    DRAFT_DFLASH("draft-dflash"),
    NGRAM_MOD("ngram-mod"),
    NGRAM_SIMPLE("ngram-simple"),
    NGRAM_MAP_K("ngram-map-k"),
    NGRAM_MAP_K4V("ngram-map-k4v"),
    NGRAM_CACHE("ngram-cache");

    val requiresDraftModel: Boolean
        get() = this == DRAFT_SIMPLE || this == DRAFT_DFLASH

    companion object {
        fun fromFlagValue(value: String?): LlamaSpeculativeMode =
            entries.firstOrNull { it.flagValue == value } ?: DRAFT_SIMPLE
    }
}
