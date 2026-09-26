package com.example.llamadroid.harness

import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Supplier
import org.apache.sshd.common.util.security.SecurityProviderChoice
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.common.util.io.PathUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * Selects the bundled Bouncy Castle provider by instance for Apache MINA SSHD.
 *
 * Android already registers a provider named `BC`. MINA SSHD 2.19 resolves its EC parameter
 * factory through that name, but some Android releases do not expose the `secp384r1` parameters
 * through the platform provider. Registering another provider under the same name cannot replace
 * Android's provider. A provider instance choice keeps the fix inside SSHD's JCA factories and
 * leaves the process-wide provider order untouched. The SSHD BC registrar is disabled before its
 * first registration so it cannot select Android's same-named provider and bypass the instance
 * choice.
 */
internal object HarnessSshdPlatform {
    private val providerChoice: SecurityProviderChoice by lazy {
        SecurityProviderChoice.toSecurityProviderChoice(BouncyCastleProvider())
    }

    /**
     * Must run before the first SSHD server/client class initializes its default factories.
     *
     * Android does not provide a usable JVM `user.home`. SSHD's default authorized-key setup
     * resolves that value while `SshServer` initializes, so give SSHD a canonical directory in
     * the app's private files tree. This uses SSHD's resolver hook and deliberately leaves the
     * process-wide `user.home` property unchanged.
     */
    fun configure(userHome: Path) {
        val resolvedHome = userHome.toAbsolutePath().normalize()
        require(Files.isDirectory(resolvedHome)) { "The SSHD user-home directory is unavailable." }
        require(!Files.isSymbolicLink(resolvedHome)) {
            "The SSHD user-home directory must not be a symbolic link."
        }
        PathUtils.setUserHomeFolderResolver(Supplier { resolvedHome })
        // SecurityEntityFactory gives a registered provider registrar precedence over the default
        // choice. Android already has a provider named BC, so leave that registrar out of SSHD's
        // registry and let every SSHD JCA factory use this provider instance instead.
        SecurityUtils.setAPrioriDisabledProvider(SecurityUtils.BOUNCY_CASTLE, true)
        SecurityUtils.setDefaultProviderChoice(providerChoice)
    }

    /** Exposed to JVM tests without exposing the provider to the rest of the app. */
    internal fun providerForTest(): SecurityProviderChoice = providerChoice
}
