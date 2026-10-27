package com.example.llamadroid.tama.ui

/**
 * Farm art is baked clean (crop edge fringes removed offline, see docs/farm-pixel-assets.md), so assets load
 * as-is. Kept as a pass-through so the remaining call sites (store, Tama screen) need no change.
 */
@Suppress("NOTHING_TO_INLINE")
internal inline fun rememberFarmAssetModel(assetUri: String): Any = assetUri
