package com.example.llamadroid.data.db

import android.net.Uri
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import com.example.llamadroid.service.GenerationQueueAlarmReceiver
import com.example.llamadroid.service.GenerationQueueScheduler
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32

@RunWith(RobolectricTestRunner::class)
class RestoreCoordinatorTest {
    @get:Rule val folder = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()
    private val media get() = File(context.filesDir, "tama_gallery/existing.txt")
    private val prefs get() = File(context.applicationInfo.dataDir, "shared_prefs/restore_test.xml")

    @Before fun setUp() {
        context.getDir("restore_operations", 0).deleteRecursively()
        media.parentFile!!.mkdirs()
        media.writeText("old media")
        prefs.parentFile!!.mkdirs()
        prefs.writeText("<map><string name=\"value\">old</string></map>")
    }

    @After fun cleanUp() {
        AppDatabase.closeInstance()
        RestoreCoordinator.restoreLegacyComponents(context)
        RestoreCoordinator.javaClass.getDeclaredField("maintenanceLatched").apply {
            isAccessible = true
            setBoolean(RestoreCoordinator, false)
        }
        context.getDir("restore_operations", 0).deleteRecursively()
        File(context.filesDir, "tama_gallery").deleteRecursively()
        prefs.delete()
    }

    @Test fun `invalid database leaves every live root untouched`() {
        val db = context.getDatabasePath(DatabaseBackupManager.APP_DB_NAME)
        db.parentFile!!.mkdirs()
        db.writeText("old database")
        val archive = zip("bad-db.zip", mapOf(
            DatabaseBackupManager.APP_DB_NAME to "not a sqlite database",
            "media_roots/tama_gallery/existing.txt" to "new media",
            "shared_prefs/restore_test.xml" to "<map/>"
        ))
        try {
            assertTrue(runCatching { RestoreCoordinator.prepare(context, Uri.fromFile(archive)) }.isFailure)
            assertEquals("old database", db.readText())
            assertEquals("old media", media.readText())
            assertTrue(prefs.readText().contains("old"))
            assertFalse(RestoreCoordinator.hasPending(context))
        } finally { db.delete() }
    }

    @Test fun `malformed preferences reject archive before changing media`() {
        val archive = zip("bad-prefs.zip", mapOf(
            "media_roots/tama_gallery/existing.txt" to "new media",
            "shared_prefs/restore_test.xml" to "<map><broken></map>"
        ))
        assertTrue(runCatching { RestoreCoordinator.prepare(context, Uri.fromFile(archive)) }.isFailure)
        assertEquals("old media", media.readText())
        assertTrue(prefs.readText().contains("old"))
        assertFalse(RestoreCoordinator.hasPending(context))
    }

    @Test fun `bad CRC and truncated ZIP reject archive before changing media`() {
        val archive = folder.newFile("bad-crc.zip")
        val bytes = "new media".toByteArray()
        ZipOutputStream(archive.outputStream()).use { output ->
            val entry = ZipEntry("media_roots/tama_gallery/existing.txt").apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = size
                crc = CRC32().apply { update(bytes) }.value
            }
            output.putNextEntry(entry)
            output.write(bytes)
            output.closeEntry()
        }
        val original = archive.readBytes()
        val offset = original.indexOfSequence(bytes)
        assertTrue(offset >= 0)
        original[offset] = 'b'.code.toByte()
        archive.writeBytes(original)
        assertTrue(runCatching { RestoreCoordinator.prepare(context, Uri.fromFile(archive)) }.isFailure)
        assertEquals("old media", media.readText())

