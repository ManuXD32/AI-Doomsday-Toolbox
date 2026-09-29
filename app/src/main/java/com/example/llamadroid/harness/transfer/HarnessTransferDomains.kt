package com.example.llamadroid.harness.transfer

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

/**
 * Schema-aware preparation and merge for DSH's canonical workspace domain.
 *
 * DSH v2 uses a JSON storage unit named `workspace`, version 2, with a `workspaces` table.
 * The normal on-disk layout is `<DSH_HOME>/storages/workspace/global.json` and
 * `<DSH_HOME>/storages/workspace/workspaces/<workspaceId>.json`; the helper also accepts the
 * legacy single-file `<DSH_HOME>/storages/workspace.json` layout so an older home can be imported.
 * Only this domain and the selected `sessions/` subtree are merged. Provider/settings files
 * remain owned by the destination home and are never copied here.
 */
object HarnessTransferDomains {
    const val WORKSPACE_DOMAIN_NAME = "workspace"
    const val WORKSPACE_DOMAIN_VERSION = 2
    const val WORKSPACE_TABLE_NAME = "workspaces"

    private const val GLOBAL_FILE = "global.json"
    private const val RECORDS_DIRECTORY = "workspaces"
    private const val SESSION_DIRECTORY = "sessions"
    private const val UNIT_FILE = "workspace.json"
    private val NOFOLLOW = arrayOf(LinkOption.NOFOLLOW_LINKS)
    private val SAFE_KEY = Regex("[A-Za-z0-9_-]{1,256}")
    private val ATTACHMENT_OBJECT_KINDS = setOf("objects", "file-objects", "files")
    private val HEX_DIGEST = Regex("[0-9a-f]{64}")
    private val REFERENCE_KEYS = setOf(
        "provider", "providerId", "provider_id", "providerRef", "provider_ref",
        "model", "modelId", "model_id", "modelRef", "model_ref",
    )

    private enum class Layout { PER_RECORD, SINGLE }

    private data class Location(
        val path: File,
        val relativePath: String,
        val layout: Layout,
    )

    /**
     * Rewrites the staged import in place. The source is already authenticated and session logs
     * have been remapped by [HarnessTransferArchive.stage]; this pass handles only the canonical
     * workspace records and its global ordering/mutation marker.
     */
    fun prepare(
        stagedHome: File,
        sessionIds: Map<String, String>,
        cwdMap: Map<String, String>,
        workspaceIds: Map<String, String>,
        referenceMapper: ((key: String, value: String) -> String?)? = null,
    ): Map<String, String> {
        val home = canonicalDirectory(stagedHome, "TRANSFER_DOMAIN_HOME_INVALID")
        workspaceIds.keys.forEach { require(SAFE_KEY.matches(it)) { "TRANSFER_WORKSPACE_ID_INVALID" } }
        require(workspaceIds.values.distinct().size == workspaceIds.size) {
            "TRANSFER_WORKSPACE_ID_COLLISION"
        }
        workspaceIds.values.forEach { require(SAFE_KEY.matches(it)) { "TRANSFER_WORKSPACE_ID_INVALID" } }
        val location = findLocation(home) ?: return emptyMap()
        return when (location.layout) {
            Layout.PER_RECORD -> preparePerRecord(location, sessionIds, cwdMap, workspaceIds, referenceMapper)
            Layout.SINGLE -> prepareSingle(location, sessionIds, cwdMap, workspaceIds, referenceMapper)
        }
    }

