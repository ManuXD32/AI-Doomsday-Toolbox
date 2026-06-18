package com.example.llamadroid.service

enum class LlamaSpeculativeMode(val flagValue: String) {
    DRAFT_SIMPLE("draft-simple"),
    DRAFT_MTP("draft-mtp");

    companion object {
        fun fromFlagValue(value: String?): LlamaSpeculativeMode =
            entries.firstOrNull { it.flagValue == value } ?: DRAFT_SIMPLE
    }
}
