package com.example.llamadroid.data.proot

import android.content.Context
import android.net.ConnectivityManager
import java.io.File

/**
 * Materializes the current Android resolver list for one guest invocation. PRoot cannot safely
 * infer Android's private resolver from the guest image (the image defaults to 127.0.0.53), so
 * the executor binds this app-private file as `/etc/resolv.conf`. An offline device gets an
 * explicit empty resolver file and therefore a normal DNS failure instead of an accidental host
 * path or a stale image-time nameserver.
 */
object AgentProotNetworkConfig {
    private const val FILE_NAME = "resolv.conf"
    private const val MAX_NAMESERVERS = 4

    fun write(context: Context, environmentId: String): File {
        val root = File(
            File(context.cacheDir, "agent-proot"),
            AgentProotEnvironmentPaths.requireSafeEnvironmentId(environmentId)
        ).canonicalFile
        require(AgentProotEnvironmentPaths.isDescendantOrSame(root, context.cacheDir.canonicalFile))
        root.mkdirs()
        val resolverFile = File(root, FILE_NAME).canonicalFile
        require(AgentProotEnvironmentPaths.isDescendantOrSame(resolverFile, root))
        val nameservers = runCatching {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            connectivity.activeNetwork?.let(connectivity::getLinkProperties)
                ?.dnsServers
                ?.asSequence()
                ?.mapNotNull { address -> address.hostAddress?.trim()?.takeIf(String::isNotBlank) }
                ?.distinct()
                ?.take(MAX_NAMESERVERS)
                ?.toList()
                .orEmpty()
        }.getOrDefault(emptyList())
        val content = buildString {
            append("# Generated from Android ConnectivityManager for this invocation.\n")
            nameservers.forEach { append("nameserver ").append(it).append('\n') }
            if (nameservers.isEmpty()) append("# No active Android resolver was available.\n")
        }
        resolverFile.writeText(content)
        return resolverFile
    }
}
