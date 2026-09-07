package com.example.llamadroid.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.example.llamadroid.R
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Manages backup and restore of both Room databases (AppDatabase + TamaDatabase).
 * 
 * Backups are ZIP files containing the raw .db, -wal, and -shm files for both databases.
 * Uses SAF (Storage Access Framework) URIs for file I/O so the user picks the location.
 */
object DatabaseBackupManager {
    
    private const val TAG = "[DB-Backup]"
    
    // Database file names (must match the names in AppDatabase/TamaDatabase builders)
    private const val APP_DB_NAME = "llama_droid_db"
    private const val TAMA_DB_NAME = "tama_database"
    
    // Journal file suffixes
    private val DB_SUFFIXES = listOf("", "-wal", "-shm")
    private const val MEDIA_ROOT_PREFIX = "media_roots/"
    private const val SHARED_PREFS_PREFIX = "shared_prefs/"
    private const val SHARED_PREFS_DIR_NAME = "shared_prefs"
    private val PORTABLE_MEDIA_ROOTS = listOf(
        "ai_server_artifacts",
        "bgr_output",
        "live_translator_audio",
        "onnx_image_output",
        "onnx_tts_output",
        "sd_output",
        "video_upscale_output",
        "video_gen_output",
        "agent_local_workspaces",
        "workflow_media_inputs",
        "workflow_media_translation",
        "workflow_subtitle_translation",
        "media_translation_runtime",
        "tama_gallery",
        "tama_chat_images",
        "tama_chat_audio",
        "adventure_worlds",
        "llama_chat_media",
        "llama_chat_audio",
        "agent_chat_images",
        "native_chat_notes_imports"
    )

    private data class BackupFile(
        val entryName: String,
        val file: File
    )
    
