package com.example.llamadroid.harness.runtime

import java.io.File
import java.net.URI

/** Lifecycle states persisted by the app-owned shared Harness runtime. */
enum class HarnessRuntimeState {
    STOPPED,
    STARTING,
    RUNNING,
    STOP_REQUESTED,
    FORCE_STOPPING,
    FAILED,
    INTERRUPTED
}

enum class HarnessStopMode {
    GRACEFUL,
    FORCE
}

/**
 * The only client-facing runtime credential. `token` is an opaque Cookie header value;
 * it is never persisted or emitted to metadata logs.
 */
data class HarnessEndpoint(
    val origin: String,
    val token: String
) {
    init {
        val uri = URI(origin)
        require(uri.scheme.equals("http", ignoreCase = true)) {
            "Harness endpoint must use loopback HTTP"
        }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Harness endpoint must not contain credentials, query, or fragment"
        }
        require(uri.host == LOOPBACK_V4 || uri.host == LOOPBACK_V6 || uri.host == LOOPBACK_V6_BRACKETED) {
            "Harness endpoint must use a loopback host"
        }
        require(uri.port in 1024..65535) { "Harness endpoint port is invalid" }
        require(uri.path.isNullOrEmpty() || uri.path == "/") {
            "Harness endpoint must be an origin"
        }
        require(token.isNotBlank() && token.length <= MAX_COOKIE_BYTES &&
            token.none { it == '\u0000' || it == '\r' || it == '\n' }) {
            "Harness cookie header is invalid"
        }
    }

    val cookieHeader: String
        get() = token

    fun url(path: String): String {
        val normalized = path.trim().let { if (it.startsWith('/')) it else "/$it" }
        require(!normalized.contains("..")) { "Harness endpoint path may not escape its origin" }
        return origin.trimEnd('/') + normalized
    }

    companion object {
        private const val LOOPBACK_V4 = "127.0.0.1"
        private const val LOOPBACK_V6 = "::1"
        private const val LOOPBACK_V6_BRACKETED = "[::1]"
        private const val MAX_COOKIE_BYTES = 8 * 1024
    }
}

/** The process identity needed to distinguish a live owner from a recycled PID. */
data class HarnessProcessIdentity(
    val brokerPid: Int,
    val brokerStartTimeTicks: Long,
    val brokerUid: Int,
    val processGroupId: Int,
    val childPid: Int? = null,
    val childStartTimeTicks: Long? = null,
    /** Non-secret generation marker inherited by every harness descendant. */
    val ownerMarker: String,
    /** The live guest Node process targeted by graceful shutdown, when it can be observed. */
    val nodePid: Int? = null,
    val nodeStartTimeTicks: Long? = null
) {
    init {
        require(brokerPid > 0) { "Broker PID must be positive" }
        require(brokerStartTimeTicks > 0L) { "Broker start-time token must be positive" }
        require(brokerUid >= 0) { "Broker UID must not be negative" }
        require(processGroupId > 0) { "Process-group ID must be positive" }
        require(childPid == null || childPid > 0) { "Child PID must be positive" }
        require(childStartTimeTicks == null || childStartTimeTicks > 0L) {
            "Child start-time token must be positive"
        }
        require(nodePid == null || nodePid > 0) { "Node PID must be positive" }
        require(nodeStartTimeTicks == null || nodeStartTimeTicks > 0L) {
            "Node start-time token must be positive"
        }
        require((nodePid == null) == (nodeStartTimeTicks == null)) {
            "Node PID and start-time token must be paired"
        }
        require(ownerMarker.matches(OWNER_MARKER_PATTERN)) { "Owner marker is invalid" }
    }

    companion object {
        val OWNER_MARKER_PATTERN: Regex = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
    }
}

