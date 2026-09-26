package com.example.llamadroid.data.db

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Xml
import androidx.room.Room
import com.example.llamadroid.R
import com.example.llamadroid.MainActivity
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.db.TamaMigrations
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** A private, restart-bound restore transaction. No archive byte is written into live data. */
internal object RestoreCoordinator {
    enum class InstallResult { RESTORED, FAILED_ROLLED_BACK, RETRY_BUSY, RECOVERY_BLOCKED }

    private const val PENDING = "pending-v1"
    private const val JOURNAL = "journal-v1"
    private const val ENTRIES = "entries-v1"
    private const val LOCK = "gate.lock"
    private const val LEGACY_COMPONENT_LOCK = "legacy-components.lock"
    private const val LEGACY_COMPONENT_STATES = "legacy-component-states-v1"
    private const val LEGACY_COMPONENT_DONE = "legacy-components-blocked-v1"
    private val databaseNames = listOf(DatabaseBackupManager.APP_DB_NAME, DatabaseBackupManager.TAMA_DB_NAME)
    private val suffixes = listOf("", "-wal", "-shm")
    private var normalChannel: FileChannel? = null
    private var normalLock: FileLock? = null
    @Volatile private var maintenanceLatched = false

    /** A process which entered maintenance cannot resume ordinary work after the marker clears. */
    fun isMaintenance(context: Context): Boolean = maintenanceLatched || hasPending(context)

    fun latchMaintenance() { maintenanceLatched = true }

    fun requireNormalAccess(context: Context) {
        check(!isMaintenance(context)) { "RESTORE_MAINTENANCE_ACTIVE" }
    }

    /** API 26-27 have no AppComponentFactory; disable normal manifest entries before providers start. */
    fun blockLegacyComponents(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 28) return true
        return runCatching { withLegacyComponentLock(context) {
            val base = root(context)
            val statesFile = File(base, LEGACY_COMPONENT_STATES)
            val done = File(base, LEGACY_COMPONENT_DONE)
            val manager = context.packageManager
            val info = legacyPackageInfo(context)
            if (statesFile.isFile && done.isFile &&
                done.readText().trim() == info.lastUpdateTime.toString()) return@withLegacyComponentLock true
            val states = readLegacyComponentStates(statesFile)
            val names = normalComponentNames(info)
            names.forEach { name ->
                states.putIfAbsent(name, manager.getComponentEnabledSetting(
                    ComponentName(context.packageName, name)))
            }
            writeAtomic(statesFile, states.entries.sortedBy { it.key }
                .joinToString("\n", postfix = "\n") { "${it.key}|${it.value}" })
            names.forEach { name ->
                val component = ComponentName(context.packageName, name)
                if (manager.getComponentEnabledSetting(component) !=
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    manager.setComponentEnabledSetting(component,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                }
            }
            writeAtomic(done, info.lastUpdateTime.toString())
            true
        } }.getOrDefault(false)
    }

