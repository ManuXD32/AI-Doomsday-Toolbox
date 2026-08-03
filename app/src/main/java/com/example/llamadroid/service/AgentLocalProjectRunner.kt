package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.data.db.AgentProjectRunEntity
import com.example.llamadroid.data.db.AppDatabase
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class AgentLocalRunState(
    val conversationId: Long,
    val projectFolder: String,
    val runtime: String,
    val entrypoint: String,
    val uiMode: String,
    val status: String,
    val logs: String,
    val previewUrl: String? = null,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val exitCode: Int? = null
)

class AgentLocalProjectRunner(private val context: Context) {
    private data class ActiveRun(
        val stateKey: Long,
        val projectRoot: File,
        val server: LocalProjectHttpServer?,
        val job: Job?
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeRuns = ConcurrentHashMap<Long, ActiveRun>()
    private val _states = MutableStateFlow<Map<Long, AgentLocalRunState>>(emptyMap())
    val states: StateFlow<Map<Long, AgentLocalRunState>> = _states.asStateFlow()

    suspend fun runProject(
        conversationId: Long,
        projectFolder: String,
        capabilities: AgentLocalRuntimeCapabilities
    ): Result<AgentLocalRunState> = withContext(Dispatchers.IO) {
        runCatching {
            stopProject(conversationId, force = true).getOrNull()
            val projectRoot = AgentLocalWorkspaceSupport.rootForProject(context, projectFolder)
            val runFile = File(projectRoot, ".adt/run.json")
            require(runFile.isFile) { ".adt/run.json is required before the project can run." }
            val config = AgentRunConfigParser.parse(runFile.readText(Charsets.UTF_8))
            val entryFile = AgentLocalWorkspaceSupport.resolvePath(context, projectFolder, config.entrypoint)
            require(entryFile.isFile) { "Entrypoint not found: ${config.entrypoint}" }

            when (config.runtime) {
                AgentLocalRuntimeType.WEB -> startWebProject(conversationId, projectFolder, projectRoot, config)
                AgentLocalRuntimeType.PYTHON -> startPythonProject(conversationId, projectFolder, projectRoot, entryFile, config, capabilities)
            }
        }
    }

    suspend fun checkProject(conversationId: Long): Result<AgentLocalRunState> = withContext(Dispatchers.IO) {
        runCatching {
            _states.value[conversationId]
                ?: AppDatabase.getDatabase(context).agentChatDao().getLatestProjectRun(conversationId)?.toState()
                ?: error("No local project run has been recorded yet.")
        }
    }

    suspend fun stopProject(conversationId: Long, force: Boolean = false): Result<AgentLocalRunState> = withContext(Dispatchers.IO) {
        runCatching {
            val now = System.currentTimeMillis()
            val active = activeRuns.remove(conversationId)
            active?.server?.stop()
            active?.job?.cancel(CancellationException(if (force) "Force stopped by user." else "Stopped by user."))
            val current = _states.value[conversationId]
                ?: AppDatabase.getDatabase(context).agentChatDao().getLatestProjectRun(conversationId)?.toState()
                ?: error("No local project run is active.")
            val stopped = current.copy(
                status = if (force) "FORCE_STOPPED" else "STOPPED",
                logs = current.logs.appendLog(if (force) "Force stop requested." else "Stop requested."),
                endedAt = now,
                exitCode = current.exitCode
            )
            publishState(stopped)
            persistRun(stopped, forceStop = force, stop = !force)
            stopped
        }
    }

    suspend fun installPythonDependency(
        projectFolder: String,
        packageName: String,
        wheelPath: String?,
        capabilities: AgentLocalRuntimeCapabilities
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(capabilities.allowPythonDependencies) {
                "Python dependency installs are disabled for this project."
            }
            val cleanName = packageName.trim().lowercase(Locale.US)
            require(cleanName.matches(Regex("[a-z0-9_.-]{1,80}"))) {
                "Package name contains unsupported characters."
            }
            val projectRoot = AgentLocalWorkspaceSupport.rootForProject(context, projectFolder)
            val dependencyDir = File(projectRoot, ".adt/python/site-packages").apply { mkdirs() }
            val dependencyManifest = File(projectRoot, ".adt/dependencies.json")
            val installed = mutableListOf<String>()
            if (dependencyManifest.isFile) {
                val array = JSONObject(dependencyManifest.readText(Charsets.UTF_8))
                    .optJSONArray("python")
                if (array != null) {
                    for (index in 0 until array.length()) {
                        array.optString(index).takeIf { it.isNotBlank() }?.let(installed::add)
                    }
                }
            }

            if (!wheelPath.isNullOrBlank()) {
                val wheelFile = AgentLocalWorkspaceSupport.resolvePath(context, projectFolder, wheelPath)
                require(wheelFile.isFile && wheelFile.extension.equals("whl", ignoreCase = true)) {
                    "Only project-local pure-Python .whl files are accepted."
                }
                require(isPurePythonWheel(wheelFile.name)) {
                    "Native Android wheels are not installed dynamically. Bundle native packages with the app or use REMOTE_SSH."
                }
                unzipWheel(wheelFile, dependencyDir)
            }

            if (cleanName !in installed) installed += cleanName
            dependencyManifest.parentFile?.mkdirs()
            dependencyManifest.writeText(JSONObject().put("python", JSONArray(installed)).toString(2), Charsets.UTF_8)
            "Registered Python dependency '$cleanName' in the project-local sandbox."
        }
    }

