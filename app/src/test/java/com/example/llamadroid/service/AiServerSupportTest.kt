package com.example.llamadroid.service

import com.example.llamadroid.data.db.AiServerConfigEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiServerSupportTest {

    @Test
    fun `default configs use reserved high ports and public access`() {
        val configs = AiServerType.defaultConfigs(now = 42L)

        assertEquals(AiServerType.entries.size, configs.size)
        assertEquals((10101..10108).toList(), configs.map { it.port })
        assertTrue(configs.all { it.accessMode == AiServerAccessMode.PUBLIC })
        assertTrue(configs.all { !it.enabled && !it.lanVisible })
        assertTrue(configs.all { it.createdAt == 42L && it.updatedAt == 42L })
    }

    @Test
    fun `port validation rejects low and duplicate ports`() {
        val configs = listOf(
            AiServerConfigEntity(serverType = "image", displayName = "Image", port = 10101),
            AiServerConfigEntity(serverType = "video", displayName = "Video", port = 10102)
        )

        assertFalse(AiServerNetwork.isValidServerPort(9999))
        assertTrue(AiServerNetwork.isValidServerPort(10000))
        assertTrue(AiServerNetwork.isValidServerPort(65535))
        assertTrue(AiServerNetwork.portConflict(configs, "image", 10102))
        assertFalse(AiServerNetwork.portConflict(configs, "image", 10101))
    }

    @Test
    fun `bind host follows lan visibility`() {
        assertEquals("127.0.0.1", AiServerNetwork.bindHost(lanVisible = false))
        assertEquals("0.0.0.0", AiServerNetwork.bindHost(lanVisible = true))
    }

    @Test
    fun `password hashes and signed sessions validate without storing raw secrets`() {
        val salt = AiServerAuth.createSalt()
        val hash = AiServerAuth.hashPassword("correct horse", salt)

        assertNotEquals("correct horse", hash)
        assertTrue(AiServerAuth.verifyPassword("correct horse", salt, hash))
        assertFalse(AiServerAuth.verifyPassword("wrong", salt, hash))

        val token = AiServerAuth.createSessionToken()
        val signed = AiServerAuth.signToken(token, "server-secret")
        assertEquals(token, AiServerAuth.verifySignedToken(signed, "server-secret"))
        assertNull(AiServerAuth.verifySignedToken("$signed-tampered", "server-secret"))
        assertNotNull(AiServerAuth.tokenHash(token))
    }

    @Test
    fun `log store appends and clears per server`() {
        AiServerLogStore.clear("image")
        AiServerLogStore.clear("video")

        AiServerLogStore.append("image", "started")
        AiServerLogStore.append("video", "other")

        assertEquals(listOf("started"), AiServerLogStore.get("image").map { it.message })
        assertEquals(listOf("other"), AiServerLogStore.get("video").map { it.message })

        AiServerLogStore.clear("image")

        assertTrue(AiServerLogStore.get("image").isEmpty())
        assertEquals(listOf("other"), AiServerLogStore.get("video").map { it.message })
    }

    @Test
    fun `job store transform updates only the matching server job`() {
        val serverType = "test_server_${System.nanoTime()}"
        val otherType = "other_server_${System.nanoTime()}"
        val job = AiServerJob(
            id = "job-1",
            serverType = serverType,
            title = "Generate",
            status = "QUEUED"
        )
        val otherJob = job.copy(id = "job-2", serverType = otherType)

        AiServerJobStore.add(job)
        AiServerJobStore.add(otherJob)
        AiServerJobStore.update(serverType, "job-1") {
            it.copy(status = "RUNNING", progress = 0.42f, message = "Working")
        }

        val updated = AiServerJobStore.get(serverType).single { it.id == "job-1" }
        val untouched = AiServerJobStore.get(otherType).single { it.id == "job-2" }

        assertEquals("RUNNING", updated.status)
        assertEquals(0.42f, updated.progress, 0.001f)
        assertEquals("Working", updated.message)
        assertEquals("QUEUED", untouched.status)
    }

    @Test
    fun `job store removes queued and terminal tasks but keeps running tasks`() {
        val serverType = "queue_test_${System.nanoTime()}"
        val queued = AiServerJob("queued", serverType, "Queued", "QUEUED")
        val running = AiServerJob("running", serverType, "Running", "RUNNING")
        val failed = AiServerJob("failed", serverType, "Failed", "FAILED")

        AiServerJobStore.add(queued)
        AiServerJobStore.add(running)
        AiServerJobStore.add(failed)

        assertTrue(AiServerJobStore.remove(serverType, "queued"))
        assertFalse(AiServerJobStore.remove(serverType, "running"))
        assertEquals(1, AiServerJobStore.clearFailed(serverType))

        val remaining = AiServerJobStore.get(serverType).map { it.id }
        assertEquals(listOf("running"), remaining)
    }

    @Test
    fun `job store cancellation makes queued and running tasks terminal`() {
        val serverType = "cancel_test_${System.nanoTime()}"
        AiServerJobStore.add(AiServerJob("queued", serverType, "Queued", "QUEUED"))
        AiServerJobStore.add(AiServerJob("running", serverType, "Running", "RUNNING", progress = 0.5f))

        assertTrue(AiServerJobStore.markCancelled(serverType, "queued", "Cancelled before start"))
        assertTrue(AiServerJobStore.markCancelled(serverType, "running", "Cancellation requested"))

        val jobs = AiServerJobStore.get(serverType).associateBy { it.id }
        assertEquals("CANCELLED", jobs["queued"]?.status)
        assertEquals("CANCELLED", jobs["running"]?.status)
        assertTrue(AiServerJobStore.remove(serverType, "queued"))
        assertTrue(AiServerJobStore.remove(serverType, "running"))
    }

    @Test
    fun `job updates mirror progress into android hub logs`() {
        val serverType = "log_progress_${System.nanoTime()}"
        AiServerLogStore.clear(serverType)
        AiServerJobStore.add(AiServerJob("job", serverType, "Upscale", "QUEUED"))

        AiServerJobStore.update(serverType, "job") {
            it.copy(status = "RUNNING", progress = 0.42f, message = "Upscaling 12/30 | ETA: 1m 05s")
        }

        val logs = AiServerLogStore.get(serverType).map { it.message }
        assertTrue(logs.any { it.contains("queued") })
        assertTrue(logs.any { it.contains("42%") && it.contains("Upscaling 12/30") })
    }

    @Test
    fun `browser webui exposes tasks instead of logs`() {
        val assetDir = listOf(
            File("app/src/main/assets/ai_servers_webui"),
            File("src/main/assets/ai_servers_webui")
        ).first { it.exists() }
        val index = File(assetDir, "index.html").readText()
        val app = File(assetDir, "app.js").readText()

        assertTrue(index.contains("tasksTab"))
        assertFalse(index.contains("logsTab"))
        assertFalse(app.contains("/api/logs"))
        assertFalse(app.contains("copyLogs"))
        assertFalse(app.contains("clearLogs"))
    }

    @Test
    fun `browser webui supports descriptor multi file uploads`() {
        val assetDir = listOf(
            File("app/src/main/assets/ai_servers_webui"),
            File("src/main/assets/ai_servers_webui")
        ).first { it.exists() }
        val app = File(assetDir, "app.js").readText()

        assertTrue(app.contains("field.multiple"))
        assertTrue(app.contains("uploadOneFile"))
        assertTrue(app.contains("XMLHttpRequest"))
        assertTrue(app.contains("upload_progress_"))
        assertTrue(app.contains("cancelJob"))
        assertTrue(app.contains("/cancel"))
        assertTrue(app.contains("removeJob"))
        assertTrue(app.contains("clearFailedJobs"))
        assertTrue(app.contains("sendMessage"))
        assertTrue(app.contains("split(/\\n+/)"))
    }

    @Test
    fun `browser webui preserves state and supports backend-specific chat settings`() {
        val assetDir = listOf(
            File("app/src/main/assets/ai_servers_webui"),
            File("src/main/assets/ai_servers_webui")
        ).first { it.exists() }
        val app = File(assetDir, "app.js").readText()

        assertTrue(app.contains("snapshotFormState"))
        assertTrue(app.contains("resolveFieldValue"))
        assertTrue(app.contains("normalizeSelectionValue"))
        assertTrue(app.contains("maxOutputTokens"))
        assertTrue(app.contains("refreshProviderModels"))
        assertTrue(app.contains("/api/chat/provider/models"))
        assertTrue(app.contains("sendChatMessage"))
        assertTrue(app.contains("providerDraftEngine"))
        assertTrue(app.contains("providerDraftModelName"))
        assertTrue(app.contains("openToolSections"))
        assertTrue(app.contains("tool-section"))
        assertTrue(app.contains("chatMessageStatus"))
        assertTrue(app.contains("linkifyMessageText"))
        assertTrue(app.contains("message-media-link"))
        assertTrue(app.contains("clearToolUsage"))
        assertTrue(app.contains("/api/chat/tool-events/clear"))
        assertTrue(app.contains("/api/auth/logout"))
        assertFalse(app.contains("state.formCache.providerModelName = models[0]"))
    }

    @Test
    fun `browser webui supports ai hub launcher mode`() {
        val assetDir = listOf(
            File("app/src/main/assets/ai_servers_webui"),
            File("src/main/assets/ai_servers_webui")
        ).first { it.exists() }
        val app = File(assetDir, "app.js").readText()

        assertTrue(app.contains("const isHubServer = serverType === \"ai_hub\""))
        assertTrue(app.contains("hubServers: \"Servers\""))
        assertTrue(app.contains("hubServers: \"Servidores\""))
        assertTrue(app.contains("window.location.protocol"))
        assertTrue(app.contains("window.location.hostname"))
        assertTrue(app.contains("target=\"_blank\""))
        assertTrue(app.contains("hub-server-card \${server.running ? \"active\" : \"inactive\"}"))
        assertTrue(app.contains("hubInactiveHint"))
    }

    @Test
    fun `native web contracts cover every exposed server action`() {
        val expected = mapOf(
            AiServerType.IMAGE to setOf("sd_txt2img", "sd_img2img", "sd_upscale", "onnx_txt2img", "onnx_img2img", "onnx_bgr"),
            AiServerType.VIDEO to setOf("txt2vid", "img2vid"),
            AiServerType.WORKFLOWS to setOf("transcribe_summary", "txt2img_upscale", "manga_translation", "media_translation", "subtitle_translation"),
            AiServerType.TTS to setOf("tts_text", "tts_document"),
            AiServerType.VIDEO_UPSCALE to setOf("video_upscale"),
            AiServerType.DOCS_DATASETS to setOf(
                "pdf_merge",
                "pdf_split",
                "pdf_extract_text",
                "pdf_ocr_text",
                "pdf_ocr_searchable",
                "pdf_translate_ocr",
                "pdf_translate_text_layer",
                "pdf_images_to_pdf",
                "pdf_compress",
                "pdf_split_size",
                "pdf_summary",
                "video_summary",
                "dataset_import",
                "dataset_pipeline",
                "dataset_export"
            ),
            AiServerType.LLAMA_CHAT to setOf("web_chat_send")
        )

        expected.forEach { (type, actions) ->
            val contracts = AiServerNativeContracts.forServer(type)
            assertTrue(AiServerNativeContracts.actionIdsForServer(type).containsAll(actions))
            assertEquals(contracts.size, contracts.map { it.action }.toSet().size)
            contracts.forEach { contract ->
                assertTrue(contract.appScreen.isNotBlank())
                assertTrue(contract.entryPoint.isNotBlank())
                assertTrue(contract.configType.isNotBlank())
                assertTrue(contract.progressSource.isNotBlank())
                assertTrue(contract.defaultsSource.isNotBlank())
            }
        }

        assertTrue(AiServerNativeContracts.forServer(AiServerType.AI_HUB).isEmpty())
    }

    @Test
    fun `ai hub directory excludes itself and mirrors runtime state`() {
        val configs = listOf(
            AiServerConfigEntity(serverType = AiServerType.IMAGE.id, displayName = "Image Studio", port = 10121, lanVisible = false),
            AiServerConfigEntity(serverType = AiServerType.AI_HUB.id, displayName = "AI HUB", port = 10108, lanVisible = true),
            AiServerConfigEntity(serverType = AiServerType.LLAMA_CHAT.id, displayName = "Llama Chat", port = 10177, lanVisible = true)
        )
        val runtimeStates = listOf(
            AiServerRuntimeState(serverType = AiServerType.IMAGE.id, running = true, port = 10121, lanVisible = false),
            AiServerRuntimeState(serverType = AiServerType.AI_HUB.id, running = true, port = 10108, lanVisible = true),
            AiServerRuntimeState(serverType = AiServerType.LLAMA_CHAT.id, running = false, port = 10177, lanVisible = true)
        )

        val entries = AiHubDirectory.entries(configs, runtimeStates)

        assertFalse(entries.any { it.serverType == AiServerType.AI_HUB.id })
        assertEquals(7, entries.size)
        assertEquals(10121, entries.first { it.serverType == AiServerType.IMAGE.id }.port)
        assertTrue(entries.first { it.serverType == AiServerType.IMAGE.id }.running)
        assertEquals(10177, entries.first { it.serverType == AiServerType.LLAMA_CHAT.id }.port)
        assertFalse(entries.first { it.serverType == AiServerType.LLAMA_CHAT.id }.running)
    }

    @Test
    fun `web chat sessions and data are owner scoped`() {
        val serviceFile = listOf(
            File("app/src/main/java/com/example/llamadroid/service/AiToolServerService.kt"),
            File("src/main/java/com/example/llamadroid/service/AiToolServerService.kt")
        ).first { it.exists() }
        val entityFile = listOf(
            File("app/src/main/java/com/example/llamadroid/data/db/AiServerEntities.kt"),
            File("src/main/java/com/example/llamadroid/data/db/AiServerEntities.kt")
        ).first { it.exists() }
        val migrationFile = listOf(
            File("app/src/main/java/com/example/llamadroid/data/db/Migrations.kt"),
            File("src/main/java/com/example/llamadroid/data/db/Migrations.kt")
        ).first { it.exists() }
        val source = serviceFile.readText()
        val entities = entityFile.readText()
        val migrations = migrationFile.readText()

        assertTrue(source.contains("logout(session)"))
        assertTrue(source.contains("deleteSession(AiServerAuth.tokenHash(token))"))
        assertTrue(source.contains("webOwnerUserId"))
        assertTrue(source.contains("getWebChatsForOwner"))
        assertTrue(source.contains("getWebProvidersForOwner"))
        assertTrue(source.contains("getWebChatForOwner"))
        assertTrue(source.contains("getWebProviderForOwner"))
        assertTrue(source.contains("normalizeChatParamsForOwner"))
        assertTrue(entities.contains("val ownerUserId: Long? = null"))
        assertTrue(migrations.contains("MIGRATION_78_79"))
        assertTrue(migrations.contains("AI server web chat ownership"))
    }

    @Test
    fun `server docs dataset adapters are real actions`() {
        val serviceFile = listOf(
            File("app/src/main/java/com/example/llamadroid/service/AiToolServerService.kt"),
            File("src/main/java/com/example/llamadroid/service/AiToolServerService.kt")
        ).first { it.exists() }
        val source = serviceFile.readText()

        assertTrue(source.contains("startMangaTranslationWorkflow"))
        assertTrue(source.contains("startPdfToolJob"))
        assertTrue(source.contains("startDatasetImportJob"))
        assertTrue(source.contains("startDatasetPipelineJob"))
        assertTrue(source.contains("startDatasetExportJob"))
        assertTrue(source.contains("PDFService(applicationContext).translateMangaCbzBatch"))
        assertTrue(source.contains("DatasetForegroundService.enqueueBatch"))
        assertTrue(source.contains("chatProviderModels"))
        assertTrue(source.contains("providerParamsFromBody"))
        assertTrue(source.contains("clearChatToolEvents"))
        assertTrue(source.contains("/api/chat/tool-events/clear"))
        assertTrue(source.contains("AiServerNativeContracts.serverJson"))
        assertTrue(source.contains("finalizeNativeDescriptor"))
    }
}