        val truncated = zip("truncated.zip", mapOf("media_roots/tama_gallery/existing.txt" to "new media"))
        val full = truncated.readBytes()
        truncated.writeBytes(full.copyOf(full.size - 8))
        assertTrue(runCatching { RestoreCoordinator.prepare(context, Uri.fromFile(truncated)) }.isFailure)
        assertEquals("old media", media.readText())
        assertFalse(RestoreCoordinator.hasPending(context))
    }

    @Test fun `duplicate destinations and orphan database sidecars are rejected`() {
        val first = "media_roots/tama_gallery/a.txt"
        val second = "media_roots/tama_gallery/b.txt"
        val duplicate = zip("duplicate.zip", mapOf(first to "first", second to "second"))
        val raw = duplicate.readBytes()
        val count = raw.replaceSequence(second.toByteArray(), first.toByteArray())
        assertEquals(2, count)
        duplicate.writeBytes(raw)
        assertTrue(runCatching { RestoreCoordinator.prepare(context, Uri.fromFile(duplicate)) }.isFailure)
        assertEquals("old media", media.readText())

        val orphan = zip("orphan.zip", mapOf(
            "${DatabaseBackupManager.APP_DB_NAME}-wal" to "orphan",
            "media_roots/tama_gallery/existing.txt" to "new media"
        ))
        assertTrue(runCatching { RestoreCoordinator.prepare(context, Uri.fromFile(orphan)) }.isFailure)
        assertEquals("old media", media.readText())
        assertFalse(RestoreCoordinator.hasPending(context))
    }

    @Test fun `valid media and preferences install together`() {
        val archive = zip("valid.zip", mapOf(
            "media_roots/tama_gallery/existing.txt" to "new media",
            "shared_prefs/restore_test.xml" to "<map><string name=\"value\">new</string></map>"
        ))
        RestoreCoordinator.prepare(context, Uri.fromFile(archive))
        assertEquals("old media", media.readText())
        assertEquals(RestoreCoordinator.InstallResult.RESTORED, RestoreCoordinator.installPending(context))
        assertEquals("new media", media.readText())
        assertTrue(prefs.readText().contains("new"))
        assertFalse(RestoreCoordinator.hasPending(context))
    }

    @Test fun `failure after first replacement rolls back original targets`() {
        val archive = zip("rollback.zip", mapOf(
            "media_roots/tama_gallery/existing.txt" to "new media",
            "shared_prefs/restore_test.xml" to "<map><string name=\"value\">new</string></map>"
        ))
        RestoreCoordinator.prepare(context, Uri.fromFile(archive))
        assertEquals(RestoreCoordinator.InstallResult.FAILED_ROLLED_BACK,
            RestoreCoordinator.installPending(context, failAfterReplacement = 0))
        assertEquals("old media", media.readText())
        assertTrue(prefs.readText().contains("old"))
        assertFalse(RestoreCoordinator.hasPending(context))
    }

    @Test fun `failure after second replacement rolls back both original targets`() {
        val archive = zip("rollback-second.zip", mapOf(
            "media_roots/tama_gallery/existing.txt" to "new media",
            "shared_prefs/restore_test.xml" to "<map><string name=\"value\">new</string></map>"
        ))
        RestoreCoordinator.prepare(context, Uri.fromFile(archive))
        assertEquals(RestoreCoordinator.InstallResult.FAILED_ROLLED_BACK,
            RestoreCoordinator.installPending(context, failAfterReplacement = 1))
        assertEquals("old media", media.readText())
        assertTrue(prefs.readText().contains("old"))
        assertFalse(RestoreCoordinator.hasPending(context))
    }

    @Test fun `shared process gate defers installation without touching live roots`() {
        val archive = zip("busy.zip", mapOf("media_roots/tama_gallery/existing.txt" to "new media"))
        RestoreCoordinator.prepare(context, Uri.fromFile(archive))
        val gate = File(context.getDir("restore_operations", 0), "gate.lock")
        RandomAccessFile(gate, "rw").channel.use { channel ->
            channel.lock(0L, Long.MAX_VALUE, true).use {
                assertEquals(RestoreCoordinator.InstallResult.RETRY_BUSY, RestoreCoordinator.installPending(context))
                assertEquals("old media", media.readText())
                assertTrue(RestoreCoordinator.hasPending(context))
            }
        }
        assertEquals(RestoreCoordinator.InstallResult.RESTORED, RestoreCoordinator.installPending(context))
    }

    @Test fun `alarm arriving with prepared restore cannot start normal service or open Room`() {
        val archive = zip("alarm.zip", mapOf("media_roots/tama_gallery/existing.txt" to "new media"))
        RestoreCoordinator.prepare(context, Uri.fromFile(archive))
        var serviceStarts = 0
        val guardedContext = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName? {
                serviceStarts++
                return null
            }
        }
        GenerationQueueAlarmReceiver().onReceive(guardedContext,
            Intent(GenerationQueueScheduler.ACTION_FIRE))
        assertEquals(0, serviceStarts)
        assertTrue(runCatching { AppDatabase.getDatabase(guardedContext) }.isFailure)
        assertEquals("old media", media.readText())
    }

    @Test fun `bootstrap process stays in maintenance after marker clears and recreation reads result`() {
        val archive = zip("rotation.zip", mapOf("media_roots/tama_gallery/existing.txt" to "new media"))
        RestoreCoordinator.prepare(context, Uri.fromFile(archive))
        assertEquals(RestoreCoordinator.InstallResult.RESTORED, RestoreCoordinator.installPending(context))
        RestoreCoordinator.latchMaintenance()
        assertFalse(RestoreCoordinator.hasPending(context))
        assertTrue(RestoreCoordinator.isMaintenance(context))
        assertTrue(runCatching { AppDatabase.getDatabase(context) }.isFailure)
        assertEquals(RestoreCoordinator.InstallResult.RESTORED, RestoreCoordinator.installPending(context))
    }

    @Test @Config(sdk = [26])
    fun `Android 8 gate blocks ordinary components and restores prior enabled states`() {
        val archive = zip("legacy-gate.zip", mapOf("media_roots/tama_gallery/existing.txt" to "new media"))
        RestoreCoordinator.prepare(context, Uri.fromFile(archive))
        val manager = context.packageManager
        val alarm = ComponentName(context, GenerationQueueAlarmReceiver::class.java)
        val control = ComponentName(context, LlamaRuntimeRestoreReceiver::class.java)
        val prior = manager.getComponentEnabledSetting(alarm)
        assertTrue(RestoreCoordinator.blockLegacyComponents(context))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            manager.getComponentEnabledSetting(alarm))
        assertTrue(manager.getComponentEnabledSetting(control) !=
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        assertEquals("old media", media.readText())
        assertTrue(RestoreCoordinator.restoreLegacyComponents(context))
        assertEquals(prior, manager.getComponentEnabledSetting(alarm))
    }

    @Test fun `unfinished replacement journal restores originals on next bootstrap`() {
        val archive = zip("interrupted.zip", mapOf("media_roots/tama_gallery/existing.txt" to "new media"))
        RestoreCoordinator.prepare(context, Uri.fromFile(archive))
        val root = context.getDir("restore_operations", 0)
        val operation = File(root, File(root, "pending-v1").readText().trim())
        val backup = File(operation, "rollback/0/0/existing.txt")
        backup.parentFile!!.mkdirs()
        backup.writeText("old media")
        File(operation, "journal-v1").writeText("BEGIN\nREADY|0|1\n")
        media.writeText("new media")
        assertEquals(RestoreCoordinator.InstallResult.FAILED_ROLLED_BACK,
            RestoreCoordinator.installPending(context))
        assertEquals("old media", media.readText())
        assertFalse(RestoreCoordinator.hasPending(context))
    }

    @Test fun `begin only journal leaves originals and clears pending restore`() {
        val archive = zip("begin-only.zip", mapOf("media_roots/tama_gallery/existing.txt" to "new media"))
        RestoreCoordinator.prepare(context, Uri.fromFile(archive))
        val root = context.getDir("restore_operations", 0)
        val operation = File(root, File(root, "pending-v1").readText().trim())
        File(operation, "journal-v1").writeText("BEGIN\n")
        assertEquals(RestoreCoordinator.InstallResult.FAILED_ROLLED_BACK,
            RestoreCoordinator.installPending(context))
        assertEquals("old media", media.readText())
        assertFalse(RestoreCoordinator.hasPending(context))
    }

    @Test fun `interrupted journal after both replacements restores both roots`() {
        val archive = zip("interrupted-both.zip", mapOf(
            "media_roots/tama_gallery/existing.txt" to "new media",
            "shared_prefs/restore_test.xml" to "<map><string name=\"value\">new</string></map>"
        ))
        RestoreCoordinator.prepare(context, Uri.fromFile(archive))
        val root = context.getDir("restore_operations", 0)
        val operation = File(root, File(root, "pending-v1").readText().trim())
        assertTrue(File(context.filesDir, "tama_gallery").copyRecursively(File(operation, "rollback/0/0")))
        assertTrue(prefs.parentFile!!.copyRecursively(File(operation, "rollback/1/0")))
        media.writeText("new media")
        prefs.writeText("<map><string name=\"value\">new</string></map>")
        File(operation, "journal-v1").writeText("BEGIN\nREADY|0|1\nREADY|1|1\n")
        assertEquals(RestoreCoordinator.InstallResult.FAILED_ROLLED_BACK,
            RestoreCoordinator.installPending(context))
        assertEquals("old media", media.readText())
        assertTrue(prefs.readText().contains("old"))
        assertFalse(RestoreCoordinator.hasPending(context))
    }

    @Test fun `committed journal finishes without reverting installed data`() {
        val archive = zip("committed.zip", mapOf("media_roots/tama_gallery/existing.txt" to "new media"))
        RestoreCoordinator.prepare(context, Uri.fromFile(archive))
        val root = context.getDir("restore_operations", 0)
        val operation = File(root, File(root, "pending-v1").readText().trim())
        File(operation, "journal-v1").writeText("BEGIN\nREADY|0|1\nCOMMITTED\n")
        media.writeText("new media")
        assertEquals(RestoreCoordinator.InstallResult.RESTORED, RestoreCoordinator.installPending(context))
        assertEquals("new media", media.readText())
        assertFalse(RestoreCoordinator.hasPending(context))
    }

    @Test fun `single app database without manifest restores while omitted media stays`() {
        AppDatabase.closeInstance()
        context.deleteDatabase(DatabaseBackupManager.APP_DB_NAME)
        val db = AppDatabase.getDatabase(context)
        db.openHelper.writableDatabase.execSQL("CREATE TABLE IF NOT EXISTS restore_marker (value TEXT)")
        db.openHelper.writableDatabase.execSQL("INSERT INTO restore_marker (value) VALUES ('backup')")
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        AppDatabase.closeInstance()
        val live = context.getDatabasePath(DatabaseBackupManager.APP_DB_NAME)
        val archive = folder.newFile("single-db.zip")
        java.util.zip.ZipOutputStream(archive.outputStream()).use { output ->
            output.putNextEntry(ZipEntry(DatabaseBackupManager.APP_DB_NAME))
            live.inputStream().use { it.copyTo(output) }
            output.closeEntry()
        }
        val sqlite = SQLiteDatabase.openDatabase(live.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try { sqlite.execSQL("UPDATE restore_marker SET value='current'") } finally { sqlite.close() }
        RestoreCoordinator.prepare(context, Uri.fromFile(archive))
        assertEquals(RestoreCoordinator.InstallResult.RESTORED, RestoreCoordinator.installPending(context))
        val restored = SQLiteDatabase.openDatabase(live.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            restored.rawQuery("SELECT value FROM restore_marker", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("backup", cursor.getString(0))
            }
        } finally { restored.close() }
        assertEquals("old media", media.readText())
        live.delete()
    }

    private fun zip(name: String, entries: Map<String, String>): File = folder.newFile(name).apply {
        ZipOutputStream(outputStream()).use { output ->
            entries.forEach { (path, value) ->
                output.putNextEntry(ZipEntry(path))
                output.write(value.toByteArray())
                output.closeEntry()
            }
        }
    }

    private fun ByteArray.indexOfSequence(sequence: ByteArray): Int =
        (0..size - sequence.size).firstOrNull { offset ->
            sequence.indices.all { this[offset + it] == sequence[it] }
        } ?: -1

    private fun ByteArray.replaceSequence(old: ByteArray, new: ByteArray): Int {
        require(old.size == new.size)
        var count = 0
        for (offset in 0..size - old.size) {
            if (old.indices.all { this[offset + it] == old[it] }) {
                new.copyInto(this, offset)
                count++
            }
        }
        return count
    }
}