/** Durable metadata. The auth cookie and command output are intentionally absent. */
data class HarnessRuntimeRecord(
    val runtimeId: String = DEFAULT_RUNTIME_ID,
    val environmentId: String,
    val state: HarnessRuntimeState,
    val generation: String,
    val brokerPid: Int? = null,
    val processGroupId: Int? = null,
    val processStartTicks: Long? = null,
    val childPid: Int? = null,
    val childStartTicks: Long? = null,
    val nodePid: Int? = null,
    val nodeStartTicks: Long? = null,
    val port: Int? = null,
    val runtimeVersion: String? = null,
    val startedAt: Long? = null,
    val stopRequestedAt: Long? = null,
    val forceStopRequestedAt: Long? = null,
    val endedAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val errorCode: String? = null,
    val errorMessage: String? = null
) {
    init {
        require(runtimeId.matches(ID_PATTERN)) { "Harness runtime ID is invalid" }
        require(environmentId.matches(ID_PATTERN)) { "Harness environment ID is invalid" }
        require(generation.matches(HarnessProcessIdentity.OWNER_MARKER_PATTERN)) {
            "Harness generation is invalid"
        }
        require(port == null || port in 1024..65535) { "Harness port is invalid" }
        require(errorMessage == null || errorMessage.length <= 1_000) {
            "Harness error message is too long"
        }
        require(nodePid == null || nodePid > 0) { "Node PID must be positive" }
        require(nodeStartTicks == null || nodeStartTicks > 0L) {
            "Node start-time token must be positive"
        }
        require((nodePid == null) == (nodeStartTicks == null)) {
            "Node PID and start-time token must be paired"
        }
    }

    companion object {
        const val DEFAULT_RUNTIME_ID = "deepseek-harness-shared"
        const val DEFAULT_ENVIRONMENT_ID = "deepseek-harness-shared"
        private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")
    }
}

data class HarnessStartRequest(
    val environmentId: String = HarnessRuntimeRecord.DEFAULT_ENVIRONMENT_ID,
    val preferredPort: Int? = null,
    val readinessTimeoutMs: Long = 60_000L
) {
    init {
        require(environmentId == HarnessRuntimeRecord.DEFAULT_ENVIRONMENT_ID) {
            "DeepSeek Harness uses one shared environment"
        }
        require(preferredPort == null || preferredPort in 1024..65535) {
            "Harness preferred port is invalid"
        }
        require(readinessTimeoutMs in 1_000L..120_000L) {
            "Harness readiness timeout is invalid"
        }
    }
}

data class HarnessLaunchPreparation(
    /** Bootstrap nonce for the private ready receipt; DSH generates its own browser token. */
    val webToken: String,
    val authEnvironmentKey: String = "ADT_HARNESS_BOOTSTRAP_NONCE",
    val environment: Map<String, String> = emptyMap()
) {
    init {
        require(authEnvironmentKey.matches(ENVIRONMENT_KEY_PATTERN)) {
            "Harness auth environment key is invalid"
        }
        require(webToken.isNotBlank() && webToken.length <= MAX_WEB_TOKEN_BYTES &&
            webToken.none { it == '\u0000' || it == '\r' || it == '\n' }) {
            "Harness web token is invalid"
        }
        require(environment.keys.all { it.matches(ENVIRONMENT_KEY_PATTERN) }) {
            "Harness environment key is invalid"
        }
        require(environment.values.all { value ->
            value.length <= MAX_ENVIRONMENT_VALUE_LENGTH &&
                value.none { it == '\u0000' || it == '\r' || it == '\n' }
        }) {
            "Harness environment value is too long"
        }
        require(environment.keys.none { it in RESERVED_ENVIRONMENT_KEYS || it == authEnvironmentKey }) {
            "Harness environment attempts to override a protected value"
        }
    }

    private companion object {
        val ENVIRONMENT_KEY_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")
        const val MAX_WEB_TOKEN_BYTES = 8 * 1024
        const val MAX_ENVIRONMENT_VALUE_LENGTH = 16 * 1024
        val RESERVED_ENVIRONMENT_KEYS = setOf(
            "ADT_HARNESS_OWNER_MARKER",
            "ADT_HARNESS_PORT",
            "ADT_HARNESS_READY_FILE",
            "ADT_HARNESS_BOOTSTRAP_NONCE",
            "DSH_WEB_TOKEN",
            "HOME",
            "DSH_HOME",
            "PATH",
            "PROOT_LOADER",
            "PROOT_TMP_DIR",
            "TMPDIR",
            "TMP",
            "TEMP"
        )
    }
}