    /**
     * Generate a backup filename with current timestamp.
     */
    fun generateBackupFilename(): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return "aidoomsday_backup_${dateFormat.format(Date())}.zip"
    }
    
    /**
     * Create a backup ZIP of both databases and write it to the given SAF URI.
     * 
     * @param context Application context
     * @param destinationUri SAF URI where the ZIP file will be written
     * @return Result with the backup filename on success, or error message on failure
     */
    suspend fun createBackup(context: Context, destinationUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            DebugLog.log("$TAG Starting backup...")
            
            // Checkpoint WAL to ensure all data is flushed to main DB files
            checkpointDatabases(context)
            
            val dbDir = context.getDatabasePath(APP_DB_NAME).parentFile
                ?: return@withContext Result.failure(
                    Exception(context.getString(R.string.backup_error_db_dir_missing))
                )
            
            val tempDir = File(context.cacheDir, "database_backup_${System.currentTimeMillis()}").apply {
                mkdirs()
            }
            val filesToBackup = mutableListOf<BackupFile>()

            try {
                val appDbFile = File(dbDir, APP_DB_NAME)
                if (appDbFile.exists()) {
                    val sanitizedAppDb = File(tempDir, APP_DB_NAME)
                    appDbFile.copyTo(sanitizedAppDb, overwrite = true)
                    sanitizePortableAppDatabase(sanitizedAppDb)
                    filesToBackup.add(BackupFile(APP_DB_NAME, sanitizedAppDb))
                    DebugLog.log("$TAG Including sanitized: ${sanitizedAppDb.name} (${sanitizedAppDb.length()} bytes)")
                }

                // Tama has no portable model registry, so it can be copied as-is after checkpoint.
                for (suffix in DB_SUFFIXES) {
                    val file = File(dbDir, "$TAMA_DB_NAME$suffix")
                    if (file.exists()) {
                        filesToBackup.add(BackupFile(file.name, file))
                        DebugLog.log("$TAG Including: ${file.name} (${file.length()} bytes)")
                    }
                }

                val mediaFiles = collectPortableMediaFiles(context)
                filesToBackup.addAll(mediaFiles)
                DebugLog.log("$TAG Including portable media files: ${mediaFiles.size}")

                val sharedPrefsFiles = collectSharedPreferencesFiles(context)
                filesToBackup.addAll(sharedPrefsFiles)
                DebugLog.log("$TAG Including shared preference files: ${sharedPrefsFiles.size}")

                if (filesToBackup.isEmpty()) {
                    return@withContext Result.failure(
                        Exception(context.getString(R.string.backup_error_no_files_found))
                    )
                }

                // Write ZIP to the SAF URI
                context.contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                    ZipOutputStream(outputStream).use { zipOut ->
                        for (backupFile in filesToBackup) {
                            val entry = ZipEntry(backupFile.entryName)
                            zipOut.putNextEntry(entry)
                            FileInputStream(backupFile.file).use { fis ->
                                fis.copyTo(zipOut)
                            }
                            zipOut.closeEntry()
                        }
                    }
                } ?: return@withContext Result.failure(
                    Exception(context.getString(R.string.backup_error_open_output_stream))
                )

                DebugLog.log("$TAG Backup completed: ${filesToBackup.size} files")
                Result.success("${filesToBackup.size} files backed up")
            } finally {
                tempDir.deleteRecursively()
            }
            
        } catch (e: Exception) {
            DebugLog.log("$TAG Backup failed: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * Restore databases from a backup ZIP file.
     * 
     * IMPORTANT: After calling this, the app MUST be restarted for changes to take effect,
     * because Room caches the database connection.
     * 
     * @param context Application context
     * @param sourceUri SAF URI of the backup ZIP file
     * @return Result with success message or error
     */
    suspend fun restoreBackup(context: Context, sourceUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            DebugLog.log("$TAG Starting restore...")
            
            val dbDir = context.getDatabasePath(APP_DB_NAME).parentFile
                ?: return@withContext Result.failure(
                    Exception(context.getString(R.string.backup_error_db_dir_missing))
                )
            
            // Close existing database instances so we can overwrite files
            closeDatabases()
            
            var restoredCount = 0
            val restoredFileNames = mutableSetOf<String>()
            val restoredMediaRoots = mutableSetOf<String>()
            var restoredSharedPreferences = false
            val validFileNames = buildSet {
                for (dbName in listOf(APP_DB_NAME, TAMA_DB_NAME)) {
                    for (suffix in DB_SUFFIXES) {
                        add("$dbName$suffix")
                    }
                }
            }
            
            // Read the ZIP and extract database files
            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipIn ->
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        val fileName = entry.name
                        
                        // Only restore known database files (security: prevent path traversal)
                        if (fileName in validFileNames) {
                            val targetFile = File(dbDir, fileName)
                            FileOutputStream(targetFile).use { fos ->
                                zipIn.copyTo(fos)
                            }
                            restoredCount++
                            restoredFileNames += fileName
                            DebugLog.log("$TAG Restored: $fileName (${targetFile.length()} bytes)")
                        } else if (fileName.startsWith(MEDIA_ROOT_PREFIX) && !entry.isDirectory) {
                            val restored = restorePortableMediaEntry(
                                context = context,
                                entryName = fileName,
                                restoredMediaRoots = restoredMediaRoots
                            ) { targetFile ->
                                FileOutputStream(targetFile).use { fos ->
                                    zipIn.copyTo(fos)
                                }
                            }
                            if (restored) {
                                restoredCount++
                                DebugLog.log("$TAG Restored media: $fileName")
                            } else {
                                DebugLog.log("$TAG Skipped unsafe media entry: $fileName")
                            }
                        } else if (fileName.startsWith(SHARED_PREFS_PREFIX) && !entry.isDirectory) {
                            val restored = restoreSharedPreferenceEntry(
                                context = context,
                                entryName = fileName,
                                shouldClearExisting = !restoredSharedPreferences
                            ) { targetFile ->
                                FileOutputStream(targetFile).use { fos ->
                                    zipIn.copyTo(fos)
                                }
                            }
                            if (restored) {
                                restoredCount++
                                restoredSharedPreferences = true
                                DebugLog.log("$TAG Restored preferences: $fileName")
                            } else {
                                DebugLog.log("$TAG Skipped unsafe preferences entry: $fileName")
                            }
                        } else {
                            DebugLog.log("$TAG Skipped unknown file: $fileName")
                        }
                        
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                }
            } ?: return@withContext Result.failure(
                Exception(context.getString(R.string.backup_restore_error_open_input))
            )
            
            if (restoredCount == 0) {
                return@withContext Result.failure(
                    Exception(context.getString(R.string.backup_restore_error_no_valid_files))
                )
            }

            cleanupMissingJournalFiles(dbDir, restoredFileNames)
            if (APP_DB_NAME in restoredFileNames) {
                sanitizePortableAppDatabase(File(dbDir, APP_DB_NAME))
            }
            validateRestoredDatabases(context, dbDir, restoredFileNames)
            
            DebugLog.log("$TAG Restore completed: $restoredCount files")
            Result.success("$restoredCount files restored")
            
        } catch (e: Exception) {
            DebugLog.log("$TAG Restore failed: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * Checkpoint WAL to flush pending writes to the main database files.
     */
    private fun checkpointDatabases(context: Context) {
        try {
            val appDb = AppDatabase.getDatabase(context)
            appDb.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
            DebugLog.log("$TAG AppDatabase WAL checkpointed")
        } catch (e: Exception) {
            DebugLog.log("$TAG AppDatabase checkpoint failed: ${e.message}")
        }
        
        try {
            val tamaDb = TamaDatabase.getInstance(context)
            tamaDb.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
            DebugLog.log("$TAG TamaDatabase WAL checkpointed")
        } catch (e: Exception) {
            DebugLog.log("$TAG TamaDatabase checkpoint failed: ${e.message}")
        }
    }
    
    /**
     * Close both database instances so files can be safely overwritten during restore.
     */
    private fun closeDatabases() {
        try {
            AppDatabase.closeInstance()
            DebugLog.log("$TAG AppDatabase closed")
        } catch (e: Exception) {
            DebugLog.log("$TAG AppDatabase close failed: ${e.message}")
        }
        
        try {
            TamaDatabase.closeInstance()
            DebugLog.log("$TAG TamaDatabase closed")
        } catch (e: Exception) {
            DebugLog.log("$TAG TamaDatabase close failed: ${e.message}")
        }
    }

    private fun validateRestoredDatabases(
        context: Context,
        dbDir: File,
        restoredFileNames: Set<String>
    ) {
        val databasesToValidate = listOf(
            APP_DB_NAME to context.getString(R.string.backup_restore_db_label_app),
            TAMA_DB_NAME to context.getString(R.string.backup_restore_db_label_tama)
        )

        databasesToValidate.forEach { (dbName, label) ->
            val touched = restoredFileNames.any { it == dbName || it.startsWith("$dbName-") }
            if (!touched) return@forEach

            val dbFile = File(dbDir, dbName)
            if (!dbFile.exists()) {
                throw IllegalStateException(
                    context.getString(R.string.backup_restore_error_missing_db_file, label)
                )
            }

            validateIntegrity(context, dbFile, label)
        }
    }

    private fun cleanupMissingJournalFiles(dbDir: File, restoredFileNames: Set<String>) {
        listOf(APP_DB_NAME, TAMA_DB_NAME).forEach dbLoop@ { dbName ->
            if (dbName !in restoredFileNames) return@dbLoop

            listOf("-wal", "-shm").forEach suffixLoop@ { suffix ->
                val fileName = "$dbName$suffix"
                if (fileName in restoredFileNames) return@suffixLoop

                val staleFile = File(dbDir, fileName)
                if (staleFile.exists() && staleFile.delete()) {
                    DebugLog.log("$TAG Deleted stale restore companion: $fileName")
                }
            }
        }
    }

    private fun sanitizePortableAppDatabase(dbFile: File) {
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            sanitizePortableModelLibrary(db)
            if (!tableExists(db, "models")) return

            val before = countRows(db, "models")
            db.execSQL(
                "DELETE FROM models WHERE NOT ${ModelBackupPolicy.IMPORTED_MODEL_SQL_PREDICATE}"
            )
            val after = countRows(db, "models")
            DebugLog.log(
                "$TAG Portable backup model audit kept=$after excluded=${before - after} total=$before " +
                    "(all model rows excluded)"
            )
            runCatching { db.execSQL("VACUUM") }
        } finally {
            db.close()
        }
    }

    private fun tableExists(db: SQLiteDatabase, tableName: String): Boolean {
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(tableName)
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    private fun countRows(db: SQLiteDatabase, tableName: String): Long {
        db.rawQuery("SELECT COUNT(*) FROM $tableName", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    private fun validateIntegrity(context: Context, dbFile: File, label: String) {
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                if (!cursor.moveToFirst()) {
                    throw IllegalStateException(
                        context.getString(R.string.backup_restore_error_integrity_unknown, label)
                    )
                }

                val result = cursor.getString(0).orEmpty()
                if (!result.equals("ok", ignoreCase = true)) {
                    throw IllegalStateException(
                        context.getString(R.string.backup_restore_error_integrity_failed, label, result)
                    )
                }
            }
        } finally {
            db.close()
        }
    }

    private fun collectPortableMediaFiles(context: Context): List<BackupFile> {
        val filesDir = context.filesDir
        return PORTABLE_MEDIA_ROOTS.flatMap { rootName ->
            val root = File(filesDir, rootName)
            if (!root.isDirectory) return@flatMap emptyList()
            root.walkTopDown()
                .filter { it.isFile }
                .mapNotNull { file ->
                    val relative = runCatching {
                        root.toPath().relativize(file.toPath()).toString()
                            .replace(File.separatorChar, '/')
                    }.getOrNull() ?: return@mapNotNull null
                    if (!isSafeRelativePath(relative)) return@mapNotNull null
                    BackupFile("$MEDIA_ROOT_PREFIX$rootName/$relative", file)
                }
                .toList()
        }
    }

    private fun collectSharedPreferencesFiles(context: Context): List<BackupFile> {
        val root = sharedPreferencesDir(context)
        if (!root.isDirectory) return emptyList()
        return root.listFiles()
            ?.filter { it.isFile && it.extension == "xml" }
            ?.mapNotNull { file ->
                val name = file.name
                if (!isSafeSharedPreferenceFileName(name)) return@mapNotNull null
                BackupFile("$SHARED_PREFS_PREFIX$name", file)
            }
            .orEmpty()
    }

    private fun restorePortableMediaEntry(
        context: Context,
        entryName: String,
        restoredMediaRoots: MutableSet<String>,
        writer: (File) -> Unit
    ): Boolean {
        if (!isSafeRelativePath(entryName)) return false
        val relative = entryName.removePrefix(MEDIA_ROOT_PREFIX)
        val rootName = relative.substringBefore('/', missingDelimiterValue = "")
        val childPath = relative.substringAfter('/', missingDelimiterValue = "")
        if (rootName !in PORTABLE_MEDIA_ROOTS || !isSafeRelativePath(childPath)) return false

        val root = File(context.filesDir, rootName)
        if (restoredMediaRoots.add(rootName) && root.exists()) {
            root.deleteRecursively()
        }
        val targetFile = File(root, childPath)
        val canonicalRoot = root.canonicalFile
        val canonicalTarget = targetFile.canonicalFile
        if (!canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)) return false
        canonicalTarget.parentFile?.mkdirs()
        writer(canonicalTarget)
        return true
    }

    private fun restoreSharedPreferenceEntry(
        context: Context,
        entryName: String,
        shouldClearExisting: Boolean,
        writer: (File) -> Unit
    ): Boolean {
        if (!isSafeRelativePath(entryName)) return false
        val fileName = entryName.removePrefix(SHARED_PREFS_PREFIX)
        if (!isSafeSharedPreferenceFileName(fileName)) return false

        val root = sharedPreferencesDir(context)
        if (shouldClearExisting && root.exists()) {
            root.listFiles()?.forEach { existing ->
                if (existing.isFile && (existing.extension == "xml" || existing.name.endsWith(".xml.bak"))) {
                    existing.delete()
                }
            }
        }
        val targetFile = File(root, fileName)
        val canonicalRoot = root.canonicalFile
        val canonicalTarget = targetFile.canonicalFile
        if (!canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)) return false
        canonicalTarget.parentFile?.mkdirs()
        writer(canonicalTarget)
        return true
    }

    private fun sharedPreferencesDir(context: Context): File =
        File(context.applicationInfo.dataDir, SHARED_PREFS_DIR_NAME)

    private fun isSafeSharedPreferenceFileName(fileName: String): Boolean =
        isSafeRelativePath(fileName) && fileName.endsWith(".xml") && !fileName.contains('/')

    private fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.startsWith("/") || path.startsWith("\\")) return false
        if (path.contains('\\')) return false
        return path.split('/').none { it.isBlank() || it == ".." }
    }
}