    suspend fun runApprovedSkillScript(
        projectFolder: String,
        scriptFile: File,
        args: List<String>,
        capabilities: AgentLocalRuntimeCapabilities
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val projectRoot = AgentLocalWorkspaceSupport.rootForProject(context, projectFolder).canonicalFile
            val script = scriptFile.canonicalFile
            require(script.path.startsWith(projectRoot.path + File.separator)) {
                "Skill script must stay inside the selected project"
            }
            val relative = script.relativeTo(projectRoot).invariantSeparatorsPath
            require(
                relative.startsWith(".opencode/skills/") ||
                    relative.startsWith(".agents/skills/") ||
                    relative.startsWith(".claude/skills/")
            ) {
                "Skill script must come from a discovered project skill"
            }
            require(!java.nio.file.Files.isSymbolicLink(script.toPath())) {
                "Skill scripts cannot be symlinks"
            }
            require(script.extension.equals("py", ignoreCase = true)) {
                "The mobile sandbox currently supports Python skill scripts only"
            }
            require(args.size <= 32 && args.all { it.length <= 2_000 }) {
                "Skill script arguments exceed the sandbox limit"
            }
            runPythonViaChaquopy(projectRoot, script, args, capabilities).takeLast(32_000)
        }
    }

    private suspend fun startWebProject(
        conversationId: Long,
        projectFolder: String,
        projectRoot: File,
        config: AgentRunConfig
    ): AgentLocalRunState {
        val port = AgentLocalWorkspaceSupport.acquireLoopbackPort()
        val server = LocalProjectHttpServer(port, projectRoot)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        val previewUrl = "http://127.0.0.1:$port/${config.entrypoint}"
        val state = AgentLocalRunState(
            conversationId = conversationId,
            projectFolder = projectFolder,
            runtime = "web",
            entrypoint = config.entrypoint,
            uiMode = "WEB",
            status = "RUNNING",
            logs = "Web project serving from app-private sandbox.\nPreview: $previewUrl",
            previewUrl = previewUrl,
            startedAt = System.currentTimeMillis()
        )
        activeRuns[conversationId] = ActiveRun(conversationId, projectRoot, server, null)
        publishState(state)
        persistRun(state)
        return state
    }

    private suspend fun startPythonProject(
        conversationId: Long,
        projectFolder: String,
        projectRoot: File,
        entryFile: File,
        config: AgentRunConfig,
        capabilities: AgentLocalRuntimeCapabilities
    ): AgentLocalRunState {
        val started = AgentLocalRunState(
            conversationId = conversationId,
            projectFolder = projectFolder,
            runtime = "python",
            entrypoint = config.entrypoint,
            uiMode = config.uiMode.name,
            status = "RUNNING",
            logs = "Python project started in app-private sandbox.",
            startedAt = System.currentTimeMillis()
        )
        publishState(started)
        persistRun(started)
        val job = scope.launch {
            val finished = runCatching {
                val output = runPythonViaChaquopy(projectRoot, entryFile, config.args, capabilities)
                started.copy(
                    status = "STOPPED",
                    logs = started.logs.appendLog(output.ifBlank { "Python script completed without output." }),
                    endedAt = System.currentTimeMillis(),
                    exitCode = 0
                )
            }.getOrElse { error ->
                started.copy(
                    status = if (error is CancellationException) "STOPPED" else "FAILED",
                    logs = started.logs.appendLog(error.message ?: "Python script failed."),
                    endedAt = System.currentTimeMillis(),
                    exitCode = if (error is CancellationException) null else 1
                )
            }
            activeRuns.remove(conversationId)
            publishState(finished)
            persistRun(finished)
        }
        activeRuns[conversationId] = ActiveRun(conversationId, projectRoot, null, job)
        return started
    }

    private fun runPythonViaChaquopy(
        projectRoot: File,
        entryFile: File,
        args: List<String>,
        capabilities: AgentLocalRuntimeCapabilities
    ): String {
        val pythonClass = Class.forName("com.chaquo.python.Python")
        val androidPlatformClass = Class.forName("com.chaquo.python.android.AndroidPlatform")
        val isStarted = pythonClass.getMethod("isStarted").invoke(null) as Boolean
        if (!isStarted) {
            val platform = androidPlatformClass.getConstructor(Context::class.java).newInstance(context.applicationContext)
            pythonClass.getMethod("start", androidPlatformClass).invoke(null, platform)
        }
        val python = pythonClass.getMethod("getInstance").invoke(null)
        val module = python.javaClass.getMethod("getModule", String::class.java).invoke(python, "adt_local_runner")
        val sitePackages = File(projectRoot, ".adt/python/site-packages").apply { mkdirs() }
        val packageList = JSONArray(capabilities.installedPythonPackages)
        val result = module.javaClass.getMethod(
            "callAttr",
            String::class.java,
            Array<Any>::class.java
        ).invoke(
            module,
            "run_script",
            arrayOf(projectRoot.absolutePath, entryFile.absolutePath, JSONArray(args).toString(), sitePackages.absolutePath, packageList.toString())
        )
        return result?.toString().orEmpty()
    }

    private fun publishState(state: AgentLocalRunState) {
        _states.update { current -> current + (state.conversationId to state) }
    }

    private suspend fun persistRun(
        state: AgentLocalRunState,
        stop: Boolean = false,
        forceStop: Boolean = false
    ) {
        val dao = AppDatabase.getDatabase(context).agentChatDao()
        val now = System.currentTimeMillis()
        val existing = dao.getLatestProjectRun(state.conversationId)
        val entity = existing?.copy(
            projectFolder = state.projectFolder,
            runtime = state.runtime,
            entrypoint = state.entrypoint,
            uiMode = state.uiMode,
            status = state.status,
            logs = state.logs.takeLast(32_000),
            previewUrl = state.previewUrl,
            startedAt = state.startedAt,
            endedAt = state.endedAt,
            exitCode = state.exitCode,
            stopRequestedAt = if (stop) now else existing.stopRequestedAt,
            forceStopRequestedAt = if (forceStop) now else existing.forceStopRequestedAt,
            updatedAt = now
        ) ?: AgentProjectRunEntity(
            conversationId = state.conversationId,
            projectFolder = state.projectFolder,
            runtime = state.runtime,
            entrypoint = state.entrypoint,
            uiMode = state.uiMode,
            status = state.status,
            logs = state.logs.takeLast(32_000),
            previewUrl = state.previewUrl,
            startedAt = state.startedAt,
            endedAt = state.endedAt,
            exitCode = state.exitCode,
            createdAt = now,
            updatedAt = now
        )
        if (existing == null) dao.insertProjectRun(entity) else dao.updateProjectRun(entity)
    }

    private fun AgentProjectRunEntity.toState(): AgentLocalRunState = AgentLocalRunState(
        conversationId = conversationId,
        projectFolder = projectFolder,
        runtime = runtime,
        entrypoint = entrypoint,
        uiMode = uiMode,
        status = status,
        logs = logs,
        previewUrl = previewUrl,
        startedAt = startedAt,
        endedAt = endedAt,
        exitCode = exitCode
    )

    private fun String.appendLog(line: String): String =
        (trimEnd() + "\n" + line.trim()).trim().takeLast(32_000)

    private fun isPurePythonWheel(name: String): Boolean =
        name.endsWith(".whl", ignoreCase = true) && (
            name.contains("-none-any.whl", ignoreCase = true) ||
                name.contains("-py3-none-", ignoreCase = true)
            )

    private fun unzipWheel(wheel: File, destination: File) {
        java.util.zip.ZipInputStream(wheel.inputStream().buffered()).use { zipIn ->
            var entry = zipIn.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && AgentLocalWorkspaceSupport.isSafeRelativePath(entry.name)) {
                    val target = File(destination, entry.name).canonicalFile
                    val root = destination.canonicalFile
                    if (target.path.startsWith(root.path + File.separator)) {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zipIn.copyTo(it) }
                    }
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }
    }

    private class LocalProjectHttpServer(
        port: Int,
        private val root: File
    ) : NanoHTTPD("127.0.0.1", port) {
        override fun serve(session: IHTTPSession): Response {
            val requested = session.uri.orEmpty().trimStart('/').ifBlank { "index.html" }
            return runCatching {
                require(AgentLocalWorkspaceSupport.isSafeRelativePath(requested)) { "Unsafe path" }
                val target = File(root, requested).canonicalFile
                val canonicalRoot = root.canonicalFile
                require(target.path.startsWith(canonicalRoot.path + File.separator) && target.isFile) { "Not found" }
                newFixedLengthResponse(Response.Status.OK, mimeFor(target), target.inputStream(), target.length())
            }.getOrElse {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
        }

        private fun mimeFor(file: File): String = when (file.extension.lowercase(Locale.US)) {
            "html", "htm" -> "text/html"
            "js", "mjs" -> "application/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "text/plain"
        }
    }
}
