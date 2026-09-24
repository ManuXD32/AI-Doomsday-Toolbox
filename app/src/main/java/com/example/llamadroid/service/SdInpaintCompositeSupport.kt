package com.example.llamadroid.service

/** Blends one ARGB pixel with the inpaint convention 0 = preserve and 255 = regenerate. */
internal fun compositeInpaintPixel(sourceArgb: Int, generatedArgb: Int, maskValue: Int): Int {
    val amount = maskValue.coerceIn(0, 255)
    if (amount == 0) return sourceArgb
    if (amount == 255) return generatedArgb
    val inverse = 255 - amount

    fun blend(shift: Int): Int {
        val source = sourceArgb ushr shift and 0xff
        val generated = generatedArgb ushr shift and 0xff
        return (source * inverse + generated * amount + 127) / 255
    }

    return (blend(24) shl 24) or
        (blend(16) shl 16) or
        (blend(8) shl 8) or
        blend(0)
}
