package com.example.llamadroid.data.model.library

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.data.api.HuggingFaceService
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelBundleEntity
import com.example.llamadroid.data.db.ModelBundleItemEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.PendingModelArtifactEntity
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises durable bundle rows and finalization against a private in-memory
 * database and cache fixture. No DownloadService or network call is started.
 */
@RunWith(AndroidJUnit4::class)
class ModelBundleRecoveryFlowTest {
    @Test
    fun completeGroupFinalizesOnceAndPromotesEveryMember() = runBlocking {
        withFixture { database, root ->
            val dao = database.modelLibraryDao()
            val bundle = ModelBundleEntity(id = "complete-${UUID.randomUUID()}", name = "Complete", family = "SD")
            val primary = splitItem(bundle, 0, "model-00001-of-00002.safetensors")
            val companion = splitItem(bundle, 1, "model-00002-of-00002.safetensors")
            dao.upsert(bundle)
            dao.upsertBundleItems(listOf(primary, companion))

            val firstStage = File(root, "stage/first.safetensors").apply {
                parentFile!!.mkdirs()
                writeHighConfidenceSafeTensor(this)
            }
            val secondStage = File(root, "stage/second.safetensors").apply {
                writeHighConfidenceSafeTensor(this)
            }
            val firstDestination = File(root, "bundle/${primary.relativePath}")
            val secondDestination = File(root, "bundle/${companion.relativePath}")
            val first = pending(primary, firstStage, firstDestination, bundle.id, "complete-first")
            val second = pending(companion, secondStage, secondDestination, bundle.id, "complete-second")
            dao.upsertPendingArtifacts(listOf(first, second))

            val waiting = ModelArtifactFinalizer.finalizeIfKnown(
                database,
                first,
                firstStage,
                PendingArtifactRuntimeMetadata("fixture")
            ).getOrThrow()
            assertFalse(waiting.promoted)
            assertEquals(PendingArtifactStatus.NEEDS_MANUAL_PROMOTION.storedValue,
                dao.getPendingArtifactById(first.id)!!.status)
            assertTrue(database.modelDao().getAllModels().first().isEmpty())

            val completed = ModelArtifactFinalizer.finalizeIfKnown(
                database,
                second,
                secondStage,
                PendingArtifactRuntimeMetadata("fixture")
            ).getOrThrow()
            assertTrue(completed.promoted)
            assertEquals(PendingArtifactStatus.PROMOTED.storedValue,
                dao.getPendingArtifactById(first.id)!!.status)
            assertEquals(PendingArtifactStatus.PROMOTED.storedValue,
                dao.getPendingArtifactById(second.id)!!.status)
            val models = database.modelDao().getAllModels().first()
            assertEquals(1, models.size)
            assertEquals(ModelType.SD_DIFFUSION, models.single().type)
            assertEquals(firstDestination.canonicalPath, models.single().path)
            assertFalse(firstStage.exists())
            assertFalse(secondStage.exists())
            assertTrue(firstDestination.exists())
            assertTrue(secondDestination.exists())
        }
    }

    @Test
    fun incompleteGroupStaysPendingAndDoesNotCreateRuntimeModel() = runBlocking {
        withFixture { database, root ->
            val dao = database.modelLibraryDao()
            val bundle = ModelBundleEntity(id = "incomplete-${UUID.randomUUID()}", name = "Incomplete", family = "SD")
            val onlyMember = splitItem(bundle, 0, "model-00001-of-00002.safetensors")
            dao.upsert(bundle)
            // The declared count is two, but only one definition is present.
            dao.upsert(onlyMember)
            val stage = File(root, "stage/model.safetensors").apply {
                parentFile!!.mkdirs()
                writeHighConfidenceSafeTensor(this)
            }
            val destination = File(root, "bundle/${onlyMember.relativePath}")
            val artifact = pending(onlyMember, stage, destination, bundle.id, "incomplete")
            dao.upsert(artifact)

            val result = ModelArtifactFinalizer.finalizeIfKnown(
                database,
                artifact,
                stage,
                PendingArtifactRuntimeMetadata("fixture")
            ).getOrThrow()

            assertFalse(result.promoted)
            assertEquals(PendingArtifactStatus.NEEDS_MANUAL_PROMOTION.storedValue,
                dao.getPendingArtifactById(artifact.id)!!.status)
            assertTrue(destination.exists())
            assertTrue(database.modelDao().getAllModels().first().isEmpty())
        }
    }

