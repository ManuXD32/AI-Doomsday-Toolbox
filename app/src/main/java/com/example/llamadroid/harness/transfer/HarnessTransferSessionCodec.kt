package com.example.llamadroid.harness.transfer

import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdOutputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The DSH persistence package's current physical generation. */
internal object HarnessTransferSessionCodec {
    // The wire bytes are 28 b5 2f fd; readFrame compares their little-endian integer value.
    private const val ZSTD_MAGIC = 0xFD2FB528L
    private const val SESSION_VERSION = 3
    private const val SESSION_LOG_NAME = "session.v3.jsonl.zstd"
    private const val MAX_HEADER_FRAME_BYTES = 16L * 1024L * 1024L
    private const val MAX_FRAME_BYTES = 128L * 1024L * 1024L
    private const val MAX_PLAINTEXT_FRAME_BYTES = 256L * 1024L * 1024L
    private const val MAX_SESSION_ID_CHARS = 4096
    private const val SCHEDULE_CHANGE_VERSION = 1L
    private const val MIN_EVERY_INTERVAL_SECONDS = 300L
    private val SCHEDULE_INSTANT = Regex(
        """^(?!0000)\d{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\d|3[01])T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d\.\d{3}Z$""",
    )
    private val SCHEDULE_INSTANT_FORMATTER = DateTimeFormatter
        .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(ZoneOffset.UTC)
    private val SCHEDULE_MIN_INSTANT = Instant.parse("0001-01-01T00:00:00.000Z")
    private val SCHEDULE_MAX_INSTANT = Instant.parse("9999-12-31T23:59:59.999Z")
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

    /** Structural configuration references; event text and tool arguments use different keys. */
    private val REFERENCE_KEYS = setOf(
        "model", "modelId", "model_id", "modelRef", "model_ref", "provider", "providerId",
        "provider_id", "providerRef", "provider_ref", "profile", "profileId", "profile_id",
        "credentialRef", "credential_ref", "llmModel", "llmProvider",
    )
    private val ATTACHMENT_DIGEST = Regex("^sha256:([0-9a-f]{64})$")

    data class Header(
        val id: String,
        val cwd: String?,
        val json: JSONObject,
    )

    fun isSessionPath(path: String): Boolean = path.endsWith("/sessions/") ||
        Regex("^dsh_home/sessions/[^/]+/[^/]+/$SESSION_LOG_NAME$").matches(path)

    fun readHeader(input: InputStream, cancellation: TransferCancellation = TransferCancellation.NONE): Header {
        val frame = readFrame(input, MAX_HEADER_FRAME_BYTES, cancellation)
            ?: throw IllegalArgumentException("TRANSFER_SESSION_HEADER_MISSING")
        cancellation.check(TransferWorkPhase.VALIDATE)
        val plaintext = decodeFrame(frame, MAX_PLAINTEXT_FRAME_BYTES, cancellation)
        val line = requireHeaderLine(plaintext)
        return parseHeader(line)
    }

    fun validate(
        input: InputStream,
        cancellation: TransferCancellation = TransferCancellation.NONE,
    ): Header {
        val first = readFrame(input, MAX_HEADER_FRAME_BYTES, cancellation)
            ?: throw IllegalArgumentException("TRANSFER_SESSION_HEADER_MISSING")
        cancellation.check(TransferWorkPhase.VALIDATE)
        val header = parseHeader(requireHeaderLine(decodeFrame(first, MAX_PLAINTEXT_FRAME_BYTES, cancellation)))
        var frames = 1
        while (true) {
            cancellation.check(TransferWorkPhase.VALIDATE)
            val frame = readFrame(input, MAX_FRAME_BYTES, cancellation) ?: break
            val plaintext = decodeFrame(frame, MAX_PLAINTEXT_FRAME_BYTES, cancellation)
            validateEventFrame(plaintext, cancellation)
            frames += 1
            require(frames <= 1_000_000) { "TRANSFER_SESSION_FRAME_COUNT_EXCEEDED" }
        }
        return header
    }

