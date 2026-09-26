package com.example.llamadroid.harness

import android.content.Context
import android.util.Log
import com.example.llamadroid.harness.runtime.AndroidHarnessEnvironmentProvider
import com.example.llamadroid.harness.runtime.HarnessEnvironmentPaths
import com.example.llamadroid.harness.runtime.HarnessRuntimePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.SftpClientFactory
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.io.nio2.Nio2ServiceFactoryFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.forward.RejectAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpFileSystemAccessor
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketAddress
import java.nio.file.AccessDeniedException
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** State of the explicit LAN file-transfer service. Credentials are held only while it runs. */
enum class HarnessRecoveryFileServerStatus {
    DISABLED,
    STARTING,
    STOPPING,
    RUNNING,
    FAILED
}

/** The last lifecycle phase is retained so the UI can explain what failed without leaking host details. */
enum class HarnessRecoveryFileServerPhase {
    IDLE,
    PREPARING_ENVIRONMENT,
    VALIDATING_ROOTFS,
    GENERATING_HOST_KEY,
    INITIALIZING_SERVICE,
    BINDING_SOCKET,
    VERIFYING_READINESS,
    STOPPING,
    READY
}

data class HarnessRecoveryFileServerState(
    val status: HarnessRecoveryFileServerStatus = HarnessRecoveryFileServerStatus.DISABLED,
    val phase: HarnessRecoveryFileServerPhase = HarnessRecoveryFileServerPhase.IDLE,
    val username: String? = null,
    val password: String? = null,
    val port: Int? = null,
    val boundAddressCount: Int = 0,
    val readinessVerified: Boolean = false,
    val addresses: List<String> = emptyList(),
    val rootLabel: String = "/",
    val errorCode: String? = null
) {
    val isRunning: Boolean
        get() = status == HarnessRecoveryFileServerStatus.RUNNING

    val isBusy: Boolean
        get() = status == HarnessRecoveryFileServerStatus.STARTING ||
            status == HarnessRecoveryFileServerStatus.STOPPING

    /** Copyable endpoint hints without putting the password into a URI or diagnostic log. */
    val endpoints: List<String>
        get() = if (username == null || port == null) emptyList() else addresses.map {
            "sftp://$username@${formatSftpAddress(it)}:$port/"
        }
}

private fun formatSftpAddress(address: String): String =
    if (address.contains(':') && !address.startsWith('[')) "[$address]" else address

private const val RECOVERY_SSHD_HOME_DIRECTORY = "agent_harness/recovery/sshd_home"

/**
 * Creates the SSHD-only metadata home below the app's private files directory.
 *
 * SSHD asks for a user home while constructing its default server factories, but Android has no
 * JVM `user.home`. Keep that fallback directory separate from the managed Debian rootfs and
 * reject symlinked path components before creating anything below the app-private root.
 */
internal fun resolveRecoverySshdUserHome(filesDirectory: File): Path {
    val appFiles = filesDirectory.canonicalFile
    require(appFiles.isDirectory) { "The app-private files directory is unavailable." }
    var current = appFiles
    RECOVERY_SSHD_HOME_DIRECTORY.split('/').forEach { segment ->
        current = File(current, segment)
        require(!java.nio.file.Files.isSymbolicLink(current.toPath())) {
            "The SSHD user-home path contains a symbolic link."
        }
        if (!current.exists()) {
            require(current.mkdir() || current.isDirectory) {
                "The SSHD user-home directory could not be created."
            }
        }
        require(current.isDirectory) { "The SSHD user-home path is not a directory." }
    }
    val resolved = current.canonicalFile.toPath()
    require(resolved != appFiles.toPath() && resolved.startsWith(appFiles.toPath())) {
        "The SSHD user-home path escaped the app-private files directory."
    }
    require(!java.nio.file.Files.isSymbolicLink(resolved)) {
        "The SSHD user-home directory must not be a symbolic link."
    }
    return resolved
}

/**
 * Returns the single TCP port actually bound by MINA after an ephemeral-port start.
 *
 * A listener may expose both IPv4 and IPv6 bound addresses, but they must describe the same
 * port. Failing closed here prevents the UI from advertising port 0 or an ambiguous endpoint.
 */
