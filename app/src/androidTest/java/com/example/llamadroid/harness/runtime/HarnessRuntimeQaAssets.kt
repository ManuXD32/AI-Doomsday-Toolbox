package com.example.llamadroid.harness.runtime

import android.content.Context
import com.example.llamadroid.data.proot.AgentProotEnvironmentManager
import com.example.llamadroid.data.proot.AgentProotEnvironmentPaths
import com.example.llamadroid.data.proot.AgentProotEnvironmentSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serializes the QA carrier's immutable rootfs/payload preparation.
 *
 * The cached environment is deliberately separate from the production shared environment and
 * is retained until the QA suite's final owner check. Leases serialize complete test lifecycles;
 * each lease still receives fresh bind directories, credentials, ledgers, and process markers.
 */
internal object HarnessRuntimeQaAssets {
    const val ENVIRONMENT_ID = "qa-harness-x86-shared"

    private const val PREWARM_DIRECTORY = "adt-harness-qa-prewarm"
    private val leaseMutex = Mutex()

    /** Acquire the serialized QA lease and ensure the Debian rootfs and pinned payload exist. */
    suspend fun acquire(context: Context): Lease {
        leaseMutex.lock()
        try {
            val rootfs = ensurePrepared(context)
            return Lease(rootfs, leaseMutex)
        } catch (error: Throwable) {
            leaseMutex.unlock()
            throw error
        }
    }

    internal class Lease internal constructor(
        val rootfs: File,
        private val mutex: Mutex
    ) : Closeable {
        val environmentId: String = ENVIRONMENT_ID
        private val closed = AtomicBoolean(false)

        /** Creates fresh mutable bind paths while retaining only the verified shared rootfs. */
        fun pathsFor(scratch: File): HarnessEnvironmentPaths {
            val base = scratch.canonicalFile.apply { mkdirs() }
            val projects = File(base, "projects").apply {
                mkdirs()
                File(this, HarnessRuntimePaths.DEFAULT_PROJECT_DIRECTORY).mkdirs()
            }.canonicalFile
            val dshHome = File(base, "dsh-home").apply { mkdirs() }.canonicalFile
            val temp = File(base, "tmp").apply { mkdirs() }.canonicalFile
            val run = File(base, "run").apply { mkdirs() }.canonicalFile
            val resolver = File(base, "resolv.conf").apply {
                parentFile?.mkdirs()
                writeText("nameserver 127.0.0.1\n")
            }.canonicalFile
            return HarnessEnvironmentPaths(
                environmentId = environmentId,
                rootfs = rootfs,
                projectsHost = projects,
                dshHomeHost = dshHome,
                tempHost = temp,
                runHost = run,
                resolverFile = resolver
            )
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) mutex.unlock()
        }
    }

    private suspend fun ensurePrepared(context: Context): File = withContext(Dispatchers.IO) {
        val coroutineContext = currentCoroutineContext()
        coroutineContext.ensureActive()
        val manager = AgentProotEnvironmentManager(context)
        val rootfs = manager.prepare(
            AgentProotEnvironmentSpec(ENVIRONMENT_ID)
        ).getOrThrow().canonicalFile
        coroutineContext.ensureActive()

        // Payload preparation is marker-aware, so this is a cheap validation on later tests and
        // installs the signed archive only once on a fresh QA APK/cache.
        val scratch = File(
            context.cacheDir,
            "$PREWARM_DIRECTORY-${UUID.randomUUID()}"
        ).canonicalFile
        try {
            val paths = buildPaths(rootfs, scratch)
            val payload = AssetHarnessPayloadProvider(context).prepare(paths)
            val entrypoint = File(rootfs, payload.command.first().removePrefix("/")).canonicalFile
            require(
                AgentProotEnvironmentPaths.isDescendantOrSame(entrypoint, rootfs) &&
                    entrypoint.isFile
            ) {
                "QA Harness payload entrypoint was not activated"
            }
            coroutineContext.ensureActive()
            rootfs
        } finally {
            scratch.deleteRecursively()
        }
    }

    private fun buildPaths(rootfs: File, scratch: File): HarnessEnvironmentPaths {
        val base = scratch.canonicalFile.apply { mkdirs() }
        val projects = File(base, "projects").apply {
            mkdirs()
            File(this, HarnessRuntimePaths.DEFAULT_PROJECT_DIRECTORY).mkdirs()
        }.canonicalFile
        return HarnessEnvironmentPaths(
            environmentId = ENVIRONMENT_ID,
            rootfs = rootfs,
            projectsHost = projects,
            dshHomeHost = File(base, "dsh-home").apply { mkdirs() },
            tempHost = File(base, "tmp").apply { mkdirs() },
            runHost = File(base, "run").apply { mkdirs() },
            resolverFile = File(base, "resolv.conf").apply {
                parentFile?.mkdirs()
                writeText("nameserver 127.0.0.1\n")
            }
        )
    }
}