    /** Replay the saved component settings before normal providers are installed again. */
    fun restoreLegacyComponents(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 28) return true
        return runCatching { withLegacyComponentLock(context) {
            val base = root(context)
            val statesFile = File(base, LEGACY_COMPONENT_STATES)
            val done = File(base, LEGACY_COMPONENT_DONE)
            if (!statesFile.isFile) return@withLegacyComponentLock !done.exists()
            val manager = context.packageManager
            val present = normalComponentNames(legacyPackageInfo(context)).toSet()
            readLegacyComponentStates(statesFile).forEach { (name, original) ->
                if (name in present) {
                    val component = ComponentName(context.packageName, name)
                    if (manager.getComponentEnabledSetting(component) != original) {
                        manager.setComponentEnabledSetting(component, original, PackageManager.DONT_KILL_APP)
                    }
                }
            }
            // Keep the original states if teardown is interrupted between markers.
            Files.deleteIfExists(done.toPath())
            Files.deleteIfExists(statesFile.toPath())
            syncDirectory(base)
            true
        } }.getOrDefault(false)
    }

    private fun <T> withLegacyComponentLock(context: Context, block: () -> T): T {
        RandomAccessFile(File(root(context), LEGACY_COMPONENT_LOCK), "rw").channel.use { channel ->
            channel.lock().use { return block() }
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyPackageInfo(context: Context) = context.packageManager.getPackageInfo(
        context.packageName,
        PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or
            PackageManager.MATCH_DISABLED_COMPONENTS
    )

    private fun normalComponentNames(info: android.content.pm.PackageInfo): List<String> = buildList {
        info.activities?.forEach { add(it.name) }
        info.services?.forEach { add(it.name) }
        info.receivers?.forEach { add(it.name) }
        info.providers?.forEach { add(it.name) }
    }.distinct().filterNot { name ->
        name == MainActivity::class.java.name ||
            name in setOf(
                LlamaRuntimeRestoreReceiver::class.java.name,
                LlamaSessionsRestoreReceiver::class.java.name,
                DistributedLlamaRestoreReceiver::class.java.name,
                StableAudioRestoreReceiver::class.java.name,
                OnnxBgrRestoreReceiver::class.java.name,
                LiteRtRestoreReceiver::class.java.name,
                AgentRemoteRestoreReceiver::class.java.name
            )
    }

    private fun readLegacyComponentStates(file: File): MutableMap<String, Int> =
        if (!file.isFile) mutableMapOf() else file.readLines().filter { it.isNotBlank() }
            .associate { line ->
                val fields = line.split('|')
                require(fields.size == 2 && fields[0].isNotBlank())
                fields[0] to fields[1].toInt()
            }.toMutableMap()

    fun scheduleProcessRestart(context: Context) {
        latchMaintenance()
        val launch = Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
        val pending = PendingIntent.getActivity(context, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1_000L, pending)
        AppDatabase.closeInstance()
        TamaDatabase.closeInstance()
        // Keep the lease until process death, so no installer can enter before all
        // service threads have stopped with this process.
        android.os.Process.killProcess(android.os.Process.myPid())
        kotlin.system.exitProcess(0)
    }

    fun hasPending(context: Context): Boolean = File(root(context), PENDING).isFile

    /** Every normally initialized process keeps shared access for its entire lifetime. */
    @Synchronized fun acquireNormalAccess(context: Context) {
        requireNormalAccess(context)
        if (normalLock?.isValid == true) return
        val channel = RandomAccessFile(File(root(context), LOCK), "rw").channel
        try {
            val acquired = channel.lock(0L, Long.MAX_VALUE, true)
            if (hasPending(context)) {
                latchMaintenance()
                acquired.release()
                throw IllegalStateException("RESTORE_MAINTENANCE_ACTIVE")
            }
            normalLock = acquired
            normalChannel = channel
        } catch (error: Exception) {
            channel.close()
            throw error
        }
    }

    /** Called only by a restore-control receiver in its own worker process. */
    fun acknowledgeWorkerQuiescence(context: Context, operationId: String, workerId: String) {
        require(workerId in WORKER_IDS)
        val base = root(context)
        require(File(base, PENDING).readText().trim() == operationId)
        require(UUID.fromString(operationId).toString() == operationId)
        latchMaintenance()
        AppDatabase.closeInstance()
        TamaDatabase.closeInstance()
        // The receiver terminates immediately after the ack. Retain the shared
        // lease until kernel process teardown, which closes any retained handles.
        val ack = File(base, "$operationId/worker-acks/$workerId")
        check(ack.parentFile?.mkdirs() == true || ack.parentFile?.isDirectory == true)
        writeSynced(ack, "quiesced")
    }

    private fun requestWorkerQuiescence(context: Context, operationId: String, deadline: Long): Boolean {
        val classes = listOf(
            LlamaRuntimeRestoreReceiver::class.java,
            LlamaSessionsRestoreReceiver::class.java,
            DistributedLlamaRestoreReceiver::class.java,
            StableAudioRestoreReceiver::class.java,
            OnnxBgrRestoreReceiver::class.java,
            LiteRtRestoreReceiver::class.java,
            AgentRemoteRestoreReceiver::class.java
        )
        classes.forEach { receiver ->
            context.sendBroadcast(Intent(context, receiver).putExtra("restore_operation_id", operationId))
        }
        val acknowledgements = File(root(context), "$operationId/worker-acks")
        while (SystemClock.elapsedRealtime() < deadline) {
            if (WORKER_IDS.all { File(acknowledgements, it).isFile }) return true
            try { Thread.sleep(50L) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    fun prepare(context: Context, sourceUri: Uri): String {
        val base = root(context)
        require(!File(base, PENDING).exists()) { "A restore is already prepared" }
        val operation = File(base, UUID.randomUUID().toString())
        check(operation.mkdirs()) { "Cannot create private restore staging" }
        try {
            syncDirectory(base)
            syncDirectory(base.parentFile)
            val archive = File(operation, "backup.zip")
            val input = context.contentResolver.openInputStream(sourceUri)
                ?: throw IOException(context.getString(R.string.backup_restore_error_open_input))
            input.use { source -> FileOutputStream(archive).use { target ->
                source.copyTo(target)
                target.fd.sync()
            } }
            val entries = extractVerifiedArchive(archive, File(operation, "payload")).toMutableList()
            validatePreparedDatabases(context, operation, entries)
            validatePreferences(operation, entries)
            writeSynced(File(operation, ENTRIES), entries.joinToString("\n", postfix = "\n"))
            syncTreeDirectories(operation)
            writeAtomic(File(base, PENDING), operation.name)
            return "${entries.size} files ready to restore"
        } catch (error: Exception) {
            deleteTree(operation)
            throw error
        }
    }

    fun installPending(context: Context, failAfterReplacement: Int? = null): InstallResult {
        val base = root(context)
        val pending = File(base, PENDING)
        if (!pending.exists()) return runCatching {
            InstallResult.valueOf(File(base, "last-result-v1").readText().trim())
        }.getOrDefault(InstallResult.FAILED_ROLLED_BACK)
        if (!blockLegacyComponents(context)) return InstallResult.RETRY_BUSY
        val quiescenceDeadline = SystemClock.elapsedRealtime() + 10_000L
        if (maintenanceLatched) {
            val operationId = runCatching { pending.readText().trim() }.getOrNull()
                ?: return InstallResult.RECOVERY_BLOCKED
            if (!requestWorkerQuiescence(context, operationId, quiescenceDeadline)) return InstallResult.RETRY_BUSY
        }
        val channel = runCatching { RandomAccessFile(File(base, LOCK), "rw").channel }
            .getOrElse { return InstallResult.RECOVERY_BLOCKED }
        val exclusive = try {
            var acquired: FileLock? = null
            do {
                acquired = try { channel.tryLock(0L, Long.MAX_VALUE, false) }
                    catch (_: OverlappingFileLockException) { null }
                if (acquired != null || !maintenanceLatched ||
                    SystemClock.elapsedRealtime() >= quiescenceDeadline) break
                Thread.sleep(50L)
            } while (true)
            acquired
        } catch (_: Exception) {
            runCatching { channel.close() }
            return InstallResult.RECOVERY_BLOCKED
        }
        if (exclusive == null) {
            runCatching { channel.close() }
            return InstallResult.RETRY_BUSY
        }
        try {
            val id = pending.readText().trim()
            require(UUID.fromString(id).toString() == id) { "Invalid restore operation" }
            val operation = File(base, id)
            val journal = File(operation, JOURNAL)
            val entries = File(operation, ENTRIES).readLines().filter { it.isNotBlank() }
            val replacements = buildReplacements(context, operation, entries)
            if (journal.exists()) {
                val lines = journalLines(journal)
                if (lines.any { it == "COMMITTED" }) {
                    finishCommitted(operation, pending)
                    return InstallResult.RESTORED
                }
                return recoverUnfinished(operation, pending, replacements, lines)
            }
            try {
                appendJournal(journal, "BEGIN")
                replacements.forEachIndexed { index, replacement ->
                    val before = File(operation, "rollback/$index")
                    check(before.mkdirs()) { "Cannot create restore rollback area" }
                    val originals = replacement.targets.mapIndexed { slot, target ->
                        if (target.exists()) {
                            copyTree(target, File(before, slot.toString()))
                            true
                        } else false
                    }
                    syncTreeDirectories(before)
                    syncDirectory(before.parentFile)
                    syncDirectory(operation)
                    appendJournal(journal, "READY|$index|${originals.joinToString("") { if (it) "1" else "0" }}")
                    replacement.targets.forEachIndexed { slot, target ->
                        deleteTree(target)
                        replacement.sources[slot]?.let { staged ->
                            check(target.parentFile?.mkdirs() == true || target.parentFile?.isDirectory == true)
                            moveTree(staged, target)
                        }
                    }
                    replacement.targets.mapNotNull { it.parentFile }.distinct().forEach(::syncDirectory)
                    if (failAfterReplacement == index) throw IOException("Injected restore replacement failure")
                }
                validateInstalledDatabases(context, entries)
                appendJournal(journal, "COMMITTED")
                finishCommitted(operation, pending)
                return InstallResult.RESTORED
            } catch (_: Exception) {
                return recoverUnfinished(operation, pending, replacements, journalLines(journal))
            }
        } catch (_: Exception) {
            // Keep all private recovery material when even the journal cannot be interpreted.
            return InstallResult.RECOVERY_BLOCKED
        } finally {
            runCatching { exclusive.release() }
            runCatching { channel.close() }
        }
    }

    private fun recoverUnfinished(
        operation: File,
        pending: File,
        replacements: List<Replacement>,
        lines: List<String>
    ): InstallResult {
        return try {
            val ready = lines.filter { it.startsWith("READY|") }.map { line ->
                val fields = line.split('|')
                require(fields.size == 3)
                val index = fields[1].toInt()
                require(index in replacements.indices)
                val bits = fields[2]
                require(bits.length == replacements[index].targets.size && bits.all { it == '0' || it == '1' })
                index to bits
            }
            ready.asReversed().forEach { (index, bits) ->
                replacements[index].targets.forEachIndexed { slot, target ->
                    deleteTree(target)
                    if (bits[slot] == '1') {
                        val original = File(operation, "rollback/$index/$slot")
                        require(original.exists()) { "Missing restore recovery copy" }
                        copyTree(original, target)
                        syncTreeDirectories(target)
                    }
                    syncDirectory(target.parentFile)
                }
            }
            writeAtomic(File(operation.parentFile, "last-result-v1"), "FAILED_ROLLED_BACK")
            deleteTree(pending)
            syncDirectory(pending.parentFile)
            runCatching { deleteTree(operation) }
            InstallResult.FAILED_ROLLED_BACK
        } catch (_: Exception) {
            InstallResult.RECOVERY_BLOCKED
        }
    }

    private fun finishCommitted(operation: File, pending: File) {
        writeAtomic(File(operation.parentFile, "last-result-v1"), "RESTORED")
        deleteTree(pending)
        syncDirectory(pending.parentFile)
        runCatching { deleteTree(operation) }
    }

    private data class Replacement(val targets: List<File>, val sources: List<File?>)

    private fun buildReplacements(context: Context, operation: File, entries: List<String>): List<Replacement> {
        val payload = File(operation, "payload")
        val dbDir = context.getDatabasePath(DatabaseBackupManager.APP_DB_NAME).parentFile
            ?: throw IOException("Missing database directory")
        val result = mutableListOf<Replacement>()
        databaseNames.forEach { name ->
            if (name in entries) result += Replacement(
                suffixes.map { File(dbDir, "$name$it") },
                suffixes.map { suffix -> "$name$suffix".takeIf { it in entries }?.let { File(payload, it) } }
            )
        }
        val mediaRoots = entries.filter { it.startsWith("media_roots/") }
            .map { it.removePrefix("media_roots/").substringBefore('/') }.distinct()
        mediaRoots.forEach { name ->
            result += Replacement(listOf(File(context.filesDir, name)),
                listOf(File(payload, "media_roots/$name")))
        }
        if (entries.any { it.startsWith("shared_prefs/") }) {
            result += Replacement(
                listOf(File(context.applicationInfo.dataDir, DatabaseBackupManager.SHARED_PREFS_DIR_NAME)),
                listOf(File(payload, "shared_prefs"))
            )
        }
        require(result.isNotEmpty()) { "Backup has no restorable targets" }
        return result
    }

    private fun extractVerifiedArchive(archive: File, payload: File): List<String> {
        check(payload.mkdirs()) { "Cannot create restore payload" }
        val accepted = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                require(safePath(name.removeSuffix("/"))) { "Unsafe backup path" }
                if (entry.isDirectory) continue
                if (!acceptedPath(name)) continue
                require(seen.add(name)) { "Duplicate restore destination" }
                require(entry.crc >= 0L && entry.size >= 0L) { "Incomplete ZIP metadata" }
                val destination = File(payload, name)
                require(destination.canonicalPath.startsWith(payload.canonicalPath + File.separator))
                check(destination.parentFile?.mkdirs() == true || destination.parentFile?.isDirectory == true)
                val crc = CRC32()
                var count = 0L
                zip.getInputStream(entry).use { source ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = source.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            crc.update(buffer, 0, read)
                            count += read
                        }
                        output.fd.sync()
                    }
                }
                require(count == entry.size && crc.value == entry.crc) { "Corrupt backup entry" }
                accepted += name
            }
        }
        require(accepted.isNotEmpty()) { "No restorable files in backup" }
        databaseNames.forEach { name ->
            require(accepted.none { it == "$name-wal" || it == "$name-shm" } || name in accepted) {
                "Database companion has no matching database"
            }
        }
        return accepted
    }

    private fun acceptedPath(name: String): Boolean {
        if (databaseNames.any { db -> suffixes.any { name == "$db$it" } }) return true
        if (name.startsWith("media_roots/")) {
            val relative = name.removePrefix("media_roots/")
            return relative.substringBefore('/') in DatabaseBackupManager.PORTABLE_MEDIA_ROOTS && '/' in relative
        }
        return name.startsWith("shared_prefs/") &&
            name.removePrefix("shared_prefs/").let { it.endsWith(".xml") && '/' !in it }
    }

    private fun safePath(path: String): Boolean = path.isNotBlank() &&
        !path.startsWith('/') && !path.contains('\\') &&
        path.split('/').none { it.isBlank() || it == "." || it == ".." }

    private fun validatePreparedDatabases(context: Context, operation: File, entries: MutableList<String>) {
        databaseNames.forEach { name ->
            if (name !in entries) return@forEach
            val staged = File(operation, "payload/$name")
            val expectedHeader = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
            val header = ByteArray(expectedHeader.size)
            staged.inputStream().use { input ->
                require(input.read(header) == header.size && header.contentEquals(expectedHeader)) {
                    "Invalid staged database header"
                }
            }
            DatabaseBackupManager.validateIntegrity(context, staged, name)
            if (name == DatabaseBackupManager.APP_DB_NAME) {
                val room = Room.databaseBuilder(context, AppDatabase::class.java, staged.absolutePath)
                    .addMigrations(*Migrations.ALL_MIGRATIONS).build()
                try { room.openHelper.writableDatabase } finally { room.close() }
                DatabaseBackupManager.sanitizePortableAppDatabase(staged)
            } else {
                val room = Room.databaseBuilder(context, TamaDatabase::class.java, staged.absolutePath)
                    .addMigrations(*TamaMigrations.ALL_MIGRATIONS).build()
                try { room.openHelper.writableDatabase } finally { room.close() }
            }
            // Consolidate any imported WAL before publishing the staged main file.
            val sqlite = SQLiteDatabase.openDatabase(staged.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            try {
                sqlite.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getInt(0) == 0) { "Staged database checkpoint failed" }
                }
            } finally { sqlite.close() }
            listOf("-wal", "-shm").forEach { suffix ->
                File(operation, "payload/$name$suffix").let { if (it.exists()) deleteTree(it) }
                entries.remove("$name$suffix")
            }
            DatabaseBackupManager.validateIntegrity(context, staged, name)
        }
    }

    private fun validateInstalledDatabases(context: Context, entries: List<String>) {
        databaseNames.forEach { name ->
            if (name !in entries) return@forEach
            val file = context.getDatabasePath(name)
            DatabaseBackupManager.validateIntegrity(context, file, name)
        }
    }

    private fun validatePreferences(operation: File, entries: List<String>) {
        entries.filter { it.startsWith("shared_prefs/") }.forEach { name ->
            File(operation, "payload/$name").inputStream().use { input ->
                val parser = Xml.newPullParser()
                parser.setInput(input, "UTF-8")
                var rootSeen = false
                while (true) {
                    when (parser.next()) {
                        XmlPullParser.DOCDECL -> throw IOException("Preference XML has a document declaration")
                        XmlPullParser.START_TAG -> if (!rootSeen) {
                            require(parser.name == "map") { "Invalid preferences XML root" }
                            rootSeen = true
                        }
                        XmlPullParser.END_DOCUMENT -> break
                    }
                }
                require(rootSeen) { "Empty preferences XML" }
            }
        }
    }

    private fun root(context: Context): File = context.getDir("restore_operations", Context.MODE_PRIVATE)

    private val WORKER_IDS = setOf(
        "llama_runtime", "llama_sessions_runtime", "distributed_llama_runtime",
        "stable_audio_worker", "onnx_bgr", "litert_lm", "agent_remote"
    )

    private fun appendJournal(file: File, line: String) {
        val created = !file.exists()
        FileOutputStream(file, true).use { output ->
            output.write("$line\n".toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (created) syncDirectory(file.parentFile)
    }

    private fun journalLines(file: File): List<String> {
        val content = file.readText()
        return if (content.endsWith('\n')) content.lines().dropLast(1)
            else content.substringBeforeLast('\n', "").lines().filter { it.isNotBlank() }
    }

    private fun writeSynced(file: File, value: String) {
        FileOutputStream(file).use { output ->
            output.write(value.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun writeAtomic(file: File, value: String) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        writeSynced(temp, value)
        moveTree(temp, file)
    }

    private fun moveTree(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        syncDirectory(target.parentFile)
    }

    private fun syncTreeDirectories(directory: File) {
        if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) return
        requireNotNull(directory.listFiles()) { "Cannot list restore directory for sync" }
            .filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
            .forEach(::syncTreeDirectories)
        syncDirectory(directory)
    }

    private fun syncDirectory(directory: File?) {
        requireNotNull(directory) { "Missing restore directory" }
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    private fun copyTree(source: File, target: File) {
        require(!Files.isSymbolicLink(source.toPath())) { "Restore target contains a symlink" }
        if (source.isDirectory) {
            check(target.mkdirs() || target.isDirectory)
            requireNotNull(source.listFiles()) { "Cannot list restore source directory" }
                .forEach { copyTree(it, File(target, it.name)) }
        } else {
            check(target.parentFile?.mkdirs() == true || target.parentFile?.isDirectory == true)
            source.inputStream().use { input -> FileOutputStream(target).use { output ->
                input.copyTo(output)
                output.fd.sync()
            } }
        }
    }

    private fun deleteTree(file: File) {
        if (!Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        if (file.isDirectory && !Files.isSymbolicLink(file.toPath())) {
            requireNotNull(file.listFiles()) { "Cannot list restore target directory" }
                .forEach(::deleteTree)
        }
        check(file.delete()) { "Cannot replace restore target" }
    }
}