data class HarnessHookRequest(
    val environmentId: String,
    val generation: String,
    val port: Int,
    val payload: HarnessPayload,
    val paths: HarnessEnvironmentPaths
)

data class HarnessStopRequest(
    val environmentId: String,
    val generation: String,
    val mode: HarnessStopMode
)

interface HarnessRuntimeHooks {
    suspend fun prepareLaunch(request: HarnessHookRequest): HarnessLaunchPreparation
    suspend fun beforeStop(request: HarnessStopRequest)
}

object NoopHarnessRuntimeHooks : HarnessRuntimeHooks {
    override suspend fun prepareLaunch(request: HarnessHookRequest): HarnessLaunchPreparation =
        error("HarnessRuntimeHooks must provide the authenticated DSH cookie")

    override suspend fun beforeStop(request: HarnessStopRequest) = Unit
}

/** Room adapter seam. Implementations must make state transitions compare-and-set operations. */
interface HarnessRuntimeStore {
    suspend fun current(): HarnessRuntimeRecord?

    suspend fun beginStart(record: HarnessRuntimeRecord): Boolean

    suspend fun compareAndSet(
        expectedStates: Set<HarnessRuntimeState>,
        record: HarnessRuntimeRecord
    ): Boolean

    suspend fun replace(record: HarnessRuntimeRecord)
}

data class HarnessEnvironmentPaths(
    val environmentId: String,
    val rootfs: File,
    val projectsHost: File,
    val dshHomeHost: File,
    val tempHost: File,
    val runHost: File,
    val resolverFile: File,
    val projectsGuest: String = "/workspace/projects",
    val dshHomeGuest: String = "/root/.dsh",
    val harnessInstallGuest: String = "/opt/adt-harness"
) {
    init {
        require(environmentId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}"))) {
            "Harness environment ID is invalid"
        }
        require(rootfs.isDirectory) { "Harness rootfs is unavailable" }
        require(projectsHost.isDirectory) { "Harness projects directory is unavailable" }
        require(dshHomeHost.isDirectory) { "Harness home directory is unavailable" }
        require(tempHost.isDirectory && runHost.isDirectory) { "Harness runtime directories are unavailable" }
        require(projectsGuest == "/workspace/projects") { "Harness projects mount is fixed" }
        require(dshHomeGuest == "/root/.dsh") { "Harness home mount is fixed" }
        require(harnessInstallGuest == "/opt/adt-harness") { "Harness install path is fixed" }
    }
}

interface HarnessEnvironmentProvider {
    suspend fun prepare(environmentId: String): HarnessEnvironmentPaths
}

data class HarnessPayload(
    val version: String,
    val commit: String,
    val archiveAsset: String,
    val archiveSha256: String,
    val command: List<String>,
    val healthPath: String,
    val installGuestPath: String = "/opt/adt-harness"
) {
    init {
        require(version == "0.1.6-alpha.2") { "Unexpected DeepSeek Harness version" }
        require(commit == "ddefc45fbc7f8e46dd73185e68295696d1297887") {
            "Unexpected DeepSeek Harness commit"
        }
        require(archiveAsset.startsWith("harness/") && !archiveAsset.contains("..")) {
            "Harness payload asset path is invalid"
        }
        require(archiveSha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            "Harness payload checksum is invalid"
        }
        require(command.isNotEmpty()) { "Harness payload command is empty" }
        require(command.all { it.isNotBlank() && !it.contains('\u0000') }) {
            "Harness payload command contains an invalid argument"
        }
        require(command.first().startsWith(installGuestPath + "/")) {
            "Harness payload command must start inside the pinned install path"
        }
        require(healthPath.startsWith('/') && !healthPath.contains("..")) {
            "Harness health path is invalid"
        }
    }
}

interface HarnessPayloadProvider {
    suspend fun prepare(paths: HarnessEnvironmentPaths): HarnessPayload
}

interface HarnessReadinessProbe {
    suspend fun awaitReady(
        origin: String,
        webToken: String,
        readinessPath: String,
        timeoutMs: Long
    ): HarnessEndpoint
}

