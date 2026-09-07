package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.KeyPairGenerator

class SelfSignedCertGeneratorTest {
    @Test
    fun `generated certificate is valid and signed by its own key`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }.generateKeyPair()

        val certificate = SelfSignedCertGenerator.generate(keyPair)

        certificate.checkValidity()
        certificate.verify(keyPair.public)
        assertEquals(keyPair.public, certificate.publicKey)
        assertEquals(certificate.subjectX500Principal, certificate.issuerX500Principal)
    }
}
