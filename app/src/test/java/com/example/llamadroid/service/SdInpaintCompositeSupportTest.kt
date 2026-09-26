package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Test

class SdInpaintCompositeSupportTest {
    @Test
    fun `black mask preserves the exact source pixel`() {
        val source = 0x7f123456
        assertEquals(source, compositeInpaintPixel(source, 0xffabcdef.toInt(), 0))
    }

    @Test
    fun `white mask takes the generated pixel`() {
        val generated = 0xffabcdef.toInt()
        assertEquals(generated, compositeInpaintPixel(0xff123456.toInt(), generated, 255))
    }

    @Test
    fun `gray mask blends source and generated channels`() {
        assertEquals(
            0xff808080.toInt(),
            compositeInpaintPixel(0xff000000.toInt(), 0xffffffff.toInt(), 128)
        )
    }
}
