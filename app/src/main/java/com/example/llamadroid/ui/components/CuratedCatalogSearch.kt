package com.example.llamadroid.ui.components

/** Curated bundles are a browse affordance, not part of a specific-model search. */
internal fun isCuratedCatalogBrowseMode(query: String): Boolean = query.isBlank()