    /**
     * Rewrites the header and every known ID-bearing field while preserving the
     * DSH invariant that frame one is header-only and every following frame is
     * an independently decodable Zstandard frame.
     */
    fun rewrite(
        input: InputStream,
        output: OutputStream,
        sessionIds: Map<String, String>,
        workspacePathMapper: (String) -> String? = { null },
        referenceMapper: (key: String, value: String) -> String? = { _, _ -> null },
        cancellation: TransferCancellation = TransferCancellation.NONE,
    ): Header {
        val firstFrame = readFrame(input, MAX_HEADER_FRAME_BYTES, cancellation)
            ?: throw IllegalArgumentException("TRANSFER_SESSION_HEADER_MISSING")
        cancellation.check(TransferWorkPhase.STAGE)
        val header = parseHeader(requireHeaderLine(decodeFrame(firstFrame, MAX_PLAINTEXT_FRAME_BYTES, cancellation)))
        val rewrittenHeader = rewriteHeader(header.json, sessionIds, workspacePathMapper)
        writeFrame(output, (rewrittenHeader.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
        val inbox = DurableInboxState()
        val teamMailbox = DurableTeamMailboxState()
        val schedules = DurableScheduleState(header.json.optBoolean("isSeeded", false))
        var nextSequence = 0L
        var sawSequence = false
        var lastTime = 0L

        while (true) {
            cancellation.check(TransferWorkPhase.STAGE)
            val frame = readFrame(input, MAX_FRAME_BYTES, cancellation) ?: break
            val plaintext = decodeFrame(frame, MAX_PLAINTEXT_FRAME_BYTES, cancellation)
            val rewritten = rewriteEventFrame(plaintext, sessionIds, workspacePathMapper, referenceMapper, cancellation)
            writeFrame(output, rewritten)
            rewritten.toString(StandardCharsets.UTF_8).dropLast(1).split('\n').forEach { line ->
                cancellation.check(TransferWorkPhase.STAGE)
                val event = JSONObject(line)
                inbox.observe(event)
                teamMailbox.observe(event)
                schedules.observe(event)
                val sequence = event.opt("seq")
                if (sequence is Number) {
                    val value = sequence.toLong()
                    require(value >= 0L && value == sequence.toDouble().toLong()) {
                        "TRANSFER_SESSION_SEQUENCE_INVALID"
                    }
                    nextSequence = maxOf(nextSequence, Math.addExact(value, 1L))
                    sawSequence = true
                }
                val time = event.opt("time")
                if (time is Number) lastTime = time.toLong().coerceAtLeast(0L)
            }
        }
        schedules.finish()
        val terminal = ArrayList<JSONObject>()
        if (sawSequence) {
            val inboxEvents = inbox.clearEvents(nextSequence, lastTime)
            terminal += inboxEvents
            nextSequence += inboxEvents.size
            val teamEvents = teamMailbox.clearEvents(nextSequence, lastTime)
            terminal += teamEvents
            nextSequence += teamEvents.size
            val scheduleEvents = schedules.clearEvents(nextSequence, lastTime)
            terminal += scheduleEvents
        } else {
            require(!inbox.hasPending && !teamMailbox.hasPending && !schedules.hasPending) {
                "TRANSFER_SESSION_QUEUE_SEQUENCE_MISSING"
            }
        }
        if (terminal.isNotEmpty()) {
            cancellation.check(TransferWorkPhase.STAGE)
            writeFrame(
                output,
                (terminal.joinToString(separator = "", transform = { it.toString() + "\n" }))
                    .toByteArray(StandardCharsets.UTF_8),
            )
        }
        return parseHeader(rewrittenHeader.toString())
    }

    fun encodeSegment(raw: String): String {
        require(raw.isNotEmpty() && raw.length <= MAX_SESSION_ID_CHARS) { "TRANSFER_SESSION_ID_INVALID" }
        if (raw == ".") return "~002E"
        if (raw == "..") return "~002E~002E"
        val result = StringBuilder(raw.length)
        raw.forEach { ch ->
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '.' || ch == '_' || ch == '-') {
                result.append(ch)
            } else {
                result.append('~').append(ch.code.toString(16).uppercase().padStart(4, '0'))
            }
        }
        return result.toString()
    }

    fun decodeSegment(encoded: String): String {
        require(encoded.isNotEmpty()) { "TRANSFER_SESSION_PATH_INVALID" }
        if (encoded == "~002E") return "."
        if (encoded == "~002E~002E") return ".."
        val result = StringBuilder(encoded.length)
        var index = 0
        while (index < encoded.length) {
            if (encoded[index] != '~') {
                result.append(encoded[index++])
            } else {
                require(index + 5 <= encoded.length) { "TRANSFER_SESSION_PATH_INVALID" }
                val code = encoded.substring(index + 1, index + 5).toIntOrNull(16)
                    ?: throw IllegalArgumentException("TRANSFER_SESSION_PATH_INVALID")
                result.append(code.toChar())
                index += 5
            }
        }
        return result.toString()
    }

    fun projectKey(cwd: String): String {
        require(cwd.isNotEmpty()) { "TRANSFER_SESSION_CWD_INVALID" }
        val readable = StringBuilder()
        var separatorRun = false
        cwd.forEach { ch ->
            if (ch == '/' || ch == '\\' || ch == ':') {
                if (!separatorRun) readable.append('-')
                separatorRun = true
            } else if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '.' || ch == '_' || ch == '-') {
                readable.append(ch)
                separatorRun = false
            } else {
                readable.append('~').append(ch.code.toString(16).uppercase().padStart(4, '0'))
                separatorRun = false
            }
        }
        val trimmed = readable.toString().trimStart('-').ifEmpty { "root" }
        return "--${trimmed.take(251)}--"
    }

    fun sessionDescriptor(
        input: InputStream,
        archivePath: String,
        projectKey: String,
        cancellation: TransferCancellation = TransferCancellation.NONE,
    ): TransferSession {
        val header = validate(input, cancellation)
        val parts = archivePath.split('/')
        require(parts.size == 5 && parts[0] == "dsh_home" && parts[1] == "sessions" &&
            parts[2] == projectKey && parts[4] == SESSION_LOG_NAME) {
            "TRANSFER_SESSION_PATH_INVALID"
        }
        require(parts[3] == encodeSegment(header.id)) { "TRANSFER_SESSION_ID_PATH_MISMATCH" }
        if (header.cwd != null && projectKey.startsWith("--")) {
            require(projectKey == projectKey(header.cwd)) { "TRANSFER_SESSION_PROJECT_PATH_MISMATCH" }
        }
        return TransferSession(header.id, header.cwd, archivePath, projectKey)
    }

    private fun parseHeader(line: String): Header {
        val json = try {
            JSONObject(line)
        } catch (error: Throwable) {
            throw IllegalArgumentException("TRANSFER_SESSION_HEADER_INVALID", error)
        }
        require(json.optString("type") == "session") { "TRANSFER_SESSION_HEADER_INVALID" }
        require(json.optInt("version", -1) == SESSION_VERSION) { "TRANSFER_SESSION_VERSION_UNSUPPORTED" }
        val id = json.optString("id")
        require(id.isNotEmpty() && id.length <= MAX_SESSION_ID_CHARS && '\u0000' !in id) {
            "TRANSFER_SESSION_ID_INVALID"
        }
        require(json.has("createdAt") && json.optLong("createdAt", -1L) >= 0L) {
            "TRANSFER_SESSION_HEADER_INVALID"
        }
        require(json.has("isSeeded") && (json.opt("isSeeded") is Boolean)) {
            "TRANSFER_SESSION_HEADER_INVALID"
        }
        require(json.has("delegationDepth") && json.optLong("delegationDepth", -1L) >= 0L) {
            "TRANSFER_SESSION_HEADER_INVALID"
        }
        require(!json.has("sandboxMode") && !json.has("approvalPolicy")) {
            "TRANSFER_SESSION_RETIRED_POLICY_FIELDS"
        }
        val cwd = json.optString("cwd", "").takeIf { it.isNotEmpty() }
        if (cwd != null) require(cwd.startsWith('/')) { "TRANSFER_SESSION_CWD_INVALID" }
        return Header(id, cwd, json)
    }

    private fun requireHeaderLine(bytes: ByteArray): String {
        val text = bytes.toString(StandardCharsets.UTF_8)
        require(text.endsWith("\n") && text.dropLast(1).isNotBlank() &&
            '\n' !in text.dropLast(1) && '\r' !in text.dropLast(1)) {
            "TRANSFER_SESSION_HEADER_FRAME_INVALID"
        }
        return text.dropLast(1)
    }

