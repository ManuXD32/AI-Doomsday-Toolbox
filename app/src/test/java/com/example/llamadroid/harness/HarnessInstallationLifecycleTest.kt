package com.example.llamadroid.harness

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.proot.AgentProotEnvironmentPaths
import com.example.llamadroid.data.proot.AgentProotRootfsExtractor
import com.example.llamadroid.harness.runtime.HarnessEnvironmentPaths
import com.example.llamadroid.harness.runtime.HarnessRuntimePaths
import com.example.llamadroid.harness.runtime.HarnessRuntimeScope
import com.example.llamadroid.harness.runtime.HarnessPayload
import com.example.llamadroid.harness.runtime.installHarnessPayload
import com.example.llamadroid.service.AgentForegroundService
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real catalog/activation with small failure fixtures and one complete pinned-archive installation. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], shadows = [
    HarnessDirectorySyncShadow::class, HarnessDirectorySyncShadow.CloseBridge::class,
])
class HarnessInstallationLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var manager: HarnessInstallationManager
    private lateinit var files: HarnessInstallationFiles
    private val starts = CopyOnWriteArrayList<String?>()
    private var rejectForeground = false
    private var beforePreparation: suspend () -> Unit = {}
    private var preparedStage: String? = null
    private var useBundledArchives = false

    @Before fun setUp() = runBlocking {
        HarnessDirectorySyncShadow.resetCounts()
        val storage = temporary.newFolder("files")
        val cache = temporary.newFolder("cache")
        val databases = temporary.newFolder("databases")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = storage
            override fun getCacheDir() = cache
            override fun getDatabasePath(name: String) = File(databases, name)
            override fun startForegroundService(service: Intent): ComponentName {
                if (rejectForeground) throw IllegalStateException("foreground rejected")
                starts += service.action
                return ComponentName(packageName, AgentForegroundService::class.java.name)
            }
        }
        AppDatabase.closeInstance()
        database = AppDatabase.getDatabase(context)
        files = HarnessInstallationFiles(context) { captured, stageId, progress ->
            preparedStage = stageId
            progress("ROOTFS_EXTRACT")
            beforePreparation()
            val scoped = HarnessRuntimeScope.context(captured, stageId)
            val root = AgentProotEnvironmentPaths.rootfs(captured, stageId).apply { mkdirs() }
            if (useBundledArchives) installBundledArchives(root)
            else {
                root.resolve("etc").mkdirs()
                root.resolve("etc/os-release").writeText("ID=debian")
                root.resolve("agent").writeText("verified fixture")
            }
            root.resolve(".adt-environment.json").writeText(JSONObject().put("environmentId", stageId).toString())
            val runtime = HarnessRuntimePaths.runtimeRoot(captured, stageId).apply { mkdirs() }
            progress("PAYLOAD_EXTRACT")
            HarnessEnvironmentPaths(stageId, root,
                HarnessRuntimePaths.projects(scoped).apply { mkdirs() },
                HarnessRuntimePaths.harnessHome(scoped).apply { mkdirs() },
                runtime.resolve("tmp").apply { mkdirs() }, runtime.resolve("run").apply { mkdirs() },
                runtime.resolve("resolv.conf"))
        }
        manager = HarnessInstallationManager(context, database, files)
        manager.ready()
    }

    @After fun tearDown() = runBlocking {
        if (::manager.isInitialized) {
            manager.scope.cancel()
            manager.scope.coroutineContext[Job]?.join()
        }
        AppDatabase.closeInstance()
        assertEquals(0, HarnessDirectorySyncShadow.openDirectoryCount())
    }

    @Test fun creationActivatesOneRuntimeAndPreservesTheOriginal() = runBlocking {
        val original = AgentProotEnvironmentPaths.rootfs(context, HarnessRuntimeScope.LEGACY_RUNTIME_ID).apply { mkdirs() }
        original.resolve("keep").writeText("original data")
        val id = manager.create("Experiment")
        val row = requireNotNull(database.harnessInstallationDao().getById(id))
        assertEquals("READY", row.status)
        assertEquals(id, manager.selectedId.value)
        assertEquals("COMPLETE", manager.operation.value.phase)
        assertFalse(manager.operation.value.busy)
        assertFalse(requireNotNull(manager.operation.value.toInstallationOperationUi("Completed")).busy)
        assertTrue(files.journal.pending().isEmpty())
        val root = AgentProotEnvironmentPaths.rootfs(context, id)
        assertEquals(id, JSONObject(root.resolve(".adt-environment.json").readText()).getString("environmentId"))
        assertEquals("verified fixture", root.resolve("agent").readText())
        assertEquals("original data", original.resolve("keep").readText())
        assertTrue(HarnessDirectorySyncShadow.directorySyncCount() >= 2)
        assertFalse(AgentProotEnvironmentPaths.environmentRoot(context, requireNotNull(preparedStage)).exists())
        assertEquals(listOf(AgentForegroundService.ACTION_START_HARNESS), starts.toList())
        assertEquals(0, AgentForegroundService.activeRuntimeCount())
        manager.rename(id, "Renamed")
        manager.select(HarnessRuntimeScope.LEGACY_RUNTIME_ID)
        assertEquals("Renamed", database.harnessInstallationDao().getById(id)?.displayName)
        assertEquals(2, database.harnessInstallationDao().getAll().size)
    }

    @Test fun bundledDebianAndHarnessArchivesCanCreateAnIndependentInstallation() = runBlocking {
        useBundledArchives = true
        val id = manager.create("Bundled runtime")
        val root = AgentProotEnvironmentPaths.rootfs(context, id)
        assertEquals("READY", database.harnessInstallationDao().getById(id)?.status)
        assertTrue(root.resolve("etc/os-release").isFile || root.resolve("usr/lib/os-release").isFile)
        assertTrue(root.resolve("opt/adt-harness/.adt-harness-payload.json").isFile)
        assertTrue(files.journal.pending().isEmpty())
        assertEquals(id, manager.selectedId.value)
        manager.refreshSize(id).join()
        assertTrue(requireNotNull(database.harnessInstallationDao().getById(id)).sizeBytes > 100_000_000)
    }

    private fun installBundledArchives(root: File) {
        val repository = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .first { it.resolve("asset_debian/src/main/assets").isDirectory }
        val assets = repository.resolve("asset_debian/src/main/assets")
        val rootfs = assets.resolve("debian/rootfs.tar.xz")
        assertEquals(assets.resolve("debian/rootfs.tar.xz.sha256").readText().trim().substringBefore(' '),
            AgentProotRootfsExtractor.sha256(rootfs))
        AgentProotRootfsExtractor.extract(rootfs, root)
        val manifest = JSONObject(assets.resolve("harness/manifest.json").readText())
        val command = manifest.getJSONArray("command")
        val payload = HarnessPayload(
            version = manifest.getString("version"), commit = manifest.getString("commit"),
            archiveAsset = manifest.getString("archiveAsset"), archiveSha256 = manifest.getString("archiveSha256"),
            command = List(command.length()) { command.getString(it) }, healthPath = manifest.getString("healthPath"),
        )
        installHarnessPayload(root, payload, { assets.resolve(payload.archiveAsset).inputStream() })
        assertTrue(root.resolve(payload.command.first().removePrefix("/")).isFile)
    }

    @Test fun failedCreationRetriesTheSameReceiptAndSelectsTheRecoveredRuntime() = runBlocking {
        beforePreparation = { throw IOException("private path must not become an error code") }
        assertTrue(runCatching { manager.create("Recover me") }.isFailure)
        val id = requireNotNull(manager.operation.value.runtimeId)
        assertEquals("FAILED", manager.operation.value.phase)
        val failedUi = requireNotNull(manager.operation.value.toInstallationOperationUi("Failed"))
        assertFalse(failedUi.busy)
        assertTrue(failedUi.canRetry)
        assertFalse(manager.operation.value.busy)
        assertEquals(id, files.journal.pending().single().runtimeId)
        assertEquals("HARNESS_IOEXCEPTION", files.journal.pending().single().failureCode)
        assertEquals("ROOTFS_EXTRACT", files.journal.pending().single().failurePhase)
        assertFalse(files.journal.directory.listFiles().orEmpty().filter { it.extension == "json" }.any {
            it.readText().contains("private path")
        })
        assertEquals(HarnessRuntimeScope.LEGACY_RUNTIME_ID, manager.selectedId.value)
        beforePreparation = {}
        manager.retry()
        assertEquals(id, manager.selectedId.value)
        assertEquals("READY", database.harnessInstallationDao().getById(id)?.status)
        assertEquals(2, database.harnessInstallationDao().getAll().size)
        assertTrue(files.journal.pending().isEmpty())
        assertEquals(0, AgentForegroundService.activeRuntimeCount())
    }

    @Test fun cancelledPreparationKeepsARecoverableReceiptAndBalancesTheLease() = runBlocking {
        withTimeout(30_000) {
            val entered = CompletableDeferred<Unit>()
            beforePreparation = { entered.complete(Unit); CompletableDeferred<Unit>().await() }
            val creation = async { manager.create("Cancelled") }
            entered.await()
            assertEquals("ROOTFS_EXTRACT", manager.operation.value.phase)
            assertTrue(manager.operation.value.busy)
            assertTrue(requireNotNull(manager.operation.value.toInstallationOperationUi("Preparing")).busy)
            manager.cancel()
            assertTrue(runCatching { creation.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertEquals("CANCELLED", manager.operation.value.phase)
            val id = requireNotNull(manager.operation.value.runtimeId)
            assertEquals(0, AgentForegroundService.activeRuntimeCount())
            beforePreparation = {}
            manager.retry()
            assertEquals(id, manager.selectedId.value)
            assertTrue(files.journal.pending().isEmpty())
        }
    }

    @Test fun rejectedForegroundStartDoesNotLeakALeaseOrLock() = runBlocking {
        rejectForeground = true
        assertTrue(runCatching { manager.create("Rejected") }.isFailure)
        assertEquals(0, AgentForegroundService.activeRuntimeCount())
        rejectForeground = false
        val id = manager.create("Works")
        assertEquals("READY", database.harnessInstallationDao().getById(id)?.status)
    }

    @Test fun storageFailureCannotPreventReadyOrReplaceNewerCatalogData() = runBlocking {
        val row = manager.newInstallation("Keep name")
        val outside = temporary.newFolder("outside")
        val scoped = HarnessRuntimeScope.context(context, row.id)
        val root = HarnessRuntimeScope.dataRoot(scoped)
        root.parentFile!!.mkdirs()
        Files.createSymbolicLink(root.toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { HarnessInstallationManager.safeRuntimeSize(context, row.id) }
        manager.markReady(row.id, select = false)
        manager.refreshSize(row.id).join()
        assertEquals("READY", database.harnessInstallationDao().getById(row.id)?.status)
        manager.rename(row.id, "New name")
        database.harnessInstallationDao().updateSize(row.id, 42)
        assertEquals("New name", database.harnessInstallationDao().getById(row.id)?.displayName)
        assertEquals("READY", database.harnessInstallationDao().getById(row.id)?.status)
        database.harnessInstallationDao().deleteById(row.id)
        assertEquals(0, database.harnessInstallationDao().updateSize(row.id, 99))
        assertNull(database.harnessInstallationDao().getById(row.id))
    }
}