    /**
     * Merges the prepared domain and selected sessions into a stopped destination copy. Existing
     * destination settings, provider stores, and unrelated DSH files stay untouched.
     */
    fun merge(stagedHome: File, destinationHome: File) {
        val source = canonicalDirectory(stagedHome, "TRANSFER_DOMAIN_SOURCE_INVALID")
        val destination = canonicalDirectoryOrCreate(destinationHome, "TRANSFER_DOMAIN_DESTINATION_INVALID")
        require(source.toPath() != destination.toPath()) { "TRANSFER_DOMAIN_MERGE_OVERLAP" }
        mergeAttachmentStore(File(source, "attachments/v1"), File(destination, "attachments/v1"))
        mergeSessions(File(source, SESSION_DIRECTORY), File(destination, SESSION_DIRECTORY))

        val sourceLocation = findLocation(source) ?: return
        val destinationLocation = findLocation(destination)
        if (destinationLocation == null) {
            val targetPath = safeRelative(destination, sourceLocation.relativePath)
            if (sourceLocation.layout == Layout.PER_RECORD) {
                copyTreeNoFollow(sourceLocation.path, targetPath)
            } else {
                targetPath.parentFile?.mkdirs()
                copyRegular(sourceLocation.path, targetPath)
            }
            return
        }
        require(sourceLocation.layout == destinationLocation.layout) {
            "TRANSFER_WORKSPACE_DOMAIN_LAYOUT_MISMATCH"
        }
        when (sourceLocation.layout) {
            Layout.PER_RECORD -> mergePerRecord(sourceLocation, destinationLocation)
            Layout.SINGLE -> mergeSingle(sourceLocation, destinationLocation)
        }
    }

    private fun preparePerRecord(
        location: Location,
        sessionIds: Map<String, String>,
        cwdMap: Map<String, String>,
        workspaceIds: Map<String, String>,
        referenceMapper: ((String, String) -> String?)?,
    ): Map<String, String> {
        val recordsDirectory = File(location.path, RECORDS_DIRECTORY)
        val records = linkedMapOf<String, JSONObject>()
        if (recordsDirectory.exists()) {
            require(Files.isDirectory(recordsDirectory.toPath(), *NOFOLLOW)) {
                "TRANSFER_WORKSPACE_DOMAIN_INVALID"
            }
            recordsDirectory.listFiles().orEmpty().forEach { file ->
                if (file.name == GLOBAL_FILE) return@forEach
                require(file.name.endsWith(".json") && !Files.isSymbolicLink(file.toPath())) {
                    "TRANSFER_WORKSPACE_RECORD_INVALID"
                }
                val oldId = file.name.removeSuffix(".json")
                require(SAFE_KEY.matches(oldId)) { "TRANSFER_WORKSPACE_RECORD_INVALID" }
                readRecord(file)?.let { record -> records[oldId] = record }
            }
        }

        recordsDirectory.mkdirs()
        val effectiveWorkspaceIds = linkedMapOf<String, String>().apply { putAll(workspaceIds) }
        val usedIds = effectiveWorkspaceIds.values.toMutableSet()
        records.forEach { (oldId, record) ->
            if (oldId !in effectiveWorkspaceIds && shouldRetainUnknown(record, sessionIds, cwdMap)) {
                var generated: String
                do generated = UUID.randomUUID().toString() while (generated in usedIds || generated in records.keys)
                effectiveWorkspaceIds[oldId] = generated
                usedIds += generated
            }
        }
        records.forEach { (oldId, record) ->
            val newId = effectiveWorkspaceIds[oldId]
            val target = File(recordsDirectory, "$oldId.json")
            if (newId == null) {
                require(!Files.isSymbolicLink(target.toPath())) { "TRANSFER_WORKSPACE_RECORD_INVALID" }
                require(target.delete() || !target.exists()) { "TRANSFER_WORKSPACE_RECORD_DELETE_FAILED" }
                return@forEach
            }
            val rewritten = rewriteWorkspaceRecord(record, sessionIds, cwdMap, newId, referenceMapper)
            val newTarget = File(recordsDirectory, "$newId.json")
            if (newTarget != target) {
                require(!newTarget.exists()) { "TRANSFER_WORKSPACE_RECORD_COLLISION" }
                require(target.delete()) { "TRANSFER_WORKSPACE_RECORD_DELETE_FAILED" }
            }
            writeRecord(newTarget, rewritten)
        }

        val originalGlobal = readRecord(File(location.path, GLOBAL_FILE))
        val global = rewriteGlobal(originalGlobal, records.keys, sessionIds, effectiveWorkspaceIds)
        writeRecord(File(location.path, GLOBAL_FILE), global)
        return effectiveWorkspaceIds.filterKeys { it !in workspaceIds }
    }