    @Test
    fun changedChecksumFailsBeforeGroupedPayloadCanBeRegistered() = runBlocking {
        withFixture { database, root ->
            val dao = database.modelLibraryDao()
            val bundle = ModelBundleEntity(id = "checksum-${UUID.randomUUID()}", name = "Checksum", family = "SD")
            val item = splitItem(bundle, 0, "model-00001-of-00001.safetensors").copy(
                partCount = 1,
                expectedSha256 = "0".repeat(64)
            )
            dao.upsert(bundle)
            dao.upsert(item)
            val stage = File(root, "stage/model.safetensors").apply {
                parentFile!!.mkdirs()
                writeHighConfidenceSafeTensor(this)
            }
            val destination = File(root, "bundle/${item.relativePath}")
            val artifact = pending(item, stage, destination, bundle.id, "checksum")
            dao.upsert(artifact)

            val result = ModelArtifactFinalizer.finalizeIfKnown(
                database,
                artifact,
                stage,
                PendingArtifactRuntimeMetadata("fixture")
            )

            assertTrue(result.isFailure)
            assertEquals(PendingArtifactStatus.STAGED.storedValue,
                dao.getPendingArtifactById(artifact.id)!!.status)
            assertFalse(destination.exists())
            assertTrue(database.modelDao().getAllModels().first().isEmpty())
        }
    }

    @Test
    fun cancelledArtifactCannotBeRevivedByAValidGroupedPayload() = runBlocking {
        withFixture { database, root ->
            val dao = database.modelLibraryDao()
            val bundle = ModelBundleEntity(id = "cancelled-${UUID.randomUUID()}", name = "Cancelled", family = "SD")
            val item = splitItem(bundle, 0, "model-00001-of-00001.safetensors").copy(partCount = 1)
            dao.upsert(bundle)
            dao.upsert(item)
            val stage = File(root, "stage/model.safetensors").apply {
                parentFile!!.mkdirs()
                writeHighConfidenceSafeTensor(this)
            }
            val destination = File(root, "bundle/${item.relativePath}")
            val artifact = pending(item, stage, destination, bundle.id, "cancelled")
                .copy(status = PendingArtifactStatus.CANCELLED.storedValue)
            dao.upsert(artifact)

            val result = ModelArtifactFinalizer.finalizeIfKnown(
                database,
                artifact,
                stage,
                PendingArtifactRuntimeMetadata("fixture")
            )

            assertTrue(result.isFailure)
            assertEquals(PendingArtifactStatus.CANCELLED.storedValue,
                dao.getPendingArtifactById(artifact.id)!!.status)
            assertFalse(destination.exists())
            assertTrue(database.modelDao().getAllModels().first().isEmpty())
        }
    }

