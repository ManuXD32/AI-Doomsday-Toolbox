package com.example.llamadroid.harness.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.BuildConfig
import com.example.llamadroid.data.proot.AgentProotEnvironmentPaths
import com.example.llamadroid.data.proot.AgentProotNativeBinaryProvider
import com.example.llamadroid.data.proot.DebianAssetPack
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Emulator-only carrier validation. The test is deliberately skipped by production ARM64
 * variants; root runs it on the x86_64 QA AVD after assembling the QA APK.
 */
@RunWith(AndroidJUnit4::class)
class HarnessRuntimeQaCarrierTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun qaAssetsDeclareMatchingAmd64GuestAndX64Payload() {
        assumeQaCarrier()
        val rootfsManifest = readAsset("debian/manifest.json")
        assertEquals("amd64", rootfsManifest.getString("architecture"))
        assertTrue(rootfsManifest.getBoolean("qaOnly"))
        assertTrue(rootfsManifest.getString("rootfsSha256").matches(SHA256_PATTERN))

        val harnessManifest = readAsset("harness/manifest.json")
        assertEquals("x64", harnessManifest.getString("architecture"))
        assertEquals("0.1.6-alpha.2", harnessManifest.getString("version"))
        assertEquals(
            "/opt/adt-harness/bin/dsh",
            harnessManifest.getJSONArray("command").getString(0)
        )
        assertTrue(harnessManifest.getString("archiveSha256").matches(SHA256_PATTERN))

        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        listOf("libproot.so", "libproot_loader.so", "libproot_broker.so").forEach { name ->
            assertTrue("Missing QA native carrier $name", File(nativeDir, name).isFile)
        }
    }

    @Test
    fun qaCarrierExecutesPinnedX64NodeThroughBrokerAndProot(): Unit = runBlocking {
        assumeQaCarrier()
        assertTrue(
            "QA APK must run on x86_64",
            android.os.Build.SUPPORTED_ABIS.firstOrNull() == "x86_64"
        )

        // Acquire the serialized lease before the test clock starts. The first lease prepares the
        // fixed QA-only rootfs/payload; later carrier and lifecycle tests reuse only that immutable
        // tree while retaining unique mutable bind paths.
        val assets = HarnessRuntimeQaAssets.acquire(context)
        val scratch = File(context.cacheDir, "adt-harness-qa-${UUID.randomUUID()}").canonicalFile
        var process: Process? = null
        try {
            val paths = assets.pathsFor(scratch)
            val payload = AssetHarnessPayloadProvider(context).prepare(paths).copy(
                command = listOf(
                    "/opt/adt-harness/node/bin/node",
                    "-e",
                    "process.stdout.write(process.arch + ' ' + process.versions.node + '\\n')"
                )
            )
            val binaries = HarnessNativeBinaries(
                brokerPath = AgentProotNativeBinaryProvider.requireBroker(context),
                prootPath = AgentProotNativeBinaryProvider.requireProot(context),
                loaderPath = AgentProotNativeBinaryProvider.locateLoader(context)
            )
            val spec = HarnessLaunchSpec(
                payload = payload,
                paths = paths,
                port = 39091,
                ownerMarker = "qa-carrier-${UUID.randomUUID().toString().take(8)}",
                preparation = HarnessLaunchPreparation("qa-token"),
                brokerPath = binaries.brokerPath,
                prootPath = binaries.prootPath,
                loaderPath = binaries.loaderPath
            )
            val command = buildHarnessLaunchCommand(
                spec = spec,
                childPidFile = File(paths.runHost, "child.pid"),
                processLedgerFile = File(paths.runHost, "processes.ledger"),
                procRoot = File("/proc"),
                devices = (listOf("null", "zero", "random", "urandom")
                    .map { File("/dev", it) } + listOf(File("/dev/ptmx"), File("/dev/pts")))
                    .filter { it.exists() }
            )
            val envVars = mapOf(
                "PROOT_NO_SECCOMP" to "1",
                "PROOT_TMP_DIR" to paths.tempHost.absolutePath,
                "LD_LIBRARY_PATH" to File(context.applicationInfo.nativeLibraryDir).absolutePath,
                "PROOT_LOADER" to (binaries.loaderPath?.absolutePath ?: "libproot_loader.so"),
                "HOME" to "/root",
                "DSH_HOME" to "/root/.dsh",
                "TMPDIR" to AgentProotEnvironmentPaths.TMP_MOUNT,
                "TMP" to AgentProotEnvironmentPaths.TMP_MOUNT,
                "TEMP" to AgentProotEnvironmentPaths.TMP_MOUNT,
                "PATH" to "/opt/adt-harness/node/bin:/usr/bin:/bin",
                "ADT_HARNESS_OWNER_MARKER" to spec.ownerMarker,
                "DSH_WEB_TOKEN" to "qa-token"
            )
            process = ProcessBuilder(command)
                .directory(File(paths.projectsHost, HarnessRuntimePaths.DEFAULT_PROJECT_DIRECTORY))
                .redirectErrorStream(true)
                .apply { environment().putAll(envVars) }
                .start()
            val launched = requireNotNull(process)
            assertTrue("QA broker/PRoot did not exit", launched.waitFor(30, TimeUnit.SECONDS))
            val output = launched.inputStream.bufferedReader().use { it.readText() }
            assertEquals("QA broker/PRoot exited unsuccessfully: $output", 0, launched.exitValue())
            assertTrue("Pinned x64 Node did not execute: $output", output.trim().startsWith("x64 24."))
        } finally {
            try {
                process?.takeIf { it.isAlive }?.destroyForcibly()
                scratch.deleteRecursively()
            } finally {
                // The shared QA environment is intentionally retained for the suite's final
                // owner check; only this test's mutable bind tree is removed here.
                assets.close()
            }
        }
    }

    private fun assumeQaCarrier() {
        assumeTrue("This test requires the explicit x86_64 Harness QA build", BuildConfig.HARNESS_QA_X86)
    }

    private fun readAsset(path: String): JSONObject {
        val input = requireNotNull(DebianAssetPack.open(context, path)) { "Missing QA asset $path" }
        return input.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    }

    private companion object {
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}