    private fun prepareSingle(
        location: Location,
        sessionIds: Map<String, String>,
        cwdMap: Map<String, String>,
        workspaceIds: Map<String, String>,
        referenceMapper: ((String, String) -> String?)?,
    ): Map<String, String> {
        val unit = readUnit(location.path)
        val table = unit.optJSONObject("tables")?.optJSONObject(WORKSPACE_TABLE_NAME)
            ?: JSONObject()
        val rewrittenTable = JSONObject()
        val present = linkedSetOf<String>()
        val effectiveWorkspaceIds = linkedMapOf<String, String>().apply { putAll(workspaceIds) }
        val usedIds = effectiveWorkspaceIds.values.toMutableSet()
        val sourceRecords = linkedMapOf<String, JSONObject>()
        table.keys().forEach { oldId ->
            val record = table.optJSONObject(oldId) ?: throw IllegalArgumentException("TRANSFER_WORKSPACE_RECORD_INVALID")
            sourceRecords[oldId] = record
            if (oldId !in effectiveWorkspaceIds && shouldRetainUnknown(record, sessionIds, cwdMap)) {
                var generated: String
                do generated = UUID.randomUUID().toString() while (generated in usedIds || generated in sourceRecords.keys)
                effectiveWorkspaceIds[oldId] = generated
                usedIds += generated
            }
        }
        sourceRecords.forEach { (oldId, record) ->
            val newId = effectiveWorkspaceIds[oldId] ?: return@forEach
            require(SAFE_KEY.matches(newId)) { "TRANSFER_WORKSPACE_ID_INVALID" }
            require(!rewrittenTable.has(newId)) { "TRANSFER_WORKSPACE_RECORD_COLLISION" }
            rewrittenTable.put(newId, rewriteWorkspaceRecord(record, sessionIds, cwdMap, newId, referenceMapper))
            present += oldId
        }
        val global = rewriteGlobal(unit.optJSONObject("global"), present, sessionIds, effectiveWorkspaceIds)
        unit.put("global", global)
        unit.put("tables", JSONObject().put(WORKSPACE_TABLE_NAME, rewrittenTable))
        writeJsonAtomic(location.path, unit)
        return effectiveWorkspaceIds.filterKeys { it !in workspaceIds }
    }

    private fun rewriteWorkspaceRecord(
        record: JSONObject,
        sessionIds: Map<String, String>,
        cwdMap: Map<String, String>,
        newId: String,
        referenceMapper: ((String, String) -> String?)?,
    ): JSONObject {
        val output = JSONObject(record.toString())
        output.optString("path", "").takeIf { it.isNotEmpty() }?.let { oldPath ->
            val mapped = remapPath(oldPath, cwdMap)
            require(mapped.startsWith('/') && '\u0000' !in mapped) { "TRANSFER_WORKSPACE_PATH_INVALID" }
            output.put("path", mapped)
        }
        output.optJSONArray("sessionIds")?.let { oldSessions ->
            val mapped = JSONArray()
            for (index in 0 until oldSessions.length()) {
                val oldSession = oldSessions.optString(index, "")
                if (oldSession.isNotEmpty()) sessionIds[oldSession]?.let(mapped::put)
            }
            output.put("sessionIds", mapped)
        }
        if (output.has("id")) output.put("id", newId)
        // A staged snapshot must never replay a mutation that was in flight when it was taken.
        output.remove("pendingMutation")
        applyDirectReferences(output, referenceMapper)
        return output
    }

    private fun shouldRetainUnknown(
        record: JSONObject,
        sessionIds: Map<String, String>,
        cwdMap: Map<String, String>,
    ): Boolean {
        val sessions = record.optJSONArray("sessionIds")
        if (sessions != null) {
            for (index in 0 until sessions.length()) {
                if (sessionIds.containsKey(sessions.optString(index, ""))) return true
            }
        }
        val path = record.optString("path", "")
        return path.isNotEmpty() && cwdMap.keys.any {
            path == it || path.startsWith(it.trimEnd('/') + "/")
        }
    }

