package com.example.llamadroid.harness

import android.content.Context
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.withTransaction
import com.example.llamadroid.data.db.AgentConversationEntity
import com.example.llamadroid.data.db.AgentMessageEntity
import com.example.llamadroid.data.db.AgentMessagePartEntity
import com.example.llamadroid.data.db.AgentRuntimeSource
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.HarnessRuntimeIds
import com.example.llamadroid.data.db.HarnessSessionEntity
import com.example.llamadroid.data.db.HarnessTransferImportReceiptEntity
import com.example.llamadroid.data.db.HarnessWorkspaceEntity
import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Room-owned metadata carried alongside a Harness filesystem transfer.
 *
 * The filesystem archive remains the source of session logs and project files.
 * This document carries only the selected Room identity rows and legacy chat
 * history needed to reconnect those files after import. Runtime process-owner
 * state and preferences are intentionally excluded; the installation manager
 * recreates those for the destination runtime.
 */
object HarnessTransferMetadata {
    private const val CURRENT_SCHEMA = 1
    private const val ACTIVE_RUNTIME_STATE_ERROR = "HARNESS_TRANSFER_RUNTIME_ACTIVE"
    private const val ACTIVE_WORK_ERROR = "HARNESS_TRANSFER_WORK_ACTIVE"
    private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")
    private val ACTIVE_PART_STATUSES = setOf("PENDING", "RUNNING")
    private val gson = Gson()

