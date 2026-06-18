package com.example.llamadroid.data.backup

import android.content.Context
import android.net.Uri
import androidx.annotation.Keep
import androidx.room.withTransaction
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelBackupPolicy
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.NoteEntity
import com.example.llamadroid.data.db.NoteType
import com.example.llamadroid.data.db.OrganizerAlarmEntity
import com.example.llamadroid.data.db.OrganizerEventEntity
import com.example.llamadroid.data.db.OrganizerLlmSettingsEntity
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaChatFolderEntity
import com.example.llamadroid.data.model.LlamaChatPromptProfileEntity
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.data.model.LlamaScheduledTaskEntity
import com.example.llamadroid.data.model.LlamaScheduledTaskLogEntity
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.onnx.OnnxGeneratedImageMetadata
import com.example.llamadroid.onnx.OnnxStorage
import com.example.llamadroid.service.OrganizerAlarmScheduler
import com.example.llamadroid.service.LlamaScheduledTaskScheduler
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.game.FarmEngine
import com.example.llamadroid.tama.game.FarmRepository
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.widget.NoteDisplayWidgetProvider
import com.example.llamadroid.widget.OrganizerCalendarWidgetProvider
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val MODEL_PATH_KIND_FILE = "file"
private const val MODEL_PATH_KIND_DIRECTORY = "directory"

