package com.example.llamadroid.data.model.library

import androidx.room.withTransaction
import com.example.llamadroid.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Room-backed implementation of the model deletion journal. */
class RoomModelDeletionJournal(
    private val database: AppDatabase
) : ModelDeletionJournal {
    private val dao = database.modelDeletionJournalDao()

    override suspend fun begin(preview: ModelDeletionPreview): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val operation = ModelDeletionJournalOperationEntity(
            operationId = preview.operationId,
            targetKey = preview.targetKey,
            targetKind = preview.targetKind,
            status = ModelDeletionJournalStatus.IN_PROGRESS.name,
            previewJson = preview.toJournalJson().toString(),
            resultJson = null,
            createdAt = now,
            updatedAt = now
        )
        val paths = preview.files
            .asSequence()
            .map { it.path }
            .filter(String::isNotBlank)
            .distinct()
            .map { path ->
                ModelDeletionJournalPathEntity(
                    operationId = preview.operationId,
                    path = path,
                    status = ModelDeletionJournalPathStatus.PENDING.name,
                    updatedAt = now
                )
            }
            .toList()
        database.withTransaction {
            dao.upsertOperation(operation)
            dao.deletePaths(preview.operationId)
            if (paths.isNotEmpty()) dao.upsertPaths(paths)
        }
        preview.operationId
    }

    override suspend fun recordPath(
        operationId: String,
        path: String,
        deleted: Boolean,
        failure: ModelDeletionPathFailure?
    ): Unit = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val status = when {
            deleted -> ModelDeletionJournalPathStatus.DELETED
            failure != null -> ModelDeletionJournalPathStatus.FAILED
            else -> ModelDeletionJournalPathStatus.PRESERVED
        }
        dao.upsertPath(
            ModelDeletionJournalPathEntity(
                operationId = operationId,
                path = path,
                status = status.name,
                failureCode = failure?.code?.name,
                failureMessage = failure?.message,
                updatedAt = now
            )
        )
        dao.touchOperation(operationId, now)
        Unit
    }

    override suspend fun complete(result: ModelDeletionResult) = withContext(Dispatchers.IO) {
        finish(result, ModelDeletionJournalStatus.COMPLETED)
    }

    override suspend fun fail(result: ModelDeletionResult) = withContext(Dispatchers.IO) {
        val status = if (result.status == ModelDeletionStatus.RECOVERABLE) {
            ModelDeletionJournalStatus.RECOVERABLE
        } else {
            ModelDeletionJournalStatus.FAILED
        }
        finish(result, status)
    }

    override suspend fun recoverableOperations(limit: Int): List<ModelDeletionJournalSnapshot> =
        withContext(Dispatchers.IO) {
            val operations = dao.getRecoverableOperations(limit.coerceIn(1, MAX_RECOVERY_BATCH))
            buildList {
                for (entity in operations) {
                    try {
                        add(snapshot(entity))
                    } catch (_: Throwable) {
                        // A malformed old JSON row remains queryable for diagnostics,
                        // while one bad row must not hide every other recovery candidate.
                    }
                }
            }
        }

    override suspend fun operation(operationId: String): ModelDeletionJournalSnapshot? =
        withContext(Dispatchers.IO) {
            val entity = dao.getOperation(operationId) ?: return@withContext null
            try {
                snapshot(entity)
            } catch (_: Throwable) {
                null
            }
        }

    private suspend fun finish(result: ModelDeletionResult, status: ModelDeletionJournalStatus) {
        dao.finishOperation(
            operationId = result.operationId,
            status = status.name,
            resultJson = result.toJournalJson().toString(),
            updatedAt = System.currentTimeMillis()
        )
    }

    private suspend fun snapshot(
        operation: ModelDeletionJournalOperationEntity
    ): ModelDeletionJournalSnapshot {
        val preview = runCatching {
            JSONObject(operation.previewJson).toDeletionPreview(operation.operationId)
        }.getOrElse {
            ModelDeletionPreview(
                operationId = operation.operationId,
                targetKey = operation.targetKey,
                targetLabel = operation.targetKey,
                targetKind = operation.targetKind,
                files = emptyList(),
                canDelete = false,
                blockingCode = ModelLibraryErrorCode.DELETION_RECOVERABLE
            )
        }
        val result = operation.resultJson?.let { encoded ->
            runCatching { JSONObject(encoded).toDeletionResult(operation.operationId) }.getOrNull()
        }
        val paths = dao.getPaths(operation.operationId).map { it.toModel() }
        val status = runCatching {
            ModelDeletionJournalStatus.valueOf(operation.status)
        }.getOrDefault(ModelDeletionJournalStatus.RECOVERABLE)
        return ModelDeletionJournalSnapshot(
            operationId = operation.operationId,
            status = status,
            preview = preview,
            result = result,
            paths = paths,
            createdAt = operation.createdAt,
            updatedAt = operation.updatedAt
        )
    }

    private fun ModelDeletionJournalPathEntity.toModel(): ModelDeletionJournalPath {
        val failure = failureCode?.let { code ->
            ModelDeletionPathFailure(
                path = path,
                code = runCatching { ModelLibraryErrorCode.valueOf(code) }
                    .getOrDefault(ModelLibraryErrorCode.DELETION_RECOVERABLE),
                message = failureMessage
            )
        }
        return ModelDeletionJournalPath(
            path = path,
            status = runCatching { ModelDeletionJournalPathStatus.valueOf(status) }
                .getOrDefault(ModelDeletionJournalPathStatus.PENDING),
            failure = failure,
            updatedAt = updatedAt
        )
    }

    companion object {
        private const val MAX_RECOVERY_BATCH = 100
    }
}