    /** Gson document embedded in the app-owned transfer metadata entry. */
    data class Document(
        val schemaVersion: Int = CURRENT_SCHEMA,
        val transferId: String = UUID.randomUUID().toString(),
        val sourceRuntimeId: String = HarnessRuntimeIds.LEGACY,
        val createdAtEpochMs: Long = System.currentTimeMillis(),
        val configurationIncluded: Boolean = false,
        val workspaces: List<HarnessWorkspaceEntity> = emptyList(),
        val sessions: List<HarnessSessionEntity> = emptyList(),
        val conversations: List<AgentConversationEntity> = emptyList(),
        val messages: List<AgentMessageEntity> = emptyList(),
        /** Structured workflow parts/chunks for the selected legacy conversations. */
        val parts: List<AgentMessagePartEntity> = emptyList(),
        /** Existing attachment files referenced by selected message imagePath values. */
        val attachments: List<AttachmentDescriptor> = emptyList(),
        /** Optional destination ids allocated by an export coordinator before apply. */
        val destinationConversationIds: Map<String, Long> = emptyMap(),
    ) {
        init {
            require(schemaVersion == CURRENT_SCHEMA) { "HARNESS_TRANSFER_METADATA_UNSUPPORTED" }
            require(transferId.isNotBlank() && transferId.length <= 128 && '\u0000' !in transferId) {
                "HARNESS_TRANSFER_ID_INVALID"
            }
            require(sourceRuntimeId.matches(ID_PATTERN)) { "HARNESS_TRANSFER_RUNTIME_INVALID" }
            require(createdAtEpochMs >= 0L) { "HARNESS_TRANSFER_TIMESTAMP_INVALID" }
            require(workspaces.all { it.runtimeId == sourceRuntimeId }) {
                "HARNESS_TRANSFER_WORKSPACE_RUNTIME_MISMATCH"
            }
            require(sessions.all { it.runtimeId == sourceRuntimeId }) {
                "HARNESS_TRANSFER_SESSION_RUNTIME_MISMATCH"
            }
            require(conversations.all { it.runtimeId == sourceRuntimeId }) {
                "HARNESS_TRANSFER_CONVERSATION_RUNTIME_MISMATCH"
            }
            require(destinationConversationIds.keys.all { key -> conversations.any { it.id.toString() == key } }) {
                "HARNESS_TRANSFER_CONVERSATION_MAPPING_INVALID"
            }
            require(destinationConversationIds.values.all { it > 0L } &&
                destinationConversationIds.values.distinct().size == destinationConversationIds.size) {
                "HARNESS_TRANSFER_CONVERSATION_MAPPING_INVALID"
            }
            require(workspaces.map { it.id }.distinct().size == workspaces.size) {
                "HARNESS_TRANSFER_WORKSPACE_DUPLICATE"
            }
            require(sessions.map { it.harnessSessionId }.distinct().size == sessions.size) {
                "HARNESS_TRANSFER_SESSION_DUPLICATE"
            }
            require(conversations.map { it.id }.distinct().size == conversations.size) {
                "HARNESS_TRANSFER_CONVERSATION_DUPLICATE"
            }
            require(messages.map { it.id }.distinct().size == messages.size) {
                "HARNESS_TRANSFER_MESSAGE_DUPLICATE"
            }
            require(messages.map { it.originalId }.distinct().size == messages.size) {
                "HARNESS_TRANSFER_MESSAGE_ORIGINAL_ID_DUPLICATE"
            }
            require(parts.map { it.id }.distinct().size == parts.size) {
                "HARNESS_TRANSFER_MESSAGE_PART_DUPLICATE"
            }
            require(parts.all { part -> conversations.any { it.id == part.conversationId } }) {
                "HARNESS_TRANSFER_MESSAGE_PART_CONVERSATION_MISSING"
            }
            val messageOriginalIds = messages.map { it.originalId }.toSet()
            require(parts.all { it.messageOriginalId in messageOriginalIds }) {
                "HARNESS_TRANSFER_MESSAGE_PART_MESSAGE_MISSING"
            }
            require(attachments.map { it.key }.distinct().size == attachments.size) {
                "HARNESS_TRANSFER_ATTACHMENT_DUPLICATE"
            }
            require(attachments.all { attachment ->
                attachment.key.isNotBlank() && attachment.sourcePath.isNotBlank() &&
                    attachment.archivePath.isNotBlank() && attachment.sizeBytes >= 0L &&
                    attachment.lastModifiedEpochMs >= 0L &&
                    '\u0000' !in attachment.key && '\u0000' !in attachment.sourcePath &&
                    '\u0000' !in attachment.archivePath &&
                    (attachment.partId == null || '\u0000' !in attachment.partId)
            }) {
                "HARNESS_TRANSFER_ATTACHMENT_METADATA_INVALID"
            }
            require(attachments.all { attachment ->
                conversations.any { it.id == attachment.conversationId } &&
                    if (attachment.messageOriginalId.isNotBlank()) {
                        messages.any {
                            it.conversationId == attachment.conversationId &&
                                it.originalId == attachment.messageOriginalId &&
                                it.imagePath == attachment.sourcePath
                        }
                    } else {
                        parts.any {
                            it.id == attachment.partId &&
                                it.conversationId == attachment.conversationId &&
                                it.contentRef == attachment.sourcePath
                        }
                    }
            }) {
                "HARNESS_TRANSFER_ATTACHMENT_LINK_INVALID"
            }
            require(messages.all { message -> conversations.any { it.id == message.conversationId } }) {
                "HARNESS_TRANSFER_MESSAGE_CONVERSATION_MISSING"
            }
            require(sessions.all { session -> conversations.any { it.id == session.conversationId } }) {
                "HARNESS_TRANSFER_SESSION_CONVERSATION_MISSING"
            }
            require(sessions.all { session -> workspaces.any { it.id == session.workspaceId } }) {
                "HARNESS_TRANSFER_SESSION_WORKSPACE_MISSING"
            }
        }

        fun toJson(): String = gson.toJson(this)

        companion object {
            fun fromJson(json: String): Document {
                val parsed = runCatching { gson.fromJson(json, Document::class.java) }
                    .getOrElse { throw IllegalArgumentException("HARNESS_TRANSFER_METADATA_INVALID", it) }
                    ?: throw IllegalArgumentException("HARNESS_TRANSFER_METADATA_INVALID")
                return runCatching {
                    requireNotNull(parsed.workspaces)
                    requireNotNull(parsed.sessions)
                    requireNotNull(parsed.conversations)
                    requireNotNull(parsed.messages)
                    // Gson bypasses Kotlin constructors and leaves newly added
                    // list fields null when reading an older schema-1 document.
                    // Normalize those fields before invoking Document.init.
                    // Keep the nullable boundary explicit: Gson can bypass
                    // Kotlin constructors and reflect a null into these
                    // non-null declarations when older JSON omits the fields.
                    // Re-copy every decoded row so required constructor
                    // parameters are checked before filesystem publication.
                    val normalizedWorkspaces = parsed.workspaces.map { it.copy() }
                    val normalizedSessions = parsed.sessions.map { it.copy() }
                    val normalizedConversations = parsed.conversations.map { it.copy() }
                    val normalizedMessages = parsed.messages.map { it.copy() }
                    val normalizedParts = gsonFallback(parsed.parts, emptyList<AgentMessagePartEntity>())
                        .map { it.copy() }
                    val normalizedAttachments = gsonFallback(parsed.attachments, emptyList<AttachmentDescriptor>())
                        .map { it.copy() }
                    requireNotNull(parsed.destinationConversationIds)
                    parsed.copy(
                        workspaces = normalizedWorkspaces,
                        sessions = normalizedSessions,
                        conversations = normalizedConversations,
                        messages = normalizedMessages,
                        parts = normalizedParts,
                        attachments = normalizedAttachments,
                    )
                }.getOrElse { throw IllegalArgumentException("HARNESS_TRANSFER_METADATA_INVALID", it) }
            }

            private fun <T> gsonFallback(value: T?, fallback: T): T = value ?: fallback
        }
    }

    /** A file manifest entry; the coordinator owns copying bytes to [targetPath]. */
    data class AttachmentDescriptor(
        val key: String,
        val conversationId: Long,
        val messageOriginalId: String,
        val sourcePath: String,
        val archivePath: String,
        val sizeBytes: Long = 0L,
        val lastModifiedEpochMs: Long = 0L,
        val partId: String? = null,
    ) {
        init {
            require(key.isNotBlank() && key.length <= 160 && '\u0000' !in key) {
                "HARNESS_TRANSFER_ATTACHMENT_KEY_INVALID"
            }
            require(conversationId > 0L &&
                (messageOriginalId.isNotBlank() || !partId.isNullOrBlank())) {
                "HARNESS_TRANSFER_ATTACHMENT_LINK_INVALID"
            }
            require(sourcePath.isNotBlank() && archivePath.isNotBlank()) {
                "HARNESS_TRANSFER_ATTACHMENT_PATH_INVALID"
            }
            require('\u0000' !in sourcePath && '\u0000' !in archivePath &&
                (partId == null || '\u0000' !in partId)) {
                "HARNESS_TRANSFER_ATTACHMENT_PATH_INVALID"
            }
            require(sizeBytes >= 0L && lastModifiedEpochMs >= 0L) {
                "HARNESS_TRANSFER_ATTACHMENT_METADATA_INVALID"
            }
        }
    }