    private fun validateEventFrame(
        bytes: ByteArray,
        cancellation: TransferCancellation = TransferCancellation.NONE,
    ) {
        val text = bytes.toString(StandardCharsets.UTF_8)
        require(text.endsWith("\n") && '\r' !in text) { "TRANSFER_SESSION_EVENT_FRAME_INVALID" }
        text.dropLast(1).split('\n').forEach { line ->
            cancellation.check(TransferWorkPhase.VALIDATE)
            require(line.isNotBlank()) { "TRANSFER_SESSION_EVENT_FRAME_INVALID" }
            try {
                JSONObject(line)
            } catch (error: Throwable) {
                throw IllegalArgumentException("TRANSFER_SESSION_EVENT_INVALID", error)
            }
        }
    }

    private fun rewriteHeader(
        input: JSONObject,
        sessionIds: Map<String, String>,
        workspacePathMapper: (String) -> String?,
    ): JSONObject {
        val output = JSONObject(input.toString())
        rewriteSessionId(output, "id", sessionIds)
        rewriteSessionId(output, "parentSession", sessionIds)
        rewriteCwd(output, "cwd", workspacePathMapper)
        return output
    }

    private fun rewriteEventFrame(
        bytes: ByteArray,
        sessionIds: Map<String, String>,
        workspacePathMapper: (String) -> String?,
        referenceMapper: (key: String, value: String) -> String?,
        cancellation: TransferCancellation,
    ): ByteArray {
        validateEventFrame(bytes, cancellation)
        val text = bytes.toString(StandardCharsets.UTF_8)
        val output = buildString(text.length + 32) {
            text.dropLast(1).split('\n').forEach { line ->
                cancellation.check(TransferWorkPhase.STAGE)
                append(rewriteEvent(JSONObject(line), sessionIds, workspacePathMapper, referenceMapper).toString())
                append('\n')
            }
        }
        return output.toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * Collect only references from the DSH session schema. This is deliberately separate from
     * generic JSON walking: user messages, tool arguments/results, and provider payloads can all
     * contain fields named `model`, `cwd`, or `sessionId` that are content rather than links.
     */
    fun collectReferences(
        input: InputStream,
        cancellation: TransferCancellation = TransferCancellation.NONE,
    ): Map<String, Set<String>> {
        val firstFrame = readFrame(input, MAX_HEADER_FRAME_BYTES, cancellation)
            ?: throw IllegalArgumentException("TRANSFER_SESSION_HEADER_MISSING")
        cancellation.check(TransferWorkPhase.VALIDATE)
        val header = parseHeader(requireHeaderLine(decodeFrame(firstFrame, MAX_PLAINTEXT_FRAME_BYTES, cancellation)))
        val collector = ReferenceCollector()
        collectHeaderReferences(header.json, collector)
        while (true) {
            cancellation.check(TransferWorkPhase.VALIDATE)
            val frame = readFrame(input, MAX_FRAME_BYTES, cancellation) ?: break
            val plaintext = decodeFrame(frame, MAX_PLAINTEXT_FRAME_BYTES, cancellation)
            validateEventFrame(plaintext, cancellation)
            val text = plaintext.toString(StandardCharsets.UTF_8)
            text.dropLast(1).split('\n').forEach { line ->
                collectEventReferences(JSONObject(line), collector)
            }
        }
        return collector.toMap()
    }

    /**
     * Collect content-addressed attachment digests from canonical message blocks only.
     *
     * DSH stores image/file bytes below `attachments/v1`, while session messages retain
     * `sha256:<hex>` references.  We intentionally inspect only the message content blocks
     * owned by the session schema; arbitrary tool arguments and user text can contain an
     * `attachmentId`-looking string and must not cause unrelated objects to be exported.
     */
    fun collectAttachmentDigests(
        input: InputStream,
        cancellation: TransferCancellation = TransferCancellation.NONE,
    ): Set<String> {
        val firstFrame = readFrame(input, MAX_HEADER_FRAME_BYTES, cancellation)
            ?: throw IllegalArgumentException("TRANSFER_SESSION_HEADER_MISSING")
        cancellation.check(TransferWorkPhase.VALIDATE)
        parseHeader(requireHeaderLine(decodeFrame(firstFrame, MAX_PLAINTEXT_FRAME_BYTES, cancellation)))
        val digests = linkedSetOf<String>()
        while (true) {
            cancellation.check(TransferWorkPhase.VALIDATE)
            val frame = readFrame(input, MAX_FRAME_BYTES, cancellation) ?: break
            val plaintext = decodeFrame(frame, MAX_PLAINTEXT_FRAME_BYTES, cancellation)
            validateEventFrame(plaintext, cancellation)
            plaintext.toString(StandardCharsets.UTF_8).dropLast(1).split('\n').forEach { line ->
                cancellation.check(TransferWorkPhase.VALIDATE)
                collectEventAttachmentDigests(JSONObject(line), digests)
            }
        }
        return digests
    }

    /** Collect known configuration keys from a DSH JSON settings document. */
    fun collectConfigurationReferences(json: String): Map<String, Set<String>> {
        val collector = ReferenceCollector()
        collectConfigurationJson(JSONObject(json), null, collector)
        return collector.toMap()
    }

    private fun rewriteEvent(
        event: JSONObject,
        sessionIds: Map<String, String>,
        workspacePathMapper: (String) -> String?,
        referenceMapper: (key: String, value: String) -> String?,
    ): JSONObject {
        val output = JSONObject(event.toString())
        val type = output.optString("type")
        val data = output.optJSONObject("data") ?: return output
        when (type) {
            "request/header" -> data.optJSONObject("header")?.optJSONObject("config")?.let {
                rewriteReference(it, "provider", referenceMapper)
                rewriteReference(it, "model", referenceMapper)
            }
            "request/context" -> {
                rewriteReference(data, "provider", referenceMapper)
                rewriteReference(data, "model", referenceMapper)
            }
            "assistant/message", "system/message", "user/message", "tool/result" ->
                data.optJSONObject("message")?.let {
                    rewriteMessage(it, sessionIds, referenceMapper)
                }
            "agent/inbox/spliced" -> data.optJSONArray("inserted")?.let { messages ->
                for (index in 0 until messages.length()) {
                    messages.optJSONObject(index)?.let { rewriteMessage(it, sessionIds, referenceMapper) }
                }
            }
            "model/selection" -> {
                rewriteReference(data, "provider", referenceMapper)
                rewriteReference(data, "model", referenceMapper)
            }
            "subagent/descriptor" -> {
                rewriteReference(data, "provider", referenceMapper)
                rewriteReference(data, "agentProvider", referenceMapper)
                rewriteReference(data, "agentModel", referenceMapper)
            }
            "subagent/catalog" -> rewriteSessionId(data, "childId", sessionIds)
            "team/member" -> data.optJSONObject("member")?.let {
                rewriteSessionId(it, "id", sessionIds)
                rewriteReference(it, "provider", referenceMapper)
            }
            "team/task" -> data.optJSONObject("task")?.let { rewriteSessionId(it, "ownerId", sessionIds) }
            "team/message/queued" -> {
                rewriteSessionId(data, "teamId", sessionIds)
                data.optJSONObject("message")?.let { message ->
                    rewriteSessionId(message, "senderId", sessionIds)
                    rewriteSessionId(message, "targetId", sessionIds)
                }
            }
            "team/message/delivered" -> {
                rewriteSessionId(data, "teamId", sessionIds)
                rewriteSessionId(data, "targetId", sessionIds)
            }
            "feedback/message-put", "feedback/message-delete" -> rewriteSessionId(data, "sessionId", sessionIds)
            "session/title" -> data.optJSONObject("source")?.let { source ->
                rewriteReference(source, "provider", referenceMapper)
                source.optJSONObject("model")?.let { model ->
                    rewriteReference(model, "provider", referenceMapper)
                    rewriteReference(model, "model", referenceMapper)
                }
            }
            "compaction/summary" -> {
                rewriteReference(data, "provider", referenceMapper)
                rewriteReference(data, "model", referenceMapper)
            }
            // These names are accepted by a few older host builds. Their structural envelope is
            // intentionally narrow; arbitrary nested payloads are never traversed.
            "fork", "session/fork", "delegation", "agent/delegation" ->
                rewriteDelegationEnvelope(data, sessionIds, workspacePathMapper, referenceMapper)
        }
        return output
    }

    private fun rewriteMessage(
        message: JSONObject,
        sessionIds: Map<String, String>,
        referenceMapper: (key: String, value: String) -> String?,
    ) {
        message.optJSONObject("source")?.let { source ->
            when (source.optString("kind")) {
                "model" -> {
                    rewriteReference(source, "provider", referenceMapper)
                    rewriteReference(source, "model", referenceMapper)
                }
                "agent-message", "subagent-settled" -> rewriteSessionId(source, "senderSessionId", sessionIds)
                "team-message" -> rewriteSessionId(source, "senderId", sessionIds)
                "session-reference" -> source.optJSONArray("references")?.let { references ->
                    for (index in 0 until references.length()) {
                        references.optJSONObject(index)?.let { rewriteSessionId(it, "sessionId", sessionIds) }
                    }
                }
                else -> Unit
            }
        }
    }

    private fun rewriteDelegationEnvelope(
        data: JSONObject,
        sessionIds: Map<String, String>,
        workspacePathMapper: (String) -> String?,
        referenceMapper: (key: String, value: String) -> String?,
    ) {
        listOf("sessionId", "parentSession", "parentSessionId", "childSessionId", "targetSessionId",
            "sourceSessionId", "senderSessionId", "agentId").forEach { rewriteSessionId(data, it, sessionIds) }
        rewriteCwd(data, "cwd", workspacePathMapper)
        listOf("provider", "providerId", "providerRef", "model", "modelId", "modelRef",
            "agentProvider", "agentModel").forEach { rewriteReference(data, it, referenceMapper) }
        listOf("child", "parent", "target", "source", "agent", "descriptor").forEach { key ->
            data.optJSONObject(key)?.let { rewriteDelegationEnvelope(it, sessionIds, workspacePathMapper, referenceMapper) }
        }
    }

    private fun collectEventReferences(event: JSONObject, collector: ReferenceCollector) {
        val type = event.optString("type")
        val data = event.optJSONObject("data") ?: return
        when (type) {
            "request/header" -> data.optJSONObject("header")?.optJSONObject("config")?.let {
                collectReference(it, "provider", collector)
                collectReference(it, "model", collector)
            }
            "request/context", "model/selection" -> {
                collectReference(data, "provider", collector)
                collectReference(data, "model", collector)
            }
            "assistant/message", "system/message", "user/message", "tool/result" ->
                data.optJSONObject("message")?.let { collectMessageReferences(it, collector) }
            "agent/inbox/spliced" -> data.optJSONArray("inserted")?.let { messages ->
                for (index in 0 until messages.length()) messages.optJSONObject(index)?.let {
                    collectMessageReferences(it, collector)
                }
            }
            "subagent/descriptor" -> {
                collectReference(data, "provider", collector)
                collectReference(data, "agentProvider", collector)
                collectReference(data, "agentModel", collector)
            }
            "team/member" -> data.optJSONObject("member")?.let { collectReference(it, "provider", collector) }
            "session/title" -> data.optJSONObject("source")?.let { source ->
                collectReference(source, "provider", collector)
                source.optJSONObject("model")?.let { model ->
                    collectReference(model, "provider", collector)
                    collectReference(model, "model", collector)
                }
            }
            "compaction/summary" -> {
                collectReference(data, "provider", collector)
                collectReference(data, "model", collector)
            }
            "fork", "session/fork", "delegation", "agent/delegation" -> collectDelegationReferences(data, collector)
        }
    }

    private fun collectHeaderReferences(header: JSONObject, collector: ReferenceCollector) {
        listOf("provider", "providerId", "providerRef", "model", "modelId", "modelRef",
            "profile", "profileId", "llmProvider", "llmModel").forEach {
            collectReference(header, it, collector)
        }
        header.optJSONObject("config")?.let { config ->
            collectReference(config, "provider", collector)
            collectReference(config, "model", collector)
        }
        header.optJSONObject("model")?.let { model ->
            collectReference(model, "provider", collector)
            collectReference(model, "model", collector)
            collectReference(model, "modelId", collector)
        }
    }

    private fun collectMessageReferences(message: JSONObject, collector: ReferenceCollector) {
        message.optJSONObject("source")?.let { source ->
            when (source.optString("kind")) {
                "model" -> {
                    collectReference(source, "provider", collector)
                    collectReference(source, "model", collector)
                }
            }
        }
    }

    private fun collectEventAttachmentDigests(event: JSONObject, digests: MutableSet<String>) {
        val data = event.optJSONObject("data") ?: return
        when (event.optString("type")) {
            "assistant/message", "system/message", "user/message", "tool/result" ->
                data.optJSONObject("message")?.let { collectMessageAttachmentDigests(it, digests) }
            "agent/inbox/spliced" -> data.optJSONArray("inserted")?.let { messages ->
                for (index in 0 until messages.length()) {
                    messages.optJSONObject(index)?.let { collectMessageAttachmentDigests(it, digests) }
                }
            }
            "team/message/queued" -> data.optJSONObject("message")?.let {
                collectMessageAttachmentDigests(it, digests)
            }
        }
    }

    /** Walk only DSH message content and nested tool-result content, never arbitrary payloads. */
    private fun collectMessageAttachmentDigests(message: JSONObject, digests: MutableSet<String>) {
        collectContentAttachmentDigests(message.optJSONArray("content"), digests)
    }

    private fun collectContentAttachmentDigests(blocks: JSONArray?, digests: MutableSet<String>) {
        if (blocks == null) return
        for (index in 0 until blocks.length()) {
            val block = blocks.optJSONObject(index) ?: continue
            when (block.optString("type")) {
                "image", "file" -> {
                    val raw = block.optJSONObject("attachment")?.optString("attachmentId", "") ?: ""
                    ATTACHMENT_DIGEST.matchEntire(raw)?.groupValues?.getOrNull(1)?.let(digests::add)
                }
                "tool-result" -> collectContentAttachmentDigests(block.optJSONArray("content"), digests)
            }
        }
    }

    private fun collectDelegationReferences(data: JSONObject, collector: ReferenceCollector) {
        listOf("provider", "providerId", "providerRef", "model", "modelId", "modelRef",
            "agentProvider", "agentModel").forEach { collectReference(data, it, collector) }
        listOf("child", "parent", "target", "source", "agent", "descriptor").forEach { key ->
            data.optJSONObject(key)?.let { collectDelegationReferences(it, collector) }
        }
    }

    private fun collectConfigurationJson(value: Any?, key: String?, collector: ReferenceCollector) {
        if (key != null && key in OPAQUE_KEYS) return
        when (value) {
            is JSONObject -> value.keys().forEach { childKey ->
                val child = value.opt(childKey)
                if (childKey in REFERENCE_KEYS && child is String) collector.add(childKey, child)
                else collectConfigurationJson(child, childKey, collector)
            }
            is JSONArray -> for (index in 0 until value.length()) {
                collectConfigurationJson(value.opt(index), key, collector)
            }
        }
    }

    private fun rewriteSessionId(value: JSONObject, key: String, sessionIds: Map<String, String>) {
        val old = value.optString(key, "").takeIf { it.isNotEmpty() } ?: return
        value.put(key, sessionIds[old] ?: old)
    }

    private fun rewriteCwd(value: JSONObject, key: String, workspacePathMapper: (String) -> String?) {
        val old = value.optString(key, "").takeIf { it.isNotEmpty() } ?: return
        workspacePathMapper(old)?.let { mapped ->
            require(mapped.startsWith('/') && '\u0000' !in mapped) { "TRANSFER_SESSION_CWD_INVALID" }
            value.put(key, mapped)
        }
    }

    private fun rewriteReference(value: JSONObject, key: String, referenceMapper: (key: String, value: String) -> String?) {
        val old = value.optString(key, "").takeIf { it.isNotEmpty() } ?: return
        referenceMapper(key, old)?.let { mapped ->
            require(mapped.isNotEmpty() && '\u0000' !in mapped) { "TRANSFER_REFERENCE_INVALID" }
            value.put(key, mapped)
        }
    }

    private fun collectReference(value: JSONObject, key: String, collector: ReferenceCollector) {
        value.optString(key, "").takeIf { it.isNotEmpty() }?.let { collector.add(key, it) }
    }

    private class ReferenceCollector {
        private val values = linkedMapOf<String, LinkedHashSet<String>>()

        fun add(key: String, value: String) {
            if (key in REFERENCE_KEYS && value.isNotEmpty() && value.length <= 4096) {
                values.getOrPut(key) { linkedSetOf() }.add(value)
            }
        }

        fun toMap(): Map<String, Set<String>> = values.mapValues { (_, values) -> values.toSet() }
    }

    /**
     * Mirrors dsh-schedule's durable fold while an imported session is rewritten.
     *
     * Schedule creation and prompt history remains in the copied log, but active records are
     * deleted in a final event so opening the imported session cannot dispatch old work. Seeded
     * sessions are folded only after their last inherited `session/end-seed` marker, matching the
     * pinned projection's `event.seq < inheritedEventCount` boundary.
     */
    private class DurableScheduleState(private val seeded: Boolean) {
        private data class Active(
            val kind: String,
            val everySeconds: Long?,
            var scheduledAt: Instant,
        )

        private val seenIds = linkedSetOf<String>()
        private val active = linkedMapOf<String, Active>()
        private var inheritedEventCount: Long? = null

        val hasPending: Boolean
            get() = active.isNotEmpty()

        fun observe(event: JSONObject) {
            val type = event.optString("type")
            val sequence = if (type == "schedule/change" || type == "session/end-seed") {
                scheduleSequence(event)
            } else {
                null
            }
            if (type == "session/end-seed") {
                observeSeedMarker(event, sequence!!)
                return
            }
            if (type != "schedule/change") return
            if (seeded && (inheritedEventCount == null || sequence!! < inheritedEventCount!!)) return
            applyChange(event.optJSONObject("data"))
        }

        fun finish() {
            if (seeded) {
                require(inheritedEventCount != null) { "TRANSFER_SESSION_SEED_MARKER_MISSING" }
            }
        }

        fun clearEvents(sequence: Long, time: Long): List<JSONObject> = active.keys.mapIndexed { index, id ->
            JSONObject()
                .put("type", "schedule/change")
                .put("seq", Math.addExact(sequence, index.toLong()))
                .put("time", time)
                .put(
                    "data",
                    JSONObject()
                        .put("version", SCHEDULE_CHANGE_VERSION)
                        .put("operation", "delete")
                        .put("id", id),
                )
        }

        private fun observeSeedMarker(event: JSONObject, sequence: Long) {
            val data = event.optJSONObject("data")
                ?: throw IllegalArgumentException("TRANSFER_SESSION_SEED_MARKER_INVALID")
            val inherited = when {
                !data.has("inherited") -> false
                data.opt("inherited") == true -> true
                else -> throw IllegalArgumentException("TRANSFER_SESSION_SEED_MARKER_INVALID")
            }
            if (!seeded) {
                require(!inherited) { "TRANSFER_SESSION_INHERITED_MARKER_INVALID" }
                return
            }
            if (inherited) {
                // A nested fork can contain more than one marker. The last marker is the
                // projection's current inherited cut, so discard folds accumulated before it.
                inheritedEventCount = sequence
                seenIds.clear()
                active.clear()
            }
        }

        private fun applyChange(data: JSONObject?) {
            require(data != null) { "TRANSFER_SESSION_SCHEDULE_INVALID" }
            require(scheduleVersion(data.opt("version"))) { "TRANSFER_SESSION_SCHEDULE_INVALID" }
            when (data.optString("operation")) {
                "create" -> {
                    require(exactKeys(data, setOf("version", "operation", "schedule"))) {
                        "TRANSFER_SESSION_SCHEDULE_INVALID"
                    }
                    val record = decodeRecord(data.optJSONObject("schedule"))
                    require(seenIds.add(record.first)) { "TRANSFER_SESSION_SCHEDULE_INVALID" }
                    active[record.first] = record.second
                }
                "delete" -> {
                    require(exactKeys(data, setOf("version", "operation", "id"))) {
                        "TRANSFER_SESSION_SCHEDULE_INVALID"
                    }
                    val id = scheduleId(data.opt("id"))
                    require(active.remove(id) != null) { "TRANSFER_SESSION_SCHEDULE_INVALID" }
                }
                "dispatch" -> {
                    val hasAcceptedAt = data.has("acceptedAt")
                    val expected = if (hasAcceptedAt) {
                        setOf("version", "operation", "id", "acceptedAt")
                    } else {
                        setOf("version", "operation", "id")
                    }
                    require(exactKeys(data, expected)) { "TRANSFER_SESSION_SCHEDULE_INVALID" }
                    val id = scheduleId(data.opt("id"))
                    val record = active[id] ?: throw IllegalArgumentException("TRANSFER_SESSION_SCHEDULE_INVALID")
                    if (record.kind != "every") {
                        require(!hasAcceptedAt) { "TRANSFER_SESSION_SCHEDULE_INVALID" }
                        active.remove(id)
                    } else {
                        require(hasAcceptedAt) { "TRANSFER_SESSION_SCHEDULE_INVALID" }
                        val acceptedAt = scheduleInstant(data.opt("acceptedAt"))
                        val targetMillis = record.scheduledAt.toEpochMilli()
                        val acceptedMillis = acceptedAt.toEpochMilli()
                        require(acceptedMillis >= targetMillis) { "TRANSFER_SESSION_SCHEDULE_INVALID" }
                        val intervalMillis = Math.multiplyExact(record.everySeconds!!, 1_000L)
                        val steps = (acceptedMillis - targetMillis) / intervalMillis
                        val occurrence = Math.addExact(
                            targetMillis,
                            Math.multiplyExact(steps, intervalMillis),
                        )
                        val next = Math.addExact(occurrence, intervalMillis)
                        if (next > SCHEDULE_MAX_INSTANT.toEpochMilli()) {
                            active.remove(id)
                        } else {
                            record.scheduledAt = Instant.ofEpochMilli(next)
                        }
                    }
                }
                else -> throw IllegalArgumentException("TRANSFER_SESSION_SCHEDULE_INVALID")
            }
        }

        private fun decodeRecord(value: JSONObject?): Pair<String, Active> {
            require(value != null) { "TRANSFER_SESSION_SCHEDULE_INVALID" }
            val kind = value.optString("kind")
            val id: String
            val everySeconds: Long?
            when (kind) {
                "after" -> {
                    require(exactKeys(value, setOf("id", "kind", "prompt", "afterSeconds", "scheduledAt"))) {
                        "TRANSFER_SESSION_SCHEDULE_INVALID"
                    }
                    id = scheduleId(value.opt("id"))
                    schedulePrompt(value.opt("prompt"))
                    schedulePositiveSafeInteger(value.opt("afterSeconds"))
                    everySeconds = null
                }
                "at" -> {
                    require(exactKeys(value, setOf("id", "kind", "prompt", "scheduledAt"))) {
                        "TRANSFER_SESSION_SCHEDULE_INVALID"
                    }
                    id = scheduleId(value.opt("id"))
                    schedulePrompt(value.opt("prompt"))
                    everySeconds = null
                }
                "every" -> {
                    require(exactKeys(value, setOf("id", "kind", "prompt", "everySeconds", "scheduledAt"))) {
                        "TRANSFER_SESSION_SCHEDULE_INVALID"
                    }
                    id = scheduleId(value.opt("id"))
                    schedulePrompt(value.opt("prompt"))
                    val interval = scheduleSafeInteger(value.opt("everySeconds"))
                    require(interval >= MIN_EVERY_INTERVAL_SECONDS &&
                        interval <= MAX_SAFE_INTEGER / 1_000L) {
                        "TRANSFER_SESSION_SCHEDULE_INVALID"
                    }
                    everySeconds = interval
                }
                else -> throw IllegalArgumentException("TRANSFER_SESSION_SCHEDULE_INVALID")
            }
            return id to Active(kind, everySeconds, scheduleInstant(value.opt("scheduledAt")))
        }

        private fun scheduleSequence(event: JSONObject): Long {
            val value = event.opt("seq")
            require(value is Number && value.toDouble().isFinite()) {
                "TRANSFER_SESSION_SEQUENCE_INVALID"
            }
            val sequence = value.toLong()
            require(sequence >= 0L && sequence <= MAX_SAFE_INTEGER &&
                sequence.toDouble() == value.toDouble()) {
                "TRANSFER_SESSION_SEQUENCE_INVALID"
            }
            return sequence
        }

        private fun scheduleVersion(value: Any?): Boolean =
            value is Number && value.toDouble() == SCHEDULE_CHANGE_VERSION.toDouble()

        private fun scheduleId(value: Any?): String {
            require(value is String && value.isNotEmpty() && value.trim() == value) {
                "TRANSFER_SESSION_SCHEDULE_INVALID"
            }
            return value
        }

        private fun schedulePrompt(value: Any?) {
            require(value is String && value.isNotEmpty() && value.trim() == value) {
                "TRANSFER_SESSION_SCHEDULE_INVALID"
            }
        }

        private fun schedulePositiveSafeInteger(value: Any?) {
            require(scheduleSafeInteger(value) > 0L) { "TRANSFER_SESSION_SCHEDULE_INVALID" }
        }

        private fun scheduleSafeInteger(value: Any?): Long {
            require(value is Number && value.toDouble().isFinite()) {
                "TRANSFER_SESSION_SCHEDULE_INVALID"
            }
            val numeric = value.toDouble()
            val integer = value.toLong()
            require(numeric >= 0.0 && numeric <= MAX_SAFE_INTEGER.toDouble() &&
                numeric == integer.toDouble()) {
                "TRANSFER_SESSION_SCHEDULE_INVALID"
            }
            return integer
        }

        private fun scheduleInstant(value: Any?): Instant {
            require(value is String && SCHEDULE_INSTANT.matches(value)) {
                "TRANSFER_SESSION_SCHEDULE_INVALID"
            }
            val instant = runCatching { Instant.parse(value) }
                .getOrElse { throw IllegalArgumentException("TRANSFER_SESSION_SCHEDULE_INVALID", it) }
            require(instant in SCHEDULE_MIN_INSTANT..SCHEDULE_MAX_INSTANT &&
                SCHEDULE_INSTANT_FORMATTER.format(instant) == value) {
                "TRANSFER_SESSION_SCHEDULE_INVALID"
            }
            return instant
        }

        private fun exactKeys(value: JSONObject, expected: Set<String>): Boolean =
            value.keys().asSequence().toSet() == expected
    }

    /** Mirrors the pinned agent-loop inbox projection while a staged log is rewritten. */
    private class DurableInboxState {
        private val nextTurn = ArrayList<Unit>()
        private val nextStep = ArrayList<Unit>()

        val hasPending: Boolean
            get() = nextTurn.isNotEmpty() || nextStep.isNotEmpty()

        fun observe(event: JSONObject) {
            if (event.optString("type") != "agent/inbox/spliced") return
            val data = event.optJSONObject("data")
                ?: throw IllegalArgumentException("TRANSFER_SESSION_INBOX_INVALID")
            val target = when (data.optString("target")) {
                "next-turn" -> nextTurn
                "next-step" -> nextStep
                else -> throw IllegalArgumentException("TRANSFER_SESSION_INBOX_INVALID")
            }
            val start = integer(data.opt("start"))
            val removed = if (!data.has("removedCount") || data.opt("removedCount") == JSONObject.NULL) {
                0
            } else integer(data.opt("removedCount"))
            val inserted = data.optJSONArray("inserted")
                ?: throw IllegalArgumentException("TRANSFER_SESSION_INBOX_INVALID")
            require(start in 0..target.size && removed in 0..(target.size - start)) {
                "TRANSFER_SESSION_INBOX_INVALID"
            }
            repeat(removed) { target.removeAt(start) }
            repeat(inserted.length()) { target.add(Unit) }
        }

        fun clearEvents(sequence: Long, time: Long): List<JSONObject> {
            val output = ArrayList<JSONObject>(2)
            if (nextStep.isNotEmpty()) {
                output += clearEvent("next-step", nextStep.size, sequence, time)
                nextStep.clear()
            }
            if (nextTurn.isNotEmpty()) {
                output += clearEvent("next-turn", nextTurn.size, sequence + output.size, time)
                nextTurn.clear()
            }
            return output
        }

        private fun clearEvent(target: String, removed: Int, sequence: Long, time: Long): JSONObject =
            JSONObject()
                .put("type", "agent/inbox/spliced")
                .put("seq", sequence)
                .put("time", time)
                .put(
                    "data",
                    JSONObject()
                        .put("target", target)
                        .put("start", 0)
                        .put("removedCount", removed)
                        .put("inserted", JSONArray())
                        .put("outcome", "canceled"),
                )
    }

    /** Mirrors Agent Teams' durable queued/delivered fold so queued work cannot wake on import. */
    private class DurableTeamMailboxState {
        private data class Pending(val teamId: String, val targetId: String)

        private val pending = linkedMapOf<String, Pending>()

        val hasPending: Boolean
            get() = pending.isNotEmpty()

        fun observe(event: JSONObject) {
            val data = event.optJSONObject("data") ?: return
            when (event.optString("type")) {
                "team/message/queued" -> {
                    val message = data.optJSONObject("message")
                        ?: throw IllegalArgumentException("TRANSFER_SESSION_TEAM_MESSAGE_INVALID")
                    val id = message.optString("id", "")
                    val teamId = data.optString("teamId", "")
                    val targetId = message.optString("targetId", "")
                    require(id.isNotEmpty() && teamId.isNotEmpty() && targetId.isNotEmpty()) {
                        "TRANSFER_SESSION_TEAM_MESSAGE_INVALID"
                    }
                    pending[id] = Pending(teamId, targetId)
                }
                "team/message/delivered" -> {
                    data.optString("messageId", "").takeIf { it.isNotEmpty() }?.let(pending::remove)
                }
            }
        }

        fun clearEvents(sequence: Long, time: Long): List<JSONObject> = pending.entries.mapIndexed { index, (messageId, message) ->
            JSONObject()
                .put("type", "team/message/delivered")
                .put("seq", sequence + index)
                .put("time", time)
                .put(
                    "data",
                    JSONObject()
                        .put("version", 2)
                        .put("teamId", message.teamId)
                        .put("messageId", messageId)
                        .put("targetId", message.targetId),
                )
        }
    }

    private fun integer(value: Any?): Int {
        require(value is Number && value.toDouble() == value.toInt().toDouble() && value.toInt() >= 0) {
            "TRANSFER_SESSION_INBOX_INVALID"
        }
        return value.toInt()
    }

    private val OPAQUE_KEYS = setOf(
        "content", "arguments", "output", "message", "messages", "prompt", "text", "reasoning",
        "summary", "description", "title", "toolArguments", "toolCall", "toolCalls", "toolResult",
        "rawOutput", "parameters", "meta",
    )

    private fun decodeFrame(
        frame: ByteArray,
        maxPlaintextBytes: Long,
        cancellation: TransferCancellation,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        NonClosingInputStream(ByteArrayInputStream(frame)).use { input ->
            ZstdInputStream(input).use { zstd ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    cancellation.check(TransferWorkPhase.VALIDATE)
                    val count = zstd.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    require(total <= maxPlaintextBytes) { "TRANSFER_SESSION_FRAME_TOO_LARGE" }
                    output.write(buffer, 0, count)
                }
            }
        }
        return output.toByteArray()
    }

