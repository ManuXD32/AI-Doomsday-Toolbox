package com.example.llamadroid.harness

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class HarnessCredentialStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = HarnessCredentialStore(context)
    private val preferences = context.getSharedPreferences("harness_credentials", Context.MODE_PRIVATE)

    @Test fun secretsAreEncryptedAndBoundToTheirReferences() {
        val reference = "HARNESS_TEST_" + UUID.randomUUID().toString().replace("-", "")
        val copied = "${reference}_COPY"
        val secret = UUID.randomUUID().toString()
        try {
            store.set(reference, secret)
            val persisted = requireNotNull(preferences.getString("ref:$reference", null))
            assertFalse(persisted.contains(secret))
            assertEquals(secret, store.resolve(reference))
            assertTrue(store.describe(reference).getBoolean("configured"))
            assertFalse(store.describe(reference).toString().contains(secret))
            preferences.edit().putString("ref:$copied", persisted).commit()
            assertTrue(runCatching { store.resolve(copied) }.isFailure)
        } finally { store.unset(reference); store.unset(copied) }
    }

    @Test fun tokenRefreshHonorsRevisionAndPrivateNamespacesStayOutOfInventory() {
        val key = "test/${UUID.randomUUID()}"
        val privateKey = "adt-ssh/${UUID.randomUUID()}"
        try {
            val record = JSONObject().put("kind", "grant").put("payload", JSONObject().put("test", "redacted"))
            val first = requireNotNull(store.replaceRecord(key, null, record))
            assertTrue(runCatching { store.replaceRecord(key, "stale", record) }.isFailure)
            val second = requireNotNull(store.replaceRecord(key, first.getString("revision"), record))
            assertNotEquals(first.getString("revision"), second.getString("revision"))
            store.replaceRecord(privateKey, null, record)
            assertFalse(store.listRecords().toString().contains(privateKey))
            assertFalse(store.listRecords().toString().contains("redacted"))
        } finally {
            for (stored in listOf(key, privateKey)) store.readRecord(stored)?.let { store.replaceRecord(stored, it.getString("revision"), null) }
        }
    }

    @Test fun separateNativeAndBridgeFacadesCannotBothOverwriteTheSameRevision() {
        val key = "test/${UUID.randomUUID()}"
        val workers = Executors.newFixedThreadPool(2)
        try {
            val record = JSONObject().put("kind", "grant").put("payload", JSONObject().put("test", "redacted"))
            val revision = requireNotNull(store.replaceRecord(key, null, record)).getString("revision")
            val start = CountDownLatch(1)
            val results = (0..1).map {
                workers.submit<Boolean> {
                    start.await()
                    runCatching { HarnessCredentialStore(context).replaceRecord(key, revision, record) }.isSuccess
                }
            }
            start.countDown()
            assertEquals(1, results.count { it.get(10, TimeUnit.SECONDS) })
        } finally {
            workers.shutdownNow()
            store.readRecord(key)?.let { store.replaceRecord(key, it.getString("revision"), null) }
        }
    }
}