    data class ImportResult(
        val transferId: String,
        val targetRuntimeId: String,
        val workspaceIdMap: Map<String, String>,
        val harnessWorkspaceIdMap: Map<String, String>,
        val sessionIdMap: Map<String, String>,
        val conversationIdMap: Map<Long, Long>,
        val imported: Boolean,
    )

    data class PreparedMappings(
        val operationId: String,
        val workspaceIdMap: Map<String, String>,
        val harnessWorkspaceIdMap: Map<String, String>,
    )

    private data class ImportMarker(
        val transferId: String,
        val sourceRuntimeId: String,
        val targetRuntimeId: String,
        val workspaceIds: Map<String, String>,
        val harnessWorkspaceIds: Map<String, String>,
        val sessionIds: Map<String, String>,
        val conversationIds: Map<String, Long>,
    )

    /**
     * Capture selected workspaces/sessions and legacy history rooted in the
     * selected project folders. The runtime owner and all active job/run rows
     * must be stopped.
     */
    suspend fun snapshot(
        context: Context,
        runtimeId: String,
        workspaceIds: Set<String>? = null,
        sessionIds: Set<String>? = null,
        includeConfig: Boolean = false,
        legacyConversationIds: Set<Long>? = null,
    ): Document {
        requireValidRuntimeId(runtimeId)
        val database = AppDatabase.getDatabase(context)
        val dao = database.harnessTransferDao()
        ensureQuiescent(database, dao, runtimeId)

        val allWorkspaces = dao.workspaces(runtimeId)
        val allSessions = dao.sessions(runtimeId)
        val legacyIdsFromSessionSelection = sessionIds.orEmpty()
            .filter { it.startsWith("legacy:") }
            .mapNotNull { it.removePrefix("legacy:").toLongOrNull() }
            .toSet()
        val effectiveLegacyConversationIds = when {
            legacyConversationIds != null -> legacyConversationIds + legacyIdsFromSessionSelection
            legacyIdsFromSessionSelection.isNotEmpty() -> legacyIdsFromSessionSelection
            else -> null
        }
        val configOnly = includeConfig && workspaceIds?.isEmpty() == true &&
            sessionIds?.isEmpty() == true &&
            (legacyConversationIds == null || legacyConversationIds.isEmpty())
        val requestedWorkspaces = when {
            workspaceIds != null -> workspaceIds
            configOnly -> emptySet()
            sessionIds != null -> emptySet()
            else -> allWorkspaces.map { it.id }.toSet()
        }
        val requestedSessions = sessionIds
        require(requestedWorkspaces.all { id -> allWorkspaces.any { it.id == id } }) {
            "HARNESS_TRANSFER_WORKSPACE_NOT_FOUND"
        }
        val roomSessionIds = requestedSessions.orEmpty().filterNot { it.startsWith("legacy:") }.toSet()
        require(roomSessionIds.all { id -> allSessions.any { it.harnessSessionId == id } }) {
            "HARNESS_TRANSFER_SESSION_NOT_FOUND"
        }

        val selectedSessions = allSessions.filter { session ->
            when {
                requestedSessions != null -> session.harnessSessionId in roomSessionIds
                workspaceIds != null -> session.workspaceId in requestedWorkspaces
                configOnly -> false
                else -> true
            }
        }
        val selectedWorkspaceIds = requestedWorkspaces + selectedSessions.map { it.workspaceId }
        val selectedWorkspaces = allWorkspaces.filter { it.id in selectedWorkspaceIds }
        val selectedConversationIds = selectedSessions.map { it.conversationId }.toSet()
        val selectedLegacyFolders = selectedWorkspaces
            .map { it.backend to it.projectFolder }
            .toSet()
        val conversations = dao.conversations(runtimeId).filter { conversation ->
            conversation.id in selectedConversationIds ||
                conversation.id in effectiveLegacyConversationIds.orEmpty() ||
                (effectiveLegacyConversationIds == null && conversation.runtimeSource == AgentRuntimeSource.LEGACY_ARCHIVE &&
                    (conversation.workspaceBackend to conversation.projectFolder) in selectedLegacyFolders)
        }
        require(effectiveLegacyConversationIds.orEmpty().all { id -> conversations.any { it.id == id } }) {
            "HARNESS_TRANSFER_LEGACY_CONVERSATION_NOT_FOUND"
        }
        val conversationIds = conversations.map { it.id }.toSet()
        val messages = dao.messagesForConversations(conversationIds.toList())
        val parts = dao.messagePartsForConversations(conversationIds.toList())
        val messageAttachments = messages.mapNotNull { message ->
            val path = message.imagePath?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val file = File(path)
            if (!file.isFile) return@mapNotNull null
            AttachmentDescriptor(
                key = attachmentKey(message.conversationId, message.originalId, path),
                conversationId = message.conversationId,
                messageOriginalId = message.originalId,
                sourcePath = path,
                archivePath = attachmentArchivePath(runtimeId, path),
                sizeBytes = file.length().coerceAtLeast(0L),
                lastModifiedEpochMs = file.lastModified().coerceAtLeast(0L),
            )
        }
        val attachmentPaths = messageAttachments.mapTo(mutableSetOf()) { it.sourcePath }
        val partAttachments = parts.mapNotNull { part ->
            val path = part.contentRef?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (path in attachmentPaths) return@mapNotNull null
            val file = File(path)
            if (!file.isFile) return@mapNotNull null
            attachmentPaths += path
            AttachmentDescriptor(
                key = attachmentKey(part.conversationId, part.id, path),
                conversationId = part.conversationId,
                messageOriginalId = "",
                sourcePath = path,
                archivePath = attachmentArchivePath(runtimeId, path),
                sizeBytes = file.length().coerceAtLeast(0L),
                lastModifiedEpochMs = file.lastModified().coerceAtLeast(0L),
                partId = part.id,
            )
        }
        return Document(
            sourceRuntimeId = runtimeId,
            configurationIncluded = includeConfig,
            workspaces = selectedWorkspaces,
            sessions = selectedSessions,
            conversations = conversations,
            messages = messages,
            parts = parts,
            attachments = messageAttachments + partAttachments,
        )
    }

