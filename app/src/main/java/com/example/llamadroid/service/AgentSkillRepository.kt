package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.data.db.AgentSkillAssignmentEntity
import com.example.llamadroid.data.db.AgentSkillEntity
import com.example.llamadroid.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class SkillCatalogEntry(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val license: String?,
    val assetPath: String?,
    val downloadUrl: String?,
    val sha256: String?
)

data class LoadedAgentSkill(
    val entity: AgentSkillEntity,
    val manifest: SkillManifest,
    val instructions: String
)

class AgentSkillRepository(
    private val context: Context,
    private val database: AppDatabase = AppDatabase.getDatabase(context)
) {
    private val dao = database.agentWorkflowDao()
    private val installRoot = File(context.filesDir, "agent_skills")
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    fun observeInstalled(): Flow<List<AgentSkillEntity>> = dao.observeSkills()

    fun observeAssignments(): Flow<List<AgentSkillAssignmentEntity>> = dao.observeSkillAssignments()

    suspend fun findSkill(skillIdOrName: String): AgentSkillEntity? = withContext(Dispatchers.IO) {
        dao.getEnabledSkills().firstOrNull {
            it.id.equals(skillIdOrName, ignoreCase = true) ||
                it.name.equals(skillIdOrName, ignoreCase = true)
        }
    }

    suspend fun permissionFor(
        skill: AgentSkillEntity,
        conversationId: Long?,
        agentKey: String
    ): SkillPermission = withContext(Dispatchers.IO) {
        val assignment = dao.resolveSkillAssignment(skill.id, conversationId, agentKey)
        runCatching {
            SkillPermission.valueOf(
                assignment?.permission ?: defaultPermission(skill.sourceType).name
            )
        }.getOrDefault(SkillPermission.ASK)
    }

    suspend fun catalogEntries(): List<SkillCatalogEntry> = withContext(Dispatchers.IO) {
        val json = JSONObject(context.assets.open(CATALOG_INDEX).bufferedReader().use { it.readText() })
        val array = json.optJSONArray("skills") ?: JSONArray()
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").trim()
            val name = item.optString("name").trim()
            if (id.isBlank() || name.isBlank()) return@mapNotNull null
            SkillCatalogEntry(
                id = id,
                name = name,
                description = item.optString("description").trim(),
                version = item.optString("version", "1.0.0").trim(),
                license = item.optString("license").takeIf { it.isNotBlank() },
                assetPath = item.optString("asset_path").takeIf { it.isNotBlank() },
                downloadUrl = item.optString("download_url").takeIf { it.isNotBlank() },
                sha256 = item.optString("sha256").takeIf { it.isNotBlank() }
            )
        }
    }

    suspend fun installCatalogSkill(entry: SkillCatalogEntry): AgentSkillEntity = withContext(Dispatchers.IO) {
        val assetPath = requireNotNull(entry.assetPath) { "Catalog entry is not bundled" }
        val destination = File(installRoot, safeDirectoryName(entry.id))
        val staging = File(context.cacheDir, "agent_skill_install/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            copyAssetDirectory("$CATALOG_ROOT/$assetPath", staging)
            installValidatedDirectory(
                directory = staging,
                destination = destination,
                sourceType = "CURATED",
                sourceUri = "asset://$assetPath",
                expectedHash = entry.sha256,
                defaultPermission = SkillPermission.ALLOW
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun installZip(
        zipFile: File,
        sourceType: String = "IMPORTED",
        sourceUri: String? = zipFile.name
    ): AgentSkillEntity = withContext(Dispatchers.IO) {
        require(zipFile.isFile && zipFile.length() in 1..MAX_ARCHIVE_BYTES) {
            "Skill archive is missing or exceeds ${MAX_ARCHIVE_BYTES / 1024 / 1024} MiB"
        }
        val staging = File(context.cacheDir, "agent_skill_install/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            extractZipSafely(zipFile, staging)
            val skillRoot = locateSingleSkillRoot(staging)
            val manifest = parseSkillFile(File(skillRoot, SKILL_FILE))
            val destination = File(installRoot, safeDirectoryName(manifest.name))
            installValidatedDirectory(
                directory = skillRoot,
                destination = destination,
                sourceType = sourceType,
                sourceUri = sourceUri,
                expectedHash = null,
                defaultPermission = SkillPermission.ASK
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun installFromHttps(url: String): AgentSkillEntity = withContext(Dispatchers.IO) {
        val parsed = java.net.URI(url)
        require(parsed.scheme.equals("https", ignoreCase = true)) { "Skill imports require HTTPS" }
        val temporary = File(context.cacheDir, "agent_skill_install/${UUID.randomUUID()}.zip")
        temporary.parentFile?.mkdirs()
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                require(response.isSuccessful) { "Skill download failed with HTTP ${response.code}" }
                val body = requireNotNull(response.body)
                require((body.contentLength().takeIf { it >= 0 } ?: 0L) <= MAX_ARCHIVE_BYTES) {
                    "Skill archive is too large"
                }
                body.byteStream().use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= MAX_ARCHIVE_BYTES) { "Skill archive is too large" }
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
            installZip(temporary, sourceType = "HTTPS", sourceUri = url)
        } finally {
            temporary.delete()
        }
    }

    suspend fun discoverProjectSkills(projectRoot: File): List<AgentSkillEntity> = withContext(Dispatchers.IO) {
        val roots = PROJECT_SKILL_PATHS.map { File(projectRoot, it) }
        val discovered = mutableListOf<AgentSkillEntity>()
        for (root in roots) {
            if (!root.isDirectory) continue
            root.listFiles()
                ?.filter(File::isDirectory)
                ?.sortedBy { it.name.lowercase() }
                ?.forEach { directory ->
                    runCatching {
                        validateSkillDirectory(directory)
                        val manifest = parseSkillFile(File(directory, SKILL_FILE))
                        val hash = hashDirectory(directory)
                        val entity = skillEntity(
                            manifest = manifest,
                            sourceType = "PROJECT",
                            sourceUri = directory.absolutePath,
                            installPath = directory.absolutePath,
                            hash = hash
                        )
                        dao.upsertSkill(entity)
                        ensureDefaultAssignment(entity.id, SkillPermission.ASK)
                        discovered += entity
                    }
                }
        }
        discovered
    }

    suspend fun loadSkill(
        skillIdOrName: String,
        conversationId: Long?,
        agentKey: String,
        approvedForCall: Boolean = false
    ): LoadedAgentSkill = withContext(Dispatchers.IO) {
        val skills = dao.getEnabledSkills()
        val entity = skills.firstOrNull {
            it.id.equals(skillIdOrName, ignoreCase = true) ||
                it.name.equals(skillIdOrName, ignoreCase = true)
        } ?: throw IllegalArgumentException("Skill is not installed: $skillIdOrName")
        val permission = permissionFor(entity, conversationId, agentKey)
        require(permission != SkillPermission.DENY) { "Skill is denied for this project or agent" }
        require(permission == SkillPermission.ALLOW || approvedForCall) {
            "Skill requires user approval before loading"
        }
        val skillFile = File(entity.installPath, SKILL_FILE)
        val parsed = parseSkillDocument(skillFile.readText())
        LoadedAgentSkill(entity, parsed.first, parsed.second)
    }

    suspend fun readSkillResource(skillId: String, relativePath: String): String = withContext(Dispatchers.IO) {
        val entity = dao.getSkill(skillId) ?: throw IllegalArgumentException("Skill is not installed")
        val root = File(entity.installPath).canonicalFile
        val requested = resolveInsideSkill(root, relativePath)
        require(requested.isFile && requested.name != SKILL_FILE) { "Skill resource was not found" }
        require(requested.length() <= MAX_RESOURCE_BYTES) { "Skill resource is too large" }
        requested.readText()
    }

    suspend fun resolveSkillScript(skillId: String, relativePath: String): File = withContext(Dispatchers.IO) {
        val entity = dao.getSkill(skillId) ?: throw IllegalArgumentException("Skill is not installed")
        require(entity.sourceType == "PROJECT") {
            "Only project-local skill scripts may run in the project sandbox"
        }
        val root = File(entity.installPath).canonicalFile
        val requested = resolveInsideSkill(root, relativePath)
        require(requested.isFile) { "Skill script was not found" }
        require(requested.extension.lowercase() in setOf("py", "sh")) {
            "Skill scripts must be .py or .sh files"
        }
        requested
    }

    suspend fun setAssignment(
        skillId: String,
        permission: SkillPermission,
        conversationId: Long? = null,
        agentKey: String = "*"
    ) {
        dao.upsertSkillAssignment(
            AgentSkillAssignmentEntity(
                skillId = skillId,
                conversationId = conversationId,
                agentKey = agentKey,
                permission = permission.name
            )
        )
    }

    suspend fun uninstall(skillId: String) = withContext(Dispatchers.IO) {
        val skill = dao.getSkill(skillId) ?: return@withContext
        if (skill.sourceType != "PROJECT") {
            val root = installRoot.canonicalFile
            val directory = File(skill.installPath).canonicalFile
            if (directory.path.startsWith(root.path + File.separator)) directory.deleteRecursively()
        }
        dao.deleteSkill(skillId)
    }

    suspend fun metadataCatalogForPrompt(
        conversationId: Long?,
        agentKey: String
    ): String = withContext(Dispatchers.IO) {
        dao.getEnabledSkills().mapNotNull { skill ->
            val assignment = dao.resolveSkillAssignment(skill.id, conversationId, agentKey)
            val permission = assignment?.permission ?: defaultPermission(skill.sourceType).name
            if (permission == SkillPermission.DENY.name) null
            else "- ${skill.name}: ${skill.description} [${skill.id}; permission=$permission]"
        }.joinToString("\n")
    }

    private suspend fun installValidatedDirectory(
        directory: File,
        destination: File,
        sourceType: String,
        sourceUri: String?,
        expectedHash: String?,
        defaultPermission: SkillPermission
    ): AgentSkillEntity {
        validateSkillDirectory(directory)
        val manifest = parseSkillFile(File(directory, SKILL_FILE))
        val hash = hashDirectory(directory)
        require(expectedHash.isNullOrBlank() || expectedHash.equals(hash, ignoreCase = true)) {
            "Skill hash does not match the catalog"
        }
        installRoot.mkdirs()
        val stableId = safeDirectoryName(manifest.name)
        val existing = dao.getSkill(stableId)
        require(
            existing == null ||
                existing.sourceType == sourceType ||
                (existing.sourceType == "CURATED" && sourceType == "CURATED")
        ) {
            "A skill named '${manifest.name}' is already installed from another source"
        }
        val stagedDestination = File(installRoot, ".${destination.name}.${UUID.randomUUID()}.staging")
        stagedDestination.deleteRecursively()
        directory.copyRecursively(stagedDestination, overwrite = false)
        destination.deleteRecursively()
        require(stagedDestination.renameTo(destination)) { "Could not finalize skill installation" }
        val entity = skillEntity(
            manifest = manifest,
            sourceType = sourceType,
            sourceUri = sourceUri,
            installPath = destination.absolutePath,
            hash = hash
        )
        dao.upsertSkill(entity)
        ensureDefaultAssignment(entity.id, defaultPermission)
        return entity
    }

    private suspend fun ensureDefaultAssignment(skillId: String, permission: SkillPermission) {
        val existing = dao.resolveSkillAssignment(skillId, null, "*")
        if (existing == null) setAssignment(skillId, permission)
    }

    private fun skillEntity(
        manifest: SkillManifest,
        sourceType: String,
        sourceUri: String?,
        installPath: String,
        hash: String
    ): AgentSkillEntity {
        val id = safeDirectoryName(manifest.name)
        return AgentSkillEntity(
            id = id,
            name = manifest.name,
            description = manifest.description,
            version = manifest.version,
            license = manifest.license,
            sourceType = sourceType,
            sourceUri = sourceUri,
            installPath = installPath,
            manifestJson = JSONObject().apply {
                put("name", manifest.name)
                put("description", manifest.description)
                put("version", manifest.version)
                put("license", manifest.license)
                put("invocation", manifest.invocation)
                put("allowed_tools", JSONArray(manifest.allowedTools))
                put("metadata", JSONObject(manifest.metadata))
            }.toString(),
            contentHash = hash
        )
    }

    private fun validateSkillDirectory(directory: File) {
        require(directory.isDirectory) { "Skill package is not a directory" }
        val root = directory.canonicalFile
        val files = root.walkTopDown().filter(File::isFile).toList()
        require(files.size in 1..MAX_FILES) { "Skill package has too many files" }
        var total = 0L
        for (file in files) {
            require(!java.nio.file.Files.isSymbolicLink(file.toPath())) { "Skill packages cannot contain symlinks" }
            val canonical = file.canonicalFile
            require(canonical.path.startsWith(root.path + File.separator)) { "Skill file escapes its package" }
            require(canonical.length() <= MAX_RESOURCE_BYTES) { "Skill resource is too large: ${file.name}" }
            total += canonical.length()
            require(total <= MAX_PACKAGE_BYTES) { "Skill package is too large" }
        }
        require(File(root, SKILL_FILE).isFile) { "Skill package must contain $SKILL_FILE" }
        parseSkillFile(File(root, SKILL_FILE))
    }

    private fun parseSkillFile(file: File): SkillManifest = parseSkillDocument(file.readText()).first

    internal fun parseSkillDocument(text: String): Pair<SkillManifest, String> {
        val normalized = text.replace("\r\n", "\n")
        require(normalized.startsWith("---\n")) { "SKILL.md must start with YAML frontmatter" }
        val end = normalized.indexOf("\n---\n", startIndex = 4)
        require(end >= 0) { "SKILL.md frontmatter is not closed" }
        val metadata = linkedMapOf<String, String>()
        normalized.substring(4, end).lineSequence().forEach { line ->
            val separator = line.indexOf(':')
            if (separator > 0) {
                val key = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim().trim('"', '\'')
                metadata[key] = value
            }
        }
        val name = metadata["name"].orEmpty().trim()
        val description = metadata["description"].orEmpty().trim()
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9 _.-]{0,63}"))) { "Skill name is invalid" }
        require(description.isNotBlank() && description.length <= 500) { "Skill description is required" }
        val allowedTools = metadata["allowed-tools"]
            ?.trim('[', ']')
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            .orEmpty()
        val known = setOf("name", "description", "version", "license", "invocation", "allowed-tools")
        val manifest = SkillManifest(
            name = name,
            description = description,
            version = metadata["version"],
            license = metadata["license"],
            invocation = metadata["invocation"] ?: "both",
            allowedTools = allowedTools,
            metadata = metadata.filterKeys { it !in known }
        )
        return manifest to normalized.substring(end + 5).trim()
    }

    private fun extractZipSafely(zipFile: File, destination: File) {
        var fileCount = 0
        var totalBytes = 0L
        ZipInputStream(FileInputStream(zipFile)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                fileCount++
                require(fileCount <= MAX_FILES) { "Skill archive has too many entries" }
                val output = File(destination, entry.name).canonicalFile
                require(output.path.startsWith(destination.canonicalPath + File.separator)) {
                    "Skill archive contains an unsafe path"
                }
                require(!entry.name.startsWith("/") && !entry.name.contains('\\')) {
                    "Skill archive contains an unsafe path"
                }
                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    output.outputStream().use { stream ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var entryBytes = 0L
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            entryBytes += read
                            totalBytes += read
                            require(entryBytes <= MAX_RESOURCE_BYTES && totalBytes <= MAX_PACKAGE_BYTES) {
                                "Skill archive expands beyond its size limit"
                            }
                            stream.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun locateSingleSkillRoot(staging: File): File {
        if (File(staging, SKILL_FILE).isFile) return staging
        val candidates = staging.walkTopDown()
            .filter { it.isFile && it.name == SKILL_FILE }
            .mapNotNull(File::getParentFile)
            .toList()
        require(candidates.size == 1) { "Archive must contain exactly one skill package" }
        return candidates.single()
    }

    private fun hashDirectory(directory: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val root = directory.canonicalFile
        root.walkTopDown()
            .filter(File::isFile)
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .forEach { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                digest.update(relative.toByteArray(Charsets.UTF_8))
                digest.update(0)
                file.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun copyAssetDirectory(assetPath: String, destination: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                destination.outputStream().use(input::copyTo)
            }
            return
        }
        destination.mkdirs()
        children.forEach { child ->
            copyAssetDirectory("$assetPath/$child", File(destination, child))
        }
    }

    private fun safeDirectoryName(value: String): String =
        value.lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
            .take(64)
            .ifBlank { "skill" }

    private fun resolveInsideSkill(root: File, relativePath: String): File {
        require(relativePath.isNotBlank() && !File(relativePath).isAbsolute) {
            "Skill resource path must be relative"
        }
        val requested = File(root, relativePath).canonicalFile
        require(requested.path.startsWith(root.path + File.separator)) {
            "Skill resource escapes its package"
        }
        require(!java.nio.file.Files.isSymbolicLink(requested.toPath())) {
            "Skill resources cannot be symlinks"
        }
        return requested
    }

    private fun defaultPermission(sourceType: String): SkillPermission =
        if (sourceType == "CURATED") SkillPermission.ALLOW else SkillPermission.ASK

    companion object {
        private const val SKILL_FILE = "SKILL.md"
        private const val CATALOG_ROOT = "agent_skills_catalog"
        private const val CATALOG_INDEX = "$CATALOG_ROOT/index.json"
        private const val MAX_FILES = 256
        private const val MAX_ARCHIVE_BYTES = 5L * 1024L * 1024L
        private const val MAX_PACKAGE_BYTES = 5L * 1024L * 1024L
        private const val MAX_RESOURCE_BYTES = 512L * 1024L
        val PROJECT_SKILL_PATHS = listOf(".opencode/skills", ".agents/skills", ".claude/skills")
    }
}
