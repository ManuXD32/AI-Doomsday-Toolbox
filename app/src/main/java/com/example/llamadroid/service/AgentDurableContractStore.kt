package com.example.llamadroid.service

import android.content.Context
import androidx.room.withTransaction
import com.example.llamadroid.data.db.AgentContinuationOutboxEntity
import com.example.llamadroid.data.db.AgentContinuationStatus
import com.example.llamadroid.data.db.AgentDecisionEntity
import com.example.llamadroid.data.db.AgentInvocationEntity
import com.example.llamadroid.data.db.AgentMessageEntity
import com.example.llamadroid.data.db.AgentPendingInputEntity
import com.example.llamadroid.data.db.AgentPendingQuestionEntity
import com.example.llamadroid.data.db.AgentProjectContractEntity
import com.example.llamadroid.data.db.AgentWorkReportEntity
import com.example.llamadroid.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Result of the durable question answer boundary. */
internal data class AnswerQuestionTransactionResult(
    val question: AgentPendingQuestionEntity,
    val message: AgentMessageEntity,
    val decisions: List<AgentDecisionEntity>,
    val receipt: AgentContinuationOutboxEntity,
    val created: Boolean
)

/** Input for an optional invocation completion continuation receipt. */
internal data class ContinuationReceiptSpec(
    val id: String,
    val conversationId: Long,
    val rootTurnId: String? = null,
    val kind: String,
    val dedupeKey: String,
    val payloadJson: String = "{}",
    val status: String = AgentContinuationStatus.QUEUED,
    val createdAt: Long = System.currentTimeMillis()
)

/** Result of the invocation/report/parent-result transaction. */
internal data class InvocationCompletionTransactionResult(
    val invocation: AgentInvocationEntity,
    val report: AgentWorkReportEntity,
    val parentMessage: AgentMessageEntity?,
    val receipt: AgentContinuationOutboxEntity?
)

/** Result of the Stop persistence fence. */
internal data class CancelConversationWorkResult(
    val cancelledQuestions: Int,
    val cancelledInputs: Int,
    val cancelledContinuations: Int
)

/** Result of the durable pending-input plus optional correction boundary. */
internal data class PendingInputTransactionResult(
    val input: AgentPendingInputEntity,
    val correction: AgentDecisionEntity?,
    val inserted: Boolean
)

/** Normalized terminal semantics used by report and invocation persistence. */
internal data class AgentReportOutcome(
    val status: String,
    val summary: String,
    val errorClass: String? = null,
    val errorMessage: String? = null,
    val requiresUserDirection: Boolean = false
)

/** Result of the durable per-planning-episode research admission gate. */
internal data class AgentResearchAdmission(
    val admitted: Boolean,
    val conversationId: Long,
    val planningEpisodeId: String,
    val tool: String,
    val used: Int,
    val limit: Int?,
    val reason: String? = null,
    val receipt: AgentContinuationOutboxEntity? = null
) {
    /** Alias for callers that name the gate result as an allowance. */
    val allowed: Boolean get() = admitted
}

/**
 * Durable research budget projection for one planning episode.
 *
 * Counts come from the persisted admission receipts rather than an in-memory
 * counter, so a resumed process sees the same limits as the process that
 * created the episode.
 */
internal data class AgentResearchBudget(
    val conversationId: Long,
    val planningEpisodeId: String,
    val searchUsed: Int,
    val searchLimit: Int,
    val fetchUsed: Int,
    val fetchLimit: Int
) {
    val searchRemaining: Int get() = (searchLimit - searchUsed).coerceAtLeast(0)
    val fetchRemaining: Int get() = (fetchLimit - fetchUsed).coerceAtLeast(0)
    val exhausted: Boolean get() = searchUsed >= searchLimit && fetchUsed >= fetchLimit
}

/** Result of the atomic canonical tool outcome plus optional continuation write. */
internal data class CanonicalToolOutcomeTransactionResult(
    val message: AgentMessageEntity,
    val receipt: AgentContinuationOutboxEntity?,
    val messageInserted: Boolean,
    val receiptInserted: Boolean
)

/** Result of the one-repair research-budget failure boundary. */
internal data class ResearchBudgetFailureTransactionResult(
    val outcome: CanonicalToolOutcomeTransactionResult,
    val automaticRepairAllowed: Boolean,
    val repeatedFailure: Boolean,
    val repairReceipt: AgentContinuationOutboxEntity?
)

/**
 * Result of repairing pre-profile user-correction rows.  The repair changes
 * only the decision key/latest marker; the original row id and payload stay
 * durable so a restart cannot lose an earlier correction.
 */
internal data class AgentUserCorrectionMigrationResult(
    val migratedCount: Int,
    val collisionCount: Int
)

/**
 * Small model-facing projection of a user correction.  It deliberately does
 * not carry provenance or submitted-answer envelopes: those remain in the
 * durable decision row, while this value preserves the exact correction text
 * and the stable ids needed to cite/replay it.
 */
internal data class AgentUserCorrectionValue(
    val decisionId: String,
    val messageId: String,
    val content: String,
    val createdAt: Long
)

internal data class AgentUserCorrectionProjection(
    val values: List<AgentUserCorrectionValue>,
    val latestDecisionId: String?
)

/** One committed project-relative artifact path from a successful mutation. */
internal data class AgentArtifactLedgerEntry(
    val path: String,
    val operation: String,
    val actionId: String
)

/**
 * Bounded projection of successful mutation receipts.  The projection carries
 * no file content and makes omission explicit so a required prompt section
 * can pause instead of pretending that the ledger is complete.
 */
internal data class AgentArtifactLedger(
    val entries: List<AgentArtifactLedgerEntry>,
    val omittedCount: Int = 0,
    val incomplete: Boolean = false
) {
    fun render(): String = buildString {
        appendLine("## Committed Artifact Ledger")
        appendLine("- ledger_incomplete: $incomplete")
        appendLine("- omitted_count: $omittedCount (lower_bound)")
        appendLine("- scan_truncated: $incomplete")
        appendLine("- writes_recorded_only: validation still required")
        if (incomplete) {
            appendLine("- incomplete_action: pause and inspect durable state; do not recreate blindly")
        }
        if (entries.isEmpty()) {
            appendLine("- entries: none")
        } else {
            entries.forEach { entry ->
                appendLine(
                    "- path: ${entry.path} | operation: ${entry.operation} | " +
                        "action_id: ${entry.actionId}"
                )
            }
        }
    }.trimEnd()
}

/**
 * Stable persistence seam for the agent's user contract and handoffs.
 *
 * All methods that accept [AppDatabase] are intentionally easy to exercise
 * against an in-memory Room database. Context overloads are convenience
 * wrappers for runtime call sites and always move database work to IO.
 */
internal object AgentDurableContractStore {
    private const val CONTRACT_VERSION = 1
    private const val QUESTION_TOOL = "question"
    private const val QUESTION_ANSWER_PREFIX = "question-answer:"
    private const val QUESTION_RECEIPT_PREFIX = "question-continuation:"
    private const val QUESTION_RECEIPT_KIND = "QUESTION_CONTINUATION"
    private const val ACTION_RECEIPT_KIND = "ACTION_RECEIPT"
    private const val RESEARCH_ADMISSION_KIND = "RESEARCH_ADMISSION"
    private const val TOOL_OUTCOME_PREFIX = "tool-outcome:"
    private const val RESEARCH_BUDGET_FAILURE_KIND = "RESEARCH_BUDGET_FAILURE"
    private const val RESEARCH_BUDGET_REPAIR_KIND = "RESEARCH_BUDGET_REPAIR"
    private const val MAX_RESEARCH_SEARCHES = 2
    private const val MAX_RESEARCH_FETCHES = 4
    private const val DEFAULT_ARTIFACT_RECEIPT_LIMIT = 32
    private const val DEFAULT_ARTIFACT_PATH_LIMIT = 64
    private const val MAX_ARTIFACT_RECEIPT_LIMIT = 256
    private const val MAX_ARTIFACT_PATH_LIMIT = 512
    private const val MAX_METADATA_PATHS = 256
    private val MUTATION_RECEIPT_TOOLS = setOf(
        "write_file",
        "edit_lines",
        "apply_patch",
        "delete_file"
    )
    private val ARTIFACT_OPERATIONS = setOf("write", "edit", "patch", "create", "delete")
    private val SUCCESSFUL_RECEIPT_STATUSES = setOf(
        "SUCCESS",
        "SUCCEEDED",
        "OK",
        "COMPLETED",
        "PASSED",
        "PASS"
    )
    internal const val LEGACY_USER_CORRECTION_DECISION_KEY = "latest_user_correction"
    internal const val USER_CORRECTION_DECISION_KEY_PREFIX = "user_correction:"
    private val NO_MORE_QUESTIONS_DIRECTIVE = Regex(
        "no (more|further) (optional )?questions|do not ask (any )?more|no m[aá]s preguntas",
        RegexOption.IGNORE_CASE
    )
    private val GREENFIELD_DIRECTIVE = Regex(
        "greenfield|no (existing )?codebase|no files have been written|project has not (yet )?started|proyect has not (yet )?started",
        RegexOption.IGNORE_CASE
    )

    /**
     * Produces the only mutation metadata that is safe to replay into a
     * prompt: exact project-relative paths and their operation. File contents,
     * patch hunks, and model summaries are deliberately excluded.
     */
    internal fun mutationReceiptMetadata(
        tool: String,
        args: Map<String, String>,
        success: Boolean
    ): String {
        if (!success) return "{}"
        val normalizedTool = tool.trim().lowercase()
        val entries = when (normalizedTool) {
            "write_file" -> args["path"]
                ?.let { normalizeArtifactPath(it) }
                ?.let { listOf(ArtifactMetadataEntry(it, "write")) }
                .orEmpty()
            "edit_lines" -> args["path"]
                ?.let { normalizeArtifactPath(it) }
                ?.let { listOf(ArtifactMetadataEntry(it, "edit")) }
                .orEmpty()
            "delete_file" -> (args["path"] ?: args["file"])
                ?.let { normalizeArtifactPath(it) }
                ?.let { listOf(ArtifactMetadataEntry(it, "delete")) }
                .orEmpty()
            "apply_patch" -> parsePatchArtifactEntries(args["patch"].orEmpty())
            else -> emptyList()
        }
        if (entries.isEmpty()) {
            return if (normalizedTool in MUTATION_RECEIPT_TOOLS) {
                JSONObject()
                    .put("entries", JSONArray())
                    .put("omitted_count", 1)
                    .put("incomplete", true)
                    .toString()
            } else {
                "{}"
            }
        }

        val distinctEntries = entries.distinctBy { it.path to it.operation }
        val omittedCount = (distinctEntries.size - MAX_METADATA_PATHS).coerceAtLeast(0)
        val retainedEntries = distinctEntries.take(MAX_METADATA_PATHS)
        return JSONObject()
            .put(
                "entries",
                JSONArray().apply {
                    retainedEntries.forEach { entry ->
                        put(
                            JSONObject()
                                .put("path", entry.path)
                                .put("operation", entry.operation)
                        )
                    }
                }
            )
            .put("omitted_count", omittedCount)
            .put("incomplete", omittedCount > 0)
            .toString()
    }