    /**
     * Allocate deterministic workspace and native Harness group ids before the
     * archive staging step. The coordinator can use these values to rewrite
     * filesystem paths and then pass the same maps to [apply].
     */
    suspend fun prepareMappings(
        context: Context,
        targetId: String,
        document: Document,
        operationId: String = document.transferId,
    ): PreparedMappings {
        requireValidRuntimeId(targetId)
        require(operationId.isNotBlank() && operationId.length <= 128 && '\u0000' !in operationId) {
            "HARNESS_TRANSFER_ID_INVALID"
        }
        val database = AppDatabase.getDatabase(context)
        val dao = database.harnessTransferDao()
        ensureQuiescent(database, dao, targetId)
        val receipt = dao.receipt(operationId)
        if (receipt != null) {
            require(receipt.sourceRuntimeId == document.sourceRuntimeId && receipt.targetRuntimeId == targetId) {
                "HARNESS_TRANSFER_RECEIPT_MISMATCH"
            }
            return decodeReceipt(receipt).also { it.requireMatches(document) }
                .toPreparedMappings(operationId)
        }
        val workspaceIds = deterministicWorkspaceMappings(dao, document, operationId)
        val harnessIds = deterministicHarnessWorkspaceMappings(dao, document, operationId, targetId)
        return PreparedMappings(operationId, workspaceIds, harnessIds)
    }

    /** Serialize a typed document for [com.example.llamadroid.harness.transfer.TransferMetadata]. */
    fun encode(document: Document): String = document.toJson()

    /** Parse and validate a typed Gson document. */
    fun decode(json: String): Document = Document.fromJson(json)

