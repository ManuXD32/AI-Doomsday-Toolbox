package com.example.llamadroid.ui.easy

import com.example.llamadroid.data.repository.UnifiedServer
import com.example.llamadroid.data.repository.UnifiedServerKind

/** Saved servers an Easy chat can use: local managed (card-backed) servers that are not Easy-mode cards. */
internal fun easySavedServerChoices(servers: List<UnifiedServer>): List<UnifiedServer> =
    servers.filter { it.kind == UnifiedServerKind.LOCAL_MANAGED && it.cardId != null && !it.isEasy }
        .sortedBy { it.name.lowercase() }
