package com.example.llamadroid.data.easy

import com.example.llamadroid.data.model.LlamaServerCardEntity

/**
 * Easy mode owns its own server cards. They are identified by `easyModelId = "easy:" + filename`; the
 * advanced Dashboard / Quick Setup cards keep the plain filename, so the two kinds are never shared.
 */
const val EASY_MODE_CARD_PREFIX = "easy:"

/** True when [card] belongs to Easy mode (hidden from every advanced list and picker). */
fun isEasyModeCard(card: LlamaServerCardEntity): Boolean =
    card.easyModelId?.startsWith(EASY_MODE_CARD_PREFIX) == true

/** The `easyModelId` of the Easy-mode card serving the model file [filename]. */
fun easyModeCardModelId(filename: String): String = EASY_MODE_CARD_PREFIX + filename

/** Inverse of [easyModeCardModelId]; null when [id] is not an Easy-mode card id. */
fun filenameFromEasyModeCardId(id: String?): String? =
    id?.takeIf { it.startsWith(EASY_MODE_CARD_PREFIX) }?.removePrefix(EASY_MODE_CARD_PREFIX)