    /**
     * Apply metadata into a stopped destination runtime. All Room rows are
     * inserted in one transaction. The operation receipt stores all mappings,
     * so a retry returns the original mappings without duplicating copied rows.
     */
    suspend fun apply(
        context: Context,
        targetId: String,
        metadata: Document,
        sessionIdMap: Map<String, String> = emptyMap(),
        cwdMap: Map<String, String> = emptyMap(),
        newRuntime: Boolean = true,
        workspaceIdMap: Map<String, String> = emptyMap(),
        harnessWorkspaceIdMap: Map<String, String> = emptyMap(),
        attachmentPathMap: Map<String, String> = emptyMap(),
    ): ImportResult {
        requireValidRuntimeId(targetId)
        require(metadata.sourceRuntimeId.matches(ID_PATTERN)) { "HARNESS_TRANSFER_RUNTIME_INVALID" }
        if (newRuntime) require(targetId != metadata.sourceRuntimeId) { "HARNESS_TRANSFER_TARGET_MUST_DIFFER" }
        val database = AppDatabase.getDatabase(context)
        val dao = database.harnessTransferDao()
        ensureQuiescent(database, dao, targetId)

        return database.withTransaction {
            val existingReceipt = dao.receipt(metadata.transferId)
            if (existingReceipt != null) {
                val marker = decodeReceipt(existingReceipt)
                require(marker.sourceRuntimeId == metadata.sourceRuntimeId && marker.targetRuntimeId == targetId) {
                    "HARNESS_TRANSFER_RECEIPT_MISMATCH"
                }
                marker.requireMatches(metadata)
                return@withTransaction ImportResult(
                    transferId = metadata.transferId,
                    targetRuntimeId = targetId,
                    workspaceIdMap = marker.workspaceIds,
                    harnessWorkspaceIdMap = marker.harnessWorkspaceIds,
                    sessionIdMap = marker.sessionIds,
                    conversationIdMap = marker.conversationIds.mapKeys { it.key.toLong() },
                    imported = false,
                )
            }

            val relevantSessionMappings = sessionIdMap.filterKeys { key ->
                metadata.sessions.any { it.harnessSessionId == key }
            }
            require(relevantSessionMappings.values.all { it.isNotBlank() && '\u0000' !in it }) {
                "HARNESS_TRANSFER_SESSION_MAPPING_INVALID"
            }
            require(relevantSessionMappings.values.distinct().size == relevantSessionMappings.size) {
                "HARNESS_TRANSFER_SESSION_MAPPING_DUPLICATE"
            }

            val resolvedWorkspaceIdMap = resolveWorkspaceMappings(
                dao = dao,
                metadata = metadata,
                supplied = workspaceIdMap,
            )
            val resolvedHarnessWorkspaceIdMap = resolveHarnessWorkspaceMappings(
                dao = dao,
                metadata = metadata,
                supplied = harnessWorkspaceIdMap,
                targetId = targetId,
            )
            val copiedWorkspaces = metadata.workspaces.map { source ->
                val mappedGuestPath = mappedGuestPath(source, resolvedHarnessWorkspaceIdMap, cwdMap)
                val mappedProjectFolder = mappedProjectFolder(source, mappedGuestPath)
                source.copy(
                    id = resolvedWorkspaceIdMap.getValue(source.id),
                    runtimeId = targetId,
                    projectFolder = mappedProjectFolder,
                    guestPath = mappedGuestPath,
                    harnessWorkspaceId = source.harnessWorkspaceId?.let {
                        resolvedHarnessWorkspaceIdMap.getValue(it)
                    },
                    // The destination rootfs owns imported workspace execution.
                    // Never retain an environment id belonging to the source or
                    // another installation when no explicit environment map is
                    // part of the transfer contract.
                    prootEnvironmentId = mapImportedEnvironmentId(source.prootEnvironmentId, targetId),
                )
            }
            if (copiedWorkspaces.isNotEmpty()) dao.insertWorkspaces(copiedWorkspaces)

            val conversationIdMap = allocateConversationIds(dao, metadata)
            val workspaceByConversation = metadata.sessions.associateBy({ it.conversationId }, { it.workspaceId })
            val sourceWorkspaceById = metadata.workspaces.associateBy { it.id }
            val copiedWorkspaceById = copiedWorkspaces.associateBy { it.id }
            val copiedConversations = metadata.conversations.map { source ->
                val sourceWorkspaceId = workspaceByConversation[source.id]
                val sourceWorkspace = sourceWorkspaceId?.let(sourceWorkspaceById::get)
                    ?: metadata.workspaces.firstOrNull {
                        it.backend == source.workspaceBackend && it.projectFolder == source.projectFolder
                    }
                val copiedWorkspace = sourceWorkspace?.let { copiedWorkspaceById[resolvedWorkspaceIdMap[it.id]] }
                source.copy(
                    id = conversationIdMap.getValue(source.id),
                    runtimeId = targetId,
                    projectFolder = copiedWorkspace?.projectFolder ?: source.projectFolder,
                    workspaceBackend = copiedWorkspace?.backend ?: source.workspaceBackend,
                    resumeState = "IDLE",
                    lastStopReason = "HARNESS_TRANSFER_IMPORTED",
                    lastTask = null,
                    // Project-folder rows are global and are not part of this
                    // document, so retaining their ids could attach history to
                    // an unrelated destination folder.
                    projectFolderId = null,
                    prootEnvironmentId = mapImportedEnvironmentId(source.prootEnvironmentId, targetId),
                )
            }
            if (copiedConversations.isNotEmpty()) dao.insertConversations(copiedConversations)

            val usedSessionIds = dao.sessionIds(targetId).toMutableSet()
            val resolvedSessionMap = metadata.sessions.associate { source ->
                val requested = relevantSessionMappings[source.harnessSessionId]
                val destination = requested ?: deterministicSessionId(metadata.transferId, source.harnessSessionId)
                val resolved = if (destination in usedSessionIds) {
                    require(requested == null) { "HARNESS_TRANSFER_SESSION_ID_COLLISION" }
                    allocateSessionId(usedSessionIds)
                } else destination
                usedSessionIds += resolved
                source.harnessSessionId to resolved
            }
            val copiedSessions = metadata.sessions.map { source ->
                source.copy(
                    harnessSessionId = resolvedSessionMap.getValue(source.harnessSessionId),
                    conversationId = conversationIdMap.getValue(source.conversationId),
                    workspaceId = resolvedWorkspaceIdMap.getValue(source.workspaceId),
                    runtimeId = targetId,
                )
            }
            if (copiedSessions.isNotEmpty()) dao.insertSessions(copiedSessions)

            val usedOriginalIds = dao.messageOriginalIds().toMutableSet()
            val originalIdMap = linkedMapOf<String, String>()
            val copiedMessages = metadata.messages.mapIndexed { index, source ->
                var originalId = importedOriginalId(metadata.transferId, source, index)
                while (!usedOriginalIds.add(originalId)) {
                    originalId = importedOriginalId(metadata.transferId, source, index, UUID.randomUUID().toString())
                }
                originalIdMap[source.originalId] = originalId
                source.copy(
                    id = 0L,
                    originalId = originalId,
                    conversationId = conversationIdMap.getValue(source.conversationId),
                    imagePath = mapAttachmentPath(
                        source.imagePath,
                        metadata,
                        attachmentPathMap,
                        clearUnmapped = true,
                    ),
                    isStreaming = false,
                    needsApproval = false,
                    isApproved = null,
                    isPlanApproved = null,
                    pendingToolCall = null,
                    isOutputExpanded = false,
                )
            }
            if (copiedMessages.isNotEmpty()) dao.insertMessages(copiedMessages)

            val usedPartIds = dao.messagePartIds().toMutableSet()
            val copiedParts = metadata.parts.mapIndexed { index, source ->
                var id = importedPartId(metadata.transferId, source, index)
                while (!usedPartIds.add(id)) {
                    id = importedPartId(metadata.transferId, source, index, UUID.randomUUID().toString())
                }
                source.copy(
                    id = id,
                    conversationId = conversationIdMap.getValue(source.conversationId),
                    messageOriginalId = originalIdMap.getValue(source.messageOriginalId),
                    status = if (source.status in ACTIVE_PART_STATUSES) "INTERRUPTED" else source.status,
                    contentRef = mapAttachmentPath(
                        source.contentRef,
                        metadata,
                        attachmentPathMap,
                        clearUnmapped = isFileAttachmentReference(source.contentRef),
                    ),
                )
            }
            if (copiedParts.isNotEmpty()) dao.insertMessageParts(copiedParts)

            val marker = ImportMarker(
                transferId = metadata.transferId,
                sourceRuntimeId = metadata.sourceRuntimeId,
                targetRuntimeId = targetId,
                workspaceIds = resolvedWorkspaceIdMap,
                harnessWorkspaceIds = resolvedHarnessWorkspaceIdMap,
                sessionIds = resolvedSessionMap,
                conversationIds = conversationIdMap.mapKeys { it.key.toString() },
            )
            val markerJson = gson.toJson(marker)
            dao.insertReceipt(HarnessTransferImportReceiptEntity(
                operationId = metadata.transferId,
                sourceRuntimeId = metadata.sourceRuntimeId,
                targetRuntimeId = targetId,
                mappingJson = markerJson,
                createdAt = System.currentTimeMillis(),
            ))
            ImportResult(
                transferId = metadata.transferId,
                targetRuntimeId = targetId,
                workspaceIdMap = resolvedWorkspaceIdMap,
                harnessWorkspaceIdMap = resolvedHarnessWorkspaceIdMap,
                sessionIdMap = resolvedSessionMap,
                conversationIdMap = conversationIdMap,
                imported = true,
            )
        }
    }

