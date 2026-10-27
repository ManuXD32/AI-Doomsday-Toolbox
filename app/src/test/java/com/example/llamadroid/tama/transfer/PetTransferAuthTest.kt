package com.example.llamadroid.tama.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PetTransferAuthTest {
    private val hello = Hello("t1", "pet1", "Pixel", "dragon", "BABY", 10, "ab", 22, "dev-a", "Phone A")

    @Test fun codeIsSixDigits() {
        repeat(50) {
            val code = PetTransferAuth.generateCode()
            assertEquals(6, code.length)
            assertTrue(PetTransferAuth.isWellFormedCode(code))
        }
        assertFalse(PetTransferAuth.isWellFormedCode("12345"))
        assertFalse(PetTransferAuth.isWellFormedCode("12345a"))
    }

    @Test fun signedHelloVerifies() {
        val signed = PetTransferAuth.sign(hello, "123456")
        assertTrue(signed.hmac.isNotEmpty())
        assertTrue(PetTransferAuth.verify(signed, "123456"))
    }

    @Test fun wrongCodeRejected() {
        assertFalse(PetTransferAuth.verify(PetTransferAuth.sign(hello, "123456"), "654321"))
    }

    @Test fun tamperedFieldsRejected() {
        val signed = PetTransferAuth.sign(hello, "123456")
        assertFalse(PetTransferAuth.verify(signed.copy(zipSha256 = "cd"), "123456"))
        assertFalse(PetTransferAuth.verify(signed.copy(petName = "Other"), "123456"))
        assertFalse(PetTransferAuth.verify(signed.copy(isReturn = true), "123456"))
        assertFalse(PetTransferAuth.verify(signed.copy(zipBytes = 11), "123456"))
    }

    @Test fun malformedOrMissingHmacRejected() {
        assertFalse(PetTransferAuth.verify(hello, "123456"))
        assertFalse(PetTransferAuth.verify(hello.copy(hmac = "zz"), "123456"))
        assertFalse(PetTransferAuth.verify(hello.copy(hmac = "abc"), "123456"))
    }

    @Test fun fieldBoundariesAreUnambiguous() {
        val a = hello.copy(petId = "ab", petName = "c")
        val b = hello.copy(petId = "a", petName = "bc")
        assertFalse(PetTransferAuth.verify(PetTransferAuth.sign(a, "111111").copy(petId = b.petId, petName = b.petName), "111111"))
    }
}
