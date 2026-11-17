package com.example.llamadroid.chat.cards

/** Card kinds the model can write; the user can switch each one off globally (see [ChatCardsDefaults]). */
enum class ChatCardType(val key: String) {
    CHOICES("choices"),
    FORM("form"),
    INPUT("input"),
    FOLLOWUPS("followups"),
    MAP("map"),
    PLACES("places"),
    IMAGES("images"),
    WEATHER("weather"),
    BUDGET("budget"),
    STATS("stats"),
    TIMELINE("timeline"),
    CALLOUT("callout"),
    LINKS("links"),
    CHART("chart"),
    OMNI("omni");

    companion object {
        fun fromKey(key: String): ChatCardType? = entries.firstOrNull { it.key == key }
    }
}