    private suspend fun ensureQuiescent(
        database: AppDatabase,
        dao: HarnessTransferDao,
        runtimeId: String,
    ) {
        val owner = database.harnessDao().runtime()
        require(owner == null || owner.state !in ACTIVE_STATES) {
            ACTIVE_RUNTIME_STATE_ERROR
        }
        require(dao.activeJobCount(runtimeId) == 0 && dao.activeRunCount(runtimeId) == 0) {
            ACTIVE_WORK_ERROR
        }
    }

    private suspend fun deterministicWorkspaceMappings(
        dao: HarnessTransferDao,
        metadata: Document,
        operationId: String,
    ): Map<String, String> {
        val occupied = dao.allWorkspaceIds().toMutableSet()
        return metadata.workspaces.associate { source ->
            val candidate = nextDeterministicId("workspace", operationId, source.id, occupied)
            occupied += candidate
            source.id to candidate
        }
    }

    private suspend fun deterministicHarnessWorkspaceMappings(
        dao: HarnessTransferDao,
        metadata: Document,
        operationId: String,
        targetId: String,
    ): Map<String, String> {
        val occupied = dao.harnessWorkspaceIds(targetId).toMutableSet()
        return metadata.workspaces.mapNotNull { it.harnessWorkspaceId }.distinct().associateWith { sourceId ->
            val candidate = nextDeterministicId("harness", operationId, sourceId, occupied)
            occupied += candidate
            candidate
        }
    }

    private suspend fun resolveWorkspaceMappings(
        dao: HarnessTransferDao,
        metadata: Document,
        supplied: Map<String, String>,
    ): Map<String, String> {
        val mappings = if (supplied.isEmpty()) {
            deterministicWorkspaceMappings(dao, metadata, metadata.transferId)
        } else supplied
        require(metadata.workspaces.all { mappings[it.id].orEmpty().isNotBlank() }) {
            "HARNESS_TRANSFER_WORKSPACE_MAPPING_INVALID"
        }
        val values = metadata.workspaces.map { mappings.getValue(it.id) }
        require(values.distinct().size == values.size && values.all { it.length <= 256 && '\u0000' !in it }) {
            "HARNESS_TRANSFER_WORKSPACE_MAPPING_INVALID"
        }
        val occupied = dao.allWorkspaceIds().toSet()
        require(values.none { it in occupied }) { "HARNESS_TRANSFER_WORKSPACE_ID_COLLISION" }
        return metadata.workspaces.associate { it.id to mappings.getValue(it.id) }
    }

    private suspend fun resolveHarnessWorkspaceMappings(
        dao: HarnessTransferDao,
        metadata: Document,
        supplied: Map<String, String>,
        targetId: String,
    ): Map<String, String> {
        val sourceIds = metadata.workspaces.mapNotNull { it.harnessWorkspaceId }.distinct()
        if (sourceIds.isEmpty()) return emptyMap()
        val mappings = if (supplied.isEmpty()) {
            deterministicHarnessWorkspaceMappings(dao, metadata, metadata.transferId, targetId)
        } else supplied
        require(sourceIds.all { mappings[it].orEmpty().isNotBlank() }) {
            "HARNESS_TRANSFER_HARNESS_WORKSPACE_MAPPING_INVALID"
        }
        val values = sourceIds.map { mappings.getValue(it) }
        require(values.distinct().size == values.size && values.all { it.length <= 256 && '\u0000' !in it }) {
            "HARNESS_TRANSFER_HARNESS_WORKSPACE_MAPPING_INVALID"
        }
        val occupied = dao.harnessWorkspaceIds(targetId).toSet()
        require(values.none { it in occupied }) { "HARNESS_TRANSFER_HARNESS_WORKSPACE_ID_COLLISION" }
        return sourceIds.associateWith { mappings.getValue(it) }
    }