    private fun rewriteGlobal(
        original: JSONObject?,
        recordIds: Set<String>,
        sessionIds: Map<String, String>,
        workspaceIds: Map<String, String>,
    ): JSONObject {
        val output = JSONObject(original?.toString() ?: "{}")
        val workspaceOrder = ArrayList<String>()
        original?.optJSONArray("workspaceIds")?.let { oldOrder ->
            for (index in 0 until oldOrder.length()) {
                val oldId = oldOrder.optString(index, "")
                val newId = workspaceIds[oldId]
                if (newId != null && oldId in recordIds && newId !in workspaceOrder) workspaceOrder += newId
            }
        }
        workspaceIds.forEach { (oldId, newId) -> if (oldId in recordIds && newId !in workspaceOrder) workspaceOrder += newId }
        output.put("initialized", true)
        output.put("workspaceIds", JSONArray(workspaceOrder))
        val archived = JSONArray()
        original?.optJSONArray("archivedSessionIds")?.let { oldArchived ->
            for (index in 0 until oldArchived.length()) {
                val mapped = sessionIds[oldArchived.optString(index, "")]
                if (mapped != null && !contains(archived, mapped)) archived.put(mapped)
            }
        }
        output.put("archivedSessionIds", archived)
        output.remove("pendingMutation")
        return output
    }

    private fun mergePerRecord(source: Location, destination: Location) {
        val sourceRecords = File(source.path, RECORDS_DIRECTORY)
        val destinationRecords = File(destination.path, RECORDS_DIRECTORY)
        if (sourceRecords.exists()) {
            require(Files.isDirectory(sourceRecords.toPath(), *NOFOLLOW)) { "TRANSFER_WORKSPACE_DOMAIN_INVALID" }
            require(destinationRecords.mkdirs() || Files.isDirectory(destinationRecords.toPath(), *NOFOLLOW)) {
                "TRANSFER_WORKSPACE_DOMAIN_INVALID"
            }
            sourceRecords.listFiles().orEmpty().forEach { file ->
                if (!file.name.endsWith(".json")) return@forEach
                require(!Files.isSymbolicLink(file.toPath())) { "TRANSFER_WORKSPACE_RECORD_INVALID" }
                val target = File(destinationRecords, file.name)
                require(!target.exists()) { "TRANSFER_WORKSPACE_RECORD_COLLISION" }
                copyRegular(file, target)
            }
        }
        val sourceGlobal = readRecord(File(source.path, GLOBAL_FILE))
        val destinationGlobal = readRecord(File(destination.path, GLOBAL_FILE))
        val merged = mergeGlobal(sourceGlobal, destinationGlobal, destinationRecords)
        writeRecord(File(destination.path, GLOBAL_FILE), merged)
    }

    private fun mergeSingle(source: Location, destination: Location) {
        val sourceUnit = readUnit(source.path)
        val destinationUnit = readUnit(destination.path)
        val sourceTable = sourceUnit.optJSONObject("tables")?.optJSONObject(WORKSPACE_TABLE_NAME) ?: JSONObject()
        val destinationTable = destinationUnit.optJSONObject("tables")?.optJSONObject(WORKSPACE_TABLE_NAME) ?: JSONObject()
        sourceTable.keys().forEach { key ->
            require(!destinationTable.has(key)) { "TRANSFER_WORKSPACE_RECORD_COLLISION" }
            destinationTable.put(key, sourceTable.getJSONObject(key))
        }
        destinationUnit.put("tables", JSONObject().put(WORKSPACE_TABLE_NAME, destinationTable))
        destinationUnit.put(
            "global",
            mergeGlobal(sourceUnit.optJSONObject("global"), destinationUnit.optJSONObject("global"), null),
        )
        writeJsonAtomic(destination.path, destinationUnit)
    }