object NativeChatNotesBackupManager {
    private const val TAG = "[NativeBackup]"
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val SCHEMA_VERSION = 2
    private const val MIN_SUPPORTED_SCHEMA_VERSION = 1
    private const val TAMA_BACKUP_ENTRY = "tama/tama_backup.zip"

    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun generateBackupFilename(): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return "llama_native_notes_${dateFormat.format(Date())}.zip"
    }

    suspend fun exportToZip(
        context: Context,
        database: AppDatabase,
        destinationUri: Uri
    ): Result<NativeChatNotesBackupExportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val collector = MediaCollector()
            val modelCollector = ModelFileCollector()
            val servers = database.llamaServerDao().getAllServers().first()
            val folders = database.llamaChatFolderDao().getAllFolders().first()
            val profiles = database.llamaChatPromptProfileDao().getAllProfiles().first()
            val chats = database.llamaChatDao().getAllChats().first()
            val messages = chats
                .flatMap { chat -> database.llamaMessageDao().getMessagesOnce(chat.id) }
                .sortedWith(compareBy<LlamaMessageEntity> { it.chatId }.thenBy { it.timestamp }.thenBy { it.id })
            val notes = database.noteDao().getAllNotesOnce()
            val organizerEvents = database.organizerDao().getAllEventsOnce()
            val organizerAlarms = database.organizerDao().getAllAlarmsOnce()
            val organizerSettings = database.organizerDao().getLlmSettingsOnce()
            val scheduledTasks = database.llamaScheduledTaskDao().getAllTasks().first()
            val scheduledTaskLogs = database.llamaScheduledTaskDao().getRecentLogs(10_000).first()
            val onnxGalleryImages = collectOnnxGalleryImages(context, collector)
            val tamaBackup = exportTamaBackup(context)

            val backupMessages = messages.map { message ->
                val imageMedia = collector.register(
                    originalPath = message.imagePath,
                    kind = NativeBackupMediaKind.CHAT_IMAGE,
                    owner = "message_${message.id}"
                )
                val audioMedia = collector.register(
                    originalPath = message.audioPath,
                    kind = NativeBackupMediaKind.CHAT_AUDIO,
                    owner = "message_${message.id}"
                )
                LlamaMessageBackup(
                    oldId = message.id,
                    chatOldId = message.chatId,
                    role = message.role,
                    content = message.content,
                    imagePath = message.imagePath,
                    imageMediaKey = imageMedia?.entry?.key,
                    audioPath = message.audioPath,
                    audioMediaKey = audioMedia?.entry?.key,
                    timestamp = message.timestamp,
                    isError = message.isError,
                    isTruncated = message.isTruncated,
                    thinking = message.thinking,
                    promptTokens = message.promptTokens,
                    completionTokens = message.completionTokens,
                    tps = message.tps,
                    generationTimeMs = message.generationTimeMs
                )
            }

            val backupNotes = notes.map { note ->
                val audioMedia = collector.register(
                    originalPath = note.audioPath,
                    kind = NativeBackupMediaKind.NOTE_AUDIO,
                    owner = "note_${note.id}"
                )
                val sourceMedia = collector.registerSourceMedia(note.sourceFile, "note_${note.id}_source")
                val imageRefs = NativeChatNotesBackupSupport.findMarkdownImageTargets(note.content)
                    .mapNotNull { target ->
                        collector.register(
                            originalPath = target,
                            kind = NativeBackupMediaKind.NOTE_IMAGE,
                            owner = "note_${note.id}_image"
                        )?.let { media ->
                            NoteImageBackup(target = target, mediaKey = media.entry.key)
                        }
                    }
                    .distinctBy { it.target }

                NoteBackup(
                    oldId = note.id,
                    title = note.title,
                    content = note.content,
                    type = note.type.name,
                    sourceFile = note.sourceFile,
                    sourceFileMediaKey = sourceMedia?.entry?.key,
                    language = note.language,
                    audioPath = note.audioPath,
                    audioMediaKey = audioMedia?.entry?.key,
                    createdAt = note.createdAt,
                    updatedAt = note.updatedAt,
                    isLlmWhitelisted = note.isLlmWhitelisted,
                    imageRefs = imageRefs
                )
            }

            val backupModels = database.modelDao()
                .getAllModels()
                .first()
                .filter(ModelBackupPolicy::shouldKeepInPortableBackup)
                .mapNotNull { model ->
                    val modelGroup = modelCollector.register(
                        originalPath = model.path,
                        owner = "model_${model.filename}"
                    )
                    if (modelGroup == null) {
                        DebugLog.log("$TAG Skipping imported model with missing files: ${model.filename}")
                        return@mapNotNull null
                    }
                    val mmprojGroup = modelCollector.register(
                        originalPath = model.mmprojPath,
                        owner = "model_${model.filename}_mmproj"
                    )
                    model.toBackup(modelGroup, mmprojGroup)
                }
            val modelFiles = modelCollector.fileEntries()
            val media = collector.entries().map { it.entry }
            val manifest = NativeChatNotesBackupManifest(
                schemaVersion = SCHEMA_VERSION,
                exportedAt = System.currentTimeMillis(),
                servers = servers.map { it.toBackup() },
                folders = folders.map { it.toBackup() },
                promptProfiles = profiles.map { it.toBackup() },
                chats = chats.map { it.toBackup() },
                messages = backupMessages,
                notes = backupNotes,
                organizerSettings = organizerSettings?.toBackup(),
                organizerEvents = organizerEvents.map { it.toBackup() },
                organizerAlarms = organizerAlarms.map { it.toBackup() },
                scheduledTasks = scheduledTasks.map { it.toBackup() },
                scheduledTaskLogs = scheduledTaskLogs.map { it.toBackup() },
                onnxGalleryImages = onnxGalleryImages,
                tamaBackup = tamaBackup?.entry,
                models = backupModels,
                modelFiles = modelFiles,
                media = media
            )

            context.contentResolver.openOutputStream(destinationUri)?.use { output ->
                ZipOutputStream(output.buffered()).use { zipOut ->
                    zipOut.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                    zipOut.write(gson.toJson(manifest).toByteArray(Charsets.UTF_8))
                    zipOut.closeEntry()

                    collector.entries().forEach { mediaWork ->
                        zipOut.putNextEntry(ZipEntry(mediaWork.entry.zipPath))
                        FileInputStream(mediaWork.file).use { input ->
                            input.copyTo(zipOut)
                        }
                        zipOut.closeEntry()
                        val metadataZipPath = mediaWork.entry.metadataZipPath
                        val metadataFile = mediaWork.metadataFile
                        if (metadataZipPath != null && metadataFile != null) {
                            zipOut.putNextEntry(ZipEntry(metadataZipPath))
                            FileInputStream(metadataFile).use { input ->
                                input.copyTo(zipOut)
                            }
                            zipOut.closeEntry()
                        }
                    }
                    modelCollector.fileWorks().forEach { modelWork ->
                        zipOut.putNextEntry(ZipEntry(modelWork.entry.zipPath))
                        FileInputStream(modelWork.file).use { input ->
                            input.copyTo(zipOut)
                        }
                        zipOut.closeEntry()
                    }
                    tamaBackup?.let { backup ->
                        zipOut.putNextEntry(ZipEntry(backup.entry.zipPath))
                        zipOut.write(backup.bytes)
                        zipOut.closeEntry()
                    }
                }
            } ?: error("Could not open backup output stream")

            DebugLog.log(
                "$TAG Exported ${chats.size} chats, ${messages.size} messages, " +
                    "${notes.size} notes, ${media.size} media files, ${backupModels.size} imported models, " +
                    "${scheduledTasks.size} scheduled tasks, ${scheduledTaskLogs.size} scheduler logs, " +
                    "${onnxGalleryImages.size} ONNX gallery images, tamaBackup=${tamaBackup != null}"
            )
            NativeChatNotesBackupExportResult(
                servers = servers.size,
                folders = folders.size,
                promptProfiles = profiles.size,
                chats = chats.size,
                messages = messages.size,
                notes = notes.size,
                organizerEvents = organizerEvents.size,
                organizerAlarms = organizerAlarms.size,
                scheduledTasks = scheduledTasks.size,
                scheduledTaskLogs = scheduledTaskLogs.size,
                mediaFiles = media.size,
                models = backupModels.size
            )
        }
    }

    suspend fun importFromZip(
        context: Context,
        database: AppDatabase,
        sourceUri: Uri
    ): Result<NativeChatNotesBackupImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val timestamp = System.currentTimeMillis()
            val tempDir = File(context.cacheDir, "native_chat_notes_import_$timestamp")
            val mediaRoot = File(context.filesDir, "native_chat_notes_imports/import_$timestamp")
            val modelRoot = File(mediaRoot, "models")
            tempDir.mkdirs()
            mediaRoot.mkdirs()
            modelRoot.mkdirs()
            try {
                extractZipToTemp(context, sourceUri, tempDir)
                val manifestFile = File(tempDir, MANIFEST_ENTRY)
                require(manifestFile.isFile) { "Backup manifest is missing" }
                val manifest = gson.fromJson(
                    manifestFile.readText(Charsets.UTF_8),
                    NativeChatNotesBackupManifest::class.java
                )
                require(manifest.schemaVersion in MIN_SUPPORTED_SCHEMA_VERSION..SCHEMA_VERSION) {
                    "Unsupported native chat backup version ${manifest.schemaVersion}"
                }

                val onnxMediaKind = NativeBackupMediaKind.ONNX_GALLERY_IMAGE.name
                val importedMediaPaths = copyImportedMedia(
                    tempDir,
                    mediaRoot,
                    manifest.media.filterNot { it.kind == onnxMediaKind }
                )
                val importedOnnxGalleryCount = copyImportedOnnxGalleryMedia(
                    context = context,
                    tempDir = tempDir,
                    images = manifest.onnxGalleryImages.orEmpty(),
                    mediaEntries = manifest.media
                )
                val importedModelPaths = copyImportedModelFiles(
                    tempDir = tempDir,
                    modelRoot = modelRoot,
                    models = manifest.models,
                    modelFiles = manifest.modelFiles
                )

                val existingFolderNames = database.llamaChatFolderDao()
                    .getAllFolders()
                    .first()
                    .map { it.name }
                    .toMutableSet()
                val existingProfileNames = database.llamaChatPromptProfileDao()
                    .getAllProfiles()
                    .first()
                    .map { it.name }
                    .toMutableSet()

                var importedServers = 0
                var importedFolders = 0
                var importedProfiles = 0
                var importedChats = 0
                var importedMessages = 0
                var importedNotes = 0
                var importedOrganizerEvents = 0
                var importedOrganizerAlarms = 0
                var importedScheduledTasks = 0
                var importedScheduledTaskLogs = 0
                var importedModels = 0
                val importedTasksToSchedule = mutableListOf<LlamaScheduledTaskEntity>()

                database.withTransaction {
                    manifest.models.forEach { model ->
                        val entity = model.toEntity(importedModelPaths) ?: return@forEach
                        database.modelDao().insertModel(entity)
                        importedModels += 1
                    }

                    val serverIdMap = mutableMapOf<Long, Long>()
                    manifest.servers.forEach { server ->
                        val newId = database.llamaServerDao().insertServer(server.toEntity())
                        serverIdMap[server.oldId] = newId
                        importedServers += 1
                    }

                    val folderIdMap = mutableMapOf<Long, Long>()
                    manifest.folders.forEach { folder ->
                        val folderName = NativeChatNotesBackupSupport.uniqueImportedName(
                            preferredName = folder.name,
                            existingNames = existingFolderNames
                        )
                        val newId = database.llamaChatFolderDao().insertFolder(folder.toEntity(folderName))
                        folderIdMap[folder.oldId] = newId
                        importedFolders += 1
                    }

                    manifest.promptProfiles.forEach { profile ->
                        val profileName = NativeChatNotesBackupSupport.uniqueImportedName(
                            preferredName = profile.name,
                            existingNames = existingProfileNames
                        )
                        database.llamaChatPromptProfileDao().insertProfile(profile.toEntity(profileName))
                        importedProfiles += 1
                    }

                    val chatIdMap = mutableMapOf<Long, Long>()
                    manifest.chats.forEach { chat ->
                        val newFolderId = chat.folderOldId?.let { folderIdMap[it] }
                        val newId = database.llamaChatDao().insertChat(chat.toEntity(newFolderId))
                        chatIdMap[chat.oldId] = newId
                        importedChats += 1
                    }

                    manifest.messages.forEach { message ->
                        val newChatId = chatIdMap[message.chatOldId] ?: return@forEach
                        database.llamaMessageDao().insertMessage(
                            message.toEntity(
                                chatId = newChatId,
                                imagePath = message.imageMediaKey?.let { importedMediaPaths[it] },
                                audioPath = message.audioMediaKey?.let { importedMediaPaths[it] }
                            )
                        )
                        importedMessages += 1
                    }

                    manifest.notes.forEach { note ->
                        val noteImageReplacements = note.imageRefs.associate { image ->
                            image.target to (importedMediaPaths[image.mediaKey] ?: image.target)
                        }
                        val remappedContent = NativeChatNotesBackupSupport.rewriteMarkdownImageTargets(
                            content = note.content,
                            replacements = noteImageReplacements
                        )
                        database.noteDao().insert(
                            note.toEntity(
                                content = remappedContent,
                                sourceFile = note.sourceFileMediaKey?.let { importedMediaPaths[it] }
                                    ?: note.sourceFile,
                                audioPath = note.audioMediaKey?.let { importedMediaPaths[it] }
                                    ?: note.audioPath
                            )
                        )
                        importedNotes += 1
                    }

                    manifest.organizerSettings?.let { settings ->
                        database.organizerDao().upsertLlmSettings(settings.toEntity())
                    }

                    val organizerEventIdMap = mutableMapOf<Long, Long>()
                    manifest.organizerEvents.orEmpty().forEach { event ->
                        val newId = database.organizerDao().insertEvent(event.toEntity())
                        organizerEventIdMap[event.oldId] = newId
                        importedOrganizerEvents += 1
                    }

                    manifest.organizerAlarms.orEmpty().forEach { alarm ->
                        val newEventId = alarm.eventOldId?.let { organizerEventIdMap[it] }
                        val newId = database.organizerDao().insertAlarm(alarm.toEntity(newEventId))
                        val importedAlarm = database.organizerDao().getAlarmById(newId)
                        if (importedAlarm != null && importedAlarm.enabled && importedAlarm.triggerAtMillis > System.currentTimeMillis()) {
                            OrganizerAlarmScheduler.scheduleAlarm(context, importedAlarm)
                        }
                        importedOrganizerAlarms += 1
                    }

                    val scheduledTaskIdMap = mutableMapOf<Long, Long>()
                    manifest.scheduledTasks.orEmpty().forEach { task ->
                        val newServerId = task.serverOldId?.let { serverIdMap[it] }
                        val newId = database.llamaScheduledTaskDao().insertTask(task.toEntity(newServerId))
                        scheduledTaskIdMap[task.oldId] = newId
                        database.llamaScheduledTaskDao().getTaskById(newId)?.let { importedTasksToSchedule += it }
                        importedScheduledTasks += 1
                    }

                    manifest.scheduledTaskLogs.orEmpty().forEach { log ->
                        database.llamaScheduledTaskDao().insertLog(
                            log.toEntity(
                                taskId = log.taskOldId?.let { scheduledTaskIdMap[it] },
                                serverId = log.serverOldId?.let { serverIdMap[it] }
                            )
                        )
                        importedScheduledTaskLogs += 1
                    }
                }

                val importedTamaBackup = importTamaBackup(
                    context = context,
                    tempDir = tempDir,
                    backup = manifest.tamaBackup
                )
                if (importedOrganizerEvents > 0 || importedOrganizerAlarms > 0) {
                    OrganizerCalendarWidgetProvider.refreshAll(context.applicationContext)
                }
                if (importedNotes > 0) {
                    NoteDisplayWidgetProvider.refreshAll(context.applicationContext)
                }
                importedTasksToSchedule
                    .filter { it.enabled && (it.nextRunAtMillis ?: 0L) > System.currentTimeMillis() }
                    .forEach { LlamaScheduledTaskScheduler.scheduleTask(context, it) }

                DebugLog.log(
                    "$TAG Imported $importedChats chats, $importedMessages messages, " +
                        "$importedNotes notes, $importedOrganizerEvents organizer events, " +
                        "$importedOrganizerAlarms organizer alarms, $importedScheduledTasks scheduled tasks, " +
                        "$importedScheduledTaskLogs scheduler logs, ${importedMediaPaths.size} media files, " +
                        "$importedOnnxGalleryCount ONNX gallery images, $importedModels imported models, tamaBackup=$importedTamaBackup"
                )
                NativeChatNotesBackupImportResult(
                    servers = importedServers,
                    folders = importedFolders,
                    promptProfiles = importedProfiles,
                    chats = importedChats,
                    messages = importedMessages,
                    notes = importedNotes,
                    organizerEvents = importedOrganizerEvents,
                    organizerAlarms = importedOrganizerAlarms,
                    scheduledTasks = importedScheduledTasks,
                    scheduledTaskLogs = importedScheduledTaskLogs,
                    mediaFiles = importedMediaPaths.size + importedOnnxGalleryCount,
                    models = importedModels
                )
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    private fun extractZipToTemp(context: Context, sourceUri: Uri, tempDir: File) {
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            ZipInputStream(input.buffered()).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    if (!NativeChatNotesBackupSupport.isSafeZipEntryName(entryName)) {
                        throw IllegalArgumentException("Unsafe ZIP entry: $entryName")
                    }
                    val target = File(tempDir, entryName)
                    val canonicalRoot = tempDir.canonicalFile
                    val canonicalTarget = target.canonicalFile
                    if (!canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)) {
                        throw IllegalArgumentException("Unsafe ZIP entry: $entryName")
                    }
                    if (entry.isDirectory) {
                        canonicalTarget.mkdirs()
                    } else {
                        canonicalTarget.parentFile?.mkdirs()
                        FileOutputStream(canonicalTarget).use { output ->
                            zipIn.copyTo(output)
                        }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
        } ?: error("Could not open backup input stream")
    }

    private fun copyImportedMedia(
        tempDir: File,
        mediaRoot: File,
        mediaEntries: List<MediaBackupEntry>
    ): Map<String, String> {
        val importedPaths = mutableMapOf<String, String>()
        mediaEntries.forEach { media ->
            if (!NativeChatNotesBackupSupport.isSafeZipEntryName(media.zipPath)) return@forEach
            val source = File(tempDir, media.zipPath)
            if (!source.isFile) return@forEach
            val relativePath = media.zipPath.removePrefix("media/").ifBlank { media.zipPath }
            val target = File(mediaRoot, relativePath)
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
            val metadataZipPath = media.metadataZipPath
            if (metadataZipPath != null && NativeChatNotesBackupSupport.isSafeZipEntryName(metadataZipPath)) {
                val metadataSource = File(tempDir, metadataZipPath)
                if (metadataSource.isFile) {
                    val metadataTarget = File(target.parentFile, "${target.name}.json")
                    metadataSource.copyTo(metadataTarget, overwrite = true)
                }
            }
            importedPaths[media.key] = target.absolutePath
        }
        return importedPaths
    }

    private fun copyImportedModelFiles(
        tempDir: File,
        modelRoot: File,
        models: List<ModelBackup>,
        modelFiles: List<ModelFileBackupEntry>
    ): Map<String, String> {
        val pathKinds = mutableMapOf<String, String>()
        models.forEach { model ->
            model.modelFileGroupKey?.let { pathKinds[it] = model.modelPathKind }
            model.mmprojFileGroupKey?.let { pathKinds[it] = model.mmprojPathKind ?: MODEL_PATH_KIND_FILE }
        }

        val importedPaths = mutableMapOf<String, String>()
        modelFiles.groupBy { it.modelKey }.forEach { (modelKey, entries) ->
            val targetBase = File(modelRoot, NativeChatNotesBackupSupport.sanitizeZipName(modelKey))
            entries.forEach entryLoop@ { entry ->
                if (!NativeChatNotesBackupSupport.isSafeZipEntryName(entry.zipPath)) return@entryLoop
                if (!NativeChatNotesBackupSupport.isSafeZipEntryName("models/${entry.relativePath}")) return@entryLoop
                val source = File(tempDir, entry.zipPath)
                if (!source.isFile) return@entryLoop
                val target = File(targetBase, entry.relativePath)
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = true)
            }

            val pathKind = pathKinds[modelKey] ?: MODEL_PATH_KIND_DIRECTORY
            importedPaths[modelKey] = if (pathKind == MODEL_PATH_KIND_FILE) {
                val firstEntry = entries.minByOrNull { it.relativePath.count { char -> char == '/' } }
                firstEntry?.let { File(targetBase, it.relativePath).absolutePath } ?: targetBase.absolutePath
            } else {
                targetBase.absolutePath
            }
        }
        return importedPaths
    }

    private fun collectOnnxGalleryImages(
        context: Context,
        collector: MediaCollector
    ): List<OnnxGalleryImageBackup> {
        val outputDir = OnnxStorage.txt2ImgOutputDir(context)
        val imageExtensions = setOf("png", "jpg", "jpeg", "webp")
        return outputDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase(Locale.US) in imageExtensions }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { file ->
                collector.register(
                    originalPath = file.absolutePath,
                    kind = NativeBackupMediaKind.ONNX_GALLERY_IMAGE,
                    owner = "onnx_gallery_${file.nameWithoutExtension}"
                )?.let { media ->
                    OnnxGalleryImageBackup(
                        originalPath = file.absolutePath,
                        fileName = file.name,
                        imageMediaKey = media.entry.key
                    )
                }
            }
            .orEmpty()
    }

    private fun copyImportedOnnxGalleryMedia(
        context: Context,
        tempDir: File,
        images: List<OnnxGalleryImageBackup>,
        mediaEntries: List<MediaBackupEntry>
    ): Int {
        val entriesByKey = mediaEntries.associateBy { it.key }
        val outputDir = OnnxStorage.txt2ImgOutputDir(context).apply { mkdirs() }
        var imported = 0
        images.forEach { image ->
            val entry = entriesByKey[image.imageMediaKey] ?: return@forEach
            if (!NativeChatNotesBackupSupport.isSafeZipEntryName(entry.zipPath)) return@forEach
            val source = File(tempDir, entry.zipPath)
            if (!source.isFile) return@forEach
            val targetName = NativeChatNotesBackupSupport.uniqueFileName(
                directory = outputDir,
                preferredName = image.fileName.ifBlank { source.name }
            )
            val target = File(outputDir, targetName)
            source.copyTo(target, overwrite = true)

            val metadataZipPath = entry.metadataZipPath
            if (metadataZipPath != null && NativeChatNotesBackupSupport.isSafeZipEntryName(metadataZipPath)) {
                val metadataSource = File(tempDir, metadataZipPath)
                if (metadataSource.isFile) {
                    val metadataTarget = OnnxStorage.metadataFileFor(target)
                    val rewritten = runCatching {
                        OnnxGeneratedImageMetadata.fromJson(metadataSource.readText(Charsets.UTF_8))
                            .copy(imagePath = target.absolutePath)
                            .toJsonString()
                    }.getOrElse {
                        metadataSource.readText(Charsets.UTF_8)
                    }
                    metadataTarget.writeText(rewritten, Charsets.UTF_8)
                }
            }
            imported += 1
        }
        return imported
    }

    private suspend fun exportTamaBackup(context: Context): TamaBackupWork? {
        return runCatching {
            val engine = buildTamaGameEngine(context)
            try {
                val bytes = ByteArrayOutputStream()
                if (!engine.exportToBackupZip(bytes)) return@runCatching null
                val payload = bytes.toByteArray()
                if (payload.isEmpty()) return@runCatching null
                TamaBackupWork(
                    entry = TamaBackupEntry(
                        zipPath = TAMA_BACKUP_ENTRY,
                        sizeBytes = payload.size.toLong()
                    ),
                    bytes = payload
                )
            } finally {
                engine.close()
            }
        }.onFailure { error ->
            DebugLog.log("$TAG Tama portable export skipped: ${error.message}")
        }.getOrNull()
    }

    private suspend fun importTamaBackup(
        context: Context,
        tempDir: File,
        backup: TamaBackupEntry?
    ): Boolean {
        val entry = backup ?: return false
        if (!NativeChatNotesBackupSupport.isSafeZipEntryName(entry.zipPath)) return false
        val file = File(tempDir, entry.zipPath)
        if (!file.isFile) return false
        return runCatching {
            val engine = buildTamaGameEngine(context)
            try {
                ByteArrayInputStream(file.readBytes()).use { input ->
                    engine.importFromBackup(input)
                }
            } finally {
                engine.close()
            }
        }.onFailure { error ->
            DebugLog.log("$TAG Tama portable import failed: ${error.message}")
        }.getOrDefault(false)
    }

    private fun buildTamaGameEngine(context: Context): TamaGameEngine {
        val appContext = context.applicationContext
        val tamaDatabase = TamaDatabase.getInstance(appContext)
        val farmRepository = FarmRepository(tamaDatabase.farmDao(), appContext)
        val farmEngine = FarmEngine(farmRepository)
        return TamaGameEngine(
            context = appContext,
            dao = tamaDatabase.tamaDao(),
            farmEngine = farmEngine,
            farmRepository = farmRepository,
            settingsRepo = SettingsRepository(appContext)
        )
    }

    private fun LlamaServerEntity.toBackup(): LlamaServerBackup = LlamaServerBackup(
        oldId = id,
        name = name,
        host = host,
        port = port,
        engine = engine,
        supportsVision = supportsVision,
        supportsAudio = supportsAudio,
        modelName = modelName,
        whisperModelPath = whisperModelPath,
        whisperLanguage = whisperLanguage,
        preferWhisperAudioTranscription = preferWhisperAudioTranscription,
        defaultApiParams = defaultApiParams,
        lastUsed = lastUsed
    )

    private fun LlamaChatFolderEntity.toBackup(): LlamaChatFolderBackup = LlamaChatFolderBackup(
        oldId = id,
        name = name,
        createdAt = createdAt
    )

    private fun LlamaChatPromptProfileEntity.toBackup(): LlamaChatPromptProfileBackup =
        LlamaChatPromptProfileBackup(
            oldId = id,
            name = name,
            content = content,
            createdAt = createdAt,
            updatedAt = updatedAt
        )

    private fun LlamaChatEntity.toBackup(): LlamaChatBackup = LlamaChatBackup(
        oldId = id,
        title = title,
        lastModified = lastModified,
        contextSize = contextSize,
        systemPrompt = systemPrompt,
        apiParams = apiParams,
        folderOldId = folderId
    )

    private fun LlamaServerBackup.toEntity(): LlamaServerEntity = LlamaServerEntity(
        name = name,
        host = host,
        port = port,
        engine = engine,
        supportsVision = supportsVision,
        supportsAudio = supportsAudio,
        modelName = modelName,
        whisperModelPath = whisperModelPath,
        whisperLanguage = whisperLanguage,
        preferWhisperAudioTranscription = preferWhisperAudioTranscription,
        defaultApiParams = defaultApiParams,
        lastUsed = lastUsed
    )

    private fun LlamaChatFolderBackup.toEntity(name: String): LlamaChatFolderEntity =
        LlamaChatFolderEntity(
            name = name,
            createdAt = createdAt
        )

    private fun LlamaChatPromptProfileBackup.toEntity(name: String): LlamaChatPromptProfileEntity =
        LlamaChatPromptProfileEntity(
            name = name,
            content = content,
            createdAt = createdAt,
            updatedAt = updatedAt
        )

    private fun LlamaChatBackup.toEntity(folderId: Long?): LlamaChatEntity = LlamaChatEntity(
        title = title,
        lastModified = lastModified,
        contextSize = contextSize,
        systemPrompt = systemPrompt,
        apiParams = apiParams,
        folderId = folderId
    )

    private fun LlamaMessageBackup.toEntity(
        chatId: Long,
        imagePath: String?,
        audioPath: String?
    ): LlamaMessageEntity = LlamaMessageEntity(
        chatId = chatId,
        role = role,
        content = content,
        imagePath = imagePath,
        audioPath = audioPath,
        timestamp = timestamp,
        isError = isError,
        isTruncated = isTruncated,
        thinking = thinking,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        tps = tps,
        generationTimeMs = generationTimeMs
    )

    private fun NoteBackup.toEntity(
        content: String,
        sourceFile: String?,
        audioPath: String?
    ): NoteEntity = NoteEntity(
        title = title,
        content = content,
        type = runCatching { NoteType.valueOf(type) }.getOrDefault(NoteType.MANUAL),
        sourceFile = sourceFile,
        language = language,
        audioPath = audioPath,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isLlmWhitelisted = isLlmWhitelisted
    )

    private fun OrganizerLlmSettingsEntity.toBackup(): OrganizerSettingsBackup = OrganizerSettingsBackup(
        calendarToolsAllowed = calendarToolsAllowed,
        alarmToolsAllowed = alarmToolsAllowed,
        updatedAt = updatedAt
    )

    private fun OrganizerSettingsBackup.toEntity(): OrganizerLlmSettingsEntity = OrganizerLlmSettingsEntity(
        calendarToolsAllowed = calendarToolsAllowed,
        alarmToolsAllowed = alarmToolsAllowed,
        updatedAt = updatedAt
    )

    private fun OrganizerEventEntity.toBackup(): OrganizerEventBackup = OrganizerEventBackup(
        oldId = id,
        title = title,
        description = description,
        location = location,
        startAtMillis = startAtMillis,
        endAtMillis = endAtMillis,
        allDay = allDay,
        timezoneId = timezoneId,
        colorArgb = colorArgb,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun OrganizerEventBackup.toEntity(): OrganizerEventEntity = OrganizerEventEntity(
        title = title,
        description = description,
        location = location,
        startAtMillis = startAtMillis,
        endAtMillis = endAtMillis,
        allDay = allDay,
        timezoneId = timezoneId,
        colorArgb = colorArgb,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun OrganizerAlarmEntity.toBackup(): OrganizerAlarmBackup = OrganizerAlarmBackup(
        oldId = id,
        eventOldId = eventId,
        title = title,
        message = message,
        triggerAtMillis = triggerAtMillis,
        timezoneId = timezoneId,
        soundEnabled = soundEnabled,
        enabled = enabled,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deliveredAt = deliveredAt
    )

    private fun OrganizerAlarmBackup.toEntity(eventId: Long?): OrganizerAlarmEntity = OrganizerAlarmEntity(
        eventId = eventId,
        title = title,
        message = message,
        triggerAtMillis = triggerAtMillis,
        timezoneId = timezoneId,
        soundEnabled = soundEnabled,
        enabled = enabled,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deliveredAt = deliveredAt
    )

    private fun LlamaScheduledTaskEntity.toBackup(): LlamaScheduledTaskBackup = LlamaScheduledTaskBackup(
        oldId = id,
        name = name,
        enabled = enabled,
        serverOldId = serverId,
        contextSize = contextSize,
        systemPrompt = systemPrompt,
        taskPrompt = taskPrompt,
        apiParams = apiParams,
        scheduleType = scheduleType,
        oneTimeAtMillis = oneTimeAtMillis,
        timeOfDayMinutes = timeOfDayMinutes,
        weekdaysMask = weekdaysMask,
        dayOfMonth = dayOfMonth,
        timezoneId = timezoneId,
        nextRunAtMillis = nextRunAtMillis,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastRunAtMillis = lastRunAtMillis
    )

    private fun LlamaScheduledTaskBackup.toEntity(serverId: Long?): LlamaScheduledTaskEntity =
        LlamaScheduledTaskEntity(
            name = name,
            enabled = enabled,
            serverId = serverId,
            contextSize = contextSize,
            systemPrompt = systemPrompt,
            taskPrompt = taskPrompt,
            apiParams = apiParams,
            scheduleType = scheduleType,
            oneTimeAtMillis = oneTimeAtMillis,
            timeOfDayMinutes = timeOfDayMinutes,
            weekdaysMask = weekdaysMask,
            dayOfMonth = dayOfMonth,
            timezoneId = timezoneId,
            nextRunAtMillis = nextRunAtMillis,
            createdAt = createdAt,
            updatedAt = updatedAt,
            lastRunAtMillis = lastRunAtMillis
        )

    private fun LlamaScheduledTaskLogEntity.toBackup(): LlamaScheduledTaskLogBackup =
        LlamaScheduledTaskLogBackup(
            oldId = id,
            taskOldId = taskId,
            taskName = taskName,
            scheduledAtMillis = scheduledAtMillis,
            startedAtMillis = startedAtMillis,
            finishedAtMillis = finishedAtMillis,
            durationMs = durationMs,
            status = status,
            serverOldId = serverId,
            serverName = serverName,
            serverBaseUrl = serverBaseUrl,
            finalOutput = finalOutput,
            error = error,
            toolActivity = toolActivity,
            createdAt = createdAt
        )

    private fun LlamaScheduledTaskLogBackup.toEntity(
        taskId: Long?,
        serverId: Long?
    ): LlamaScheduledTaskLogEntity = LlamaScheduledTaskLogEntity(
        taskId = taskId,
        taskName = taskName,
        scheduledAtMillis = scheduledAtMillis,
        startedAtMillis = startedAtMillis,
        finishedAtMillis = finishedAtMillis,
        durationMs = durationMs,
        status = status,
        serverId = serverId,
        serverName = serverName,
        serverBaseUrl = serverBaseUrl,
        finalOutput = finalOutput,
        error = error,
        toolActivity = toolActivity,
        createdAt = createdAt
    )

    private fun ModelEntity.toBackup(
        modelGroup: ModelFileGroupWork,
        mmprojGroup: ModelFileGroupWork?
    ): ModelBackup = ModelBackup(
        filename = filename,
        path = path,
        sizeBytes = sizeBytes,
        type = type.name,
        repoId = repoId,
        isDownloaded = isDownloaded,
        isVision = isVision,
        mmprojPath = mmprojPath,
        mmprojFileGroupKey = mmprojGroup?.key,
        mmprojPathKind = mmprojGroup?.pathKind,
        sdCapabilities = sdCapabilities,
        sdFamily = sdFamily,
        sdVariant = sdVariant,
        sdCompatProfiles = sdCompatProfiles,
        onnxCapabilities = onnxCapabilities,
        onnxAssetKind = onnxAssetKind,
        onnxPipelineFamily = onnxPipelineFamily,
        onnxReferenceUri = onnxReferenceUri,
        onnxReferencePath = onnxReferencePath,
        layerCount = layerCount,
        modelFileGroupKey = modelGroup.key,
        modelPathKind = modelGroup.pathKind
    )

    private fun ModelBackup.toEntity(importedModelPaths: Map<String, String>): ModelEntity? {
        val typeValue = runCatching { ModelType.valueOf(type) }.getOrNull() ?: return null
        val resolvedPath = modelFileGroupKey?.let { importedModelPaths[it] } ?: return null
        val resolvedFile = File(resolvedPath)
        if (!resolvedFile.exists()) return null
        val resolvedMmprojPath = mmprojFileGroupKey?.let { importedModelPaths[it] } ?: mmprojPath
        val resolvedSize = NativeChatNotesBackupSupport.fileOrDirectorySize(resolvedFile).takeIf { it > 0L }
            ?: sizeBytes
        return ModelEntity(
            filename = filename,
            path = resolvedPath,
            sizeBytes = resolvedSize,
            type = typeValue,
            repoId = repoId,
            isDownloaded = isDownloaded,
            isVision = isVision,
            mmprojPath = resolvedMmprojPath,
            sdCapabilities = sdCapabilities,
            sdFamily = sdFamily,
            sdVariant = sdVariant,
            sdCompatProfiles = sdCompatProfiles,
            onnxCapabilities = onnxCapabilities,
            onnxAssetKind = onnxAssetKind,
            onnxPipelineFamily = onnxPipelineFamily,
            onnxReferenceUri = onnxReferenceUri,
            onnxReferencePath = onnxReferencePath,
            layerCount = layerCount
        )
    }
}

object NativeChatNotesBackupSupport {
    private val noteImagePattern = Regex("""!\[([^\]]*)]\(([^)]+)\)""")

    fun findMarkdownImageTargets(content: String): List<String> =
        noteImagePattern.findAll(content)
            .map { it.groupValues[2].trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

    fun rewriteMarkdownImageTargets(content: String, replacements: Map<String, String>): String =
        noteImagePattern.replace(content) { match ->
            val altText = match.groupValues[1]
            val target = match.groupValues[2].trim()
            val replacement = replacements[target] ?: return@replace match.value
            "![$altText]($replacement)"
        }

    fun uniqueImportedName(preferredName: String, existingNames: MutableSet<String>): String {
        val base = preferredName.trim().ifBlank { "Imported" }
        if (existingNames.add(base)) return base
        var index = 2
        while (true) {
            val candidate = "$base ($index)"
            if (existingNames.add(candidate)) return candidate
            index += 1
        }
    }

    fun isSafeZipEntryName(name: String): Boolean {
        if (name.isBlank()) return false
        if (name.startsWith("/") || name.startsWith("\\")) return false
        if (name.contains('\\')) return false
        return name.split('/').none { it == ".." || it.isBlank() }
    }

    fun sanitizeZipName(value: String): String {
        val clean = value
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9._-]+"), "_")
            .trim('_', '.', '-')
        return clean.ifBlank { "file" }
    }

    fun uniqueFileName(directory: File, preferredName: String): String {
        val cleanName = sanitizeFileName(preferredName)
        val base = cleanName.substringBeforeLast('.', cleanName)
        val extension = cleanName.substringAfterLast('.', missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
            ?.let { ".$it" }
            .orEmpty()
        var candidate = cleanName
        var index = 2
        while (File(directory, candidate).exists()) {
            candidate = "${base}_$index$extension"
            index += 1
        }
        return candidate
    }

    private fun sanitizeFileName(value: String): String {
        val clean = value
            .replace('\\', '_')
            .replace('/', '_')
            .replace(Regex("[\\r\\n\\t]+"), "_")
            .trim()
        return clean.ifBlank { "image.png" }
    }

    fun fileOrDirectorySize(file: File): Long {
        if (file.isFile) return file.length()
        if (!file.isDirectory) return 0L
        return file.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }
}

private class MediaCollector {
    private val entriesByCanonicalPath = linkedMapOf<String, MediaWork>()

    fun register(
        originalPath: String?,
        kind: NativeBackupMediaKind,
        owner: String
    ): MediaWork? {
        val file = localFile(originalPath) ?: return null
        val canonicalPath = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        entriesByCanonicalPath[canonicalPath]?.let { return it }

        val extension = file.extension.ifBlank { kind.defaultExtension }
        val key = "${kind.keyPrefix}_${shortHash(canonicalPath)}"
        val zipName = NativeChatNotesBackupSupport.sanitizeZipName(
            "${owner}_${file.nameWithoutExtension}"
        )
        val metadataFile = sidecarMetadataFile(file).takeIf {
            it.isFile && kind.hasMetadataSidecar
        }
        val entry = MediaBackupEntry(
            key = key,
            kind = kind.name,
            zipPath = "media/${kind.directory}/$zipName.$extension",
            originalPath = originalPath,
            sizeBytes = file.length(),
            metadataZipPath = metadataFile?.let { "media/${kind.directory}/$zipName.$extension.json" }
        )
        return MediaWork(entry = entry, file = file, metadataFile = metadataFile).also {
            entriesByCanonicalPath[canonicalPath] = it
        }
    }

    fun registerSourceMedia(sourceFile: String?, owner: String): MediaWork? {
        val file = localFile(sourceFile) ?: return null
        val extension = file.extension.lowercase(Locale.US)
        val kind = when (extension) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif" -> NativeBackupMediaKind.NOTE_IMAGE
            "wav", "mp3", "m4a", "aac", "ogg", "flac", "opus" -> NativeBackupMediaKind.NOTE_AUDIO
            else -> return null
        }
        return register(sourceFile, kind, owner)
    }

    fun entries(): List<MediaWork> = entriesByCanonicalPath.values.toList()

    private fun localFile(path: String?): File? {
        val cleanPath = path?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (cleanPath.startsWith("http://", ignoreCase = true)) return null
        if (cleanPath.startsWith("https://", ignoreCase = true)) return null
        if (cleanPath.startsWith("content://", ignoreCase = true)) return null
        val filePath = cleanPath.removePrefix("file://")
        val file = File(filePath)
        return file.takeIf { it.isFile }
    }

    private fun sidecarMetadataFile(file: File): File = File(
        file.parentFile ?: file.absoluteFile.parentFile,
        "${file.name}.json"
    )

    private fun shortHash(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }
}

private data class MediaWork(
    val entry: MediaBackupEntry,
    val file: File,
    val metadataFile: File? = null
)

private class ModelFileCollector {
    private val groupsByCanonicalPath = linkedMapOf<String, ModelFileGroupWork>()

    fun register(originalPath: String?, owner: String): ModelFileGroupWork? {
        val root = localFileOrDirectory(originalPath) ?: return null
        val canonicalPath = runCatching { root.canonicalPath }.getOrElse { root.absolutePath }
        groupsByCanonicalPath[canonicalPath]?.let { return it }

        val key = "model_${shortHash(canonicalPath)}"
        val zipBase = "models/${key}_${NativeChatNotesBackupSupport.sanitizeZipName(owner)}"
        val pathKind = if (root.isDirectory) {
            MODEL_PATH_KIND_DIRECTORY
        } else {
            MODEL_PATH_KIND_FILE
        }
        val files = if (root.isDirectory) {
            root.walkTopDown()
                .filter { it.isFile }
                .mapNotNull { file ->
                    val relativePath = runCatching {
                        root.toPath().relativize(file.toPath()).toString()
                            .replace(File.separatorChar, '/')
                    }.getOrNull()
                        ?.takeIf { NativeChatNotesBackupSupport.isSafeZipEntryName("models/$it") }
                        ?: return@mapNotNull null
                    ModelFileWork(
                        entry = ModelFileBackupEntry(
                            modelKey = key,
                            relativePath = relativePath,
                            zipPath = "$zipBase/$relativePath",
                            sizeBytes = file.length()
                        ),
                        file = file
                    )
                }
                .toList()
        } else {
            val relativePath = root.name.takeIf {
                NativeChatNotesBackupSupport.isSafeZipEntryName("models/$it")
            } ?: return null
            listOf(
                ModelFileWork(
                    entry = ModelFileBackupEntry(
                        modelKey = key,
                        relativePath = relativePath,
                        zipPath = "$zipBase/$relativePath",
                        sizeBytes = root.length()
                    ),
                    file = root
                )
            )
        }
        if (files.isEmpty()) return null

        return ModelFileGroupWork(
            key = key,
            pathKind = pathKind,
            files = files
        ).also {
            groupsByCanonicalPath[canonicalPath] = it
        }
    }

    fun fileEntries(): List<ModelFileBackupEntry> =
        groupsByCanonicalPath.values.flatMap { it.files.map { work -> work.entry } }

    fun fileWorks(): List<ModelFileWork> =
        groupsByCanonicalPath.values.flatMap { it.files }

    private fun localFileOrDirectory(path: String?): File? {
        val cleanPath = path?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (cleanPath.startsWith("http://", ignoreCase = true)) return null
        if (cleanPath.startsWith("https://", ignoreCase = true)) return null
        if (cleanPath.startsWith("content://", ignoreCase = true)) return null
        val filePath = cleanPath.removePrefix("file://")
        val file = File(filePath)
        return file.takeIf { it.isFile || it.isDirectory }
    }

    private fun shortHash(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }
}

private data class ModelFileGroupWork(
    val key: String,
    val pathKind: String,
    val files: List<ModelFileWork>
)

private data class ModelFileWork(
    val entry: ModelFileBackupEntry,
    val file: File
)

private enum class NativeBackupMediaKind(
    val directory: String,
    val keyPrefix: String,
    val defaultExtension: String,
    val hasMetadataSidecar: Boolean
) {
    CHAT_IMAGE("chat_images", "chat_image", "jpg", true),
    CHAT_AUDIO("chat_audio", "chat_audio", "m4a", false),
    NOTE_IMAGE("note_images", "note_image", "png", true),
    NOTE_AUDIO("note_audio", "note_audio", "m4a", false),
    ONNX_GALLERY_IMAGE("onnx_gallery", "onnx_image", "png", true)
}

data class NativeChatNotesBackupExportResult(
    val servers: Int,
    val folders: Int,
    val promptProfiles: Int,
    val chats: Int,
    val messages: Int,
    val notes: Int,
    val organizerEvents: Int = 0,
    val organizerAlarms: Int = 0,
    val scheduledTasks: Int = 0,
    val scheduledTaskLogs: Int = 0,
    val mediaFiles: Int,
    val models: Int = 0
)

data class NativeChatNotesBackupImportResult(
    val servers: Int,
    val folders: Int,
    val promptProfiles: Int,
    val chats: Int,
    val messages: Int,
    val notes: Int,
    val organizerEvents: Int = 0,
    val organizerAlarms: Int = 0,
    val scheduledTasks: Int = 0,
    val scheduledTaskLogs: Int = 0,
    val mediaFiles: Int,
    val models: Int = 0
)

@Keep
data class NativeChatNotesBackupManifest(
    val schemaVersion: Int = 1,
    val app: String = "AI-Doomsday-Toolbox",
    val exportedAt: Long = System.currentTimeMillis(),
    val servers: List<LlamaServerBackup> = emptyList(),
    val folders: List<LlamaChatFolderBackup> = emptyList(),
    val promptProfiles: List<LlamaChatPromptProfileBackup> = emptyList(),
    val chats: List<LlamaChatBackup> = emptyList(),
    val messages: List<LlamaMessageBackup> = emptyList(),
    val notes: List<NoteBackup> = emptyList(),
    val organizerSettings: OrganizerSettingsBackup? = null,
    val organizerEvents: List<OrganizerEventBackup>? = emptyList(),
    val organizerAlarms: List<OrganizerAlarmBackup>? = emptyList(),
    val scheduledTasks: List<LlamaScheduledTaskBackup>? = emptyList(),
    val scheduledTaskLogs: List<LlamaScheduledTaskLogBackup>? = emptyList(),
    val onnxGalleryImages: List<OnnxGalleryImageBackup>? = emptyList(),
    val tamaBackup: TamaBackupEntry? = null,
    val models: List<ModelBackup> = emptyList(),
    val modelFiles: List<ModelFileBackupEntry> = emptyList(),
    val media: List<MediaBackupEntry> = emptyList()
)

@Keep
data class LlamaServerBackup(
    val oldId: Long = 0,
    val name: String = "",
    val host: String = "",
    val port: Int = 0,
    val engine: String = LlamaServerEntity.ENGINE_LLAMA_SERVER,
    val supportsVision: Boolean = false,
    val supportsAudio: Boolean = false,
    val modelName: String? = null,
    val whisperModelPath: String? = null,
    val whisperLanguage: String = LlamaServerEntity.DEFAULT_WHISPER_LANGUAGE,
    val preferWhisperAudioTranscription: Boolean = false,
    val defaultApiParams: String? = null,
    val lastUsed: Long = 0
)

@Keep
data class LlamaChatFolderBackup(
    val oldId: Long = 0,
    val name: String = "",
    val createdAt: Long = 0
)

@Keep
data class LlamaChatPromptProfileBackup(
    val oldId: Long = 0,
    val name: String = "",
    val content: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0
)

@Keep
data class LlamaChatBackup(
    val oldId: Long = 0,
    val title: String = "",
    val lastModified: Long = 0,
    val contextSize: Int = 8192,
    val systemPrompt: String? = null,
    val apiParams: String? = null,
    val folderOldId: Long? = null
)

@Keep
data class LlamaMessageBackup(
    val oldId: Long = 0,
    val chatOldId: Long = 0,
    val role: String = "",
    val content: String = "",
    val imagePath: String? = null,
    val imageMediaKey: String? = null,
    val audioPath: String? = null,
    val audioMediaKey: String? = null,
    val timestamp: Long = 0,
    val isError: Boolean = false,
    val isTruncated: Boolean = false,
    val thinking: String? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val tps: Double = 0.0,
    val generationTimeMs: Long = 0
)

@Keep
data class NoteBackup(
    val oldId: Int = 0,
    val title: String = "",
    val content: String = "",
    val type: String = NoteType.MANUAL.name,
    val sourceFile: String? = null,
    val sourceFileMediaKey: String? = null,
    val language: String? = null,
    val audioPath: String? = null,
    val audioMediaKey: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val isLlmWhitelisted: Boolean = false,
    val imageRefs: List<NoteImageBackup> = emptyList()
)

@Keep
data class NoteImageBackup(
    val target: String = "",
    val mediaKey: String = ""
)

@Keep
data class OrganizerSettingsBackup(
    val calendarToolsAllowed: Boolean = false,
    val alarmToolsAllowed: Boolean = false,
    val updatedAt: Long = 0
)

@Keep
data class OrganizerEventBackup(
    val oldId: Long = 0,
    val title: String = "",
    val description: String = "",
    val location: String = "",
    val startAtMillis: Long = 0,
    val endAtMillis: Long? = null,
    val allDay: Boolean = false,
    val timezoneId: String = java.time.ZoneId.systemDefault().id,
    val colorArgb: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0
)

@Keep
data class OrganizerAlarmBackup(
    val oldId: Long = 0,
    val eventOldId: Long? = null,
    val title: String = "",
    val message: String = "",
    val triggerAtMillis: Long = 0,
    val timezoneId: String = java.time.ZoneId.systemDefault().id,
    val soundEnabled: Boolean = true,
    val enabled: Boolean = true,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val deliveredAt: Long? = null
)

@Keep
data class LlamaScheduledTaskBackup(
    val oldId: Long = 0,
    val name: String = "",
    val enabled: Boolean = true,
    val serverOldId: Long? = null,
    val contextSize: Int = 8192,
    val systemPrompt: String? = null,
    val taskPrompt: String = "",
    val apiParams: String? = null,
    val scheduleType: String = "ONE_TIME",
    val oneTimeAtMillis: Long? = null,
    val timeOfDayMinutes: Int = 7 * 60,
    val weekdaysMask: Int = 0,
    val dayOfMonth: Int = 1,
    val timezoneId: String = java.time.ZoneId.systemDefault().id,
    val nextRunAtMillis: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val lastRunAtMillis: Long? = null
)

@Keep
data class LlamaScheduledTaskLogBackup(
    val oldId: Long = 0,
    val taskOldId: Long? = null,
    val taskName: String = "",
    val scheduledAtMillis: Long = 0,
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
    val durationMs: Long? = null,
    val status: String = "QUEUED",
    val serverOldId: Long? = null,
    val serverName: String? = null,
    val serverBaseUrl: String? = null,
    val finalOutput: String = "",
    val error: String? = null,
    val toolActivity: String = "",
    val createdAt: Long = 0
)

@Keep
data class OnnxGalleryImageBackup(
    val originalPath: String = "",
    val fileName: String = "",
    val imageMediaKey: String = ""
)

@Keep
data class TamaBackupEntry(
    val zipPath: String = "",
    val sizeBytes: Long = 0
)

@Keep
data class ModelBackup(
    val filename: String = "",
    val path: String = "",
    val sizeBytes: Long = 0,
    val type: String = ModelType.LLM.name,
    val repoId: String = "",
    val isDownloaded: Boolean = false,
    val isVision: Boolean = false,
    val mmprojPath: String? = null,
    val mmprojFileGroupKey: String? = null,
    val mmprojPathKind: String? = null,
    val sdCapabilities: String? = null,
    val sdFamily: String? = null,
    val sdVariant: String? = null,
    val sdCompatProfiles: String? = null,
    val onnxCapabilities: String? = null,
    val onnxAssetKind: String? = null,
    val onnxPipelineFamily: String? = null,
    val onnxReferenceUri: String? = null,
    val onnxReferencePath: String? = null,
    val layerCount: Int = 0,
    val modelFileGroupKey: String? = null,
    val modelPathKind: String = "file"
)

@Keep
data class ModelFileBackupEntry(
    val modelKey: String = "",
    val relativePath: String = "",
    val zipPath: String = "",
    val sizeBytes: Long = 0
)

@Keep
data class MediaBackupEntry(
    val key: String = "",
    val kind: String = "",
    val zipPath: String = "",
    val originalPath: String? = null,
    val sizeBytes: Long = 0,
    val metadataZipPath: String? = null
)

private data class TamaBackupWork(
    val entry: TamaBackupEntry,
    val bytes: ByteArray
)
