package com.example.llamadroid.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream

enum class CustomBinaryFamily(val manifestValue: String) {
    LLM_SERVER("llm_server"),
    STABLE_DIFFUSION("stable_diffusion");

    companion object {
        fun fromManifest(value: String): CustomBinaryFamily? =
            entries.firstOrNull { it.manifestValue == value.trim().lowercase() }
    }
}

data class CustomBinaryPackage(
    val id: String,
    val name: String,
    val version: String,
    val description: String?,
    val family: CustomBinaryFamily,
    val entrypoint: String,
    val libraries: List<String>,
    val installedBytes: Long,
    val directory: File
) {
    val selectionValue: String
        get() = CustomBinaryPackageManager.selectionValue(id)

    val entrypointFile: File
        get() = File(directory, entrypoint)

    val libraryDirectory: File
        get() = entrypointFile.parentFile ?: directory
}

/**
 * Imports user-provided native packages without mixing them with Play-delivered
 * binaries. Packages are intentionally isolated by ID so removal is atomic and
 * picker values remain stable across app restarts.
 */
class CustomBinaryPackageManager(context: Context) {
    private val appContext = context.applicationContext
    private val packagesDir = File(appContext.filesDir, PACKAGES_DIR)

    fun listPackages(): List<CustomBinaryPackage> {
        if (!packagesDir.isDirectory) return emptyList()
        return packagesDir.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory && !it.name.startsWith(STAGING_PREFIX) }
            .mapNotNull { directory ->
                runCatching { readInstalledPackage(directory) }.getOrNull()
            }
            .sortedWith(compareBy<CustomBinaryPackage> { it.family.ordinal }.thenBy { it.name.lowercase() })
            .toList()
    }

    fun resolve(selection: String?, family: CustomBinaryFamily): CustomBinaryPackage? {
        val id = selectionId(selection) ?: return null
        return listPackages().firstOrNull { it.id == id && it.family == family }
    }

    suspend fun importZip(uri: Uri): Result<CustomBinaryPackage> = withContext(Dispatchers.IO) {
        runCatching {
            packagesDir.mkdirs()
            val staging = File(packagesDir, "$STAGING_PREFIX${UUID.randomUUID()}").apply { mkdirs() }
            try {
                extractCheckedZip(uri, staging)
                val imported = readInstalledPackage(staging)
                require(listPackages().none { it.id == imported.id }) {
                    "A custom binary package with ID '${imported.id}' is already installed."
                }
                val destination = File(packagesDir, imported.id)
                require(staging.renameTo(destination)) { "Unable to finalize the imported binary package." }
                readInstalledPackage(destination)
            } catch (error: Throwable) {
                staging.deleteRecursively()
                throw error
            }
        }
    }

    suspend fun remove(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(ID_PATTERN.matches(id)) { "Invalid custom binary package ID." }
            val target = File(packagesDir, id)
            require(target.isDirectory) { "Custom binary package not found." }
            require(target.deleteRecursively()) { "Unable to remove the custom binary package." }
        }
    }

    private fun extractCheckedZip(uri: Uri, staging: File) {
        val rootPath = staging.canonicalPath + File.separator
        var fileCount = 0
        var totalBytes = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        appContext.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val normalizedName = normalizeArchivePath(entry.name)
                    if (normalizedName.isBlank()) {
                        zip.closeEntry()
                        continue
                    }
                    val destination = File(staging, normalizedName)
                    require(destination.canonicalPath.startsWith(rootPath)) { "Unsafe path in binary package." }
                    if (entry.isDirectory) {
                        destination.mkdirs()
                    } else {
                        fileCount += 1
                        require(fileCount <= MAX_FILES) { "Binary package contains too many files." }
                        destination.parentFile?.mkdirs()
                        FileOutputStream(destination).use { output ->
                            while (true) {
                                val read = zip.read(buffer)
                                if (read <= 0) break
                                totalBytes += read
                                require(totalBytes <= MAX_UNCOMPRESSED_BYTES) { "Binary package is too large." }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        } ?: error("Unable to open the selected ZIP.")
        require(fileCount > 0) { "The selected ZIP is empty." }
    }

    private fun readInstalledPackage(directory: File): CustomBinaryPackage {
        val manifestFile = File(directory, MANIFEST_FILE)
        require(manifestFile.isFile) { "manifest.json is missing from the ZIP root." }
        val manifest = JSONObject(manifestFile.readText())
        require(manifest.optInt("schemaVersion", 0) == SCHEMA_VERSION) {
            "Unsupported custom binary package schema."
        }
        val id = manifest.getString("id").trim().lowercase()
        require(ID_PATTERN.matches(id)) { "Package ID must use 3-48 lowercase letters, numbers, dots, underscores, or hyphens." }
        val name = manifest.getString("name").trim()
        require(name.isNotBlank() && name.length <= 80) { "Package name is invalid." }
        val version = manifest.optString("version", "custom").trim().take(40).ifBlank { "custom" }
        val family = CustomBinaryFamily.fromManifest(manifest.getString("family"))
            ?: error("Package family must be 'llm_server' or 'stable_diffusion'.")
        val entrypoint = normalizeArchivePath(manifest.getString("entrypoint"))
        require(entrypoint.startsWith(ABI_DIRECTORY)) { "Entrypoint must be inside $ABI_DIRECTORY." }
        val expectedEntrypoint = when (family) {
            CustomBinaryFamily.LLM_SERVER -> "libllama_server.so"
            CustomBinaryFamily.STABLE_DIFFUSION -> "libsd.so"
        }
        require(File(entrypoint).name == expectedEntrypoint) {
            "The ${family.manifestValue} entrypoint must be named $expectedEntrypoint."
        }
        val libraryArray = manifest.optJSONArray("libraries")
        val libraries = buildList {
            if (libraryArray != null) {
                for (index in 0 until libraryArray.length()) {
                    add(normalizeArchivePath(libraryArray.getString(index)))
                }
            }
        }.distinct()
        val declaredFiles = (libraries + entrypoint).distinct()
        declaredFiles.forEach { relativePath ->
            require(relativePath.startsWith(ABI_DIRECTORY)) { "All native files must be inside $ABI_DIRECTORY." }
            require(relativePath.endsWith(".so")) { "Native package files must use the .so suffix." }
            val file = File(directory, relativePath)
            require(file.isFile) { "Declared native file is missing: $relativePath" }
            validateArm64Elf(file)
            file.setExecutable(true, false)
        }
        val description = manifest.optString("description").trim().take(300).ifBlank { null }
        return CustomBinaryPackage(
            id = id,
            name = name,
            version = version,
            description = description,
            family = family,
            entrypoint = entrypoint,
            libraries = libraries,
            installedBytes = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() },
            directory = directory
        )
    }

    private fun validateArm64Elf(file: File) {
        val header = ByteArray(20)
        val read = FileInputStream(file).use { it.read(header) }
        require(read == header.size) { "Native file is truncated: ${file.name}" }
        require(
            header[0] == 0x7f.toByte() &&
                header[1] == 'E'.code.toByte() &&
                header[2] == 'L'.code.toByte() &&
                header[3] == 'F'.code.toByte()
        ) { "Native file is not ELF: ${file.name}" }
        require(header[4].toInt() == 2 && header[5].toInt() == 1) {
            "Native file must be 64-bit little-endian ELF: ${file.name}"
        }
        val machine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
        require(machine == ELF_MACHINE_AARCH64) { "Native file is not ARM64: ${file.name}" }
    }

    companion object {
        private const val PACKAGES_DIR = "custom_native_packages"
        private const val STAGING_PREFIX = ".staging-"
        private const val MANIFEST_FILE = "manifest.json"
        private const val ABI_DIRECTORY = "lib/arm64-v8a/"
        private const val SCHEMA_VERSION = 1
        private const val MAX_FILES = 96
        private const val MAX_UNCOMPRESSED_BYTES = 1_610_612_736L
        private const val ELF_MACHINE_AARCH64 = 183
        private val ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{2,47}")
        private val SAFE_PATH_SEGMENT = Regex("[A-Za-z0-9._+-]+")
        private const val SELECTION_PREFIX = "custom:"

        fun selectionValue(id: String): String = "$SELECTION_PREFIX$id"

        fun selectionId(selection: String?): String? {
            val value = selection?.trim()?.lowercase() ?: return null
            if (!value.startsWith(SELECTION_PREFIX)) return null
            return value.removePrefix(SELECTION_PREFIX).takeIf(ID_PATTERN::matches)
        }

        internal fun normalizeArchivePath(path: String): String {
            val normalized = path.replace('\\', '/').trim().trimStart('/')
            require(normalized.isNotBlank()) { "Binary package contains an empty path." }
            val segments = normalized.split('/').filter { it.isNotBlank() }
            require(segments.isNotEmpty() && segments.none { it == "." || it == ".." }) {
                "Unsafe path in binary package."
            }
            require(segments.all(SAFE_PATH_SEGMENT::matches)) { "Unsupported character in binary package path." }
            return segments.joinToString("/")
        }
    }
}