    private fun mergeGlobal(
        source: JSONObject?,
        destination: JSONObject?,
        destinationRecords: File?,
    ): JSONObject {
        val output = JSONObject(destination?.toString() ?: "{}")
        val order = ArrayList<String>()
        destination?.optJSONArray("workspaceIds")?.let { appendUnique(order, it) }
        source?.optJSONArray("workspaceIds")?.let { sourceOrder ->
            for (index in 0 until sourceOrder.length()) {
                val id = sourceOrder.optString(index, "")
                if (id.isNotEmpty() && (destinationRecords == null || File(destinationRecords, "$id.json").isFile) && id !in order) {
                    order += id
                }
            }
        }
        output.put("workspaceIds", JSONArray(order))
        val archived = ArrayList<String>()
        destination?.optJSONArray("archivedSessionIds")?.let { appendUnique(archived, it) }
        source?.optJSONArray("archivedSessionIds")?.let { appendUnique(archived, it) }
        output.put("archivedSessionIds", JSONArray(archived))
        output.put("initialized", true)
        output.remove("pendingMutation")
        return output
    }

    private fun findLocation(home: File): Location? {
        val direct = File(home, WORKSPACE_DOMAIN_NAME)
        if (isDomainDirectory(direct)) return Location(direct, WORKSPACE_DOMAIN_NAME, Layout.PER_RECORD)
        val directFile = File(home, UNIT_FILE)
        if (isRegular(directFile)) return Location(directFile, UNIT_FILE, Layout.SINGLE)
        val candidates = ArrayList<Path>()
        val fileCandidates = ArrayList<Path>()
        Files.walkFileTree(home.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (home.toPath().relativize(dir).nameCount > 5) return FileVisitResult.SKIP_SUBTREE
                if (dir.fileName?.toString() == WORKSPACE_DOMAIN_NAME && isDomainDirectory(dir.toFile())) {
                    candidates.add(dir)
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (home.toPath().relativize(file).nameCount <= 5 &&
                    file.fileName?.toString() == UNIT_FILE && !Files.isSymbolicLink(file) &&
                    Files.isRegularFile(file, *NOFOLLOW)) {
                    fileCandidates.add(file)
                }
                return FileVisitResult.CONTINUE
            }
        })
        if (candidates.size > 1) throw IllegalArgumentException("TRANSFER_WORKSPACE_DOMAIN_AMBIGUOUS")
        if (candidates.isNotEmpty() && fileCandidates.isNotEmpty()) {
            throw IllegalArgumentException("TRANSFER_WORKSPACE_DOMAIN_AMBIGUOUS")
        }
        val nested = candidates.singleOrNull()?.toFile()
        if (nested != null) return Location(
            nested,
            home.toPath().relativize(nested.toPath()).toString().replace(File.separatorChar, '/'),
            Layout.PER_RECORD,
        )
        if (fileCandidates.size > 1) throw IllegalArgumentException("TRANSFER_WORKSPACE_DOMAIN_AMBIGUOUS")
        return fileCandidates.singleOrNull()?.toFile()?.let {
            Location(it, home.toPath().relativize(it.toPath()).toString().replace(File.separatorChar, '/'), Layout.SINGLE)
        }
    }

    private fun isDomainDirectory(file: File): Boolean =
        file.exists() && file.isDirectory && !Files.isSymbolicLink(file.toPath()) &&
            (File(file, GLOBAL_FILE).exists() || File(file, RECORDS_DIRECTORY).exists())

    private fun readRecord(file: File): JSONObject? {
        if (!file.isFile) return null
        require(!Files.isSymbolicLink(file.toPath())) { "TRANSFER_WORKSPACE_RECORD_INVALID" }
        val wrapper = JSONObject(file.readText(Charsets.UTF_8))
        require(wrapper.optInt("version", -1) == WORKSPACE_DOMAIN_VERSION) {
            "TRANSFER_WORKSPACE_DOMAIN_VERSION_UNSUPPORTED"
        }
        return wrapper.optJSONObject("record")
    }

    private fun writeRecord(file: File, record: JSONObject) {
        writeJsonAtomic(file, JSONObject().put("version", WORKSPACE_DOMAIN_VERSION).put("record", record))
    }

    private fun readUnit(file: File): JSONObject {
        require(isRegular(file)) { "TRANSFER_WORKSPACE_DOMAIN_INVALID" }
        val unit = JSONObject(file.readText(Charsets.UTF_8))
        require(unit.optJSONObject("unit")?.optString("name") == WORKSPACE_DOMAIN_NAME &&
            unit.optJSONObject("unit")?.optInt("version", -1) == WORKSPACE_DOMAIN_VERSION) {
            "TRANSFER_WORKSPACE_DOMAIN_VERSION_UNSUPPORTED"
        }
        return unit
    }

    private fun applyDirectReferences(record: JSONObject, mapper: ((String, String) -> String?)?) {
        if (mapper == null) return
        REFERENCE_KEYS.forEach { key ->
            val value = record.optString(key, "").takeIf { it.isNotEmpty() } ?: return@forEach
            mapper(key, value)?.let { mapped ->
                require(mapped.isNotEmpty() && '\u0000' !in mapped) { "TRANSFER_REFERENCE_INVALID" }
                record.put(key, mapped)
            }
        }
    }

    private fun remapPath(path: String, mappings: Map<String, String>): String {
        val match = mappings.keys.filter { path == it || path.startsWith(it.trimEnd('/') + "/") }
            .maxByOrNull { it.length } ?: return path
        return mappings.getValue(match) + path.removePrefix(match)
    }

    private fun mergeSessions(source: File, destination: File) {
        if (!source.exists()) return
        require(Files.isDirectory(source.toPath(), *NOFOLLOW)) { "TRANSFER_SESSION_DIRECTORY_INVALID" }
        require(destination.mkdirs() || Files.isDirectory(destination.toPath(), *NOFOLLOW)) {
            "TRANSFER_SESSION_DIRECTORY_INVALID"
        }
        Files.walkFileTree(source.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(!Files.isSymbolicLink(dir)) { "TRANSFER_SESSION_SYMLINK_FORBIDDEN" }
                val target = destination.toPath().resolve(source.toPath().relativize(dir))
                require(target.toFile().mkdirs() || Files.isDirectory(target, *NOFOLLOW)) { "TRANSFER_SESSION_DIRECTORY_INVALID" }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(!Files.isSymbolicLink(file) && Files.isRegularFile(file, *NOFOLLOW)) {
                    "TRANSFER_SESSION_FILE_INVALID"
                }
                val target = destination.toPath().resolve(source.toPath().relativize(file)).toFile()
                require(!target.exists()) { "TRANSFER_SESSION_COLLISION" }
                target.parentFile?.mkdirs()
                copyRegular(file.toFile(), target)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /** Merge immutable attachment objects while retaining content-addressed identity. */
    private fun mergeAttachmentStore(source: File, destination: File) {
        if (!source.exists()) return
        require(isDirectoryNoFollow(source)) { "TRANSFER_ATTACHMENT_STORE_INVALID" }
        require(destination.mkdirs() || isDirectoryNoFollow(destination)) { "TRANSFER_ATTACHMENT_STORE_INVALID" }
        Files.walkFileTree(source.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(!Files.isSymbolicLink(dir)) { "TRANSFER_ATTACHMENT_LINK_FORBIDDEN" }
                val relative = source.toPath().relativize(dir).toString().replace(File.separatorChar, '/')
                if (relative.isNotEmpty() && relative.substringBefore('/') !in ATTACHMENT_OBJECT_KINDS) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                val target = destination.toPath().resolve(source.toPath().relativize(dir)).toFile()
                require(target.mkdirs() || isDirectoryNoFollow(target)) { "TRANSFER_ATTACHMENT_STORE_INVALID" }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(!Files.isSymbolicLink(file) && Files.isRegularFile(file, *NOFOLLOW)) {
                    "TRANSFER_ATTACHMENT_FILE_INVALID"
                }
                val relative = source.toPath().relativize(file).toString().replace(File.separatorChar, '/')
                val expected = attachmentDigest(relative) ?: return FileVisitResult.CONTINUE
                val sourceFile = file.toFile()
                require(digest(sourceFile) == expected) { "TRANSFER_ATTACHMENT_DIGEST_MISMATCH" }
                val target = destination.toPath().resolve(source.toPath().relativize(file)).toFile()
                if (target.exists()) {
                    require(!Files.isSymbolicLink(target.toPath()) && Files.isRegularFile(target.toPath(), *NOFOLLOW)) {
                        "TRANSFER_ATTACHMENT_COLLISION"
                    }
                    require(target.length() == sourceFile.length() && digest(target) == expected) {
                        "TRANSFER_ATTACHMENT_COLLISION"
                    }
                } else {
                    target.parentFile?.mkdirs()
                    Files.copy(file, target.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
                }
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun attachmentDigest(relative: String): String? {
        val parts = relative.split('/').filter { it.isNotEmpty() }
        if (parts.size < 3 || parts[1].length != 2) return null
        val kind = parts[0]
        if (kind !in ATTACHMENT_OBJECT_KINDS) return null
        val digest = parts[2]
        if (!digest.matches(HEX_DIGEST) || digest.substring(0, 2) != parts[1]) return null
        return digest
    }

    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) hash.update(buffer, 0, count)
            }
        }
        return hash.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun copyTreeNoFollow(source: File, destination: File) {
        require(!Files.isSymbolicLink(source.toPath())) { "TRANSFER_WORKSPACE_DOMAIN_INVALID" }
        Files.walkFileTree(source.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val target = destination.toPath().resolve(source.toPath().relativize(dir))
                require(target.toFile().mkdirs() || Files.isDirectory(target, *NOFOLLOW)) {
                    "TRANSFER_WORKSPACE_DOMAIN_INVALID"
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(!Files.isSymbolicLink(file) && Files.isRegularFile(file, *NOFOLLOW)) {
                    "TRANSFER_WORKSPACE_DOMAIN_INVALID"
                }
                val target = destination.toPath().resolve(source.toPath().relativize(file)).toFile()
                require(!target.exists()) { "TRANSFER_WORKSPACE_DOMAIN_COLLISION" }
                copyRegular(file.toFile(), target)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun copyRegular(source: File, destination: File) {
        require(isRegular(source)) { "TRANSFER_WORKSPACE_DOMAIN_INVALID" }
        destination.parentFile?.mkdirs()
        Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
    }

    private fun writeJsonAtomic(file: File, value: JSONObject) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write((value.toString(2) + "\n").toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun appendUnique(destination: MutableList<String>, values: JSONArray) {
        for (index in 0 until values.length()) {
            val value = values.optString(index, "")
            if (value.isNotEmpty() && value !in destination) destination += value
        }
    }

    private fun contains(values: JSONArray, value: String): Boolean {
        for (index in 0 until values.length()) if (values.optString(index) == value) return true
        return false
    }

    private fun canonicalDirectory(file: File, error: String): File {
        require(file.exists() && file.isDirectory && !Files.isSymbolicLink(file.toPath())) { error }
        return file.canonicalFile
    }

    private fun canonicalDirectoryOrCreate(file: File, error: String): File {
        if (!file.exists()) require(file.mkdirs()) { error }
        return canonicalDirectory(file, error)
    }

    private fun isRegular(file: File): Boolean =
        file.exists() && file.isFile && !Files.isSymbolicLink(file.toPath())

    private fun isDirectoryNoFollow(file: File): Boolean =
        file.exists() && file.isDirectory && !Files.isSymbolicLink(file.toPath())

    private fun safeRelative(root: File, relative: String): File {
        require(relative.isNotBlank() && relative.split('/').none { it in setOf("", ".", "..") }) {
            "TRANSFER_WORKSPACE_DOMAIN_PATH_INVALID"
        }
        val file = File(root, relative)
        require(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) {
            "TRANSFER_WORKSPACE_DOMAIN_PATH_INVALID"
        }
        return file
    }
}
