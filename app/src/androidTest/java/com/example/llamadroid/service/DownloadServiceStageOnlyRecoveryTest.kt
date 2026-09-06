package com.example.llamadroid.service

import android.os.Process
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_CANCELLED
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_COMPLETED
import com.example.llamadroid.data.db.DownloadTaskEntity
import com.example.llamadroid.data.db.ModelBundleEntity
import com.example.llamadroid.data.db.ModelBundleItemEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.PendingModelArtifactEntity
import com.example.llamadroid.data.model.library.ModelLibraryRepositoryFactory
import com.example.llamadroid.data.model.library.ModelFamily
import com.example.llamadroid.data.model.library.ModelSourceDraft
import com.example.llamadroid.data.model.library.ModelSourceUrlValidator
import com.example.llamadroid.data.model.library.PendingArtifactStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Exercises the real foreground service against a loopback-only fixture.
 * Invocation is opt-in on secondary Android user 10 because this test writes
 * reversible rows to the real app database. Every row and file uses a random
 * namespace and cleanup is limited to that namespace.
 */
@RunWith(AndroidJUnit4::class)
class DownloadServiceStageOnlyRecoveryTest {
    @Test
    fun stageOnlyCancelKeepsPartialAndOnlyExplicitRetryRearmsIt() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Set isolated_feature_qa=true before touching the real model fixture",
            arguments.getString(ARG_ISOLATED)?.equals("true", ignoreCase = true) == true
        )
        assumeTrue(
            "Run this test only in secondary Android user 10",
            Process.myUid() / USER_ID_RANGE == SECONDARY_USER_ID
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Keep a real foreground activity alive while the service is started;
        // Android 14+/36 can reject a background foreground-service start.
        val activityScenario = ActivityScenario.launch(ComponentActivity::class.java)
        val database = AppDatabase.getDatabase(context)
        val namespace = "service-stage-only-${UUID.randomUUID()}"
        val root = context.cacheDir.resolve(namespace).apply { mkdirs() }
        val filename = "fixture-$namespace.bin"
        val destination = root.resolve(filename)
        val part = root.resolve("$filename.part")
        val pendingId = "pending:$namespace"
        val taskId = "task:$namespace"
        val server = SlowLoopbackFixture()
        try {
            UnifiedNotificationManager.init(context)
            val pending = PendingModelArtifactEntity(
                id = pendingId,
                downloadTaskId = taskId,
                filename = filename,
                stagingPath = destination.absolutePath,
                destinationPath = destination.absolutePath,
                requestedFamily = ModelFamily.LLM.storedValue,
                requestedRole = "llm",
                status = PendingArtifactStatus.STAGED.storedValue
            )
            database.modelLibraryDao().upsert(pending)
            // Persist the complete task so the service exercises the durable
            // recovery path instead of relying on process-local holder state.
            database.downloadTaskDao().upsert(
                DownloadTaskEntity(
                    id = taskId,
                    url = server.url,
                    destPath = destination.absolutePath,
                    filename = filename,
                    repoId = "loopback",
                    progressKey = taskId,
                    modelType = ModelType.LLM.name,
                    artifactFamily = ModelFamily.LLM.storedValue,
                    artifactRole = "llm",
                    pendingArtifactId = pendingId,
                    stageOnly = true
                )
            )

            DownloadService.startDownload(
                context = context,
                url = server.url,
                destPath = destination.absolutePath,
                filename = filename,
                downloadId = taskId
            )
            awaitCondition {
                server.requestCount.get() >= 1 && part.length() >= MIN_PARTIAL_BYTES &&
                    database.downloadTaskDao().getById(taskId) != null
            }
            DownloadService.cancelDownload(context, filename, taskId)
            awaitCondition {
                database.modelLibraryDao().getPendingArtifactById(pendingId)?.status ==
                    PendingArtifactStatus.CANCELLED.storedValue &&
                    database.downloadTaskDao().getById(taskId)?.status == DOWNLOAD_TASK_STATUS_CANCELLED
            }
            val partialSize = part.length()
            assertTrue("Cancel must retain stage-only partial bytes", partialSize >= MIN_PARTIAL_BYTES)
            val requestsAfterCancel = server.requestCount.get()

            // A recovery callback without explicit user intent must preserve
            // the cancellation sentinel and must not issue a network request.
            DownloadService.resumeDownload(context, taskId, explicitRetry = false)
            delay(750)
            assertEquals(requestsAfterCancel, server.requestCount.get())
            assertEquals(
                PendingArtifactStatus.CANCELLED.storedValue,
                database.modelLibraryDao().getPendingArtifactById(pendingId)?.status
            )

            DownloadService.resumeDownload(context, taskId, explicitRetry = true)
            awaitCondition {
                server.requestCount.get() >= requestsAfterCancel + 1 &&
                    server.rangeStarts.any { it > 0L }
            }
            assertTrue("Explicit retry must resume from the retained partial", server.rangeStarts.any { it > 0L })

            // Exercise the narrow race where the user taps Retry while the
            // previous Cancel worker is still persisting its final sentinel.
            // The service must queue this retry behind cleanup and preserve
            // the same durable task/partial rather than treating cleanup's
            // later timestamp as a newer cancellation intent.
            val rangedRequestsBeforeImmediateCancel = server.rangeStarts.size
            DownloadService.cancelDownload(context, filename, taskId)
            DownloadService.resumeDownload(context, taskId, explicitRetry = true)
            awaitCondition {
                server.rangeStarts.size >= rangedRequestsBeforeImmediateCancel + 1 &&
                    server.rangeStarts.drop(rangedRequestsBeforeImmediateCancel).any { it > 0L }
            }
            assertTrue(
                "Immediate retry must wait for cancel cleanup and reuse the partial",
                server.rangeStarts.drop(rangedRequestsBeforeImmediateCancel).any { it > 0L }
            )

            // Repeating Cancel while that cleanup/retry generation is still
            // settling must update the same task's latest cancellation order.
            // The second Cancel therefore wins and cannot be erased by the
            // queued retry's finally block.
            DownloadService.cancelDownload(context, filename, taskId)
            DownloadService.resumeDownload(context, taskId, explicitRetry = true)
            DownloadService.cancelDownload(context, filename, taskId)
            awaitCondition {
                database.modelLibraryDao().getPendingArtifactById(pendingId)?.status ==
                    PendingArtifactStatus.CANCELLED.storedValue
            }
            val requestsAfterRepeatedCancel = server.requestCount.get()
            delay(750)
            assertEquals(
                "The latest repeated cancel must suppress its queued retry",
                requestsAfterRepeatedCancel,
                server.requestCount.get()
            )
            assertTrue(
                "Repeated cancel must keep the retained partial",
                part.length() >= MIN_PARTIAL_BYTES
            )

            // The inverse ordering must also hold: a retry queued first is
            // suppressed when a newer Cancel arrives before its worker starts.
            DownloadService.resumeDownload(context, taskId, explicitRetry = true)
            DownloadService.cancelDownload(context, filename, taskId)
            awaitCondition {
                database.modelLibraryDao().getPendingArtifactById(pendingId)?.status ==
                    PendingArtifactStatus.CANCELLED.storedValue
            }
            val requestsAfterNewerCancel = server.requestCount.get()
            delay(750)
            assertEquals(
                "A newer cancel must stop any retry after its sentinel settles",
                requestsAfterNewerCancel,
                server.requestCount.get()
            )

            DownloadService.discardDownload(context, taskId)
            awaitCondition {
                database.modelLibraryDao().getPendingArtifactById(pendingId)?.status ==
                    PendingArtifactStatus.CANCELLED.storedValue &&
                    !part.exists() && !destination.exists()
            }
            assertNull(database.downloadTaskDao().getById(taskId))
        } finally {
            // Discard only this unique task. Never stop the shared service or
            // clear global progress because another user download may exist.
            runCatching { DownloadService.discardDownload(context, taskId) }
            var cleanupFailure: Throwable? = null
            try {
                awaitOwnedCleanup(database, taskId, pendingId, part, destination)
            } catch (failure: Throwable) {
                cleanupFailure = failure
            }
            var serverCloseFailure: Throwable? = null
            try {
                server.close()
            } catch (failure: Throwable) {
                serverCloseFailure = failure
            }
            try {
                // Closing accepted sockets can unblock a worker that was
                // still writing after cancellation. Only retry cleanup after
                // that close; never delete a file while the service may own it.
                if (cleanupFailure != null || serverCloseFailure != null) {
                    try {
                        awaitOwnedCleanup(database, taskId, pendingId, part, destination)
                        cleanupFailure = null
                    } catch (retryFailure: Throwable) {
                        cleanupFailure = retryFailure
                    }
                }
                if (cleanupFailure == null && serverCloseFailure == null) {
                    database.downloadTaskDao().deleteById(taskId)
                    database.modelLibraryDao().deletePendingArtifactById(pendingId)
                    root.deleteRecursively()
                }
            } finally {
                activityScenario.close()
            }
            cleanupFailure = cleanupFailure ?: serverCloseFailure
            cleanupFailure?.let { throw it }
        }
    }

    @Test
    fun completedUnknownDiscardRemovesOnlyOwnedFilesAndKeepsSourceBundle() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Set isolated_feature_qa=true before touching the real model fixture",
            arguments.getString(ARG_ISOLATED)?.equals("true", ignoreCase = true) == true
        )
        assumeTrue(
            "Run this test only in secondary Android user 10",
            Process.myUid() / USER_ID_RANGE == SECONDARY_USER_ID
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val activityScenario = ActivityScenario.launch(ComponentActivity::class.java)
        val database = AppDatabase.getDatabase(context)
        val namespace = "service-discard-${UUID.randomUUID()}"
        val root = context.cacheDir.resolve(namespace).apply { mkdirs() }
        val filename = "unknown-$namespace.gguf"
        val staging = root.resolve("staging/$filename")
        val destination = root.resolve("destination/$filename")
        val part = root.resolve("destination/$filename.part")
        val sourceId = "source:$namespace"
        val bundleId = "bundle:$namespace"
        val itemId = "item:$namespace"
        val pendingId = "pending:$namespace"
        val taskId = "task:$namespace"
        try {
            val source = ModelSourceUrlValidator.toEntity(
                ModelSourceDraft(
                    family = ModelFamily.LLM,
                    url = "https://example.com/$filename",
                    label = "Discard fixture source"
                )
            ).getOrThrow().copy(
                id = sourceId,
                verified = true,
                validationStatus = "verified",
                checkedAt = System.currentTimeMillis()
            )
            val bundle = ModelBundleEntity(
                id = bundleId,
                name = "Discard fixture bundle",
                family = ModelFamily.LLM.storedValue
            )
            val item = ModelBundleItemEntity(
                id = itemId,
                bundleId = bundleId,
                itemKey = filename,
                family = ModelFamily.LLM.storedValue,
                role = "llm",
                sourceId = sourceId,
                localFilename = filename,
                relativePath = filename
            )
            val pending = PendingModelArtifactEntity(
                id = pendingId,
                downloadTaskId = taskId,
                sourceId = sourceId,
                bundleId = bundleId,
                bundleItemId = itemId,
                filename = filename,
                stagingPath = staging.absolutePath,
                destinationPath = destination.absolutePath,
                requestedFamily = ModelFamily.LLM.storedValue,
                requestedRole = "llm",
                status = PendingArtifactStatus.VALIDATED.storedValue,
                requiresManualPromotion = true
            )
            staging.parentFile?.mkdirs()
            destination.parentFile?.mkdirs()
            staging.writeBytes(byteArrayOf(1, 2, 3, 4))
            destination.writeBytes(byteArrayOf(5, 6, 7, 8))
            part.writeBytes(byteArrayOf(9, 10, 11, 12))

            val dao = database.modelLibraryDao()
            dao.upsert(source)
            dao.upsert(bundle)
            dao.upsert(item)
            dao.upsert(pending)
            database.downloadTaskDao().upsert(
                DownloadTaskEntity(
                    id = taskId,
                    url = source.url,
                    destPath = destination.absolutePath,
                    filename = filename,
                    repoId = "discard-fixture",
                    progressKey = taskId,
                    modelType = ModelType.LLM.name,
                    artifactFamily = ModelFamily.LLM.storedValue,
                    artifactRole = "llm",
                    pendingArtifactId = pendingId,
                    stageOnly = true,
                    status = DOWNLOAD_TASK_STATUS_COMPLETED,
                    bytesDownloaded = destination.length(),
                    totalBytes = destination.length()
                )
            )

            ModelLibraryRepositoryFactory.create(context)
                .discardPendingArtifact(context, pendingId)
                .getOrThrow()

            assertFalse("Staging bytes must be discarded", staging.exists())
            assertFalse("Materialized bytes must be discarded", destination.exists())
            assertFalse("Downloader partial bytes must be discarded", part.exists())
            assertNull(database.downloadTaskDao().getById(taskId))
            assertEquals(
                PendingArtifactStatus.CANCELLED.storedValue,
                dao.getPendingArtifactById(pendingId)?.status
            )
            assertEquals(source, dao.getSourceById(sourceId))
            assertEquals(bundle, dao.getBundleById(bundleId))
            assertEquals(item, dao.getBundleItemById(itemId))
        } finally {
            // The repository waits for service cleanup before returning. If a
            // prior assertion fails, issue the same owned discard and wait for
            // the task/files to settle before removing only fixture rows.
            try {
                ModelLibraryRepositoryFactory.create(context)
                    .discardPendingArtifact(context, pendingId)
            } catch (_: Throwable) {
                // Cleanup below reports a timeout if the service still owns
                // any fixture bytes; never delete them while it is active.
            }
            var cleanupFailure: Throwable? = null
            try {
                awaitOwnedCleanup(database, taskId, pendingId, part, destination, staging)
            } catch (failure: Throwable) {
                cleanupFailure = failure
            }
            if (cleanupFailure == null) {
                val dao = database.modelLibraryDao()
                database.downloadTaskDao().deleteById(taskId)
                dao.deletePendingArtifactById(pendingId)
                dao.deleteBundleItemById(itemId)
                dao.deleteBundleById(bundleId)
                dao.deleteSourceById(sourceId)
                root.deleteRecursively()
            }
            activityScenario.close()
            cleanupFailure?.let { throw it }
        }
    }

    @Test
    fun changedSourceRetryDiscardsObsoletePartialBeforeAnyRangeRequest() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString(ARG_ISOLATED) == "true")
        assumeTrue(Process.myUid() / USER_ID_RANGE == SECONDARY_USER_ID)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        val db = AppDatabase.getDatabase(context)
        val namespace = "identity-retry-${UUID.randomUUID()}"
        val root = context.cacheDir.resolve(namespace).apply { mkdirs() }
        val destination = root.resolve("fixture.bin")
        val part = root.resolve("fixture.bin.part").apply { writeBytes(ByteArray(128 * 1024) { 99 }) }
        val pendingId = "pending:$namespace"
        val taskId = "task:$namespace"
        val server = SlowLoopbackFixture()
        try {
            UnifiedNotificationManager.init(context)
            db.modelLibraryDao().upsert(PendingModelArtifactEntity(
                id = pendingId, downloadTaskId = taskId, filename = destination.name,
                stagingPath = destination.path, destinationPath = destination.path,
                requestedFamily = ModelFamily.LLM.storedValue,
                status = PendingArtifactStatus.CANCELLED.storedValue,
                validationJson = com.example.llamadroid.data.model.library.SOURCE_IDENTITY_INVALIDATED_MARKER
            ))
            db.downloadTaskDao().upsert(DownloadTaskEntity(
                id = taskId, url = server.url, destPath = destination.path, filename = destination.name,
                repoId = "loopback", progressKey = taskId, modelType = ModelType.LLM.name,
                pendingArtifactId = pendingId, stageOnly = true, status = DOWNLOAD_TASK_STATUS_CANCELLED
            ))
            DownloadService.resumeDownload(context, taskId, explicitRetry = true)
            awaitCondition { server.requestCount.get() > 0 && part.length() >= MIN_PARTIAL_BYTES }
            assertTrue("Changed source must start at byte zero", server.rangeStarts.all { it == 0L })
            assertNull(db.modelLibraryDao().getPendingArtifactById(pendingId)?.validationJson)
        } finally {
            // Only UUID-owned fixture files are removed, after the service confirms cleanup.
            try {
                DownloadService.discardDownload(context, taskId)
                server.close()
                awaitOwnedCleanup(db, taskId, pendingId, part, destination)
                db.modelLibraryDao().deletePendingArtifactById(pendingId)
                root.deleteRecursively()
            } finally { scenario.close() }
        }
    }

    @Test
    fun unverifiedSourceBecomesRetryableFailureWithoutNetwork() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString(ARG_ISOLATED) == "true")
        assumeTrue(Process.myUid() / USER_ID_RANGE == SECONDARY_USER_ID)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        val db = AppDatabase.getDatabase(context)
        val namespace = "source-recheck-${UUID.randomUUID()}"
        val root = context.cacheDir.resolve(namespace).apply { mkdirs() }
        val destination = root.resolve("fixture.bin")
        val part = root.resolve("fixture.bin.part")
        val pendingId = "pending:$namespace"
        val taskId = "task:$namespace"
        val source = ModelSourceUrlValidator.toEntity(ModelSourceDraft(ModelFamily.LLM,
            "https://fixture.example/$namespace.bin")).getOrThrow()
        val server = SlowLoopbackFixture()
        try {
            UnifiedNotificationManager.init(context)
            db.modelLibraryDao().upsert(source)
            db.modelLibraryDao().upsert(PendingModelArtifactEntity(
                id = pendingId, downloadTaskId = taskId, sourceId = source.id, filename = destination.name,
                stagingPath = destination.path, destinationPath = destination.path,
                status = PendingArtifactStatus.STAGED.storedValue
            ))
            db.downloadTaskDao().upsert(DownloadTaskEntity(
                id = taskId, url = server.url, destPath = destination.path, filename = destination.name,
                repoId = "loopback", progressKey = taskId, modelType = ModelType.LLM.name,
                sourceId = source.id, pendingArtifactId = pendingId, stageOnly = true
            ))
            DownloadService.resumeDownload(context, taskId)
            awaitCondition {
                db.modelLibraryDao().getPendingArtifactById(pendingId)?.status == PendingArtifactStatus.FAILED.storedValue
            }
            assertEquals(0, server.requestCount.get())
            assertFalse(destination.exists())
            assertFalse(part.exists())
            assertEquals(com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_FAILED,
                db.downloadTaskDao().getById(taskId)?.status)
        } finally {
            try {
                DownloadService.discardDownload(context, taskId)
                server.close()
                awaitOwnedCleanup(db, taskId, pendingId, part, destination)
                db.modelLibraryDao().deletePendingArtifactById(pendingId)
                db.modelLibraryDao().deleteSourceById(source.id)
                root.deleteRecursively() // Only the owned UUID directory, after service cleanup.
            } finally { scenario.close() }
        }
    }

    private suspend fun awaitOwnedCleanup(
        database: AppDatabase,
        taskId: String,
        pendingId: String,
        part: java.io.File,
        destination: java.io.File,
        vararg additionalFiles: java.io.File
    ) {
        withTimeout(20_000L) {
            while (
                database.downloadTaskDao().getById(taskId) != null ||
                    database.modelLibraryDao().getPendingArtifactById(pendingId)?.status
                        ?.let { it in ACTIVE_PENDING_STATUSES } == true ||
                    part.exists() || destination.exists() || additionalFiles.any { it.exists() }
            ) {
                delay(50L)
            }
        }
    }

    private suspend fun awaitCondition(condition: suspend () -> Boolean) {
        withTimeout(20_000L) {
            while (!condition()) delay(50L)
        }
    }

    private class SlowLoopbackFixture : Closeable {
        private val serverSocket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val acceptedSockets = ConcurrentHashMap.newKeySet<Socket>()
        val requestCount = AtomicInteger(0)
        val rangeStarts = CopyOnWriteArrayList<Long>()
        val url: String = "http://127.0.0.1:${serverSocket.localPort}/$FIXTURE_PATH"
        private val worker = thread(name = "model-download-fixture", start = true) { serve() }

        private fun serve() {
            while (!serverSocket.isClosed) {
                var socket: Socket? = null
                try {
                    val accepted = serverSocket.accept()
                    socket = accepted
                    acceptedSockets += accepted
                    accepted.use(::serveRequest)
                } catch (_: SocketException) {
                    // A cancelled OkHttp call can close the accepted socket
                    // while the fixture is still writing. Keep accepting the
                    // next explicit retry unless the listener itself closed.
                    if (serverSocket.isClosed) return
                } catch (_: IOException) {
                    if (serverSocket.isClosed) return
                } finally {
                    socket?.let(acceptedSockets::remove)
                }
            }
        }

        private fun serveRequest(socket: Socket) {
            socket.soTimeout = 10_000
            val requestLines = mutableListOf<String>()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) break
                requestLines += line
            }
            val range = requestLines.firstNotNullOfOrNull { line ->
                if (!line.startsWith("Range:", ignoreCase = true)) return@firstNotNullOfOrNull null
                Regex("bytes=(\\d+)-", RegexOption.IGNORE_CASE).find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()
            }
            requestCount.incrementAndGet()
            if (range != null) rangeStarts += range
            val start = range ?: 0L
            val status = if (start > 0L) "206 Partial Content" else "200 OK"
            val length = TOTAL_BYTES - start
            val output = socket.getOutputStream()
            output.write(
                "HTTP/1.1 $status\r\nContent-Type: application/octet-stream\r\n".toByteArray(StandardCharsets.ISO_8859_1)
            )
            output.write("Content-Length: $length\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            if (start > 0L) output.write("Content-Range: bytes $start-${TOTAL_BYTES - 1}/$TOTAL_BYTES\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            output.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            var remaining = length
            var sent = 0L
            while (remaining > 0L) {
                val count = minOf(remaining, CHUNK_BYTES.toLong()).toInt()
                val chunk = ByteArray(count) { index ->
                    ((start + sent + index.toLong()) and 0x7fL).toByte()
                }
                output.write(chunk, 0, count)
                output.flush()
                remaining -= count
                sent += count
                // Keep both the initial request and an explicit Range retry
                // cancellable long enough for the test to exercise discard
                // before finalization can promote the fixture.
                Thread.sleep(20L)
            }
        }

        override fun close() {
            serverSocket.close()
            acceptedSockets.toList().forEach { socket ->
                runCatching { socket.close() }
            }
            worker.join(5_000L)
            check(!worker.isAlive) { "Loopback fixture worker did not stop" }
        }

        companion object {
            private const val FIXTURE_PATH = "stage-only.bin"
            private const val TOTAL_BYTES = 768 * 1024L
            private const val CHUNK_BYTES = 4 * 1024
        }
    }

    companion object {
        private val ACTIVE_PENDING_STATUSES = setOf(
            PendingArtifactStatus.STAGED.storedValue,
            PendingArtifactStatus.INSPECTING.storedValue,
            PendingArtifactStatus.NEEDS_MANUAL_PROMOTION.storedValue,
            PendingArtifactStatus.VALIDATED.storedValue
        )
        private const val ARG_ISOLATED = "isolated_feature_qa"
        private const val SECONDARY_USER_ID = 10
        private const val USER_ID_RANGE = 100_000
        private const val MIN_PARTIAL_BYTES = 8 * 1024L
    }
}