    private fun writeFrame(output: OutputStream, plaintext: ByteArray) {
        require(plaintext.isNotEmpty() && plaintext.size <= MAX_PLAINTEXT_FRAME_BYTES) {
            "TRANSFER_SESSION_FRAME_TOO_LARGE"
        }
        val nonClosing = NonClosingOutputStream(output)
        val zstd = ZstdOutputStream(nonClosing)
        zstd.setChecksum(true)
        zstd.use { it.write(plaintext) }
        nonClosing.flush()
    }

    /** Reads exactly one complete Zstandard frame and leaves the next byte untouched. */
    private fun readFrame(
        input: InputStream,
        maxBytes: Long,
        cancellation: TransferCancellation,
    ): ByteArray? {
        cancellation.check(TransferWorkPhase.VALIDATE)
        val first = input.read()
        if (first < 0) return null
        val bytes = ByteArrayOutputStream()
        bytes.write(first)
        val magicTail = ByteArray(3)
        readExactly(input, magicTail, cancellation)
        bytes.write(magicTail)
        val magic = first or ((magicTail[0].toInt() and 0xff) shl 8) or
            ((magicTail[1].toInt() and 0xff) shl 16) or ((magicTail[2].toInt() and 0xff) shl 24)
        require((magic.toLong() and 0xffffffffL) == ZSTD_MAGIC) { "TRANSFER_SESSION_ZSTD_MAGIC_INVALID" }
        val descriptor = input.readOrThrow(cancellation).also(bytes::write)
        require((descriptor and 0x18) == 0) { "TRANSFER_SESSION_ZSTD_HEADER_INVALID" }
        val singleSegment = (descriptor and 0x20) != 0
        val contentSizeFlag = descriptor ushr 6
        val dictionaryFlag = descriptor and 0x03
        val dictionaryBytes = when (dictionaryFlag) {
            0 -> 0
            1, 2 -> 1 shl (dictionaryFlag - 1)
            else -> 4
        }
        val contentSizeBytes = when (contentSizeFlag) {
            0 -> if (singleSegment) 1 else 0
            1 -> 2
            2 -> 4
            else -> 8
        }
        if (!singleSegment) bytes.write(input.readOrThrow(cancellation)) // Window descriptor.
        repeat(dictionaryBytes) { bytes.write(input.readOrThrow(cancellation)) }
        repeat(contentSizeBytes) { bytes.write(input.readOrThrow(cancellation)) }

        var done = false
        var total = bytes.size().toLong()
        while (!done) {
            cancellation.check(TransferWorkPhase.VALIDATE)
            val blockHeader = ByteArray(3)
            readExactly(input, blockHeader, cancellation)
            bytes.write(blockHeader)
            total += 3
            val value = (blockHeader[0].toInt() and 0xff) or
                ((blockHeader[1].toInt() and 0xff) shl 8) or
                ((blockHeader[2].toInt() and 0xff) shl 16)
            done = (value and 1) != 0
            val blockType = (value ushr 1) and 3
            require(blockType != 3) { "TRANSFER_SESSION_ZSTD_BLOCK_INVALID" }
            val blockSize = value ushr 3
            val payloadSize = if (blockType == 1) 1 else blockSize
            require(payloadSize.toLong() <= maxBytes && total + payloadSize <= maxBytes) {
                "TRANSFER_SESSION_ZSTD_FRAME_TOO_LARGE"
            }
            copyExactly(input, bytes, payloadSize, cancellation)
            total += payloadSize
        }
        if ((descriptor and 0x04) != 0) {
            val checksum = ByteArray(4)
            readExactly(input, checksum, cancellation)
            bytes.write(checksum)
        }
        return bytes.toByteArray()
    }

    private fun InputStream.readOrThrow(cancellation: TransferCancellation): Int {
        cancellation.check(TransferWorkPhase.VALIDATE)
        return read().also {
        require(it >= 0) { "TRANSFER_SESSION_ZSTD_TRUNCATED" }
        }
    }

    private fun readExactly(input: InputStream, target: ByteArray, cancellation: TransferCancellation) {
        var offset = 0
        while (offset < target.size) {
            cancellation.check(TransferWorkPhase.VALIDATE)
            val count = input.read(target, offset, target.size - offset)
            require(count > 0) { "TRANSFER_SESSION_ZSTD_TRUNCATED" }
            offset += count
        }
    }

    private fun copyExactly(
        input: InputStream,
        output: OutputStream,
        count: Int,
        cancellation: TransferCancellation,
    ) {
        var remaining = count
        val buffer = ByteArray(minOf(64 * 1024, maxOf(1, count)))
        while (remaining > 0) {
            cancellation.check(TransferWorkPhase.VALIDATE)
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            require(read > 0) { "TRANSFER_SESSION_ZSTD_TRUNCATED" }
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        override fun close() = flush()
    }

    private class NonClosingInputStream(input: InputStream) : java.io.FilterInputStream(input) {
        override fun close() = Unit
    }
}
