package com.example.llamadroid.ui.easy

import org.junit.Assert.assertEquals
import org.junit.Test

class EasyChatOpenLogicTest {
    @Test fun `bound chat opens its server even without a selected model`() {
        assertEquals(EasyChatOpenAction.Open(7L), easyChatOpenAction(7L, null))
        assertEquals(EasyChatOpenAction.Open(7L), easyChatOpenAction(7L, "m.gguf"))
    }

    @Test fun `orphan chat rebinds to the selected model`() {
        assertEquals(EasyChatOpenAction.Rebind("m.gguf"), easyChatOpenAction(null, "m.gguf"))
    }

    @Test fun `orphan chat without a model asks to pick one`() {
        assertEquals(EasyChatOpenAction.PickModel, easyChatOpenAction(null, null))
        assertEquals(EasyChatOpenAction.PickModel, easyChatOpenAction(null, "  "))
    }
}