    /**
     * Reads the latest successful mutation receipts for one conversation. The
     * conversation filter intentionally spans planning episodes: artifacts
     * remain project state after a new plan starts.
     */
    internal suspend fun readCommittedArtifactLedger(
        database: AppDatabase,
        conversationId: Long,
        maxReceipts: Int = DEFAULT_ARTIFACT_RECEIPT_LIMIT,
        maxPaths: Int = DEFAULT_ARTIFACT_PATH_LIMIT
    ): AgentArtifactLedger {
        val receiptLimit = maxReceipts.coerceIn(1, MAX_ARTIFACT_RECEIPT_LIMIT)
        val pathLimit = maxPaths.coerceIn(1, MAX_ARTIFACT_PATH_LIMIT)
        val receipts = database.agentWorkflowDao().getLatestCompletedActionReceipts(
            conversationId = conversationId,
            limit = receiptLimit + 1
        )
        var omittedCount = 0
        var incomplete = false
        val boundedReceipts = if (receipts.size > receiptLimit) {
            omittedCount += receipts.size - receiptLimit
            incomplete = true
            receipts.take(receiptLimit)
        } else {
            receipts
        }
        val seenPaths = linkedSetOf<String>()
        val entries = mutableListOf<AgentArtifactLedgerEntry>()

        boundedReceipts.forEach receiptLoop@{ receipt ->
            val payload = runCatching { JSONObject(receipt.payloadJson) }.getOrNull()
            if (payload == null || !isSuccessfulActionReceipt(receipt, payload)) return@receiptLoop
            val tool = payload.optString("tool").trim().lowercase()
            if (tool !in MUTATION_RECEIPT_TOOLS) return@receiptLoop
            val actionId = payload.optString("action_id")
            if (actionId.isBlank()) {
                omittedCount++
                incomplete = true
                return@receiptLoop
            }
            val metadata = payload.optString("metadata_json", "{}")
            val parsed = parseArtifactMetadata(metadata)
            if (parsed == null) {
                omittedCount++
                incomplete = true
                return@receiptLoop
            }
            omittedCount += parsed.omittedCount
            incomplete = incomplete || parsed.incomplete
            parsed.entries.forEach entryLoop@{ entry ->
                if (entry.path in seenPaths) return@entryLoop
                if (entries.size >= pathLimit) {
                    omittedCount++
                    incomplete = true
                    return@entryLoop
                }
                seenPaths += entry.path
                entries += AgentArtifactLedgerEntry(
                    path = entry.path,
                    operation = entry.operation,
                    actionId = actionId
                )
            }
        }
        return AgentArtifactLedger(
            entries = entries,
            omittedCount = omittedCount,
            incomplete = incomplete
        )
    }

    internal suspend fun readCommittedArtifactLedger(
        context: Context,
        conversationId: Long,
        maxReceipts: Int = DEFAULT_ARTIFACT_RECEIPT_LIMIT,
        maxPaths: Int = DEFAULT_ARTIFACT_PATH_LIMIT
    ): AgentArtifactLedger = withContext(Dispatchers.IO) {
        readCommittedArtifactLedger(
            database = AppDatabase.getDatabase(context.applicationContext),
            conversationId = conversationId,
            maxReceipts = maxReceipts,
            maxPaths = maxPaths
        )
    }

    private data class ArtifactMetadataEntry(
        val path: String,
        val operation: String
    )

    private data class ParsedArtifactMetadata(
        val entries: List<ArtifactMetadataEntry>,
        val omittedCount: Int,
        val incomplete: Boolean
    )

    private fun parseArtifactMetadata(rawMetadata: String): ParsedArtifactMetadata? {
        val metadata = runCatching { JSONObject(rawMetadata) }.getOrNull() ?: return null
        val rawEntries = metadata.optJSONArray("entries") ?: return null
        var omittedCount = metadata.optInt("omitted_count", 0).coerceAtLeast(0)
        var incomplete = metadata.optBoolean("incomplete", omittedCount > 0)
        val entries = mutableListOf<ArtifactMetadataEntry>()
        for (index in 0 until rawEntries.length()) {
            val rawEntry = rawEntries.optJSONObject(index)
            val path = rawEntry?.optString("path")?.let { normalizeArtifactPath(it) }
            val operation = rawEntry?.optString("operation")?.trim()?.lowercase()
            if (path.isNullOrBlank() || operation == null || operation !in ARTIFACT_OPERATIONS) {
                omittedCount++
                incomplete = true
                continue
            }
            entries += ArtifactMetadataEntry(path, operation)
        }
        if (entries.isEmpty() && omittedCount == 0) return null
        return ParsedArtifactMetadata(entries, omittedCount, incomplete)
    }

    private fun parsePatchArtifactEntries(patch: String): List<ArtifactMetadataEntry> {
        if (patch.isBlank()) return emptyList()
        val customEntries = mutableListOf<ArtifactMetadataEntry>()
        patch.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd('\r')
            val marker = when {
                line.trimStart().startsWith("*** Update File:") -> "patch"
                line.trimStart().startsWith("*** Add File:") -> "create"
                line.trimStart().startsWith("*** Delete File:") -> "delete"
                else -> null
            }
            if (marker != null) {
                normalizeArtifactPath(line.substringAfter(":"))?.let { path ->
                    customEntries += ArtifactMetadataEntry(path, marker)
                }
            }
        }
        if (customEntries.isNotEmpty()) return customEntries

