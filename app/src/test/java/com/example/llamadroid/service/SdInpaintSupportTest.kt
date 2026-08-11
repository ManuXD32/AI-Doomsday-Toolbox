package com.example.llamadroid.service

import org.junit.Test
import java.io.File

class SdInpaintSupportTest {
    @Test fun `valid inpaint source and mask pass validation`() {
        val source = File.createTempFile("source", ".png")
        val mask = File.createTempFile("mask", ".png")
        try {
            validateSdInpaintInputs(source.absolutePath, mask.absolutePath, 0.75f, 512, 512) { _, _ ->
                SdInpaintImageInspection(512, 512, hasEditablePixels = true)
            }
        } finally {
            source.delete()
            mask.delete()
        }
    }

    @Test(expected = SdInpaintConfigurationException::class)
    fun `missing mask blocks launch`() {
        val source = File.createTempFile("source", ".png")
        try {
            validateSdInpaintInputs(source.absolutePath, null, 0.75f, 512, 512)
        } finally {
            source.delete()
        }
    }

    @Test(expected = SdInpaintConfigurationException::class)
    fun `mismatched source and mask dimensions block launch`() {
        val source = File.createTempFile("source", ".png")
        val mask = File.createTempFile("mask", ".png")
        try {
            validateSdInpaintInputs(source.absolutePath, mask.absolutePath, 0.75f, 512, 512) { file, _ ->
                if (file == source) SdInpaintImageInspection(512, 512) else SdInpaintImageInspection(256, 256)
            }
        } finally {
            source.delete()
            mask.delete()
        }
    }

    @Test(expected = SdInpaintConfigurationException::class)
    fun `all black mask blocks launch`() {
        val source = File.createTempFile("source", ".png")
        val mask = File.createTempFile("mask", ".png")
        try {
            validateSdInpaintInputs(source.absolutePath, mask.absolutePath, 0.75f, 512, 512) { _, inspectEditablePixels ->
                SdInpaintImageInspection(512, 512, hasEditablePixels = !inspectEditablePixels)
            }
        } finally {
            source.delete()
            mask.delete()
        }
    }
}
