package com.example.llamadroid.harness

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.BuildConfig
import com.example.llamadroid.harness.runtime.AndroidHarnessEnvironmentProvider
import com.example.llamadroid.harness.runtime.HarnessRuntimePaths
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ClientChannel
import org.apache.sshd.client.channel.ClientChannelEvent
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.common.io.nio2.Nio2ServiceFactoryFactory
import org.apache.sshd.core.CoreModuleProperties
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.SftpClientFactory
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.util.EnumSet
import java.util.UUID

/**
 * Opt-in API36 emulator coverage for the actual recovery SFTP stack. It deliberately uses the
 * app's MINA server and client, so the minified x86 QA carrier exercises authentication, service
 * discovery, path confinement, and shutdown together. Run with recovery_sftp_qa=true.
 */
@RunWith(AndroidJUnit4::class)
class HarnessRecoverySftpIntegrationQaTest {
    @Test
    fun qaStartAuthenticateListReadWriteRejectTraversalAndStop() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Use the explicit x86_64 Harness QA carrier", BuildConfig.HARNESS_QA_X86)
        assumeTrue(
            "Pass recovery_sftp_qa=true for disposable API36 SFTP coverage",
            args.getString("recovery_sftp_qa") == "true"
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val paths = AndroidHarnessEnvironmentProvider(context).prepare(
            HarnessRuntimePaths.SHARED_ENVIRONMENT_ID
        )
        val server = HarnessRecoveryFileServer.get(context)
        val started = server.start(paths).getOrThrow()
        assertTrue(started.isRunning)
        assertTrue(started.readinessVerified)
        assertTrue((started.port ?: 0) in 1..65_535)
        assertTrue(started.boundAddressCount > 0)

        val client = SshClient.setUpDefaultClient().apply {
            ioServiceFactoryFactory = Nio2ServiceFactoryFactory()
        }
        client.serverKeyVerifier = ServerKeyVerifier { _, _, _ -> true }
        var session: org.apache.sshd.client.session.ClientSession? = null
        var sftp: SftpClient? = null
        val markerPath = "/tmp/adt-recovery-sftp-${UUID.randomUUID()}"
        val directoryPath = "/tmp/adt-recovery-sftp-dir-${UUID.randomUUID()}"
        val renamedDirectoryPath = "$directoryPath-renamed"
        val externalFixture = java.io.File(
            context.cacheDir,
            "recovery-sftp-external-${UUID.randomUUID()}"
        )
        val rootfsTmp = paths.rootfs.resolve("tmp").canonicalFile
        check(rootfsTmp.isDirectory) { "The managed rootfs has no /tmp fixture directory." }
        val externalLink = rootfsTmp.resolve("adt-recovery-sftp-link-${UUID.randomUUID()}")
        try {
            externalFixture.mkdirs()
            externalFixture.resolve("secret").writeText("outside-managed-rootfs")
            Files.createSymbolicLink(
                externalLink.toPath(),
                rootfsTmp.toPath().relativize(externalFixture.resolve("secret").canonicalFile.toPath())
            )
            client.start()
            session = client.connect(
                requireNotNull(started.username),
                "127.0.0.1",
                started.port!!
            ).verify(10_000L).session
            val authenticatedSession = requireNotNull(session)
            authenticatedSession.addPasswordIdentity(requireNotNull(started.password))
            authenticatedSession.auth().verify(10_000L)
            // Ask SSHD to wait for the shell/exec request reply. The channel-open future alone
            // only covers allocation and can complete before the server rejects the request.
            CoreModuleProperties.REQUEST_EXEC_REPLY.set(authenticatedSession, true)
            CoreModuleProperties.REQUEST_SHELL_REPLY.set(authenticatedSession, true)

            sftp = SftpClientFactory.instance().createSftpClient(authenticatedSession)
            val authenticatedSftp = requireNotNull(sftp)
            assertTrue("SFTP root listing was empty", authenticatedSftp.readDir(".").iterator().hasNext())
            assertTrue("SFTP rootfs metadata was not readable", authenticatedSftp.stat("/etc/os-release").size >= 0L)

            val badClient = SshClient.setUpDefaultClient().apply {
                ioServiceFactoryFactory = Nio2ServiceFactoryFactory()
                serverKeyVerifier = ServerKeyVerifier { _, _, _ -> true }
            }
            var badSession: org.apache.sshd.client.session.ClientSession? = null
            try {
                badClient.start()
                badSession = badClient.connect(
                    requireNotNull(started.username),
                    "127.0.0.1",
                    started.port!!
                ).verify(5_000L).session
                val unauthenticatedSession = requireNotNull(badSession)
                unauthenticatedSession.addPasswordIdentity("definitely-not-the-generated-secret")
                assertTrue(
                    "SFTP accepted an invalid password",
                    runCatching { unauthenticatedSession.auth().verify(5_000L) }.isFailure
                )
            } finally {
                runCatching { badSession?.close() }
                runCatching { badClient.stop() }
            }

            val payload = "recovery-sftp-qa"
            authenticatedSftp.write(
                markerPath,
                EnumSet.of(
                    SftpClient.OpenMode.Create,
                    SftpClient.OpenMode.Write,
                    SftpClient.OpenMode.Truncate
                )
            ).use { output -> output.write(payload.toByteArray(Charsets.UTF_8)) }
            val readBack = authenticatedSftp.read(markerPath).use { input -> input.readBytes() }
            assertTrue(String(readBack, Charsets.UTF_8) == payload)

            authenticatedSftp.mkdir(directoryPath)
            authenticatedSftp.rename(directoryPath, renamedDirectoryPath)
            assertTrue("SFTP renamed directory was not readable", authenticatedSftp.stat(renamedDirectoryPath).isDirectory)
            authenticatedSftp.rmdir(renamedDirectoryPath)
            assertTrue(
                "SFTP removed directory remained visible",
                runCatching { authenticatedSftp.stat(renamedDirectoryPath) }.isFailure
            )

            assertTrue(
                "Traversal outside the managed rootfs was accepted",
                runCatching { authenticatedSftp.stat("../etc/passwd") }.isFailure
            )
            assertTrue(
                "An external symlink escaped the managed rootfs",
                runCatching { authenticatedSftp.stat("/tmp/${externalLink.name}") }.isFailure
            )
            assertTrue(
                "Shell channel was accepted by an SFTP-only server",
                channelRequestIsRejected(authenticatedSession.createShellChannel())
            )
            assertTrue(
                "Exec channel was accepted by an SFTP-only server",
                channelRequestIsRejected(authenticatedSession.createExecChannel("id"))
            )
        } finally {
            runCatching { sftp?.remove(markerPath) }
            runCatching { sftp?.rmdir(renamedDirectoryPath) }
            runCatching { sftp?.rmdir(directoryPath) }
            runCatching { sftp?.close() }
            runCatching { session?.close() }
            runCatching { client.stop() }
            runCatching { Files.deleteIfExists(externalLink.toPath()) }
            externalFixture.deleteRecursively()
            server.stop().getOrThrow()
        }

        assertPortClosed(started.port!!)

        val restarted = server.start(paths).getOrThrow()
        assertTrue(restarted.isRunning)
        assertTrue(restarted.readinessVerified)
        server.stop().getOrThrow()
        assertPortClosed(restarted.port!!)
    }