    @Test
    fun durableRowsSurviveRecoveryAttemptAndDeletionDetachesUnknownArtifact() = runBlocking {
        withFixture { database, root ->
            val dao = database.modelLibraryDao()
            val bundle = ModelBundleEntity(id = "durable-${UUID.randomUUID()}", name = "Durable", family = "SD")
            val item = ModelBundleItemEntity(
                id = "durable-item",
                bundleId = bundle.id,
                itemKey = "model",
                family = "SD",
                relativePath = "model.safetensors",
                localFilename = "model.safetensors"
            )
            dao.upsert(bundle)
            dao.upsert(item)
            val queueRoot = File(root, "queue-root").apply { mkdirs() }
            val staging = File(queueRoot, ".model-library-staging/${bundle.id}/${item.id}/model.safetensors")
                .apply {
                    parentFile!!.mkdirs()
                    writeText("downloaded fixture")
                }
            val artifact = PendingModelArtifactEntity(
                id = "durable-artifact",
                downloadTaskId = "durable-task",
                bundleId = bundle.id,
                bundleItemId = item.id,
                filename = "model.safetensors",
                stagingPath = staging.path,
                destinationPath = File(queueRoot, "model.safetensors").path,
                requestedFamily = "SD",
                status = PendingArtifactStatus.STAGED.storedValue
            )
            dao.upsert(artifact)

            val repository = fixtureRepository(dao)
            val recovery = repository.resumePendingBundleQueues(databaseContext(), null)
            assertEquals(1, recovery.size)
            assertTrue(recovery.single().isFailure)
            assertEquals(PendingArtifactStatus.STAGED.storedValue,
                dao.getPendingArtifactById(artifact.id)!!.status)
            assertNotNull(dao.getBundleById(bundle.id))

            // No task id means this fixture never starts DownloadService, while
            // deletion still exercises the real cancellation/Room transaction.
            dao.upsert(artifact.copy(downloadTaskId = null))
            repository.deleteBundle(databaseContext(), bundle.id)
            val detached = dao.getPendingArtifactById(artifact.id)
            assertNull(dao.getBundleById(bundle.id))
            assertNull(dao.getBundleItemById(item.id))
            assertNotNull(detached)
            assertNull(detached!!.bundleId)
            assertNull(detached.bundleItemId)
            assertEquals(PendingArtifactStatus.CANCELLED.storedValue, detached.status)
            assertTrue(staging.exists())
        }
    }

    @Test
    fun namespacedLiteRtPackagesAndLegacyLayoutRestoreSeparateEntries() = runBlocking {
        withFixture { database, root ->
            val dao = database.modelLibraryDao()
            val bundle = ModelBundleEntity(
                id = "litert-layout-${UUID.randomUUID()}",
                name = "LiteRT layouts",
                family = "LITERT"
            )
            val groups = listOf(
                "package-litert-one" to "one",
                "package-litert-two" to "two",
                "" to "legacy"
            )
            fun definition(
                prefix: String,
                groupName: String,
                role: String,
                index: Int,
                filename: String,
                metadata: String
            ) = ModelBundleItemEntity(
                id = "${groupName}-$index",
                bundleId = bundle.id,
                itemKey = if (prefix.isBlank()) filename else "$prefix/$filename",
                family = "LITERT",
                role = role,
                partGroup = groupName,
                partIndex = index,
                partCount = 2,
                localFilename = filename,
                relativePath = if (prefix.isBlank()) filename else "$prefix/$filename",
                modelMetadataJson = metadata
            )
            val definitions = groups.flatMap { (prefix, groupName) ->
                listOf(
                    definition(
                        prefix, groupName, "engine", 0, "model.tflite",
                        "{\"package\":\"$groupName\",\"entry\":true}"
                    ),
                    definition(
                        prefix, groupName, "tokenizer", 1, "tokenizer.json",
                        "{\"package\":\"$groupName\",\"companion\":true}"
                    )
                )
            }
            dao.upsert(bundle)
            dao.upsertBundleItems(definitions)
            val rows = definitions.map { item ->
                val file = File(root, "bundle/${item.relativePath}").apply {
                    parentFile!!.mkdirs()
                    writeText("fixture-${item.id}")
                }
                PendingModelArtifactEntity(
                    id = "pending-${item.id}",
                    bundleId = bundle.id,
                    bundleItemId = item.id,
                    filename = file.name,
                    stagingPath = file.path,
                    destinationPath = file.path,
                    requestedFamily = "LITERT",
                    requestedRole = item.role,
                    detectedFamily = "LITERT",
                    detectedRole = item.role,
                    validationJson = item.modelMetadataJson,
                    status = PendingArtifactStatus.INSPECTING.storedValue
                )
            }
            dao.upsertPendingArtifacts(rows)

            groups.forEach { (prefix, groupName) ->
                val expectedItems = definitions.filter { it.partGroup == groupName }.sortedBy { it.partIndex }
                val primary = rows.first { it.bundleItemId == expectedItems.first().id }
                val resolved = resolvePendingArtifactGroup(dao, primary, File(primary.stagingPath))
                val expectedEntry = if (prefix.isBlank()) {
                    File(root, "bundle")
                } else {
                    File(root, "bundle/$prefix")
                }

                assertTrue("$groupName should be complete", resolved.complete)
                assertEquals(expectedEntry.canonicalFile, resolved.entry.canonicalFile)
                assertEquals(expectedItems.map { "pending-${it.id}" }.toSet(), resolved.members.map { it.id }.toSet())
                assertEquals(primary.id, resolved.primary.id)
                resolved.members.forEach { member ->
                    val destination = File(requireNotNull(member.destinationPath)).canonicalFile
                    assertTrue(destination.path.startsWith(expectedEntry.canonicalPath + File.separator))
                }
                assertEquals(expectedItems.map { it.role }, resolved.members.sortedBy { it.bundleItemId }.map { it.requestedRole })
                assertEquals(
                    expectedItems.map { it.modelMetadataJson },
                    resolved.members.sortedBy { it.bundleItemId }.map { it.validationJson }
                )

                val persisted = dao.getWithItems(bundle.id)!!.items
                    .filter { it.partGroup == groupName }
                    .sortedBy { it.partIndex }
                assertEquals(expectedItems.map { it.relativePath }, persisted.map { it.relativePath })
                assertEquals(expectedItems.map { it.modelMetadataJson }, persisted.map { it.modelMetadataJson })
                assertEquals(expectedItems.map { it.role }, persisted.map { it.role })
            }
        }
    }

