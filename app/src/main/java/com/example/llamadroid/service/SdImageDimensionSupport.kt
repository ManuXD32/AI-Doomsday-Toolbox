package com.example.llamadroid.service

import java.util.Locale

/** Resolution alignment required by the selected diffusion family. */
internal fun requiredSdImageDimensionMultiple(family: String?, variant: String?): Int {
    val normalizedFamily = family?.trim()?.lowercase(Locale.ROOT)
    val normalizedVariant = variant?.trim()?.lowercase(Locale.ROOT)
    return if (
        normalizedFamily == "qwen_image" &&
        normalizedVariant in setOf("2.1", "qwen_image_2.1", "qwen-image-2.1")
    ) {
        32
    } else {
        8
    }
}

internal fun isValidSdImageDimensions(width: Int, height: Int, multipleOf: Int): Boolean =
    multipleOf > 0 && width in 64..4096 && height in 64..4096 &&
        width % multipleOf == 0 && height % multipleOf == 0

internal class SdImageDimensionException(
    val width: Int,
    val height: Int,
    val multipleOf: Int
) : IllegalArgumentException("Image width and height must be divisible by $multipleOf")