    private suspend fun assertPortClosed(port: Int) {
        withTimeout(5_000L) {
            while (true) {
                val open = runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", port), 250)
                    }
                    true
                }.getOrDefault(false)
                if (!open) return@withTimeout
                delay(50L)
            }
        }
    }

    /**
     * Opening a shell or exec channel sends both SSH_MSG_CHANNEL_OPEN and a channel request.
     * MINA's [ClientChannel.open] may report the first as successful before the server rejects
     * the request because no shell or command factory is configured. Wait for the resulting close
     * event and inspect exit/output evidence so a successfully executed command cannot count as a
     * rejection merely because it later closed.
     */
    private fun channelRequestIsRejected(channel: ClientChannel): Boolean {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        channel.setOut(stdout)
        channel.setErr(stderr)
        return try {
            try {
                channel.open().verify(5_000L)
            } catch (_: IOException) {
                // A CHANNEL_OPEN or request failure is expected; wait for SSHD to settle below.
            }
            val closed = ClientChannelEvent.CLOSED in channel.waitFor(
                EnumSet.of(ClientChannelEvent.CLOSED),
                5_000L,
            )
            val state = channel.channelState
            closed &&
                ClientChannelEvent.EXIT_STATUS !in state &&
                ClientChannelEvent.EXIT_SIGNAL !in state &&
                stdout.size() == 0 &&
                stderr.size() == 0
        } finally {
            runCatching { channel.close(true) }
        }
    }
}