    private fun splitItem(bundle: ModelBundleEntity, index: Int, relativePath: String) =
        ModelBundleItemEntity(
            id = "${bundle.id}-item-$index",
            bundleId = bundle.id,
            itemKey = relativePath,
            family = bundle.family,
            partGroup = "split-model",
            partIndex = index,
            partCount = 2,
            relativePath = relativePath,
            localFilename = relativePath.substringAfterLast('/')
        )

    private fun pending(
        item: ModelBundleItemEntity,
        staging: File,
        destination: File,
        bundleId: String,
        suffix: String
    ) = PendingModelArtifactEntity(
        id = "$bundleId-$suffix",
        bundleId = bundleId,
        bundleItemId = item.id,
        filename = staging.name,
        stagingPath = staging.path,
        destinationPath = destination.path,
        requestedFamily = "SD",
        status = PendingArtifactStatus.STAGED.storedValue
    )

    private suspend fun withFixture(block: suspend (AppDatabase, File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val root = File(context.cacheDir, "model-bundle-recovery-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            block(database, root)
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }

    private fun databaseContext(): Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixtureRepository(dao: com.example.llamadroid.data.db.ModelLibraryDao): ModelSourceRepository {
        val service = Proxy.newProxyInstance(
            HuggingFaceService::class.java.classLoader,
            arrayOf(HuggingFaceService::class.java)
        ) { _, _, _ -> error("Network access is forbidden in this fixture") } as HuggingFaceService
        return ModelSourceRepository(dao, HuggingFaceFolderBrowser(service))
    }

    private fun writeHighConfidenceSafeTensor(file: File) {
        val tensorName = "model.diffusion_model.blocks.0.cross_attn.norm_k.weight"
        val header = "{\"$tensorName\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,4]}}"
        val output = ByteArrayOutputStream()
        output.write(
            ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(header.toByteArray().size.toLong())
                .array()
        )
        output.write(header.toByteArray())
        // The header declares one F32 value at offsets [0, 4]. Keep the
        // fixture valid for stricter SafeTensors readers as well as the
        // structural classifier.
        output.write(byteArrayOf(0, 0, 0, 0))
        file.writeBytes(output.toByteArray())
    }
}
