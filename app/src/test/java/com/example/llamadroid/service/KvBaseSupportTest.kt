package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KvBaseSupportTest {
    @Test
    fun `prepare reasons are reported in priority order`() {
        assertEquals(KvBaseFailure.UNSUPPORTED_ENGINE, classifyBasePrepareStart(false, true, true, true))
        assertEquals(KvBaseFailure.NOT_LOCAL, classifyBasePrepareStart(true, false, true, true))
        assertEquals(KvBaseFailure.SERVER_UNREACHABLE, classifyBasePrepareStart(true, true, null, false))
        assertEquals(KvBaseFailure.SLOT_SAVE_DISABLED, classifyBasePrepareStart(true, true, false, true))
        assertEquals(KvBaseFailure.NO_FREE_SLOT, classifyBasePrepareStart(true, true, true, false))
        assertNull(classifyBasePrepareStart(true, true, true, true))
    }

    @Test
    fun `a valid base is never rebuilt`() {
        assertFalse(shouldBuildBase(true, true, KvFileStatus.VALID, false, null))
    }

    @Test
    fun `a missing or stale base is rebuilt only with a builder and without a build in flight`() {
        assertTrue(shouldBuildBase(true, true, KvFileStatus.NO_FILE, false, null))
        assertTrue(shouldBuildBase(true, true, KvFileStatus.FINGERPRINT_MISMATCH, false, null))
        assertFalse(shouldBuildBase(true, false, KvFileStatus.NO_FILE, false, null))
        assertFalse(shouldBuildBase(false, true, KvFileStatus.NO_FILE, false, null))
        assertFalse(shouldBuildBase(true, true, KvFileStatus.NO_FILE, true, null))
    }

    @Test
    fun `failed builds respect a cooldown`() {
        assertFalse(shouldBuildBase(true, true, KvFileStatus.NO_FILE, false, 1_000L))
        assertTrue(shouldBuildBase(true, true, KvFileStatus.NO_FILE, false, BASE_BUILD_COOLDOWN_MS))
    }

    @Test
    fun `miss reasons distinguish a changed prefix from a first use`() {
        assertNull(missReasonFor(KvFileStatus.VALID, false))
        assertEquals(KvBaseMissReason.NO_FILE, missReasonFor(KvFileStatus.NO_FILE, false))
        assertEquals(KvBaseMissReason.PREFIX_CHANGED, missReasonFor(KvFileStatus.NO_FILE, true))
        assertEquals(KvBaseMissReason.FINGERPRINT_MISMATCH, missReasonFor(KvFileStatus.FINGERPRINT_MISMATCH, true))
    }

    @Test
    fun `usage counters round trip and combine`() {
        val a = KvBaseUsage().withUse(10).withUse(20).withMiss(KvBaseMissReason.NO_FILE, 5)
        assertEquals(a, KvBaseUsage.parse(a.encode()))
        assertEquals(KvBaseUsage(), KvBaseUsage.parse("garbage"))
        val b = KvBaseUsage().withUse(30).withMiss(KvBaseMissReason.PREFIX_CHANGED, 40)
        val total = combineBaseUsage(listOf(a, b))
        assertEquals(3, total.uses)
        assertEquals(2, total.misses)
        assertEquals(30L, total.lastUsedAt)
        assertEquals(KvBaseMissReason.PREFIX_CHANGED, total.lastMissReason)
    }

    @Test
    fun `base hint without builder keeps legacy capture semantics`() {
        val key = KvCacheKey.Base("harness", "h")
        assertNull(KvBaseHint(key).builder)
        assertTrue(shouldCaptureBase(capture = true, baseFileValid = false, ownStatePresent = false))
    }
}