        val unifiedEntries = mutableListOf<ArtifactMetadataEntry>()
        val lines = patch.lineSequence().map { it.trimEnd('\r') }.toList()
        var index = 0
        while (index < lines.size) {
            val oldLine = lines[index]
            val newLine = lines.getOrNull(index + 1)
            if (
                newLine == null ||
                !isUnifiedDiffHeader(oldLine, "---") ||
                !isUnifiedDiffHeader(newLine, "+++")
            ) {
                index++
                continue
            }
            val oldPath = parseUnifiedDiffPath(oldLine, "---")
            val newPath = parseUnifiedDiffPath(newLine, "+++")
            when {
                oldPath == null && newPath != null ->
                    unifiedEntries += ArtifactMetadataEntry(newPath, "create")
                oldPath != null && newPath == null ->
                    unifiedEntries += ArtifactMetadataEntry(oldPath, "delete")
                oldPath != null && newPath != null && oldPath == newPath ->
                    unifiedEntries += ArtifactMetadataEntry(oldPath, "patch")
                oldPath != null && newPath != null -> {
                    unifiedEntries += ArtifactMetadataEntry(oldPath, "delete")
                    unifiedEntries += ArtifactMetadataEntry(newPath, "create")
                }
            }
            index += 2
        }
        return unifiedEntries
    }

    private fun isUnifiedDiffHeader(line: String, marker: String): Boolean {
        if (!line.startsWith("$marker ")) return false
        val rawPath = line.removePrefix("$marker ").trim().substringBefore('\t').trim()
        return rawPath == "/dev/null" || rawPath.startsWith("a/") ||
            rawPath.startsWith("b/") ||
            (rawPath.isNotBlank() && !rawPath.startsWith("+") && !rawPath.startsWith("-"))
    }

    private fun parseUnifiedDiffPath(line: String, marker: String): String? {
        val rawPath = line.removePrefix("$marker ").trim().substringBefore('\t').trim()
        if (rawPath == "/dev/null") return null
        return normalizeArtifactPath(rawPath, stripDiffPrefix = true)
    }

    private fun normalizeArtifactPath(
        rawPath: String,
        stripDiffPrefix: Boolean = false
    ): String? {
        var path = rawPath.trim()
        if (stripDiffPrefix && path.length >= 2 && path.first() == '"' && path.last() == '"') {
            path = path.substring(1, path.length - 1)
        }
        if (stripDiffPrefix && (path.startsWith("a/") || path.startsWith("b/"))) {
            path = path.substring(2)
        }
        while (path.startsWith("./")) path = path.substring(2)
        if (
            path.isBlank() ||
            path == "/dev/null" ||
            path.startsWith('/') ||
            path.contains('\n') ||
            path.contains('\r') ||
            path.length > 1_024
        ) return null
        val segments = path.split('/')
        if (segments.any { it.isBlank() || it == ".." }) return null
        return path
    }

    private fun isSuccessfulActionReceipt(
        receipt: AgentContinuationOutboxEntity,
        payload: JSONObject
    ): Boolean {
        if (receipt.kind != ACTION_RECEIPT_KIND || receipt.status != AgentContinuationStatus.COMPLETED) {
            return false
        }
        return payload.optString("receipt_type").equals("action", ignoreCase = true) &&
            payload.optString("status").trim().uppercase() in SUCCESSFUL_RECEIPT_STATUSES
    }

    suspend fun ensureContract(
        context: Context,
        conversationId: Long,
        initialGoal: String? = null,
        greenfield: Boolean? = null
    ): AgentProjectContractEntity = withContext(Dispatchers.IO) {
        ensureContract(
            AppDatabase.getDatabase(context.applicationContext),
            conversationId,
            initialGoal,
            greenfield
        )
    }

    suspend fun ensureContract(
        database: AppDatabase,
        conversationId: Long,
        initialGoal: String? = null,
        greenfield: Boolean? = null
    ): AgentProjectContractEntity = database.withTransaction {
        ensureContractInTransaction(database, conversationId, initialGoal, greenfield)
    }

    private suspend fun ensureContractInTransaction(
        database: AppDatabase,
        conversationId: Long,
        initialGoal: String? = null,
        greenfield: Boolean? = null
    ): AgentProjectContractEntity {
        val dao = database.agentWorkflowDao()
        dao.insertProjectContractIfMissing(
            AgentProjectContractEntity(
                conversationId = conversationId,
                contractVersion = CONTRACT_VERSION,
                initialGoal = initialGoal.cleanContractText(),
                initialGoalSource = if (initialGoal.cleanContractText().isBlank()) {
                    "SYSTEM"
                } else {
                    "USER"
                },
                greenfield = greenfield ?: false
            )
        )
        if (!initialGoal.cleanContractText().isBlank()) {
            dao.setInitialGoalIfBlank(
                conversationId = conversationId,
                initialGoal = initialGoal.cleanContractText(),
                initialGoalSource = "USER"
            )
        }
        greenfield?.let { dao.setGreenfield(conversationId, it) }
        return dao.getProjectContract(conversationId)
            ?: error("Project contract disappeared for conversation $conversationId")
    }

    /** Seeds the initial goal once and returns the authoritative stored value. */
    suspend fun recordInitialGoal(
        database: AppDatabase,
        conversationId: Long,
        initialGoal: String,
        source: String = "USER"
    ): AgentProjectContractEntity = database.withTransaction {
        val dao = database.agentWorkflowDao()
        dao.insertProjectContractIfMissing(
            AgentProjectContractEntity(
                conversationId = conversationId,
                contractVersion = CONTRACT_VERSION
            )
        )
        dao.setInitialGoalIfBlank(
            conversationId = conversationId,
            initialGoal = initialGoal.cleanContractText(),
            initialGoalSource = source.trim().ifBlank { "USER" }.take(80)
        )
        dao.getProjectContract(conversationId)
            ?: error("Project contract disappeared for conversation $conversationId")
    }

    suspend fun recordInitialGoal(
        context: Context,
        conversationId: Long,
        initialGoal: String,
        source: String = "USER"
    ): AgentProjectContractEntity = withContext(Dispatchers.IO) {
        recordInitialGoal(
            AppDatabase.getDatabase(context.applicationContext),
            conversationId,
            initialGoal,
            source
        )
    }

    suspend fun markNoMoreQuestions(
        database: AppDatabase,
        conversationId: Long,
        value: Boolean = true
    ): AgentProjectContractEntity = database.withTransaction {
        ensureContractInTransaction(database, conversationId)
        database.agentWorkflowDao().setNoMoreQuestions(conversationId, value)
        database.agentWorkflowDao().getProjectContract(conversationId)
            ?: error("Project contract disappeared for conversation $conversationId")
    }

    suspend fun markNoMoreQuestions(
        context: Context,
        conversationId: Long,
        value: Boolean = true
    ): AgentProjectContractEntity = withContext(Dispatchers.IO) {
        markNoMoreQuestions(AppDatabase.getDatabase(context.applicationContext), conversationId, value)
    }

    suspend fun markGreenfield(
        database: AppDatabase,
        conversationId: Long,
        value: Boolean = true
    ): AgentProjectContractEntity = database.withTransaction {
        ensureContractInTransaction(database, conversationId)
        database.agentWorkflowDao().setGreenfield(conversationId, value)
        database.agentWorkflowDao().getProjectContract(conversationId)
            ?: error("Project contract disappeared for conversation $conversationId")
    }

    suspend fun markGreenfield(
        context: Context,
        conversationId: Long,
        value: Boolean = true
    ): AgentProjectContractEntity = withContext(Dispatchers.IO) {
        markGreenfield(AppDatabase.getDatabase(context.applicationContext), conversationId, value)
    }

    /**
     * Records one completed tool/action receipt in the existing outbox table.
     * The stable episode/action key makes retries return the original row;
     * metadata is kept verbatim inside the payload for later inspection.
     */
    suspend fun recordActionReceipt(
        context: Context,
        conversationId: Long,
        planningEpisodeId: String,
        tool: String,
        actionId: String,
        status: String,
        rootTurnId: String? = null,
        evidenceFingerprint: String? = null,
        metadataJson: String = "{}"
    ): AgentContinuationOutboxEntity = withContext(Dispatchers.IO) {
        recordActionReceipt(
            database = AppDatabase.getDatabase(context.applicationContext),
            conversationId = conversationId,
            planningEpisodeId = planningEpisodeId,
            tool = tool,
            actionId = actionId,
            status = status,
            rootTurnId = rootTurnId,
            evidenceFingerprint = evidenceFingerprint,
            metadataJson = metadataJson
        )
    }

    suspend fun recordActionReceipt(
        database: AppDatabase,
        conversationId: Long,
        planningEpisodeId: String,
        tool: String,
        actionId: String,
        status: String,
        rootTurnId: String? = null,
        evidenceFingerprint: String? = null,
        metadataJson: String = "{}"
    ): AgentContinuationOutboxEntity = database.withTransaction {
        val episode = planningEpisodeId.trim()
        val normalizedTool = tool.trim().lowercase()
        val action = actionId.trim()
        require(episode.isNotBlank()) { "A planning episode ID is required" }
        require(normalizedTool.isNotBlank()) { "An action tool name is required" }
        require(action.isNotBlank()) { "An action ID is required" }
        val dedupeKey = actionReceiptDedupeKey(episode, normalizedTool, action)
        val workflow = database.agentWorkflowDao()
        workflow.getContinuationReceipt(conversationId, dedupeKey)?.let {
            return@withTransaction it
        }
        val now = System.currentTimeMillis()
        val receipt = AgentContinuationOutboxEntity(
            id = "action-receipt:$dedupeKey",
            conversationId = conversationId,
            rootTurnId = rootTurnId,
            kind = ACTION_RECEIPT_KIND,
            dedupeKey = dedupeKey,
            payloadJson = actionReceiptPayload(
                planningEpisodeId = episode,
                tool = normalizedTool,
                actionId = action,
                status = status,
                rootTurnId = rootTurnId,
                evidenceFingerprint = evidenceFingerprint,
                metadataJson = metadataJson
            ),
            status = actionReceiptStatus(status),
            createdAt = now,
            updatedAt = now
        )
        workflow.insertContinuationReceipt(receipt)
        workflow.getContinuationReceipt(conversationId, dedupeKey)
            ?: error("Action receipt could not be persisted")
    }

    /**
     * Commits a canonical tool outcome and its optional continuation receipt
     * before the caller updates the live chat projection. Both identities are
     * stable so replay repairs a missing receipt without replacing the first
     * committed message.
     */
    suspend fun recordCanonicalToolOutcomeAtomically(
        context: Context,
        conversationId: Long,
        stableOutcomeId: String,
        toolName: String,
        toolCallId: String?,
        content: String,
        toolOutput: String? = null,
        rootTurnId: String? = null,
        continuation: ContinuationReceiptSpec? = null
    ): CanonicalToolOutcomeTransactionResult = withContext(Dispatchers.IO) {
        recordCanonicalToolOutcomeAtomically(
            database = AppDatabase.getDatabase(context.applicationContext),
            conversationId = conversationId,
            stableOutcomeId = stableOutcomeId,
            toolName = toolName,
            toolCallId = toolCallId,
            content = content,
            toolOutput = toolOutput,
            rootTurnId = rootTurnId,
            continuation = continuation
        )
    }

    suspend fun recordCanonicalToolOutcomeAtomically(
        database: AppDatabase,
        conversationId: Long,
        stableOutcomeId: String,
        toolName: String,
        toolCallId: String?,
        content: String,
        toolOutput: String? = null,
        rootTurnId: String? = null,
        continuation: ContinuationReceiptSpec? = null
    ): CanonicalToolOutcomeTransactionResult = database.withTransaction {
        recordCanonicalToolOutcomeInTransaction(
            database = database,
            conversationId = conversationId,
            stableOutcomeId = stableOutcomeId,
            toolName = toolName,
            toolCallId = toolCallId,
            content = content,
            toolOutput = toolOutput,
            rootTurnId = rootTurnId,
            continuation = continuation
        )
    }

    /**
     * Commits a blocked research result and allows exactly one automatic plan
     * repair for a durable episode/bucket/count key. A later denial persists
     * its own canonical outcome but returns no new repair receipt.
     */
    suspend fun recordResearchBudgetFailureAndRepairAtomically(
        context: Context,
        conversationId: Long,
        planningEpisodeId: String,
        toolName: String,
        used: Int,
        limit: Int,
        stableOutcomeId: String,
        toolCallId: String?,
        content: String,
        toolOutput: String? = null,
        rootTurnId: String? = null,
        repairContinuation: ContinuationReceiptSpec
    ): ResearchBudgetFailureTransactionResult = withContext(Dispatchers.IO) {
        recordResearchBudgetFailureAndRepairAtomically(
            database = AppDatabase.getDatabase(context.applicationContext),
            conversationId = conversationId,
            planningEpisodeId = planningEpisodeId,
            toolName = toolName,
            used = used,
            limit = limit,
            stableOutcomeId = stableOutcomeId,
            toolCallId = toolCallId,
            content = content,
            toolOutput = toolOutput,
            rootTurnId = rootTurnId,
            repairContinuation = repairContinuation
        )
    }

    suspend fun recordResearchBudgetFailureAndRepairAtomically(
        database: AppDatabase,
        conversationId: Long,
        planningEpisodeId: String,
        toolName: String,
        used: Int,
        limit: Int,
        stableOutcomeId: String,
        toolCallId: String?,
        content: String,
        toolOutput: String? = null,
        rootTurnId: String? = null,
        repairContinuation: ContinuationReceiptSpec
    ): ResearchBudgetFailureTransactionResult = database.withTransaction {
        val episode = planningEpisodeId.trim()
        val normalizedTool = toolName.trim().lowercase()
        require(episode.isNotBlank()) { "A planning episode ID is required" }
        require(normalizedTool.isNotBlank()) { "A research tool name is required" }
        require(used >= 0) { "Research usage cannot be negative" }
        require(limit > 0) { "Research limit must be positive" }
        require(repairContinuation.conversationId == conversationId) {
            "Repair continuation belongs to a different conversation"
        }
        val workflow = database.agentWorkflowDao()
        val budgetKey = researchBudgetFailureDedupeKey(
            conversationId = conversationId,
            planningEpisodeId = episode
        )
        val previousFailure = workflow.getContinuationReceipt(
            conversationId = conversationId,
            dedupeKey = budgetKey
        )
        val stableId = stableOutcomeId.trim()
        val firstOutcomeId = previousFailure?.let {
            runCatching { JSONObject(it.payloadJson).optString("outcome_id") }
                .getOrNull()
                ?.takeIf { value -> value.isNotBlank() }
        }
        val exactOutcomeReplay = previousFailure != null && firstOutcomeId == stableId
        val repairAllowed = previousFailure == null || exactOutcomeReplay
        val durableRepair = repairContinuation.copy(
            id = "research-budget-repair:${receiptKeyComponent(budgetKey)}",
            rootTurnId = repairContinuation.rootTurnId ?: rootTurnId,
            kind = RESEARCH_BUDGET_REPAIR_KIND,
            dedupeKey = "$budgetKey:repair",
            status = AgentContinuationStatus.QUEUED
        )
        if (repairAllowed) {
            val now = System.currentTimeMillis()
            workflow.insertContinuationReceipt(
                AgentContinuationOutboxEntity(
                    id = budgetKey,
                    conversationId = conversationId,
                    rootTurnId = rootTurnId,
                    kind = RESEARCH_BUDGET_FAILURE_KIND,
                    dedupeKey = budgetKey,
                    payloadJson = JSONObject()
                        .put("receipt_type", "research_budget_failure")
                        .put("planning_episode_id", episode)
                        .put("tool", normalizedTool)
                        .put("used", used)
                        .put("limit", limit)
                        .put("outcome_id", stableId)
                        .toString(),
                    status = AgentContinuationStatus.COMPLETED,
                    createdAt = now,
                    updatedAt = now,
                    completedAt = now
                )
            )
        }
        val outcome = recordCanonicalToolOutcomeInTransaction(
            database = database,
            conversationId = conversationId,
            stableOutcomeId = stableOutcomeId,
            toolName = normalizedTool,
            toolCallId = toolCallId,
            content = content,
            toolOutput = toolOutput,
            rootTurnId = rootTurnId,
            continuation = durableRepair.takeIf { repairAllowed }
        )
        ResearchBudgetFailureTransactionResult(
            outcome = outcome,
            automaticRepairAllowed = repairAllowed,
            repeatedFailure = previousFailure != null && !exactOutcomeReplay,
            repairReceipt = outcome.receipt
        )
    }

    /**
     * Atomically admits bounded web research for one planning episode. Search
     * tools receive two calls and URL fetches four; after the limit only a
     * specific blocker reason can authorize another call.
     */
    suspend fun admitResearch(
        context: Context,
        conversationId: Long,
        planningEpisodeId: String,
        tool: String,
        specificBlockerReason: String? = null,
        rootTurnId: String? = null
    ): AgentResearchAdmission = withContext(Dispatchers.IO) {
        admitResearch(
            database = AppDatabase.getDatabase(context.applicationContext),
            conversationId = conversationId,
            planningEpisodeId = planningEpisodeId,
            tool = tool,
            specificBlockerReason = specificBlockerReason,
            rootTurnId = rootTurnId
        )
    }

    /** Reads the durable research counters used by control-packet rendering. */
    suspend fun readResearchBudget(
        context: Context,
        conversationId: Long,
        planningEpisodeId: String
    ): AgentResearchBudget = withContext(Dispatchers.IO) {
        readResearchBudget(
            database = AppDatabase.getDatabase(context.applicationContext),
            conversationId = conversationId,
            planningEpisodeId = planningEpisodeId
        )
    }

    /**
     * Returns counts for the two bounded research families. The same
     * `web_search` bucket is used for the Kiwix alias as [admitResearch].
     */
    suspend fun readResearchBudget(
        database: AppDatabase,
        conversationId: Long,
        planningEpisodeId: String
    ): AgentResearchBudget = database.withTransaction {
        val episode = planningEpisodeId.trim()
        require(episode.isNotBlank()) { "A planning episode ID is required" }
        val workflow = database.agentWorkflowDao()
        val searchPrefix = researchAdmissionPrefix(episode, "web_search")
        val fetchPrefix = researchAdmissionPrefix(episode, "fetch_url")
        val searchUsed = workflow.countContinuationReceipts(
            conversationId = conversationId,
            kind = RESEARCH_ADMISSION_KIND,
            dedupePrefix = "$searchPrefix%"
        )
        val fetchUsed = workflow.countContinuationReceipts(
            conversationId = conversationId,
            kind = RESEARCH_ADMISSION_KIND,
            dedupePrefix = "$fetchPrefix%"
        )
        AgentResearchBudget(
            conversationId = conversationId,
            planningEpisodeId = episode,
            searchUsed = searchUsed,
            searchLimit = MAX_RESEARCH_SEARCHES,
            fetchUsed = fetchUsed,
            fetchLimit = MAX_RESEARCH_FETCHES
        )
    }

    suspend fun admitResearch(
        database: AppDatabase,
        conversationId: Long,
        planningEpisodeId: String,
        tool: String,
        specificBlockerReason: String? = null,
        rootTurnId: String? = null
    ): AgentResearchAdmission = database.withTransaction {
        val episode = planningEpisodeId.trim()
        val normalizedTool = tool.trim().lowercase()
        require(episode.isNotBlank()) { "A planning episode ID is required" }
        require(normalizedTool.isNotBlank()) { "A research tool name is required" }
        val limit = researchLimit(normalizedTool)
        if (limit == null) {
            return@withTransaction AgentResearchAdmission(
                admitted = true,
                conversationId = conversationId,
                planningEpisodeId = episode,
                tool = normalizedTool,
                used = 0,
                limit = null,
                reason = "unbounded_tool"
            )
        }
        val workflow = database.agentWorkflowDao()
        val prefix = researchAdmissionPrefix(episode, if (normalizedTool == "kiwix_search") "web_search" else normalizedTool)
        val usedBefore = workflow.countContinuationReceipts(
            conversationId = conversationId,
            kind = RESEARCH_ADMISSION_KIND,
            dedupePrefix = "$prefix%"
        )
        val blocker = specificBlockerReason?.trim()?.takeIf { it.isNotBlank() }
        if (usedBefore >= limit && blocker == null) {
            return@withTransaction AgentResearchAdmission(
                admitted = false,
                conversationId = conversationId,
                planningEpisodeId = episode,
                tool = normalizedTool,
                used = usedBefore,
                limit = limit,
                reason = "RESEARCH_BUDGET_EXHAUSTED: provide a specific blocker reason to continue"
            )
        }

        val now = System.currentTimeMillis()
        val dedupeKey = prefix + UUID.randomUUID().toString()
        val receipt = AgentContinuationOutboxEntity(
            id = "research-admission:$dedupeKey",
            conversationId = conversationId,
            rootTurnId = rootTurnId,
            kind = RESEARCH_ADMISSION_KIND,
            dedupeKey = dedupeKey,
            payloadJson = researchAdmissionPayload(
                planningEpisodeId = episode,
                tool = normalizedTool,
                rootTurnId = rootTurnId,
                countBefore = usedBefore,
                limit = limit,
                blockerReason = blocker
            ),
            status = AgentContinuationStatus.COMPLETED,
            createdAt = now,
            updatedAt = now,
            completedAt = now
        )
        workflow.insertContinuationReceipt(receipt)
        val persisted = workflow.getContinuationReceipt(conversationId, dedupeKey)
            ?: error("Research admission could not be persisted")
        AgentResearchAdmission(
            admitted = true,
            conversationId = conversationId,
            planningEpisodeId = episode,
            tool = normalizedTool,
            used = usedBefore + 1,
            limit = limit,
            reason = blocker?.let { "explicit_blocker_override" },
            receipt = persisted
        )
    }

    /**
     * Records one correction, preserving the previous row and pointing it at
     * the new latest decision. The exact submitted and canonical payloads are
     * stored without parsing them into a lossy map.
     */
    suspend fun recordDecision(
        database: AppDatabase,
        conversationId: Long,
        decisionKey: String,
        answerJson: String,
        submittedAnswerJson: String = "{}",
        selectedOptionsJson: String = "[]",
        customAnswer: String? = null,
        specificationJson: String = "{}",
        provenanceJson: String = "{}",
        rootTurnId: String? = null,
        questionId: String? = null,
        supersedesDecisionId: String? = null,
        decisionId: String = "decision-${UUID.randomUUID()}",
        createdAt: Long = System.currentTimeMillis()
    ): AgentDecisionEntity = database.withTransaction {
        recordDecisionInTransaction(
            database = database,
            conversationId = conversationId,
            decisionKey = decisionKey,
            answerJson = answerJson,
            submittedAnswerJson = submittedAnswerJson,
            selectedOptionsJson = selectedOptionsJson,
            customAnswer = customAnswer,
            specificationJson = specificationJson,
            provenanceJson = provenanceJson,
            rootTurnId = rootTurnId,
            questionId = questionId,
            supersedesDecisionId = supersedesDecisionId,
            decisionId = decisionId,
            createdAt = createdAt
        )
    }

    /**
     * Records a free-form user correction as an independent decision.
     *
     * Structured question answers continue to use [recordDecision] and its
     * same-key supersession semantics.  Corrections are different: every
     * message is an applicable requirement, so its key and replay id are
     * derived from that message and never supersede another correction.
     */
    suspend fun recordUserCorrection(
        database: AppDatabase,
        conversationId: Long,
        messageId: String,
        content: String,
        rootTurnId: String? = null,
        provenanceJson: String = "{}",
        createdAt: Long = System.currentTimeMillis()
    ): AgentDecisionEntity = database.withTransaction {
        migrateLegacyUserCorrectionsInTransaction(database, conversationId)
        val normalizedMessageId = requireUserCorrectionMessageId(messageId)
        val decisionKey = userCorrectionDecisionKey(normalizedMessageId)
        val workflow = database.agentWorkflowDao()
        // A previous migration or retry may already have materialized this
        // message. Returning it keeps the operation exactly-once.
        val existing = workflow.getDecisionsForKey(conversationId, decisionKey)
            .firstOrNull()
        val decision = existing ?: recordDecisionInTransaction(
                database = database,
                conversationId = conversationId,
                decisionKey = decisionKey,
                answerJson = JSONObject()
                    .put("content", content)
                    .put("message_id", normalizedMessageId)
                    .toString(),
                submittedAnswerJson = content,
                selectedOptionsJson = "[]",
                customAnswer = content,
                specificationJson = "{}",
                provenanceJson = provenanceJson,
                rootTurnId = rootTurnId,
                questionId = null,
                supersedesDecisionId = null,
                decisionId = userCorrectionDecisionId(normalizedMessageId),
                createdAt = createdAt
            )
        // Use the durable first value on replay. A stale retry must not change
        // the contract, while a legacy row still gets the positive directive
        // flags when it is repaired by the queue drain.
        applyQueuedGuidanceDirectivesInTransaction(
            database = database,
            conversationId = conversationId,
            content = userCorrectionContent(decision)
        )
        decision
    }

    /**
     * Persists a pending input and its applicable user correction as one
     * idempotent boundary.  Queue writers use an IGNORE insert and read the
     * existing row back, so a retry cannot replace a row that Stop cancelled
     * or a row that a previous boundary delivered.  USER_MESSAGE inputs are
     * corrections even when empty; plan/build controls are corrections only
     * when their guidance has content.
     */
    suspend fun enqueuePendingInputAtomically(
        database: AppDatabase,
        input: AgentPendingInputEntity,
        rootTurnId: String? = null
    ): PendingInputTransactionResult = database.withTransaction {
        val workflow = database.agentWorkflowDao()
        val existing = workflow.getPendingInput(input.id)
        require(existing == null || existing.conversationId == input.conversationId) {
            "Pending input id belongs to another conversation"
        }
        if (existing == null) {
            workflow.insertPendingInputIfMissing(input)
        }
        val persisted = workflow.getPendingInput(input.id)
            ?: error("Pending input disappeared during enqueue")
        val correction = if (isCorrectionInput(persisted)) {
            recordUserCorrection(
                database = database,
                conversationId = persisted.conversationId,
                messageId = persisted.id,
                content = persisted.content,
                rootTurnId = rootTurnId,
                provenanceJson = queuedGuidanceProvenance(persisted),
                createdAt = persisted.createdAt
            )
        } else {
            null
        }
        PendingInputTransactionResult(
            input = persisted,
            correction = correction,
            inserted = existing == null
        )
    }

    /**
     * The queue and the legacy drain share this directive vocabulary with the
     * root's existing positive-only checks.  There is intentionally no false
     * path: a later queued message cannot clear either durable flag.
     */
    internal data class QueuedGuidanceDirectives(
        val noMoreQuestions: Boolean,
        val greenfield: Boolean
    )

    internal fun queuedGuidanceDirectives(content: String): QueuedGuidanceDirectives =
        QueuedGuidanceDirectives(
            noMoreQuestions = NO_MORE_QUESTIONS_DIRECTIVE.containsMatchIn(content),
            greenfield = GREENFIELD_DIRECTIVE.containsMatchIn(content)
        )

    private suspend fun applyQueuedGuidanceDirectivesInTransaction(
        database: AppDatabase,
        conversationId: Long,
        content: String
    ) {
        val directives = queuedGuidanceDirectives(content)
        if (!directives.noMoreQuestions && !directives.greenfield) return
        ensureContractInTransaction(database, conversationId)
        val workflow = database.agentWorkflowDao()
        if (directives.noMoreQuestions) {
            workflow.setNoMoreQuestions(conversationId, true)
        }
        if (directives.greenfield) {
            workflow.setGreenfield(conversationId, true)
        }
    }

    private fun isCorrectionInput(input: AgentPendingInputEntity): Boolean =
        input.status == "QUEUED" && (
            input.kind == "USER_MESSAGE" ||
                input.kind in setOf("MODE_PLAN", "MODE_BUILD") && input.content.isNotBlank()
            )

    private fun queuedGuidanceProvenance(
        input: AgentPendingInputEntity,
        source: String = "queued_user_guidance"
    ): String = JSONObject()
        .put("source", source)
        .put("pending_input_id", input.id)
        .put("target_invocation_id", input.targetInvocationId ?: JSONObject.NULL)
        .put("kind", input.kind)
        .toString()

    /**
     * Repairs rows written by the old single-key correction path.
     *
     * This is intentionally an in-place rewrite.  Keeping the original row
     * ids and payloads avoids a duplicate history, while making every legacy
     * correction independently latest means the normal latest-decision query
     * cannot silently drop earlier user requirements.  Rows belonging to a
     * structured question are excluded even if a caller supplied the legacy
     * key by mistake.
     */
    suspend fun migrateLegacyUserCorrections(
        database: AppDatabase,
        conversationId: Long
    ): AgentUserCorrectionMigrationResult = database.withTransaction {
        migrateLegacyUserCorrectionsInTransaction(database, conversationId)
    }

    /** Stable per-message key used for free-form user corrections. */
    internal fun userCorrectionDecisionKey(messageId: String): String {
        val normalized = requireUserCorrectionMessageId(messageId)
        return USER_CORRECTION_DECISION_KEY_PREFIX + receiptKeyComponent(normalized)
    }

    /** Stable replay id paired with [userCorrectionDecisionKey]. */
    internal fun userCorrectionDecisionId(messageId: String): String {
        val normalized = requireUserCorrectionMessageId(messageId)
        return "user-correction:" + receiptKeyComponent(normalized)
    }

    /**
     * Projects only exact correction values for the model packet.  Durable
     * provenance, submitted envelopes, and selected-option metadata remain
     * available on [AgentDecisionEntity] for audit/replay but are omitted from
     * this compact view.
     */
    internal fun projectUserCorrections(
        decisions: List<AgentDecisionEntity>
    ): AgentUserCorrectionProjection {
        val values = decisions
            .asSequence()
            .filter(::isUserCorrectionDecision)
            .map { decision ->
                AgentUserCorrectionValue(
                    decisionId = decision.id,
                    messageId = userCorrectionMessageId(decision),
                    content = userCorrectionContent(decision),
                    createdAt = decision.createdAt
                )
            }
            .distinctBy { it.decisionId }
            .sortedWith(compareBy<AgentUserCorrectionValue> { it.createdAt }.thenBy { it.decisionId })
            .toList()
        return AgentUserCorrectionProjection(
            values = values,
            latestDecisionId = values.maxWithOrNull(
                compareBy<AgentUserCorrectionValue> { it.createdAt }.thenBy { it.decisionId }
            )?.decisionId
        )
    }

    /** Derives the latest marker from durable creation order, not a volatile flag. */
    internal fun latestUserCorrectionDecisionId(
        decisions: List<AgentDecisionEntity>
    ): String? = projectUserCorrections(decisions).latestDecisionId

    internal fun isUserCorrectionDecision(decision: AgentDecisionEntity): Boolean =
        decision.questionId.isNullOrBlank() &&
            (decision.decisionKey == LEGACY_USER_CORRECTION_DECISION_KEY ||
                decision.decisionKey.startsWith(USER_CORRECTION_DECISION_KEY_PREFIX))

    private suspend fun recordDecisionInTransaction(
        database: AppDatabase,
        conversationId: Long,
        decisionKey: String,
        answerJson: String,
        submittedAnswerJson: String,
        selectedOptionsJson: String,
        customAnswer: String?,
        specificationJson: String,
        provenanceJson: String,
        rootTurnId: String?,
        questionId: String?,
        supersedesDecisionId: String?,
        decisionId: String,
        createdAt: Long = System.currentTimeMillis()
    ): AgentDecisionEntity {
        val dao = database.agentWorkflowDao()
        // A stable caller supplied ID is the replay key. Return the original
        // row before looking up a predecessor so a retry cannot self-supersede
        // it or create a second correction with the same ID.
        dao.getDecision(decisionId)?.let { return it }
        val key = decisionKey.trim().ifBlank { questionId ?: decisionId }
        val old = supersedesDecisionId?.let { dao.getDecision(it) }
            ?: dao.getDecisionsForKey(conversationId, key)
                .firstOrNull { it.isLatest }
        old?.let {
            if (supersedesDecisionId != null) {
                dao.supersedeDecision(conversationId, it.id, decisionId)
            } else {
                dao.supersedeLatestDecision(conversationId, key, decisionId)
            }
        }
        val decision = AgentDecisionEntity(
            id = decisionId,
            conversationId = conversationId,
            rootTurnId = rootTurnId,
            questionId = questionId,
            decisionKey = key,
            answerJson = answerJson,
            submittedAnswerJson = submittedAnswerJson,
            selectedOptionsJson = selectedOptionsJson,
            customAnswer = customAnswer,
            specificationJson = specificationJson,
            provenanceJson = provenanceJson,
            supersedesDecisionId = old?.id,
            isLatest = true,
            createdAt = createdAt,
            updatedAt = createdAt
        )
        dao.insertDecision(decision)
        return decision
    }

    suspend fun answerQuestionAtomically(
        context: Context,
        questionId: String,
        submittedAnswerJson: String,
        provenanceJson: String = "{}",
        continuationPayloadJson: String = "{}"
    ): AnswerQuestionTransactionResult = withContext(Dispatchers.IO) {
        answerQuestionAtomically(
            AppDatabase.getDatabase(context.applicationContext),
            questionId,
            submittedAnswerJson,
            provenanceJson,
            continuationPayloadJson
        )
    }

    /**
     * Validates and commits a structured question answer as one durable unit.
     * Repeating the call for an already answered question repairs any missing
     * message/decision/receipt and returns the same stable projection IDs.
     */
    suspend fun answerQuestionAtomically(
        database: AppDatabase,
        questionId: String,
        submittedAnswerJson: String,
        provenanceJson: String = "{}",
        continuationPayloadJson: String = "{}"
    ): AnswerQuestionTransactionResult = database.withTransaction {
        val workflow = database.agentWorkflowDao()
        val chat = database.agentChatDao()
        val pending = workflow.getPendingQuestion(questionId)
            ?: error("Pending question no longer exists: $questionId")
        val alreadyAnswered = pending.status.equals("ANSWERED", ignoreCase = true)
        val canonicalAnswer = if (alreadyAnswered && !pending.answerJson.isNullOrBlank()) {
            pending.answerJson.orEmpty()
        } else {
            authoritativeQuestionAnswerJson(
                pending.specificationJson,
                submittedAnswerJson
            )
        }
        val messageId = pending.answerMessageOriginalId
            ?.takeIf { it.isNotBlank() }
            ?: questionAnswerMessageId(questionId)
        val receiptId = pending.answerReceiptId
            ?.takeIf { it.isNotBlank() }
            ?: questionAnswerReceiptId(questionId)
        val dedupeKey = "question:$questionId"
        if (!alreadyAnswered) {
            require(pending.status.equals("PENDING", ignoreCase = true)) {
                "Question $questionId is ${pending.status} and cannot be answered"
            }
            require(
                workflow.answerQuestionWithReceiptExactlyOnce(
                    id = questionId,
                    answerJson = canonicalAnswer,
                    answerMessageOriginalId = messageId,
                    answerReceiptId = receiptId
                ) == 1
            ) { "Question $questionId was answered concurrently" }
        }

        var message = chat.getMessageByOriginalId(messageId)
            ?: chat.getToolMessage(pending.conversationId, QUESTION_TOOL, pending.toolCallId)
        if (message == null) {
            message = AgentMessageEntity(
                originalId = messageId,
                conversationId = pending.conversationId,
                role = "tool",
                content = canonicalQuestionToolResult(
                    if (alreadyAnswered) pending.answerJson ?: canonicalAnswer else canonicalAnswer
                ),
                toolName = QUESTION_TOOL,
                toolCallId = pending.toolCallId,
                toolOutput = if (alreadyAnswered) pending.answerJson ?: canonicalAnswer else canonicalAnswer,
                timestamp = pending.answeredAt ?: System.currentTimeMillis(),
                sequenceNumber = chat.getMaxMessageSequence(pending.conversationId) + 1
            )
            chat.insertMessage(message)
        } else if (pending.answerMessageOriginalId.isNullOrBlank()) {
            workflow.attachQuestionAnswerReceipt(
                id = questionId,
                answerMessageOriginalId = message.originalId,
                answerReceiptId = receiptId
            )
        }

        val storedAnswer = pending.answerJson ?: canonicalAnswer
        val canonicalItems = answerItems(storedAnswer)
        val submittedRoot = runCatching { JSONObject(submittedAnswerJson) }.getOrNull()
        val specification = questionSpecFromJson(pending.specificationJson)
        val existingDecisions = specification.questions.mapNotNull { item ->
            workflow.getDecisionsForKey(
                pending.conversationId,
                questionDecisionKey(questionId, item.id)
            ).firstOrNull { it.isLatest }
        }
        // Migration 116 has one compact root decision for a legacy answer. A
        // single-item specification can reuse it without manufacturing a
        // duplicate correction on the first recovery pass.
        val migratedRootDecision = if (specification.questions.size == 1) {
            workflow.getDecisionsForKey(
                pending.conversationId,
                questionDecisionKey(questionId)
            ).firstOrNull { it.isLatest }
        } else {
            null
        }
        val decisions = if (existingDecisions.size == specification.questions.size) {
            existingDecisions
        } else if (migratedRootDecision != null && existingDecisions.isEmpty()) {
            listOf(migratedRootDecision)
        } else {
            specification.questions.mapIndexed { index, item ->
                val canonical = canonicalItems.firstOrNull {
                    it.optString("question_id") == item.id
                } ?: canonicalItems.getOrNull(index) ?: JSONObject()
                val submittedItem = submittedRoot?.optJSONObject("answers")
                    ?.optJSONObject(item.id)
                    ?.toString()
                    ?: "{}"
                recordDecisionInTransaction(
                    database = database,
                    conversationId = pending.conversationId,
                    decisionKey = questionDecisionKey(questionId, item.id),
                    answerJson = canonical.toString(),
                    submittedAnswerJson = submittedItem,
                    selectedOptionsJson = canonical.optJSONArray("selected_options")?.toString() ?: "[]",
                    customAnswer = canonical.optString("custom_answer").takeIf { it.isNotBlank() },
                    specificationJson = pending.specificationJson,
                    provenanceJson = decisionProvenance(
                        provenanceJson,
                        questionId,
                        pending.rootTurnId,
                        item.id
                    ),
                    rootTurnId = pending.rootTurnId,
                    questionId = item.id,
                    supersedesDecisionId = null,
                    decisionId = "decision-question-$questionId-${item.id}"
                )
            }
        }

        val payload = continuationPayload(
            continuationPayloadJson,
            questionId = questionId,
            messageId = message.originalId,
            rootTurnId = pending.rootTurnId
        )
        val existingReceipt = workflow.getContinuationReceipt(
            pending.conversationId,
            dedupeKey
        )
        val receipt = existingReceipt ?: AgentContinuationOutboxEntity(
            id = receiptId,
            conversationId = pending.conversationId,
            rootTurnId = pending.rootTurnId,
            kind = QUESTION_RECEIPT_KIND,
            dedupeKey = dedupeKey,
            payloadJson = payload,
            status = AgentContinuationStatus.QUEUED,
            createdAt = pending.answeredAt ?: System.currentTimeMillis(),
            updatedAt = pending.answeredAt ?: System.currentTimeMillis()
        ).also {
            workflow.insertContinuationReceipt(it)
        }.let {
            workflow.getContinuationReceipt(pending.conversationId, dedupeKey)
                ?: error("Question continuation receipt could not be persisted")
        }

        val updatedQuestion = workflow.getPendingQuestion(questionId)
            ?: error("Question $questionId disappeared after answer")
        AnswerQuestionTransactionResult(
            question = updatedQuestion,
            message = message,
            decisions = decisions,
            receipt = receipt,
            created = !alreadyAnswered
        )
    }

    /**
     * Repairs every answered question, regardless of the legacy enqueue flag.
     * This is safe to call before restoring the in-memory history.
     */
    suspend fun restoreAnsweredQuestionMessages(
        context: Context,
        conversationId: Long
    ): List<AgentMessageEntity> = withContext(Dispatchers.IO) {
        restoreAnsweredQuestionMessages(
            AppDatabase.getDatabase(context.applicationContext),
            conversationId
        )
    }

    suspend fun restoreAnsweredQuestionMessages(
        database: AppDatabase,
        conversationId: Long
    ): List<AgentMessageEntity> = database.withTransaction {
        val workflow = database.agentWorkflowDao()
        val chat = database.agentChatDao()
        val repaired = mutableListOf<AgentMessageEntity>()
        for (answered in workflow.getAnsweredQuestions(conversationId)) {
            val answerJson = answered.answerJson ?: continue
            val expectedId = answered.answerMessageOriginalId
                ?.takeIf { it.isNotBlank() }
                ?: questionAnswerMessageId(answered.id)
            val existing = chat.getMessageByOriginalId(expectedId)
                ?: chat.getToolMessage(conversationId, QUESTION_TOOL, answered.toolCallId)
            val message = if (existing != null) {
                existing
            } else {
                AgentMessageEntity(
                    originalId = expectedId,
                    conversationId = conversationId,
                    role = "tool",
                    content = canonicalQuestionToolResult(answerJson),
                    toolName = QUESTION_TOOL,
                    toolCallId = answered.toolCallId,
                    toolOutput = answerJson,
                    timestamp = answered.answeredAt ?: answered.createdAt,
                    sequenceNumber = chat.getMaxMessageSequence(conversationId) + 1
                ).also { chat.insertMessage(it) }
            }
            if (answered.answerMessageOriginalId != message.originalId) {
                workflow.attachQuestionAnswerReceipt(
                    id = answered.id,
                    answerMessageOriginalId = message.originalId,
                    answerReceiptId = answered.answerReceiptId
                        ?: questionAnswerReceiptId(answered.id)
                )
            }
            ensureLegacyQuestionReceipt(database, answered, message)
            repaired += message
        }
        repaired
    }

    suspend fun claimContinuation(
        context: Context,
        receiptId: String
    ): AgentContinuationOutboxEntity? = withContext(Dispatchers.IO) {
        claimContinuation(AppDatabase.getDatabase(context.applicationContext), receiptId)
    }

    suspend fun claimContinuation(
        database: AppDatabase,
        receiptId: String
    ): AgentContinuationOutboxEntity? = database.withTransaction {
        val dao = database.agentWorkflowDao()
        val current = dao.getContinuationReceiptById(receiptId) ?: return@withTransaction null
        // A CLAIMED row belongs to another drain attempt. Returning it here
        // would let two callers dispatch the same continuation concurrently.
        if (current.status == AgentContinuationStatus.CLAIMED) return@withTransaction null
        if (current.status != AgentContinuationStatus.QUEUED) return@withTransaction null
        if (dao.claimContinuation(receiptId) != 1) return@withTransaction null
        dao.getContinuationReceiptById(receiptId)
    }

    suspend fun completeContinuation(
        context: Context,
        receiptId: String,
        status: String = AgentContinuationStatus.ENQUEUED,
        errorClass: String? = null,
        errorMessage: String? = null
    ): AgentContinuationOutboxEntity? = withContext(Dispatchers.IO) {
        completeContinuation(
            AppDatabase.getDatabase(context.applicationContext),
            receiptId,
            status,
            errorClass,
            errorMessage
        )
    }

    suspend fun completeContinuation(
        database: AppDatabase,
        receiptId: String,
        status: String = AgentContinuationStatus.ENQUEUED,
        errorClass: String? = null,
        errorMessage: String? = null
    ): AgentContinuationOutboxEntity? = database.withTransaction {
        require(status in setOf(
            AgentContinuationStatus.ENQUEUED,
            AgentContinuationStatus.COMPLETED,
            AgentContinuationStatus.FAILED,
            AgentContinuationStatus.CANCELLED
        )) { "Unsupported continuation completion status: $status" }
        val dao = database.agentWorkflowDao()
        val current = dao.getContinuationReceiptById(receiptId)
            ?: return@withTransaction null
        if (current.status == status) return@withTransaction current
        dao.finishContinuation(
            id = receiptId,
            status = status,
            enqueuedAt = if (status == AgentContinuationStatus.ENQUEUED) {
                System.currentTimeMillis()
            } else {
                null
            },
            completedAt = if (status in setOf(
                    AgentContinuationStatus.COMPLETED,
                    AgentContinuationStatus.FAILED,
                    AgentContinuationStatus.CANCELLED
                )
            ) {
                System.currentTimeMillis()
            } else {
                null
            },
            errorClass = errorClass,
            errorMessage = errorMessage
        )
        dao.getContinuationReceiptById(receiptId)
    }

    /** Stop fence for all durable user-input and continuation work. */
    suspend fun cancelConversationWork(
        context: Context,
        conversationId: Long,
        reason: String? = null
    ): CancelConversationWorkResult = withContext(Dispatchers.IO) {
        cancelConversationWork(AppDatabase.getDatabase(context.applicationContext), conversationId, reason)
    }

    suspend fun cancelConversationWork(
        database: AppDatabase,
        conversationId: Long,
        reason: String? = null
    ): CancelConversationWorkResult = database.withTransaction {
        val workflow = database.agentWorkflowDao()
        val questions = workflow.cancelPendingQuestions(conversationId)
        val inputs = workflow.cancelConversationPendingInputs(conversationId)
        val continuations = workflow.cancelConversationContinuations(conversationId)
        database.agentChatDao().cancelPendingToolApprovals(conversationId)
        database.agentChatDao().updateResumeState(
            conversationId,
            resumeState = "STOPPED_BY_USER",
            reason = reason?.trim()?.takeIf { it.isNotBlank() }?.take(1_000)
                ?: "Stopped by user."
        )
        CancelConversationWorkResult(questions, inputs, continuations)
    }

    /**
     * Commits invocation terminal state, report, optional parent result, and
     * optional continuation receipt in one Room transaction. Existing terminal
     * rows are returned idempotently rather than duplicated.
     */
    suspend fun completeInvocationAtomically(
        context: Context,
        invocationId: String,
        report: AgentWorkReportEntity,
        terminalStatus: String,
        resultSummary: String? = null,
        errorClass: String? = null,
        errorMessage: String? = null,
        parentMessage: AgentMessageEntity? = null,
        continuation: ContinuationReceiptSpec? = null
    ): InvocationCompletionTransactionResult = withContext(Dispatchers.IO) {
        completeInvocationAtomically(
            AppDatabase.getDatabase(context.applicationContext),
            invocationId,
            report,
            terminalStatus,
            resultSummary,
            errorClass,
            errorMessage,
            parentMessage,
            continuation
        )
    }

    suspend fun completeInvocationAtomically(
        database: AppDatabase,
        invocationId: String,
        report: AgentWorkReportEntity,
        terminalStatus: String,
        resultSummary: String? = null,
        errorClass: String? = null,
        errorMessage: String? = null,
        parentMessage: AgentMessageEntity? = null,
        continuation: ContinuationReceiptSpec? = null
    ): InvocationCompletionTransactionResult = database.withTransaction {
        val workflow = database.agentWorkflowDao()
        val chat = database.agentChatDao()
        val before = workflow.getInvocation(invocationId)
            ?: error("Invocation no longer exists: $invocationId")
        val storedReport = before.workReportId?.let { workflow.getWorkReport(it) } ?: run {
            workflow.upsertWorkReport(report)
            workflow.attachInvocationWorkReport(invocationId, report.id)
            workflow.getWorkReport(report.id) ?: report
        }
        if (before.status.equals("RUNNING", ignoreCase = true)) {
            workflow.finishInvocationExactlyOnce(
                id = invocationId,
                status = terminalStatus,
                resultSummary = resultSummary ?: storedReport.summary,
                errorClass = errorClass,
                errorMessage = errorMessage
            )
        }
        val durableParent = parentMessage?.let { candidate ->
            chat.getMessageByOriginalId(candidate.originalId)
                ?: candidate.also { chat.insertMessage(it) }
        }
        val durableReceipt = continuation?.let { spec ->
            workflow.insertContinuationReceipt(
                AgentContinuationOutboxEntity(
                    id = spec.id,
                    conversationId = spec.conversationId,
                    rootTurnId = spec.rootTurnId,
                    kind = spec.kind,
                    dedupeKey = spec.dedupeKey,
                    payloadJson = spec.payloadJson,
                    status = spec.status,
                    createdAt = spec.createdAt,
                    updatedAt = spec.createdAt
                )
            )
            workflow.getContinuationReceipt(spec.conversationId, spec.dedupeKey)
        }
        InvocationCompletionTransactionResult(
            invocation = workflow.getInvocation(invocationId)
                ?: error("Invocation disappeared after completion"),
            report = storedReport,
            parentMessage = durableParent,
            receipt = durableReceipt
        )
    }

    private suspend fun recordCanonicalToolOutcomeInTransaction(
        database: AppDatabase,
        conversationId: Long,
        stableOutcomeId: String,
        toolName: String,
        toolCallId: String?,
        content: String,
        toolOutput: String?,
        rootTurnId: String?,
        continuation: ContinuationReceiptSpec?
    ): CanonicalToolOutcomeTransactionResult {
        require(conversationId > 0L) { "A conversation ID is required" }
        val stableId = stableOutcomeId.trim()
        require(stableId.isNotBlank()) { "A stable tool outcome ID is required" }
        val normalizedTool = toolName.trim()
        require(normalizedTool.isNotBlank()) { "A tool name is required" }
        require(content.isNotBlank()) { "A canonical tool outcome cannot be blank" }
        continuation?.let {
            require(it.conversationId == conversationId) {
                "Continuation belongs to a different conversation"
            }
            require(it.id.isNotBlank()) { "A continuation ID is required" }
            require(it.kind.isNotBlank()) { "A continuation kind is required" }
            require(it.dedupeKey.isNotBlank()) { "A continuation dedupe key is required" }
        }

        val chat = database.agentChatDao()
        val workflow = database.agentWorkflowDao()
        val messageId = canonicalToolOutcomeMessageId(stableId)
        var message = chat.getMessageByOriginalId(messageId)
        if (message != null) {
            require(message.conversationId == conversationId) {
                "Tool outcome ID belongs to a different conversation"
            }
        }
        val messageInserted = message == null
        if (message == null) {
            val candidate = AgentMessageEntity(
                originalId = messageId,
                conversationId = conversationId,
                role = "tool",
                content = content,
                toolName = normalizedTool,
                toolCallId = toolCallId?.trim()?.takeIf { it.isNotBlank() },
                toolOutput = toolOutput,
                timestamp = System.currentTimeMillis(),
                sequenceNumber = chat.getMaxMessageSequence(conversationId) + 1
            )
            chat.insertMessage(candidate)
            message = chat.getMessageByOriginalId(messageId)
                ?: error("Canonical tool outcome could not be persisted")
        }

        var receipt: AgentContinuationOutboxEntity? = null
        var receiptInserted = false
        continuation?.let { spec ->
            receipt = workflow.getContinuationReceipt(conversationId, spec.dedupeKey)
                ?: workflow.getContinuationReceiptById(spec.id)
            receipt?.let {
                require(it.conversationId == conversationId) {
                    "Continuation ID belongs to a different conversation"
                }
            }
            if (receipt == null) {
                val now = spec.createdAt
                val candidate = AgentContinuationOutboxEntity(
                    id = spec.id,
                    conversationId = conversationId,
                    rootTurnId = spec.rootTurnId ?: rootTurnId,
                    kind = spec.kind,
                    dedupeKey = spec.dedupeKey,
                    payloadJson = spec.payloadJson,
                    status = spec.status,
                    createdAt = now,
                    updatedAt = now
                )
                receiptInserted = workflow.insertContinuationReceipt(candidate) > 0L
                receipt = workflow.getContinuationReceipt(conversationId, spec.dedupeKey)
                    ?: workflow.getContinuationReceiptById(spec.id)
                    ?: error("Canonical tool continuation could not be persisted")
            }
        }
        return CanonicalToolOutcomeTransactionResult(
            message = message ?: error("Canonical tool outcome disappeared"),
            receipt = receipt,
            messageInserted = messageInserted,
            receiptInserted = receiptInserted
        )
    }

    internal fun questionAnswerMessageId(questionId: String): String =
        QUESTION_ANSWER_PREFIX + questionId

    internal fun questionAnswerReceiptId(questionId: String): String =
        QUESTION_RECEIPT_PREFIX + questionId

    internal fun canonicalQuestionToolResult(answerJson: String): String = buildString {
        appendLine("status: ok")
        appendLine("tool: question")
        appendLine("summary: The user answered the structured question with authoritative requirements.")
        appendLine("important_output:")
        appendLine(answerJson)
        appendLine("next_hint: Treat this answer as critical user requirements. Follow it unless the user later explicitly changes it; do not ask the same question again.")
    }.trim()

    internal fun normalizeAgentReportOutcome(
        result: AgentResult,
        rawSummary: String
    ): AgentReportOutcome {
        val outcome = normalizeAgentReportOutcome(result.status, rawSummary)
        if (outcome.status != "SUCCESS") return outcome
        val missingEvidence = when (result) {
            is AgentResult.ResearcherResult -> result.facts.isEmpty() || result.sources.none { it.contains(Regex("https?://")) }
            is AgentResult.CoderResult -> result.changedFiles.isEmpty() || result.verificationReads.isEmpty()
            is AgentResult.PlannerResult -> result.planMarkdown.isBlank()
            is AgentResult.GenericResult -> result.summary.isBlank() || result.summary.trim().lowercase().trimEnd('.') in setOf("done", "success", "completed", "task completed", "completed successfully")
            else -> false
        }
        return if (missingEvidence) outcome.copy(status = "FAILED", errorClass = "REPORT_EVIDENCE_REQUIRED",
            errorMessage = "Successful reports require concrete findings/artifacts and sources or validation evidence.", requiresUserDirection = true) else outcome
    }

    internal fun normalizeAgentReportOutcome(
        status: String,
        rawSummary: String
    ): AgentReportOutcome {
        val normalized = when (status.trim().uppercase()) {
            "SUCCESS", "PASSED", "PASS", "COMPLETED" -> "SUCCESS"
            "BLOCKED" -> "BLOCKED"
            "CANCELLED", "CANCELED" -> "CANCELLED"
            "INTERRUPTED" -> "INTERRUPTED"
            else -> "FAILED"
        }
        val suppliedSummary = rawSummary.trim()
        // An empty SUCCESS has no durable evidence for the parent to act on;
        // classify it as a failure rather than manufacturing a successful
        // report that can trigger another continuation.
        val effectiveStatus = if (normalized == "SUCCESS" && suppliedSummary.isBlank()) {
            "FAILED"
        } else {
            normalized
        }
        val summary = suppliedSummary.take(4_000).ifBlank {
            when (effectiveStatus) {
                "BLOCKED" -> "Specialist is blocked and needs a decision."
                "CANCELLED" -> "Specialist work was cancelled."
                "INTERRUPTED" -> "Specialist work was interrupted."
                "FAILED" -> if (normalized == "SUCCESS") {
                    "Specialist returned an empty success report."
                } else {
                    "Specialist failed without a report."
                }
                else -> "Specialist completed successfully."
            }
        }
        val errorClass = when (effectiveStatus) {
            "SUCCESS" -> null
            "BLOCKED" -> "AgentBlocked"
            "CANCELLED" -> "CancellationException"
            "INTERRUPTED" -> "AgentInterrupted"
            else -> if (normalized == "SUCCESS") "AgentEmptyReport" else "AgentFailed"
        }
        return AgentReportOutcome(
            status = effectiveStatus,
            summary = summary,
            errorClass = errorClass,
            errorMessage = if (effectiveStatus == "SUCCESS") null else summary,
            requiresUserDirection = effectiveStatus in setOf("BLOCKED", "INTERRUPTED")
        )
    }

    private fun researchLimit(tool: String): Int? = when (tool) {
        "web_search", "kiwix_search" -> MAX_RESEARCH_SEARCHES
        "fetch_url" -> MAX_RESEARCH_FETCHES
        else -> null
    }

    private fun actionReceiptDedupeKey(
        planningEpisodeId: String,
        tool: String,
        actionId: String
    ): String = "action:${receiptKeyComponent(planningEpisodeId)}:" +
        "${receiptKeyComponent(tool)}:${receiptKeyComponent(actionId)}"

    private fun researchAdmissionPrefix(
        planningEpisodeId: String,
        tool: String
    ): String = "research:${receiptKeyComponent(planningEpisodeId)}:" +
        "${receiptKeyComponent(tool)}:"

    private fun researchBudgetFailureDedupeKey(
        conversationId: Long,
        planningEpisodeId: String
    ): String = "research-budget-failure:" + receiptKeyComponent(
        "$conversationId|$planningEpisodeId"
    )

    private fun canonicalToolOutcomeMessageId(stableOutcomeId: String): String =
        TOOL_OUTCOME_PREFIX + stableOutcomeId.trim()

    private fun receiptKeyComponent(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun actionReceiptStatus(status: String): String = when (
        status.trim().uppercase()
    ) {
        "CANCELLED", "CANCELED" -> AgentContinuationStatus.CANCELLED
        "FAILED", "ERROR", "REJECTED", "BLOCKED" -> AgentContinuationStatus.FAILED
        "QUEUED", "STARTED", "RUNNING", "ENQUEUED" -> AgentContinuationStatus.ENQUEUED
        else -> AgentContinuationStatus.COMPLETED
    }

    private fun actionReceiptPayload(
        planningEpisodeId: String,
        tool: String,
        actionId: String,
        status: String,
        rootTurnId: String?,
        evidenceFingerprint: String?,
        metadataJson: String
    ): String = JSONObject()
        .put("receipt_type", "action")
        .put("planning_episode_id", planningEpisodeId)
        .put("tool", tool)
        .put("action_id", actionId)
        .put("status", status.trim())
        .put("root_turn_id", rootTurnId ?: JSONObject.NULL)
        .put("evidence_fingerprint", evidenceFingerprint ?: JSONObject.NULL)
        // Preserve the caller's exact metadata bytes even if it is not JSON.
        .put("metadata_json", metadataJson)
        .toString()

    private fun researchAdmissionPayload(
        planningEpisodeId: String,
        tool: String,
        rootTurnId: String?,
        countBefore: Int,
        limit: Int,
        blockerReason: String?
    ): String = JSONObject()
        .put("receipt_type", "research_admission")
        .put("planning_episode_id", planningEpisodeId)
        .put("tool", tool)
        .put("root_turn_id", rootTurnId ?: JSONObject.NULL)
        .put("count_before", countBefore)
        .put("limit", limit)
        .put("blocker_reason", blockerReason ?: JSONObject.NULL)
        .toString()

    private suspend fun ensureLegacyQuestionReceipt(
        database: AppDatabase,
        question: AgentPendingQuestionEntity,
        message: AgentMessageEntity
    ) {
        val workflow = database.agentWorkflowDao()
        val receiptId = question.answerReceiptId
            ?.takeIf { it.isNotBlank() }
            ?: questionAnswerReceiptId(question.id)
        workflow.insertContinuationReceipt(
            AgentContinuationOutboxEntity(
                id = receiptId,
                conversationId = question.conversationId,
                rootTurnId = question.rootTurnId,
                kind = QUESTION_RECEIPT_KIND,
                dedupeKey = "question:${question.id}",
                payloadJson = JSONObject()
                    .put("question_id", question.id)
                    .put("message_id", message.originalId)
                    .put("source", "answered_question_restore")
                    .toString(),
                status = if (question.continuationEnqueued) {
                    AgentContinuationStatus.ENQUEUED
                } else {
                    AgentContinuationStatus.QUEUED
                },
                createdAt = question.answeredAt ?: question.createdAt,
                updatedAt = question.answeredAt ?: question.createdAt
            )
        )
    }

    private fun answerItems(answerJson: String): List<JSONObject> = runCatching {
        val array = JSONObject(answerJson).optJSONArray("answers") ?: JSONArray()
        buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

    private fun decisionProvenance(
        supplied: String,
        questionId: String,
        rootTurnId: String,
        itemId: String
    ): String {
        val base = runCatching { JSONObject(supplied) }.getOrElse { JSONObject() }
        base.put("question_id", questionId)
        base.put("root_turn_id", rootTurnId)
        base.put("item_id", itemId)
        base.put("source", base.optString("source").ifBlank { "structured_question" })
        return base.toString()
    }

    private fun continuationPayload(
        supplied: String,
        questionId: String,
        messageId: String,
        rootTurnId: String
    ): String {
        val payload = runCatching { JSONObject(supplied) }.getOrElse { JSONObject() }
        payload.put("question_id", questionId)
        payload.put("answer_message_id", messageId)
        payload.put("root_turn_id", rootTurnId)
        return payload.toString()
    }

    private suspend fun migrateLegacyUserCorrectionsInTransaction(
        database: AppDatabase,
        conversationId: Long
    ): AgentUserCorrectionMigrationResult {
        val workflow = database.agentWorkflowDao()
        val legacyRows = workflow.getDecisionsForKey(
            conversationId,
            LEGACY_USER_CORRECTION_DECISION_KEY
        ).filter { it.questionId.isNullOrBlank() }
        if (legacyRows.isEmpty()) {
            return AgentUserCorrectionMigrationResult(
                migratedCount = 0,
                collisionCount = 0
            )
        }

        var migratedCount = 0
        var collisionCount = 0
        val now = System.currentTimeMillis()
        legacyRows.forEach { legacy ->
            val messageId = userCorrectionMessageId(legacy)
            val canonicalKey = userCorrectionDecisionKey(messageId)
            val canonicalRows = workflow.getDecisionsForKey(conversationId, canonicalKey)
            val replacementKey = when {
                canonicalRows.isEmpty() -> canonicalKey
                canonicalRows.any { it.id == legacy.id } -> return@forEach
                else -> {
                    // A malformed legacy retry can contain the same message id
                    // more than once. Keep both exact durable values instead
                    // of replacing either one; the normal path still gets the
                    // deterministic per-message key.
                    collisionCount++
                    "$canonicalKey:legacy:${receiptKeyComponent(legacy.id)}"
                }
            }
            // Room has no DAO update for this one-time repair and the entity
            // schema must remain unchanged. This SQL runs inside Room's
            // transaction and preserves every payload column verbatim.
            database.openHelper.writableDatabase.execSQL(
                """
                UPDATE agent_decisions
                SET decisionKey = ?,
                    isLatest = 1,
                    supersedesDecisionId = NULL,
                    latestCorrectionId = NULL,
                    updatedAt = ?
                WHERE id = ? AND conversationId = ?
                """.trimIndent(),
                arrayOf(replacementKey, now, legacy.id, conversationId)
            )
            migratedCount++
        }
        return AgentUserCorrectionMigrationResult(
            migratedCount = migratedCount,
            collisionCount = collisionCount
        )
    }

    private fun requireUserCorrectionMessageId(messageId: String): String =
        messageId.trim().also {
            require(it.isNotBlank()) { "A user correction requires a stable message id" }
        }

    private fun userCorrectionMessageId(decision: AgentDecisionEntity): String {
        val payloadMessageId = runCatching {
            JSONObject(decision.answerJson).optString("message_id")
        }.getOrNull().orEmpty().trim()
        if (payloadMessageId.isNotBlank()) return payloadMessageId
        val provenanceMessageId = runCatching {
            JSONObject(decision.provenanceJson).optString("message_id")
        }.getOrNull().orEmpty().trim()
        if (provenanceMessageId.isNotBlank()) return provenanceMessageId
        return decision.id.removePrefix("user-correction:").ifBlank { decision.id }
    }

    private fun userCorrectionContent(decision: AgentDecisionEntity): String {
        // customAnswer is the exact free-form value in the current writer;
        // preserve an intentional empty string rather than falling through.
        decision.customAnswer?.let { return it }
        val payloadContent = runCatching {
            JSONObject(decision.answerJson).optString("content")
        }.getOrNull()
        if (!payloadContent.isNullOrEmpty()) return payloadContent
        return decision.submittedAnswerJson
    }

    private fun questionDecisionKey(questionId: String, itemId: String? = null): String =
        if (itemId.isNullOrBlank()) "question:$questionId" else "question:$questionId:$itemId"

    private fun String?.cleanContractText(): String = this?.trim().orEmpty()
}
