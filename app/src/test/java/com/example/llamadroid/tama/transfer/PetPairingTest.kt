package com.example.llamadroid.tama.transfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PetPairingTest {
    private val code = "123456"
    private val hello = Hello("t1", "pet1", "Pixel", "dragon", "BABY", 10, "ab", 22, "dev-a", "Phone A")

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun hkdfMatchesRfc5869Case1() {
        val okm = PetPairing.hkdf(
            hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42
        )
        assertArrayEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), okm
        )
    }

    private fun handshake(helloCode: String = code, receiverCode: String = code): Pair<PairingResult?, PairingResult?> {
        val a = PetPairing.generate()
        val b = PetPairing.generate()
        val sent = PetPairing.attachToHello(PetTransferAuth.sign(hello, helloCode), helloCode, a, "dev-a")
        val reply = if (PetPairing.helloOfferValid(sent, receiverCode)) {
            PetPairing.answer(sent, receiverCode, "dev-b", "Phone B", b)
        } else HelloReply(true)
        val recv = PetPairing.finishAsReceiver(sent, receiverCode, b, "dev-b")
        val send = PetPairing.finishAsSender(sent, reply, helloCode, a, "dev-a")
        return send to recv
    }

    @Test fun bothSidesDeriveSameKey() {
        val (send, recv) = handshake()
        assertNotNull(send); assertNotNull(recv)
        assertEquals(32, send!!.key.size)
        assertArrayEquals(send.key, recv!!.key)
        assertEquals("dev-b", send.peerDeviceId)
        assertEquals("Phone B", send.peerName)
        assertEquals("dev-a", recv.peerDeviceId)
        assertEquals("Phone A", recv.peerName)
    }

    @Test fun eachPairingGetsAFreshKey() {
        val first = handshake().first!!.key
        val second = handshake().first!!.key
        assertFalse(first.contentEquals(second))
    }

    @Test fun differentCodesNoPairing() {
        val (send, recv) = handshake(helloCode = "111111", receiverCode = "222222")
        assertNull(send); assertNull(recv)
    }

    @Test fun swappedHelloKeyRejected() {
        val a = PetPairing.generate()
        val mitm = PetPairing.generate()
        val sent = PetPairing.attachToHello(PetTransferAuth.sign(hello, code), code, a, "dev-a")
        assertTrue(PetPairing.helloOfferValid(sent, code))
        assertFalse(PetPairing.helloOfferValid(sent.copy(pairPub = mitm.publicB64), code))
        assertFalse(PetPairing.helloOfferValid(sent.copy(deviceId = "dev-x"), code))
        assertFalse(PetPairing.helloOfferValid(sent.copy(transferId = "other"), code))
    }

    @Test fun swappedReplyKeyOrIdentityRejected() {
        val a = PetPairing.generate()
        val b = PetPairing.generate()
        val mitm = PetPairing.generate()
        val sent = PetPairing.attachToHello(PetTransferAuth.sign(hello, code), code, a, "dev-a")
        val reply = PetPairing.answer(sent, code, "dev-b", "Phone B", b)
        assertNotNull(PetPairing.finishAsSender(sent, reply, code, a, "dev-a"))
        assertNull(PetPairing.finishAsSender(sent, reply.copy(pairPub = mitm.publicB64), code, a, "dev-a"))
        assertNull(PetPairing.finishAsSender(sent, reply.copy(deviceId = "dev-evil"), code, a, "dev-a"))
        assertNull(PetPairing.finishAsSender(sent, reply.copy(deviceName = "Evil"), code, a, "dev-a"))
    }

    @Test fun replyIsBoundToTheSendersKey() {
        val a = PetPairing.generate()
        val a2 = PetPairing.generate()
        val b = PetPairing.generate()
        val sent = PetPairing.attachToHello(PetTransferAuth.sign(hello, code), code, a, "dev-a")
        val replay = PetPairing.answer(sent, code, "dev-b", "Phone B", b)
        val sent2 = PetPairing.attachToHello(PetTransferAuth.sign(hello, code), code, a2, "dev-a")
        assertNull(PetPairing.finishAsSender(sent2, replay, code, a2, "dev-a"))
    }

    @Test fun oldPeerWithoutPairingFieldsIsNoPairing() {
        val a = PetPairing.generate()
        val plain = PetTransferAuth.sign(hello, code)
        assertFalse(PetPairing.helloOfferValid(plain, code))
        assertNull(PetPairing.finishAsReceiver(plain, code, a, "dev-b"))
        assertNull(PetPairing.finishAsSender(plain, HelloReply(true), code, a, "dev-a"))
        assertTrue(PetTransferAuth.verify(PetPairing.attachToHello(plain, code, a, "dev-a"), code)) // hmac unchanged
    }

    @Test fun invalidPublicKeysRejected() {
        val a = PetPairing.generate()
        assertNull(PetPairing.deriveKey(a, "", "x", "y"))
        assertNull(PetPairing.deriveKey(a, "not-base64!!", "x", "y"))
        assertNull(PetPairing.deriveKey(a, "AAAA", "x", "y"))
    }
}