internal fun resolveRecoverySftpPort(boundAddresses: Set<SocketAddress>): Int {
    val ports = boundAddresses
        .mapNotNull { (it as? InetSocketAddress)?.port }
        .filter { it in 1024..65535 }
        .distinct()
    require(ports.size == 1) {
        "The recovery file server did not expose one usable bound port."
    }
    return ports.single()
}

/**
 * Authenticated, opt-in SFTP export for the managed Debian rootfs.
 *
 * The service intentionally exposes SFTP only. There is no shell, exec channel, public-key
 * fallback, port forwarding, or anonymous mode. OpenSSH `scp` uses SFTP by default on current
 * clients, so `scp -r` works through this service; legacy `scp -O` is rejected because it needs an
 * exec channel. The root is a canonical-path confined managed rootfs, never the app data parent.
 */
class HarnessRecoveryFileServer private constructor(context: Context) {
    companion object {
        const val USERNAME = "recovery"
        private const val TAG = "HarnessRecoverySftp"
        private const val PASSWORD_BYTES = 24

        @Volatile
        private var instance: HarnessRecoveryFileServer? = null

        fun get(context: Context): HarnessRecoveryFileServer =
            instance ?: synchronized(this) {
                instance ?: HarnessRecoveryFileServer(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val environmentProvider = AndroidHarnessEnvironmentProvider(appContext)
    private val operationLock = Mutex()
    private val _state = MutableStateFlow(HarnessRecoveryFileServerState())
    private var server: SshServer? = null

    val state: StateFlow<HarnessRecoveryFileServerState> = _state.asStateFlow()

    /** Prepares the managed environment and binds a random TCP port on all local interfaces. */
    suspend fun start(): Result<HarnessRecoveryFileServerState> = withContext(Dispatchers.IO) {
        operationLock.withLock {
            // Keep environment preparation inside the same lock as bind/stop. Otherwise a
            // retry can finish after a stop request and overwrite its DISABLED state.
            publish(
                HarnessRecoveryFileServerState(
                    status = HarnessRecoveryFileServerStatus.STARTING,
                    phase = HarnessRecoveryFileServerPhase.PREPARING_ENVIRONMENT
                )
            )
            val pathsResult = runCatching {
                environmentProvider.prepare(HarnessRuntimePaths.SHARED_ENVIRONMENT_ID)
            }
            if (pathsResult.isFailure) {
                val failure = pathsResult.exceptionOrNull()!!
                publish(
                    HarnessRecoveryFileServerState(
                        status = HarnessRecoveryFileServerStatus.FAILED,
                        phase = HarnessRecoveryFileServerPhase.PREPARING_ENVIRONMENT,
                        errorCode = sanitizeRecoverySftpFailure(
                            failure,
                            HarnessRecoveryFileServerPhase.PREPARING_ENVIRONMENT
                        )
                    )
                )
                return@withLock Result.failure(failure)
            }
            startPrepared(pathsResult.getOrThrow())
        }
    }

    /** Starts the server against already prepared paths; useful when the runtime screen has them. */
    suspend fun start(paths: HarnessEnvironmentPaths): Result<HarnessRecoveryFileServerState> =
        withContext(Dispatchers.IO) {
            operationLock.withLock { startPrepared(paths) }
        }

    private fun startPrepared(paths: HarnessEnvironmentPaths): Result<HarnessRecoveryFileServerState> {
        var phase = HarnessRecoveryFileServerPhase.PREPARING_ENVIRONMENT
        var candidate: SshServer? = null
        var actualPort: Int? = null
        return runCatching {
                    if (server?.isStarted == true && _state.value.isRunning) return@runCatching _state.value
                    server?.let { stale ->
                        phase = HarnessRecoveryFileServerPhase.STOPPING
                        publish(
                            HarnessRecoveryFileServerState(
                                status = HarnessRecoveryFileServerStatus.STARTING,
                                phase = phase
                            )
                        )
                        if (stale.isStarted) stale.stop(true)
                        server = null
                    }
                    publish(
                        HarnessRecoveryFileServerState(
                            status = HarnessRecoveryFileServerStatus.STARTING,
                            phase = HarnessRecoveryFileServerPhase.VALIDATING_ROOTFS
                        )
                    )
                    phase = HarnessRecoveryFileServerPhase.VALIDATING_ROOTFS
                    require(paths.rootfs.isDirectory) { "The managed Debian rootfs is unavailable." }
                    val password = generatePassword()
                    phase = HarnessRecoveryFileServerPhase.GENERATING_HOST_KEY
                    val hostKey = File(
                        appContext.filesDir,
                        "agent_harness/recovery/ssh_host_key.ser"
                    )
                    hostKey.parentFile?.mkdirs()
                    require(hostKey.parentFile?.isDirectory == true) {
                        "The recovery SFTP host-key directory is unavailable."
                    }
                    require(!java.nio.file.Files.isSymbolicLink(hostKey.toPath())) {
                        "The recovery SFTP host-key path is a symbolic link."
                    }
                    phase = HarnessRecoveryFileServerPhase.INITIALIZING_SERVICE
                    val sshdUserHome = resolveRecoverySshdUserHome(appContext.filesDir)
                    HarnessSshdPlatform.configure(sshdUserHome)
                    val ssh = SshServer.setUpDefaultServer().apply {
                        // Use the Android-supported transport directly; R8 must not have to
                        // preserve a factory selected only by a ServiceLoader/class-name string.
                        ioServiceFactoryFactory = Nio2ServiceFactoryFactory()
                        host = "0.0.0.0"
                        port = 0
                        // RSA is available on all supported Android providers and avoids the EC
                        // provider differences that made the first connection fail on some phones.
                        keyPairProvider = SimpleGeneratorHostKeyProvider(hostKey.toPath()).apply {
                            algorithm = "RSA"
                            keySize = 2048
                        }
                        userAuthFactories = listOf(UserAuthPasswordFactory.INSTANCE)
                        passwordAuthenticator = PasswordAuthenticator { username, supplied, _ ->
                            username == USERNAME && recoveryPasswordMatches(password, supplied)
                        }
                        publickeyAuthenticator = PublickeyAuthenticator { _, _, _ -> false }
                        keyboardInteractiveAuthenticator = null
                        shellFactory = null
                        commandFactory = null
                        subsystemFactories = listOf(
                            SftpSubsystemFactory().apply {
                                fileSystemAccessor = ConfinedSftpFileSystemAccessor(paths.rootfs.toPath())
                            }
                        )
                        forwardingFilter = RejectAllForwardingFilter.INSTANCE
                        fileSystemFactory = VirtualFileSystemFactory(paths.rootfs.toPath())
                    }
                    candidate = ssh
                    phase = HarnessRecoveryFileServerPhase.BINDING_SOCKET
                    publish(
                        HarnessRecoveryFileServerState(
                            status = HarnessRecoveryFileServerStatus.STARTING,
                            phase = phase
                        )
                    )
                    try {
                        ssh.start()
                    } catch (error: Throwable) {
                        runCatching { ssh.stop(true) }
                        throw error
                    }
                    // Apache MINA allocates port 0 through the acceptor. The configured
                    // SshServer.port is not the source of truth on every Android build, so
                    // derive the published endpoint from the address that was really bound.
                    actualPort = resolveRecoverySftpPort(ssh.boundAddresses)
                    val boundAddressCount = ssh.boundAddresses.size
                    phase = HarnessRecoveryFileServerPhase.VERIFYING_READINESS
                    publish(
                        HarnessRecoveryFileServerState(
                            status = HarnessRecoveryFileServerStatus.STARTING,
                            phase = phase,
                            port = actualPort,
                            boundAddressCount = boundAddressCount,
                            rootLabel = "/"
                        )
                    )
            verifyReadiness(ssh, actualPort!!, USERNAME, password)
                    server = ssh
                    val next = HarnessRecoveryFileServerState(
                        status = HarnessRecoveryFileServerStatus.RUNNING,
                        phase = HarnessRecoveryFileServerPhase.READY,
                        username = USERNAME,
                        password = password,
                        port = actualPort,
                        boundAddressCount = boundAddressCount,
                        readinessVerified = true,
                        addresses = localAddresses(),
                        rootLabel = "/"
                    )
                    publish(next)
                    next
        }.onFailure {
            runCatching { candidate?.let { if (it.isStarted) it.stop(true) } }
            server = null
            publish(
                HarnessRecoveryFileServerState(
                    status = HarnessRecoveryFileServerStatus.FAILED,
                    phase = phase,
                    port = actualPort,
                    boundAddressCount = candidate?.boundAddresses?.size ?: 0,
                    errorCode = sanitizeRecoverySftpFailure(it, phase)
                )
            )
        }
    }

    suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        operationLock.withLock {
            publish(
                _state.value.copy(
                    status = HarnessRecoveryFileServerStatus.STOPPING,
                    phase = HarnessRecoveryFileServerPhase.STOPPING
                )
            )
            runCatching {
                val current = server
                if (current != null) current.stop(true)
                server = null
                publish(HarnessRecoveryFileServerState())
            }.onFailure {
                publish(
                    HarnessRecoveryFileServerState(
                        status = HarnessRecoveryFileServerStatus.FAILED,
                        phase = HarnessRecoveryFileServerPhase.STOPPING,
                        errorCode = sanitizeRecoverySftpFailure(
                            it,
                            HarnessRecoveryFileServerPhase.STOPPING
                        )
                    )
                )
            }
        }
    }

    suspend fun refreshAddresses(): Result<HarnessRecoveryFileServerState> =
        withContext(Dispatchers.IO) {
            operationLock.withLock {
                runCatching {
                    val current = _state.value
                    if (!current.isRunning) return@runCatching current
                    current.copy(addresses = localAddresses()).also(::publish)
                }
            }
        }

    fun isRunning(): Boolean = server?.isStarted == true

    /**
     * Verifies the complete local protocol path before advertising READY. A TCP connect alone
     * cannot detect an SSHD service-discovery, password-authentication, or SFTP subsystem failure.
     */
    private fun verifyReadiness(
        ssh: SshServer,
        port: Int,
        username: String,
        password: String
    ) {
        require(ssh.isStarted && ssh.isOpen) { "The recovery SFTP server did not remain open." }
        Socket().use { socket ->
            socket.soTimeout = 2_000
            socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
        }
        val client = SshClient.setUpDefaultClient().apply {
            ioServiceFactoryFactory = Nio2ServiceFactoryFactory()
            serverKeyVerifier = ServerKeyVerifier { _, _, _ -> true }
        }
        var session: org.apache.sshd.client.session.ClientSession? = null
        var sftp: SftpClient? = null
        try {
            client.start()
            session = client.connect(username, "127.0.0.1", port).verify(2_000L).session
            val authenticatedSession = requireNotNull(session)
            authenticatedSession.addPasswordIdentity(password)
            authenticatedSession.auth().verify(2_000L)
            sftp = SftpClientFactory.instance().createSftpClient(authenticatedSession)
            val authenticatedSftp = requireNotNull(sftp)
            require(authenticatedSftp.readDir(".").iterator().hasNext()) {
                "The recovery SFTP root listing was empty."
            }
        } finally {
            runCatching { sftp?.close() }
            runCatching { session?.close() }
            runCatching { client.stop() }
        }
    }

    private fun publish(next: HarnessRecoveryFileServerState) {
        _state.value = next
    }

    private fun localAddresses(): List<String> {
        val found = buildList {
            runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { network ->
                    if (!network.isUp || network.isLoopback) return@forEach
                    network.inetAddresses.toList().forEach { address ->
                        if (address is Inet4Address && !address.isLoopbackAddress) {
                            address.hostAddress?.let(::add)
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "Unable to enumerate LAN addresses", it) }
        }.distinct().sorted()
        return if (found.isEmpty()) listOf("127.0.0.1") else found
    }

    private fun generatePassword(): String {
        val bytes = ByteArray(PASSWORD_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

}

/** Maps host failures to stable diagnostics without exposing paths, credentials, or stack text. */
internal fun sanitizeRecoverySftpFailure(
    failure: Throwable,
    phase: HarnessRecoveryFileServerPhase? = null,
): String {
    val chain = generateSequence(failure) { it.cause }.toList()
    return when {
        chain.any { it is java.net.BindException } -> "RECOVERY_SFTP_PORT_UNAVAILABLE"
        chain.any { it is java.nio.file.NoSuchFileException } -> "RECOVERY_SFTP_ROOTFS_MISSING"
        chain.any { it is java.nio.file.AccessDeniedException } -> "RECOVERY_SFTP_ACCESS_DENIED"
        phase == HarnessRecoveryFileServerPhase.INITIALIZING_SERVICE ->
            "RECOVERY_SFTP_SERVICE_INIT_FAILED"
        chain.any { it is java.security.GeneralSecurityException } -> "RECOVERY_SFTP_KEY_FAILED"
        chain.any { it.message?.contains("rootfs", ignoreCase = true) == true } -> "RECOVERY_SFTP_ROOTFS_UNAVAILABLE"
        phase == HarnessRecoveryFileServerPhase.PREPARING_ENVIRONMENT -> "RECOVERY_SFTP_ENVIRONMENT_UNAVAILABLE"
        phase == HarnessRecoveryFileServerPhase.GENERATING_HOST_KEY -> "RECOVERY_SFTP_KEY_FAILED"
        phase == HarnessRecoveryFileServerPhase.BINDING_SOCKET -> "RECOVERY_SFTP_BIND_FAILED"
        phase == HarnessRecoveryFileServerPhase.VERIFYING_READINESS -> "RECOVERY_SFTP_READINESS_FAILED"
        phase == HarnessRecoveryFileServerPhase.STOPPING -> "RECOVERY_SFTP_STOP_FAILED"
        else -> "RECOVERY_SFTP_OPERATION_FAILED"
    }
}

/** Testable path policy shared by the SFTP accessor and recovery security tests. */
internal fun isPathInsideRoot(candidate: File, root: File): Boolean {
    val candidatePath = runCatching { candidate.canonicalFile.toPath() }.getOrNull() ?: return false
    val rootPath = runCatching { root.canonicalFile.toPath() }.getOrNull() ?: return false
    return candidatePath == rootPath || candidatePath.startsWith(rootPath)
}

/** Constant-time credential comparison kept package-visible for focused authentication tests. */
internal fun recoveryPasswordMatches(expected: String, supplied: String): Boolean {
    val expectedBytes = expected.toByteArray(Charsets.UTF_8)
    val suppliedBytes = supplied.toByteArray(Charsets.UTF_8)
    return MessageDigest.isEqual(expectedBytes, suppliedBytes)
}

/**
 * Prevents traversal and symlink escape while retaining the rootfs' internal `/usr` links.
 * Client-created links are refused entirely, so a transfer cannot turn the server into an
 * arbitrary-file bridge.
 */
private class ConfinedSftpFileSystemAccessor(
    private val root: Path
) : SftpFileSystemAccessor by SftpFileSystemAccessor.DEFAULT {
    override fun resolveLocalFilePath(
        subsystem: org.apache.sshd.sftp.server.SftpSubsystemProxy,
        dir: Path,
        remotePath: String
    ): Path {
        val candidate = SftpFileSystemAccessor.DEFAULT.resolveLocalFilePath(subsystem, dir, remotePath)
        require(isPathInsideRoot(candidate.toFile(), root.toFile())) {
            "SFTP path escapes the managed Debian rootfs."
        }
        return candidate
    }

    override fun createLink(
        subsystem: org.apache.sshd.sftp.server.SftpSubsystemProxy,
        link: Path,
        target: Path,
        symLink: Boolean
    ) {
        throw AccessDeniedException("Symbolic and hard links are disabled for recovery transfers.")
    }

    override fun resolveLinkTarget(
        subsystem: org.apache.sshd.sftp.server.SftpSubsystemProxy,
        link: Path
    ): String {
        val target = SftpFileSystemAccessor.DEFAULT.resolveLinkTarget(subsystem, link)
        require(isPathInsideRoot(link.parent.resolve(target).toFile(), root.toFile())) {
            "SFTP link target escapes the managed Debian rootfs."
        }
        return target
    }

    override fun removeFile(
        subsystem: org.apache.sshd.sftp.server.SftpSubsystemProxy,
        path: Path,
        isDirectory: Boolean
    ) {
        require(isPathInsideRoot(path.toFile(), root.toFile())) {
            "SFTP path escapes the managed Debian rootfs."
        }
        SftpFileSystemAccessor.DEFAULT.removeFile(subsystem, path, isDirectory)
    }
}