interface HarnessProcessHandle {
    val brokerPid: Int
    fun isAlive(): Boolean
    fun requestGracefulStop()
    fun requestForceStop()
    suspend fun awaitExit(timeoutMs: Long): Boolean

    /**
     * Returns content-free diagnostics collected from the launch pipes and process status.
     * Implementations must never return the captured output itself. The default keeps test and
     * non-Android launchers source compatible while allowing the Android launcher to classify an
     * early PRoot/Node failure before readiness turns it into a timeout.
     */
    fun diagnostics(): HarnessProcessDiagnostics = HarnessProcessDiagnostics()

    /**
     * Gives launch pipe readers a bounded opportunity to classify an early process exit. The
     * default keeps synthetic and non-Android handles source compatible.
     */
    suspend fun diagnosticsAfterExit(timeoutMs: Long = 250L): HarnessProcessDiagnostics = diagnostics()
}

/** Bounded, content-free process diagnostics suitable for a durable runtime event. */
data class HarnessProcessDiagnostics(
    val stderrCode: String? = null,
    val stdoutCode: String? = null,
    val exitCode: Int? = null
)

/**
 * Content-free liveness observed before stale-owner cleanup.  Nullable child/node fields mean
 * that the corresponding identity was not captured, while `false` means a captured process was
 * revalidated and is gone.  This distinction keeps recovery honest when /proc cannot identify a
 * descendant after the app process has been recreated.
 */
data class HarnessProcessObservation(
    val brokerAlive: Boolean,
    val childAlive: Boolean? = null,
    val nodeAlive: Boolean? = null,
    val ownerAlive: Boolean = brokerAlive,
    val exitCode: Int? = null,
    val errorCode: String? = null,
) {
    val knownOwnerLost: Boolean
        get() = !brokerAlive || childAlive == false || nodeAlive == false
}

data class HarnessLaunchSpec(
    val payload: HarnessPayload,
    val paths: HarnessEnvironmentPaths,
    val port: Int,
    val ownerMarker: String,
    val preparation: HarnessLaunchPreparation,
    val brokerPath: File,
    val prootPath: File,
    val loaderPath: File?
) {
    init {
        require(port in 1024..65535) { "Harness port is invalid" }
        require(ownerMarker.matches(HarnessProcessIdentity.OWNER_MARKER_PATTERN)) {
            "Harness owner marker is invalid"
        }
    }
}

interface HarnessProcessLauncher {
    fun launch(spec: HarnessLaunchSpec): HarnessProcessHandle
}

data class HarnessNativeBinaries(
    val brokerPath: File,
    val prootPath: File,
    val loaderPath: File?
) {
    init {
        require(brokerPath.isFile && brokerPath.canExecute()) { "Harness broker is unavailable" }
        require(prootPath.isFile && prootPath.canExecute()) { "Harness PRoot is unavailable" }
        require(loaderPath == null || loaderPath.isFile) { "Harness PRoot loader is unavailable" }
    }
}

fun interface HarnessPortAllocator {
    fun allocate(preferredPort: Int?): Int
}

data class HarnessRuntimeLogEvent(
    val event: String,
    val environmentId: String,
    val generation: String,
    val state: HarnessRuntimeState? = null,
    val durationMs: Long? = null,
    val errorCode: String? = null,
    /** Fixed vocabulary phase, never free-form process output. */
    val phase: String? = null,
    val exitCode: Int? = null
)

fun interface HarnessRuntimeMetadataLogger {
    fun record(event: HarnessRuntimeLogEvent)
}

object NoopHarnessRuntimeMetadataLogger : HarnessRuntimeMetadataLogger {
    override fun record(event: HarnessRuntimeLogEvent) = Unit
}

data class HarnessRuntimeOutcome(
    val record: HarnessRuntimeRecord,
    val endpoint: HarnessEndpoint? = null,
    val timedOut: Boolean = false
)

class HarnessRuntimeException(
    val code: String,
    message: String,
    cause: Throwable? = null,
    /** Also available when the process exits before a handle can be returned. */
    val processDiagnostics: HarnessProcessDiagnostics? = null,
) : IllegalStateException(message, cause)
