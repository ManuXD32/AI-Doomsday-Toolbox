package com.example.llamadroid.harness

import java.nio.file.Files
import java.security.AlgorithmParameters
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.net.BindException
import java.net.InetSocketAddress
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.apache.sshd.common.cipher.ECCurves
import org.apache.sshd.common.util.io.PathUtils
import org.apache.sshd.common.util.security.SecurityUtils

class HarnessRecoveryFileServerTest {
    @Test
    fun canonicalInsideRootfsAllowsInternalSymlink() {
        val root = Files.createTempDirectory("recovery-root").toAbsolutePath()
        try {
            root.resolve("usr/bin").createDirectories()
            root.resolve("usr/bin/bash").toFile().writeText("bash")
            root.resolve("bin").createSymbolicLinkPointingTo(root.resolve("usr/bin"))

            assertTrue(isPathInsideRoot(root.resolve("bin/bash").toFile(), root.toFile()))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun canonicalOutsideRootfsRejectsTraversalAndExternalSymlink() {
        val root = Files.createTempDirectory("recovery-root").toAbsolutePath()
        val outside = Files.createTempDirectory("recovery-outside").toAbsolutePath()
        try {
            outside.resolve("secret").toFile().writeText("private")
            root.resolve("etc").createDirectories()
            root.resolve("etc/escape").createSymbolicLinkPointingTo(outside.resolve("secret"))

            assertFalse(isPathInsideRoot(root.resolve("../recovery-outside/secret").toFile(), root.toFile()))
            assertFalse(isPathInsideRoot(root.resolve("etc/escape").toFile(), root.toFile()))
        } finally {
            root.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun endpointHintsNeverContainPassword() {
        val state = HarnessRecoveryFileServerState(
            status = HarnessRecoveryFileServerStatus.RUNNING,
            username = "recovery",
            password = "temporary-secret",
            port = 22222,
            addresses = listOf("192.0.2.20")
        )

        assertTrue(state.endpoints.single() == "sftp://recovery@192.0.2.20:22222/")
        assertFalse(state.endpoints.single().contains(state.password.orEmpty()))
    }

    @Test
    fun passwordAuthenticationRequiresExactSecret() {
        assertTrue(recoveryPasswordMatches("temporary-secret", "temporary-secret"))
        assertFalse(recoveryPasswordMatches("temporary-secret", "temporary-secret-2"))
        assertFalse(recoveryPasswordMatches("temporary-secret", ""))
    }

    @Test
    fun endpointHintsFormatIpv6WithoutChangingIpv4() {
        val state = HarnessRecoveryFileServerState(
            status = HarnessRecoveryFileServerStatus.RUNNING,
            username = "recovery",
            port = 22222,
            addresses = listOf("192.0.2.20", "2001:db8::20")
        )

        assertEquals(
            listOf(
                "sftp://recovery@192.0.2.20:22222/",
                "sftp://recovery@[2001:db8::20]:22222/"
            ),
            state.endpoints
        )
    }

    @Test
    fun resolvesPortFromActuallyBoundAddressesWhenConfiguredPortWasEphemeral() {
        val actualPort = resolveRecoverySftpPort(
            setOf(
                InetSocketAddress("192.0.2.20", 40231),
                InetSocketAddress("2001:db8::20", 40231)
            )
        )

        assertEquals(40231, actualPort)
    }

    @Test
    fun rejectsAmbiguousOrUnusableBoundPorts() {
        assertThrows(IllegalArgumentException::class.java) {
            resolveRecoverySftpPort(
                setOf(
                    InetSocketAddress("192.0.2.20", 40231),
                    InetSocketAddress("192.0.2.20", 40232)
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolveRecoverySftpPort(setOf(InetSocketAddress("192.0.2.20", 0)))
        }
    }

    @Test
    fun failureCodesRetainPhaseWithoutExposingHostDetails() {
        assertEquals(
            "RECOVERY_SFTP_PORT_UNAVAILABLE",
            sanitizeRecoverySftpFailure(BindException("secret host:1234"), HarnessRecoveryFileServerPhase.BINDING_SOCKET)
        )
        assertEquals(
            "RECOVERY_SFTP_READINESS_FAILED",
            sanitizeRecoverySftpFailure(
                IllegalStateException("socket did not accept"),
                HarnessRecoveryFileServerPhase.VERIFYING_READINESS
            )
        )
        assertEquals(
            "RECOVERY_SFTP_ENVIRONMENT_UNAVAILABLE",
            sanitizeRecoverySftpFailure(
                IllegalStateException("preparation failed at /private/path"),
                HarnessRecoveryFileServerPhase.PREPARING_ENVIRONMENT
            )
        )
        assertEquals(
            "RECOVERY_SFTP_SERVICE_INIT_FAILED",
            sanitizeRecoverySftpFailure(
                ExceptionInInitializerError(IllegalArgumentException("No EC params for nistp384")),
                HarnessRecoveryFileServerPhase.INITIALIZING_SERVICE
            )
        )
    }

    @Test
    fun sshdUserHomeIsAppPrivateAndDoesNotChangeJvmUserHome() {
        val appFiles = Files.createTempDirectory("recovery-app-files")
        try {
            val systemHome = System.getProperty("user.home")
            val sshdHome = resolveRecoverySshdUserHome(appFiles.toFile())

            HarnessSshdPlatform.configure(sshdHome)

            assertTrue(sshdHome.startsWith(appFiles))
            assertEquals(sshdHome, PathUtils.getUserHomeFolder())
            assertEquals(systemHome, System.getProperty("user.home"))
        } finally {
            appFiles.toFile().deleteRecursively()
        }
    }

    @Test
    fun sshdUserHomeRejectsSymlinkedAppPrivatePathComponent() {
        val appFiles = Files.createTempDirectory("recovery-app-files")
        val outside = Files.createTempDirectory("recovery-outside")
        try {
            Files.createSymbolicLink(appFiles.resolve("agent_harness"), outside)

            assertThrows(IllegalArgumentException::class.java) {
                resolveRecoverySshdUserHome(appFiles.toFile())
            }
        } finally {
            appFiles.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun sshdProviderChoiceUsesBundledEcParametersWithoutNamedProviderRegistration() {
        val sshdHome = Files.createTempDirectory("recovery-sshd-home")
        try {
            HarnessSshdPlatform.configure(sshdHome)
            val choice = HarnessSshdPlatform.providerForTest()
            assertFalse(choice.isNamedProviderUsed())
            assertTrue(SecurityUtils.isAPrioriDisabledProvider(SecurityUtils.BOUNCY_CASTLE))
            val params = AlgorithmParameters.getInstance("EC", choice.securityProvider)
            params.init(ECGenParameterSpec("secp384r1"))
            assertEquals(384, params.getParameterSpec(ECParameterSpec::class.java).curve.field.fieldSize)
            assertEquals(384, ECCurves.nistp384.keySize)
        } finally {
            sshdHome.toFile().deleteRecursively()
        }
    }
}