private fun ModelDeletionPreview.toJournalJson(): JSONObject = JSONObject().apply {
    put("operationId", operationId)
    put("targetKey", targetKey)
    put("targetLabel", targetLabel)
    put("targetKind", targetKind)
    put("canDelete", canDelete)
    putOpt("blockingCode", blockingCode?.name)
    put("files", JSONArray().also { array -> files.forEach { array.put(it.toJournalJson()) } })
    put("dependencies", JSONArray().also { array -> dependencies.forEach { array.put(it.toJournalJson()) } })
    put("protectedPaths", JSONArray().also { array -> protectedPaths.forEach { array.put(it) } })
}

private fun ModelDeletionFile.toJournalJson(): JSONObject = JSONObject().apply {
    put("path", path)
    put("sizeBytes", sizeBytes)
    put("kind", kind)
    put("protectedBy", JSONArray().also { array -> protectedBy.forEach { array.put(it) } })
}

private fun ModelDeletionDependency.toJournalJson(): JSONObject = JSONObject().apply {
    put("targetKey", targetKey)
    put("relation", relation)
    put("path", path)
}

private fun ModelDeletionResult.toJournalJson(): JSONObject = JSONObject().apply {
    put("operationId", operationId)
    put("targetKey", targetKey)
    put("targetKind", targetKind)
    put("status", status.name)
    put("deletedPaths", JSONArray().also { array -> deletedPaths.forEach { array.put(it) } })
    put("preservedPaths", JSONArray().also { array -> preservedPaths.forEach { array.put(it) } })
    put("reclaimedBytes", reclaimedBytes)
    putOpt("errorCode", errorCode?.name)
    putOpt("errorMessage", errorMessage)
    put(
        "failedPaths",
        JSONArray().also { array -> failedPaths.forEach { failure ->
            array.put(
                JSONObject().apply {
                    put("path", failure.path)
                    put("code", failure.code.name)
                    putOpt("message", failure.message)
                }
            )
        } }
    )
}

private fun JSONObject.toDeletionPreview(fallbackOperationId: String): ModelDeletionPreview {
    val files = optJSONArray("files").toListOfObjects().map { file ->
        ModelDeletionFile(
            path = file.optString("path", ""),
            sizeBytes = file.optLong("sizeBytes", 0L),
            kind = file.optString("kind", "unknown"),
            protectedBy = file.optJSONArray("protectedBy").toStringList()
        )
    }
    val dependencies = optJSONArray("dependencies").toListOfObjects().map { dependency ->
        ModelDeletionDependency(
            targetKey = dependency.optString("targetKey", ""),
            relation = dependency.optString("relation", ""),
            path = dependency.optString("path", "")
        )
    }
    return ModelDeletionPreview(
        operationId = optString("operationId", fallbackOperationId).ifBlank { fallbackOperationId },
        targetKey = optString("targetKey", "unknown"),
        targetLabel = optString("targetLabel", optString("targetKey", "unknown")),
        targetKind = optString("targetKind", "model"),
        files = files,
        dependencies = dependencies,
        protectedPaths = optJSONArray("protectedPaths").toStringList(),
        canDelete = optBoolean("canDelete", false),
        blockingCode = optString("blockingCode", "").toErrorCodeOrNull()
    )
}

private fun JSONObject.toDeletionResult(fallbackOperationId: String): ModelDeletionResult {
    val failures = optJSONArray("failedPaths").toListOfObjects().map { failure ->
        ModelDeletionPathFailure(
            path = failure.optString("path", ""),
            code = failure.optString("code", "").toErrorCodeOrNull()
                ?: ModelLibraryErrorCode.DELETION_RECOVERABLE,
            message = failure.optString("message", "").ifBlank { null }
        )
    }
    return ModelDeletionResult(
        operationId = optString("operationId", fallbackOperationId).ifBlank { fallbackOperationId },
        targetKey = optString("targetKey", "unknown"),
        targetKind = optString("targetKind", "model"),
        status = runCatching { ModelDeletionStatus.valueOf(optString("status", "RECOVERABLE")) }
            .getOrDefault(ModelDeletionStatus.RECOVERABLE),
        deletedPaths = optJSONArray("deletedPaths").toStringList(),
        preservedPaths = optJSONArray("preservedPaths").toStringList(),
        reclaimedBytes = optLong("reclaimedBytes", 0L),
        failedPaths = failures,
        errorCode = optString("errorCode", "").toErrorCodeOrNull(),
        errorMessage = optString("errorMessage", "").ifBlank { null }
    )
}

private fun String.toErrorCodeOrNull(): ModelLibraryErrorCode? =
    takeIf { it.isNotBlank() }?.let { raw -> runCatching { ModelLibraryErrorCode.valueOf(raw) }.getOrNull() }

private fun JSONArray?.toListOfObjects(): List<JSONObject> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.let(::add)
        }
    }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optString(index, "").takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}