    private fun mappedGuestPath(
        source: HarnessWorkspaceEntity,
        harnessWorkspaceIdMap: Map<String, String>,
        cwdMap: Map<String, String>,
    ): String {
        if (source.backend == "REMOTE_SSH") {
            val mappedGroup = source.harnessWorkspaceId?.let { harnessWorkspaceIdMap[it] }
            if (mappedGroup != null) return "/workspace/remote/$mappedGroup"
        }
        return cwdMap[source.guestPath] ?: cwdMap[source.projectFolder] ?: source.guestPath
    }

    private fun mappedProjectFolder(source: HarnessWorkspaceEntity, mappedGuestPath: String): String {
        if (source.backend == "REMOTE_SSH") return source.projectFolder
        val basename = mappedGuestPath.trimEnd('/').substringAfterLast('/').trim()
        return basename.takeIf { it.isNotBlank() && it != "." && it != ".." && '/' !in it }
            ?: source.projectFolder
    }

    private fun mapImportedEnvironmentId(sourceId: String?, targetId: String): String? =
        sourceId?.takeIf { it.isNotBlank() }?.let { _ -> targetId }

    private suspend fun allocateConversationIds(
        dao: HarnessTransferDao,
        metadata: Document,
    ): Map<Long, Long> {
        val occupied = dao.allConversationIds().toMutableSet()
        val maximum = (occupied.maxOrNull() ?: 0L).coerceAtLeast(0L)
        require(maximum < Long.MAX_VALUE) { "HARNESS_TRANSFER_CONVERSATION_ID_EXHAUSTED" }
        var next = maximum + 1L
        return metadata.conversations.associate { source ->
            val requested = metadata.destinationConversationIds[source.id.toString()]
            val destination = if (requested != null) {
                require(requested > 0L && occupied.add(requested)) { "HARNESS_TRANSFER_CONVERSATION_ID_COLLISION" }
                requested
            } else {
                while (next <= 0L || !occupied.add(next)) next++
                next++
                next - 1L
            }
            source.id to destination
        }
    }

    private fun decodeReceipt(receipt: HarnessTransferImportReceiptEntity): ImportMarker =
        runCatching {
            val marker = gson.fromJson(receipt.mappingJson, ImportMarker::class.java)
                ?: error("missing marker")
            require(marker.transferId == receipt.operationId)
            require(marker.sourceRuntimeId == receipt.sourceRuntimeId)
            require(marker.targetRuntimeId == receipt.targetRuntimeId)
            require(marker.sourceRuntimeId.matches(ID_PATTERN))
            require(marker.targetRuntimeId.matches(ID_PATTERN))
            fun Map<String, String>.isSafeMapping(): Boolean =
                keys.all { it.isNotBlank() && '\u0000' !in it } &&
                    values.all { it.isNotBlank() && '\u0000' !in it } &&
                    values.distinct().size == size
            require(marker.workspaceIds.isSafeMapping())
            require(marker.harnessWorkspaceIds.isSafeMapping())
            require(marker.sessionIds.isSafeMapping())
            require(marker.conversationIds.keys.all {
                it.toLongOrNull()?.let { id -> id > 0L && it == id.toString() } == true
            })
            require(marker.conversationIds.values.all { it > 0L })
            require(marker.conversationIds.values.distinct().size == marker.conversationIds.size)
            marker
        }.getOrElse { throw IllegalStateException("HARNESS_TRANSFER_RECEIPT_INVALID", it) }

    private fun ImportMarker.toPreparedMappings(operationId: String): PreparedMappings =
        PreparedMappings(operationId, workspaceIds, harnessWorkspaceIds)

    private fun ImportMarker.requireMatches(document: Document) {
        require(workspaceIds.keys == document.workspaces.map { it.id }.toSet()) {
            "HARNESS_TRANSFER_RECEIPT_INVALID"
        }
        require(harnessWorkspaceIds.keys == document.workspaces.mapNotNull { it.harnessWorkspaceId }.toSet()) {
            "HARNESS_TRANSFER_RECEIPT_INVALID"
        }
        require(sessionIds.keys == document.sessions.map { it.harnessSessionId }.toSet()) {
            "HARNESS_TRANSFER_RECEIPT_INVALID"
        }
        require(conversationIds.keys.mapNotNull { it.toLongOrNull() }.toSet() == document.conversations.map { it.id }.toSet()) {
            "HARNESS_TRANSFER_RECEIPT_INVALID"
        }
    }

    private fun requireValidRuntimeId(runtimeId: String) {
        require(runtimeId.matches(ID_PATTERN)) { "HARNESS_TRANSFER_RUNTIME_INVALID" }
    }

    private fun nextDeterministicId(
        kind: String,
        operationId: String,
        sourceId: String,
        occupied: Set<String>,
    ): String {
        var attempt = 0
        var candidate: String
        do {
            candidate = "transfer-" + digest("$kind:$operationId:$sourceId:$attempt").take(40)
            attempt++
        } while (candidate in occupied)
        return candidate
    }

    private fun deterministicSessionId(transferId: String, sourceId: String): String =
        "transfer-${digest("session:$transferId:$sourceId").take(40)}"

    private fun allocateSessionId(used: Set<String>): String {
        var candidate: String
        do candidate = UUID.randomUUID().toString() while (candidate in used)
        return candidate
    }

    private fun importedOriginalId(
        transferId: String,
        source: AgentMessageEntity,
        index: Int,
        salt: String = "",
    ): String = "transfer-${digest("message:$transferId:${source.id}:$index:$salt:${source.originalId}")}"

