package com.example.llamadroid.service

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Locale

enum class AgentWorkspaceBackendType {
    REMOTE_SSH,
    LOCAL_SANDBOX;

    companion object {
        fun fromStored(value: String?): AgentWorkspaceBackendType =
            entries.firstOrNull { it.name.equals(value.orEmpty(), ignoreCase = true) } ?: REMOTE_SSH
    }
}

enum class AgentLocalRuntimeType {
    PYTHON,
    WEB
}

enum class AgentRunUiMode {
    CONSOLE,
    WEB
}

data class AgentLocalRuntimeCapabilities(
    val allowPythonDependencies: Boolean = false,
    val installedPythonPackages: List<String> = emptyList()
) {
    fun toJson(): String = JSONObject()
        .put("allowPythonDependencies", allowPythonDependencies)
        .put("installedPythonPackages", JSONArray(installedPythonPackages))
        .toString()

    companion object {
        fun fromJson(json: String?): AgentLocalRuntimeCapabilities {
            if (json.isNullOrBlank()) return AgentLocalRuntimeCapabilities()
            return runCatching {
                val obj = JSONObject(json)
                val packages = obj.optJSONArray("installedPythonPackages")
                AgentLocalRuntimeCapabilities(
                    allowPythonDependencies = obj.optBoolean("allowPythonDependencies", false),
                    installedPythonPackages = buildList {
                        if (packages != null) {
                            for (index in 0 until packages.length()) {
                                packages.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                            }
                        }
                    }
                )
            }.getOrDefault(AgentLocalRuntimeCapabilities())
        }
    }
}

data class AgentRunConfig(
    val version: Int,
    val runtime: AgentLocalRuntimeType,
    val entrypoint: String,
    val uiMode: AgentRunUiMode,
    val args: List<String> = emptyList(),
    val background: Boolean = false,
    val description: String = ""
) {
    fun toJson(): String = JSONObject()
        .put("version", version)
        .put("runtime", runtime.name.lowercase(Locale.US))
        .put("entrypoint", entrypoint)
        .put("ui", uiMode.name.lowercase(Locale.US))
        .put("args", JSONArray(args))
        .put("background", background)
        .put("description", description)
        .toString(2)
}

object AgentRunConfigParser {
    fun parse(rawJson: String): AgentRunConfig {
        val obj = JSONObject(rawJson)
        val version = obj.optInt("version", 1)
        require(version == 1) { "Unsupported run.json version: $version" }
        val runtime = when (obj.optString("runtime").lowercase(Locale.US)) {
            "python" -> AgentLocalRuntimeType.PYTHON
            "web", "javascript", "js" -> AgentLocalRuntimeType.WEB
            else -> error("runtime must be python or web")
        }
        val uiMode = when (obj.optString("ui", if (runtime == AgentLocalRuntimeType.WEB) "web" else "console").lowercase(Locale.US)) {
            "web", "browser" -> AgentRunUiMode.WEB
            "console", "terminal", "log" -> AgentRunUiMode.CONSOLE
            else -> error("ui must be console or web")
        }
        val entrypoint = obj.optString("entrypoint").trim()
        require(entrypoint.isNotBlank()) { "entrypoint is required" }
        require(AgentLocalWorkspaceSupport.isSafeRelativePath(entrypoint)) {
            "entrypoint must stay inside the project workspace"
        }
        val argsJson = obj.optJSONArray("args")
        val args = buildList {
            if (argsJson != null) {
                for (index in 0 until argsJson.length()) {
                    val value = argsJson.optString(index)
                    require(!AgentRuntimeSupport.containsTraversalSegments(value)) {
                        "args must not contain path traversal segments"
                    }
                    add(value)
                }
            }
        }
        return AgentRunConfig(
            version = version,
            runtime = runtime,
            entrypoint = entrypoint,
            uiMode = uiMode,
            args = args,
            background = obj.optBoolean("background", false),
            description = obj.optString("description")
        )
    }
}

object AgentLocalWorkspaceSupport {
    const val DISPLAY_ROOT = "/local_workspace"
    private const val STORAGE_ROOT = "agent_local_workspaces"

    fun rootPathForProject(context: Context, projectFolder: String): File =
        File(context.filesDir, "$STORAGE_ROOT/${sanitizeProjectFolder(projectFolder)}")

    fun rootForProject(context: Context, projectFolder: String): File =
        rootPathForProject(context, projectFolder).apply { mkdirs() }

    fun deleteProjectRoot(context: Context, projectFolder: String): Boolean {
        val storageRoot = File(context.filesDir, STORAGE_ROOT).canonicalFile
        val projectRoot = rootPathForProject(context, projectFolder).canonicalFile
        require(projectRoot == storageRoot || projectRoot.path.startsWith(storageRoot.path + File.separator)) {
            "Local project root must stay inside agent local workspaces."
        }
        return !projectRoot.exists() || projectRoot.deleteRecursively()
    }

    fun displayRoot(projectFolder: String): String = "$DISPLAY_ROOT/${sanitizeProjectFolder(projectFolder)}"

    fun sanitizeProjectFolder(projectFolder: String): String =
        projectFolder.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(80).ifBlank { "default_project" }

    fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.startsWith("/") || path.startsWith("\\")) return false
        if (path.contains('\\')) return false
        return path.split('/').none { it.isBlank() || it == "." || it == ".." }
    }

    fun resolvePath(context: Context, projectFolder: String, requestedPath: String): File {
        val root = rootForProject(context, projectFolder).canonicalFile
        val displayRoot = displayRoot(projectFolder)
        val normalized = requestedPath.replace('\\', '/').replace(Regex("/+"), "/").trim()
        if (AgentRuntimeSupport.containsTraversalSegments(normalized)) {
            throw IllegalArgumentException("Path traversal is not allowed: $requestedPath")
        }
        val relative = when {
            normalized.isBlank() || normalized == "." -> ""
            normalized == displayRoot -> ""
            normalized.startsWith("$displayRoot/") -> normalized.removePrefix("$displayRoot/")
            normalized.startsWith(DISPLAY_ROOT) -> throw IllegalArgumentException("Path must stay inside the current local workspace: $requestedPath")
            normalized.startsWith("/") -> throw IllegalArgumentException("Absolute paths are not allowed: $requestedPath")
            else -> normalized
        }
        if (relative.isNotBlank() && !isSafeRelativePath(relative)) {
            throw IllegalArgumentException("Unsafe local workspace path: $requestedPath")
        }
        val target = if (relative.isBlank()) root else File(root, relative)
        val canonical = target.canonicalFile
        if (canonical != root && !canonical.path.startsWith(root.path + File.separator)) {
            throw IllegalArgumentException("Path must stay inside the current local workspace: $requestedPath")
        }
        return canonical
    }

    fun toDisplayPath(context: Context, projectFolder: String, file: File): String {
        val root = rootForProject(context, projectFolder).canonicalFile
        val canonical = file.canonicalFile
        val relative = if (canonical == root) "" else root.toPath().relativize(canonical.toPath()).toString()
            .replace(File.separatorChar, '/')
        return displayRoot(projectFolder) + relative.takeIf { it.isNotBlank() }?.let { "/$it" }.orEmpty()
    }

    fun acquireLoopbackPort(): Int = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
}