    private fun importedPartId(
        transferId: String,
        source: AgentMessagePartEntity,
        index: Int,
        salt: String = "",
    ): String = "transfer-part-${digest("part:$transferId:${source.id}:$index:$salt")}".take(96)

    private fun mapAttachmentPath(
        path: String?,
        metadata: Document,
        attachmentPathMap: Map<String, String>,
        clearUnmapped: Boolean = false,
    ): String? {
        val sourcePath = path?.takeIf { it.isNotBlank() } ?: return null
        val descriptors = metadata.attachments.filter { it.sourcePath == sourcePath }
        if (descriptors.isEmpty()) {
            return if (clearUnmapped) null else sourcePath
        }
        val destination = descriptors.asSequence()
            .flatMap { descriptor ->
                sequenceOf(
                    attachmentPathMap[sourcePath],
                    attachmentPathMap[descriptor.key],
                    attachmentPathMap[descriptor.archivePath],
                )
            }
            .firstOrNull { !it.isNullOrBlank() }
        // A source path is private to the source installation. If the archive
        // did not carry the bytes, clear it for every target instead of
        // leaving an absolute path that could point at unrelated media.
        return destination
    }

    private fun isFileAttachmentReference(path: String?): Boolean {
        val value = path?.trim().orEmpty()
        return value.startsWith('/') ||
            value.startsWith("file:", ignoreCase = true) ||
            value.startsWith("content:", ignoreCase = true)
    }

    private fun attachmentKey(
        conversationId: Long,
        messageOriginalId: String,
        sourcePath: String,
    ): String = "attachment-${digest("link:$conversationId:$messageOriginalId:$sourcePath").take(48)}"

    private fun attachmentArchivePath(sourceRuntimeId: String, sourcePath: String): String {
        val safeName = File(sourcePath).name
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(120)
            .ifBlank { "attachment" }
        return "attachments/${digest("file:$sourceRuntimeId:$sourcePath").take(40)}/$safeName"
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private val ACTIVE_STATES = setOf("STARTING", "RUNNING", "STOP_REQUESTED", "FORCE_STOPPING")
}

/** Room-only queries used by [HarnessTransferMetadata]. */
@Dao
interface HarnessTransferDao {
    @Query("SELECT * FROM agent_harness_workspaces WHERE runtimeId = :runtimeId ORDER BY id")
    suspend fun workspaces(runtimeId: String): List<HarnessWorkspaceEntity>

    @Query("SELECT * FROM agent_harness_sessions WHERE runtimeId = :runtimeId ORDER BY harnessSessionId")
    suspend fun sessions(runtimeId: String): List<HarnessSessionEntity>

    @Query("SELECT * FROM agent_conversations WHERE runtimeId = :runtimeId ORDER BY createdAt, id")
    suspend fun conversations(runtimeId: String): List<AgentConversationEntity>

    @Query("SELECT * FROM agent_messages WHERE conversationId IN (:conversationIds) ORDER BY conversationId, sequenceNumber, id")
    suspend fun messagesForConversations(conversationIds: List<Long>): List<AgentMessageEntity>

    @Query(
        "SELECT * FROM agent_message_parts WHERE conversationId IN (:conversationIds) " +
            "ORDER BY conversationId, messageOriginalId, position, id"
    )
    suspend fun messagePartsForConversations(conversationIds: List<Long>): List<AgentMessagePartEntity>

    @Query("SELECT id FROM agent_harness_workspaces")
    suspend fun allWorkspaceIds(): List<String>

    @Query("SELECT id FROM agent_conversations")
    suspend fun allConversationIds(): List<Long>

    @Query("SELECT harnessSessionId FROM agent_harness_sessions WHERE runtimeId = :runtimeId")
    suspend fun sessionIds(runtimeId: String): List<String>

    @Query("SELECT originalId FROM agent_messages")
    suspend fun messageOriginalIds(): List<String>

    @Query("SELECT id FROM agent_message_parts")
    suspend fun messagePartIds(): List<String>

    @Query("SELECT * FROM agent_harness_transfer_receipts WHERE operationId = :operationId LIMIT 1")
    suspend fun receipt(operationId: String): HarnessTransferImportReceiptEntity?

    @Query("SELECT harnessWorkspaceId FROM agent_harness_workspaces WHERE runtimeId = :runtimeId AND harnessWorkspaceId IS NOT NULL")
    suspend fun harnessWorkspaceIds(runtimeId: String): List<String>

    @Query(
        "SELECT COUNT(*) FROM ai_runtime_jobs j " +
            "JOIN agent_conversations c ON c.id = j.conversationId " +
            "WHERE c.runtimeId = :runtimeId " +
            "AND j.status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')"
    )
    suspend fun activeJobCount(runtimeId: String): Int

    @Query(
        "SELECT COUNT(*) FROM agent_proot_runs r " +
            "JOIN agent_conversations c ON c.id = r.conversationId " +
            "WHERE c.runtimeId = :runtimeId " +
            "AND r.status IN ('QUEUED', 'RUNNING', 'STOP_REQUESTED')"
    )
    suspend fun activeRunCount(runtimeId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWorkspaces(rows: List<HarnessWorkspaceEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConversations(rows: List<AgentConversationEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSessions(rows: List<HarnessSessionEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMessages(rows: List<AgentMessageEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMessageParts(rows: List<AgentMessagePartEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReceipt(receipt: HarnessTransferImportReceiptEntity)
}
