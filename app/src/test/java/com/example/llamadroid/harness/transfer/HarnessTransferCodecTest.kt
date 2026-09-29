package com.example.llamadroid.harness.transfer

import com.github.luben.zstd.ZstdOutputStream
import com.github.luben.zstd.ZstdInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HarnessTransferCodecTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun inspectionHonorsCancellationDuringArchiveValidation() {
        val fixture = fixture()
        val archive = temporary.newFile("cancelled-inspection.zip")
        HarnessTransferArchive.export(archive, fixture.source)
        var checks = 0
        val failure = runCatching {
            HarnessTransferArchive.inspect(
                archive,
                cancellation = TransferCancellation { phase ->
                    checks += 1
                    if (phase == TransferWorkPhase.VALIDATE && checks >= 3) {
                        throw IllegalStateException("cancelled")
                    }
                },
            )
        }.exceptionOrNull()
        assertEquals("cancelled", failure?.message)
        assertTrue(checks >= 3)
    }

    @Test
    fun reversedZstdMagicIsRejectedBeforeFrameDecoding() {
        val bytes = ByteArrayOutputStream()
        writeFrame(
            bytes,
            JSONObject().put("type", "session").put("version", 3)
                .put("id", "magic-test").put("createdAt", 1).put("isSeeded", false)
                .put("delegationDepth", 0).toString() + "\n",
        )
        val reversed = bytes.toByteArray()
        reversed[0] = 0xFD.toByte()
        reversed[1] = 0x2F.toByte()
        reversed[2] = 0xB5.toByte()
        reversed[3] = 0x28.toByte()

        val failure = runCatching {
            HarnessTransferSessionCodec.validate(ByteArrayInputStream(reversed))
        }.exceptionOrNull()
        assertEquals("TRANSFER_SESSION_ZSTD_MAGIC_INVALID", failure?.message)
    }

    @Test
    fun metadataJsonAllowsWhitespaceButRejectsTrailingValues() {
        val fixture = fixture()
        val validArchive = temporary.newFile("metadata-whitespace.zip")
        HarnessTransferArchive.export(
            validArchive,
            fixture.source.copy(metadata = listOf(TransferMetadata("whitespace", "{\"ok\":true} \n"))),
        )

        listOf("{\"ok\":true} {}", "{} null", "{\"ok\":", "{}\u0000", "{}\u0000hidden")
            .forEachIndexed { index, json ->
                val invalidArchive = temporary.newFile("metadata-invalid-$index.zip")
                val failure = runCatching {
                    HarnessTransferArchive.export(
                        invalidArchive,
                        fixture.source.copy(metadata = listOf(TransferMetadata("invalid", json))),
                    )
                }.exceptionOrNull()
                assertEquals("TRANSFER_METADATA_JSON_INVALID", failure?.message)
            }
    }

    @Test
    fun encryptedPortableSelectionAuthenticatesAndRemapsSessionsWithoutRewritingMetadata() {
        val fixture = fixture()
        val metadata = """{"sessionId":"old-id","content":"old-id must remain text"}"""
        val archive = temporary.newFile("portable.zip")
        val manifest = HarnessTransferArchive.export(
            archive,
            fixture.source.copy(
                metadata = listOf(TransferMetadata("settings", metadata), TransferMetadata("credentials", "{\"token\":\"secret\"}", sensitive = true)),
            ),
            TransferExportOptions(
                password = "archive-password".toCharArray(),
                projectFolders = setOf("project-a"),
                sessionIds = setOf("old-id"),
                includeCredentials = true,
            ),
        )

        assertEquals(TransferArchiveMode.PORTABLE, manifest.mode)
        assertTrue(manifest.encrypted)
        assertTrue(manifest.entries.any { it.path == "metadata/credentials.json" })
        val inspection = HarnessTransferArchive.inspect(archive, "archive-password".toCharArray())
        assertEquals(metadata, inspection.metadata["settings"])
        assertEquals("{\"token\":\"secret\"}", inspection.metadata["credentials"])
        assertTrue(runCatching { HarnessTransferArchive.inspect(archive, "wrong".toCharArray()) }.isFailure)

        val staged = HarnessTransferArchive.stage(
            archive,
            TransferStageOptions(
                password = "archive-password".toCharArray(),
                stagingRoot = temporary.root,
                sessionIdFactory = { "new-id" },
                workspacePathMapper = { "/workspace/projects/project-a-new" },
                referenceMapper = { key, value -> if (key == "model") "destination-model" else value },
            ),
        )
        try {
            val stagedMetadata = JSONObject(File(staged.stagingDirectory, "metadata/settings.json").readText())
            assertEquals("old-id", stagedMetadata.getString("sessionId"))
            val mappedSession = staged.mapping.archivePaths[fixture.sessionArchivePath]
            assertNotNull(mappedSession)
            assertTrue(mappedSession!!.contains("new-id"))
            val sessionFile = File(staged.stagingDirectory, mappedSession)
            assertTrue(sessionFile.isFile)
            FileInputStream(sessionFile).use { input ->
                val descriptor = HarnessTransferSessionCodec.sessionDescriptor(
                    input,
                    mappedSession,
                    mappedSession.split('/')[2],
                )
                assertEquals("new-id", descriptor.id)
                assertEquals("/workspace/projects/project-a-new", descriptor.cwd)
            }
            assertTrue(sessionFile.readBytes().isNotEmpty())
        } finally {
            staged.stagingDirectory.deleteRecursively()
        }
    }

    @Test
    fun sessionRewriteChangesStructuralForkReferencesButPreservesOpaqueToolPayload() {
        val fixture = fixture()
        val archive = temporary.newFile("opaque.zip")
        HarnessTransferArchive.export(
            archive,
            fixture.source,
            TransferExportOptions(sessionIds = setOf("old-id")),
        )

        val staged = HarnessTransferArchive.stage(
            archive,
            TransferStageOptions(
                stagingRoot = temporary.root,
                sessionIdFactory = { "new-id" },
                workspacePathMapper = { "/workspace/projects/project-a-new" },
                referenceMapper = { key, value -> if (key == "model") "destination-model" else value },
            ),
        )
        try {
            val mappedPath = staged.mapping.archivePaths.getValue(fixture.sessionArchivePath)
            val plaintext = FileInputStream(File(staged.stagingDirectory, mappedPath)).use { input ->
                ZstdInputStream(input).use { it.readBytes().toString(Charsets.UTF_8) }
            }
            assertTrue(plaintext.contains("\"childSessionId\":\"new-id\""))
            assertTrue(plaintext.contains("\"cwd\":\"/workspace/projects/project-a-new\""))
            assertTrue(plaintext.contains("\"model\":\"destination-model\""))
            assertTrue(plaintext.contains("\"sessionId\":\"old-id\""))
            assertTrue(plaintext.contains("\"cwd\":\"/workspace/projects/project-a\""))
            assertTrue(plaintext.contains("\"model\":\"source-model\""))
        } finally {
            staged.stagingDirectory.deleteRecursively()
        }
    }

    @Test
    fun inspectionExposesStructuralModelReferencesAndKeepsMetadataWhenConfigurationIsExcluded() {
        val fixture = fixture()
        File(fixture.dsh, "settings.json").writeText("{\"model\":\"config-model\",\"provider\":\"config-provider\"}")
        val attachment = temporary.newFile("selected-image.png").apply { writeText("png") }
        val archive = temporary.newFile("selection.zip")
        HarnessTransferArchive.export(
            archive,
            fixture.source.copy(
                additionalFiles = mapOf("attachments/conversation/selected-image.png" to attachment),
                metadata = listOf(TransferMetadata("room", "{\"keep\":true}")),
            ),
            TransferExportOptions(
                includeConfiguration = false,
                sessionIds = setOf("old-id"),
            ),
        )
        val inspection = HarnessTransferArchive.inspect(archive)
        assertEquals("{\"keep\":true}", inspection.metadata.getValue("room"))
        assertTrue(inspection.references.getValue("model").contains("source-model"))
        assertTrue(inspection.references.getValue("model").contains("config-model").not())
        assertFalse(inspection.entries.any { it.path == "dsh_home/settings.json" })
        assertTrue(inspection.entries.any { it.path == "attachments/conversation/selected-image.png" })

        val staged = HarnessTransferArchive.stage(archive, stagingRoot = temporary.root)
        try {
            assertEquals("png", File(staged.attachments, "conversation/selected-image.png").readText())
        } finally {
            staged.stagingDirectory.deleteRecursively()
        }
    }

    @Test
    fun selectedSessionExportsOnlyReferencedContentAddressedAttachmentsAndMergesSafely() {
        val root = temporary.newFolder("attachment-store")
        val dsh = File(root, "dsh_home").apply { mkdirs() }
        val projects = File(root, "projects").apply { mkdirs() }
        val cwd = "/workspace/projects/attachments"
        val sessionId = "attachment-session"
        val projectKey = HarnessTransferSessionCodec.projectKey(cwd)
        val sessionPath = "dsh_home/sessions/$projectKey/${HarnessTransferSessionCodec.encodeSegment(sessionId)}/session.v3.jsonl.zstd"
        val imageBytes = "selected-image".toByteArray()
        val fileBytes = "selected-file".toByteArray()
        val unrelatedBytes = "unrelated".toByteArray()
        val imageDigest = digest(imageBytes)
        val fileDigest = digest(fileBytes)
        val unrelatedDigest = digest(unrelatedBytes)
        File(dsh, "attachments/v1/objects/${imageDigest.take(2)}/$imageDigest").apply {
            requireNotNull(parentFile).mkdirs()
            writeBytes(imageBytes)
        }
        File(dsh, "attachments/v1/file-objects/${fileDigest.take(2)}/$fileDigest").apply {
            requireNotNull(parentFile).mkdirs()
            writeBytes(fileBytes)
        }
        File(dsh, "attachments/v1/files/${fileDigest.take(2)}/$fileDigest/notes.txt").apply {
            requireNotNull(parentFile).mkdirs()
            writeBytes(fileBytes)
        }
        File(dsh, "attachments/v1/objects/${unrelatedDigest.take(2)}/$unrelatedDigest").apply {
            requireNotNull(parentFile).mkdirs()
            writeBytes(unrelatedBytes)
        }
        val session = File(dsh, sessionPath.removePrefix("dsh_home/")).apply {
            requireNotNull(parentFile).mkdirs()
        }
        FileOutputStream(session).use { output ->
            writeFrame(output, JSONObject().put("type", "session").put("version", 3)
                .put("id", sessionId).put("createdAt", 1).put("isSeeded", false)
                .put("delegationDepth", 0).put("cwd", cwd).toString() + "\n")
            val message = JSONObject()
                .put("id", "attachment-message")
                .put("role", "user")
                .put("source", JSONObject().put("kind", "user"))
                .put("content", JSONArray()
                    .put(JSONObject().put("type", "image").put("attachment", JSONObject()
                        .put("attachmentId", "sha256:$imageDigest")
                        .put("mediaType", "image/png").put("bytes", imageBytes.size)
                        .put("width", 1).put("height", 1)))
                    .put(JSONObject().put("type", "file").put("attachment", JSONObject()
                        .put("attachmentId", "sha256:$fileDigest")
                        .put("name", "notes.txt").put("bytes", fileBytes.size))))
            writeFrame(output, JSONObject().put("type", "user/message").put("seq", 0).put("time", 1)
                .put("data", JSONObject().put("message", message)).toString() + "\n")
        }

        val archive = temporary.newFile("attachments.zip")
        val manifest = HarnessTransferArchive.export(
            archive,
            TransferSource("attachment-runtime", dsh, projects),
            TransferExportOptions(includeConfiguration = false, sessionIds = setOf(sessionId)),
        )
        assertTrue(manifest.entries.any { it.path == "dsh_home/attachments/v1/objects/${imageDigest.take(2)}/$imageDigest" })
        assertTrue(manifest.entries.any { it.path == "dsh_home/attachments/v1/file-objects/${fileDigest.take(2)}/$fileDigest" })
        assertTrue(manifest.entries.any { it.path == "dsh_home/attachments/v1/files/${fileDigest.take(2)}/$fileDigest/notes.txt" })
        assertFalse(manifest.entries.any { it.path.contains(unrelatedDigest) })

        val staged = HarnessTransferArchive.stage(
            archive,
            TransferStageOptions(stagingRoot = temporary.root, sessionIdFactory = { it }),
        )
        try {
            val destination = temporary.newFolder("attachment-destination")
            val existing = File(destination, "attachments/v1/objects/${imageDigest.take(2)}/$imageDigest").apply {
                requireNotNull(parentFile).mkdirs()
                writeBytes(imageBytes)
            }
            HarnessTransferDomains.merge(staged.dshHome, destination)
            assertEquals(imageBytes.toList(), existing.readBytes().toList())
            assertEquals(fileBytes.toList(), File(destination, "attachments/v1/file-objects/${fileDigest.take(2)}/$fileDigest").readBytes().toList())
            assertEquals(fileBytes.toList(), File(destination, "attachments/v1/files/${fileDigest.take(2)}/$fileDigest/notes.txt").readBytes().toList())
            assertFalse(File(destination, "attachments/v1/objects/${unrelatedDigest.take(2)}/$unrelatedDigest").exists())
        } finally {
            staged.stagingDirectory.deleteRecursively()
        }
    }

    @Test
    fun stagingSettlesDurableInboxAndTeamQueueWhilePreservingHistoricalMessages() {
        val fixture = fixture()
        val session = File(fixture.dsh, fixture.sessionArchivePath.removePrefix("dsh_home/"))
        FileOutputStream(session).use { output ->
            writeFrame(output, JSONObject().put("type", "session").put("version", 3)
                .put("id", "old-id").put("createdAt", 1).put("isSeeded", false)
                .put("delegationDepth", 0).put("cwd", "/workspace/projects/project-a").toString() + "\n")
            val queuedMessage = JSONObject().put("id", "queued-input").put("role", "user")
                .put("source", JSONObject().put("kind", "user"))
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "queued text")))
            writeFrame(output, JSONObject().put("type", "agent/inbox/spliced").put("seq", 0).put("time", 1)
                .put("data", JSONObject().put("target", "next-turn").put("start", 0)
                    .put("inserted", JSONArray().put(queuedMessage))).toString() + "\n")
            val teamMessage = JSONObject().put("id", "team-message-1").put("senderId", "old-id")
                .put("senderName", "lead").put("targetId", "old-id")
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "team text")))
            writeFrame(output, JSONObject().put("type", "team/message/queued").put("seq", 1).put("time", 2)
                .put("data", JSONObject().put("version", 2).put("teamId", "old-id")
                    .put("message", teamMessage)).toString() + "\n")
            val history = JSONObject().put("id", "history")
                .put("role", "user")
                .put("source", JSONObject().put("kind", "user"))
                .put("content", JSONArray().put(JSONObject()
                    .put("type", "text")
                    .put("text", "history text")))
            val historyEvent = JSONObject().put("type", "user/message")
                .put("seq", 2)
                .put("time", 3)
                .put("data", JSONObject().put("message", history))
            writeFrame(output, historyEvent.toString() + "\n")
        }
        val archive = temporary.newFile("queued.zip")
        HarnessTransferArchive.export(archive, fixture.source)
        val staged = HarnessTransferArchive.stage(
            archive,
            TransferStageOptions(stagingRoot = temporary.root, sessionIdFactory = { "new-id" }),
        )
        try {
            val mappedPath = staged.mapping.archivePaths.getValue(fixture.sessionArchivePath)
            val decompressed = FileInputStream(File(staged.stagingDirectory, mappedPath)).use { input ->
                ZstdInputStream(input).apply { setContinuous(true) }.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
            }
            assertTrue(decompressed.contains("queued text"))
            assertTrue(decompressed.contains("team text"))
            assertTrue(decompressed.contains("history text"))
            assertTrue(decompressed.contains("agent/inbox/spliced"))
            assertTrue(decompressed.contains("\"outcome\":\"canceled\""))
            assertTrue(decompressed.contains("team/message/delivered"))
            assertTrue(decompressed.contains("\"messageId\":\"team-message-1\""))
            FileInputStream(File(staged.stagingDirectory, mappedPath)).use { input ->
                HarnessTransferSessionCodec.validate(input)
            }
        } finally {
            staged.stagingDirectory.deleteRecursively()
        }
    }

    @Test
    fun stagingDeletesActiveSchedulesButPreservesScheduleHistory() {
        val fixture = fixture()
        val session = File(fixture.dsh, fixture.sessionArchivePath.removePrefix("dsh_home/"))
        FileOutputStream(session).use { output ->
            writeFrame(output, JSONObject().put("type", "session").put("version", 3)
                .put("id", "old-id").put("createdAt", 1).put("isSeeded", false)
                .put("delegationDepth", 0).put("cwd", "/workspace/projects/project-a").toString() + "\n")
            writeFrame(output, scheduleCreate(0, "one-shot", "at", "one-shot prompt", "2030-01-01T00:00:00.000Z"))
            writeFrame(output, scheduleCreate(1, "repeating", "every", "repeating prompt", "2030-01-01T00:05:00.000Z", 300))
            writeFrame(output, scheduleCreate(2, "already-deleted", "after", "deleted prompt", "2030-01-01T00:10:00.000Z", 60))
            writeFrame(output, scheduleChange(3, "delete", "already-deleted"))
            writeFrame(output, scheduleCreate(4, "already-dispatched", "at", "dispatched prompt", "2030-01-01T00:15:00.000Z"))
            writeFrame(output, scheduleChange(5, "dispatch", "already-dispatched"))
        }
        val archive = temporary.newFile("schedules.zip")
        HarnessTransferArchive.export(archive, fixture.source)
        val staged = HarnessTransferArchive.stage(
            archive,
            TransferStageOptions(stagingRoot = temporary.root, sessionIdFactory = { "new-id" }),
        )
        try {
            val mappedPath = staged.mapping.archivePaths.getValue(fixture.sessionArchivePath)
            val lines = FileInputStream(File(staged.stagingDirectory, mappedPath)).use { input ->
                ZstdInputStream(input).apply { setContinuous(true) }.use { stream ->
                    stream.readBytes().toString(Charsets.UTF_8).trim().lines()
                }
            }
            val events = lines.map { line -> JSONObject(line) }
            val deletes = events.filter { it.optString("type") == "schedule/change" }
                .filter { it.optJSONObject("data")?.optString("operation") == "delete" }
            assertTrue(deletes.any {
                it.optLong("seq") == 3L && it.optJSONObject("data")?.optString("id") == "already-deleted"
            })
            val appendedDeletes = deletes.filter { it.optLong("seq") >= 6L }
                .mapNotNull { it.optJSONObject("data")?.optString("id") }
            assertEquals(setOf("one-shot", "repeating"), appendedDeletes.toSet())
            assertTrue(events.any {
                it.optJSONObject("data")?.optString("operation") == "create" &&
                    it.optJSONObject("data")?.optJSONObject("schedule")?.optString("prompt") == "one-shot prompt"
            })
            assertTrue(events.any {
                it.optJSONObject("data")?.optString("operation") == "dispatch" &&
                    it.optJSONObject("data")?.optString("id") == "already-dispatched"
            })
            FileInputStream(File(staged.stagingDirectory, mappedPath)).use { input ->
                HarnessTransferSessionCodec.validate(input)
            }
        } finally {
            staged.stagingDirectory.deleteRecursively()
        }
    }

    @Test
    fun stagingUsesTheLastInheritedSeedMarkerForScheduleFolding() {
        val fixture = fixture()
        val session = File(fixture.dsh, fixture.sessionArchivePath.removePrefix("dsh_home/"))
        FileOutputStream(session).use { output ->
            writeFrame(output, JSONObject().put("type", "session").put("version", 3)
                .put("id", "old-id").put("createdAt", 1).put("isSeeded", true)
                .put("delegationDepth", 1).put("cwd", "/workspace/projects/project-a").toString() + "\n")
            // This create belongs to the inherited prefix and must not be folded for the child.
            writeFrame(output, scheduleCreate(0, "inherited", "at", "inherited prompt", "2030-01-01T00:00:00.000Z"))
            writeFrame(output, JSONObject().put("type", "session/end-seed").put("seq", 1).put("time", 1)
                .put("data", JSONObject().put("inherited", true)).toString() + "\n")
            // This source-owned record falls before the final inherited cut and must be discarded.
            writeFrame(output, scheduleCreate(2, "between-markers", "at", "discarded prompt", "2030-01-01T00:03:00.000Z"))
            writeFrame(output, JSONObject().put("type", "session/end-seed").put("seq", 3).put("time", 3)
                .put("data", JSONObject().put("inherited", true)).toString() + "\n")
            writeFrame(output, scheduleCreate(4, "child-active", "at", "child prompt", "2030-01-01T00:05:00.000Z"))
        }
        val archive = temporary.newFile("seeded-schedules.zip")
        HarnessTransferArchive.export(archive, fixture.source)
        val staged = HarnessTransferArchive.stage(
            archive,
            TransferStageOptions(stagingRoot = temporary.root, sessionIdFactory = { "new-id" }),
        )
        try {
            val mappedPath = staged.mapping.archivePaths.getValue(fixture.sessionArchivePath)
            val lines = FileInputStream(File(staged.stagingDirectory, mappedPath)).use { input ->
                ZstdInputStream(input).apply { setContinuous(true) }.use { stream ->
                    stream.readBytes().toString(Charsets.UTF_8).trim().lines()
                }
            }
            val events = lines.map { line -> JSONObject(line) }
            val deletes = events.filter { it.optString("type") == "schedule/change" }
                .filter { it.optJSONObject("data")?.optString("operation") == "delete" }
                .mapNotNull { it.optJSONObject("data")?.optString("id") }
            assertEquals(listOf("child-active"), deletes)
            assertTrue(events.none {
                it.optJSONObject("data")?.optString("operation") == "delete" &&
                    it.optJSONObject("data")?.optString("id") == "inherited"
            })
            FileInputStream(File(staged.stagingDirectory, mappedPath)).use { input ->
                HarnessTransferSessionCodec.validate(input)
            }
        } finally {
            staged.stagingDirectory.deleteRecursively()
        }
    }

    @Test
    fun selectedSessionIncludesParentAndChildClosure() {
        val root = temporary.newFolder("closure")
        val dsh = File(root, "dsh_home").apply { mkdirs() }
        val projects = File(root, "projects").apply { mkdirs() }
        File(projects, "project-a").mkdirs()
        val cwd = "/workspace/projects/project-a"
        writeSession(dsh, cwd, "parent-id", null)
        writeSession(dsh, cwd, "child-id", "parent-id")
        val archive = temporary.newFile("closure.zip")
        val manifest = HarnessTransferArchive.export(
            archive,
            TransferSource("closure-runtime", dsh, projects),
            TransferExportOptions(sessionIds = setOf("child-id")),
        )
        assertEquals(setOf("parent-id", "child-id"), manifest.sessions.map { it.id }.toSet())

        val staged = HarnessTransferArchive.stage(
            archive,
            TransferStageOptions(stagingRoot = temporary.root, sessionIdFactory = { "mapped-$it" }),
        )
        try {
            assertEquals(setOf("parent-id", "child-id"), staged.mapping.sessionIds.keys)
            assertEquals(setOf("mapped-parent-id", "mapped-child-id"), staged.mapping.sessionIds.values.toSet())
        } finally {
            staged.stagingDirectory.deleteRecursively()
        }
    }

    @Test
    fun fullSnapshotPreservesModesLinksAndRootfsWhileOmittingScratchAndSecrets() {
        val fixture = fixture()
        File(fixture.rootfs, "root/auth.json").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("secret")
        }
        File(fixture.rootfs, "bin/tool").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("#!/bin/sh\n")
            setExecutable(true, false)
        }
        val sharedTemporary = File(fixture.rootfs, "var/tmp").apply { mkdirs() }
        Files.setAttribute(sharedTemporary.toPath(), "unix:mode", 0x3FF)
        val broken = File(fixture.rootfs, "bin/broken")
        Files.createSymbolicLink(broken.toPath(), java.nio.file.Paths.get("missing-tool"))
        val absoluteGuest = File(fixture.rootfs, "bin/absolute")
        Files.createSymbolicLink(absoluteGuest.toPath(), java.nio.file.Paths.get("/usr/bin/tool"))
        val hardTarget = File(fixture.rootfs, "share/data.txt").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("same bytes")
        }
        val hardLink = File(fixture.rootfs, "share/data-copy.txt")
        Files.createLink(hardLink.toPath(), hardTarget.toPath())
        File(fixture.dsh, "credentials.json").apply { writeText("secret") }
        File(fixture.dsh, "plugins/node_modules/example/providers/index.js").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("module.exports = {}\n")
        }
        val runtimeScratch = temporary.newFolder("runtime-scratch").apply { File(this, "pid").writeText("1") }
        val archive = temporary.newFile("full.zip")

        val manifest = HarnessTransferArchive.export(
            archive,
            fixture.source.copy(runtimeRoot = runtimeScratch),
            TransferExportOptions(mode = TransferArchiveMode.FULL),
        )
        assertTrue(manifest.entries.any { it.path == "rootfs/bin/tool" })
        assertTrue(manifest.entries.any { it.path == "rootfs/bin/broken" && it.symlink })
        val hardlinkPaths = manifest.entries
            .filter { it.path == "rootfs/share/data.txt" || it.path == "rootfs/share/data-copy.txt" }
            .map { it.path }
            .toSet()
        assertEquals(setOf("rootfs/share/data.txt", "rootfs/share/data-copy.txt"), hardlinkPaths)
        val dataEntry = manifest.entries.single { it.path == "rootfs/share/data.txt" }
        val copyEntry = manifest.entries.single { it.path == "rootfs/share/data-copy.txt" }
        assertTrue((dataEntry.hardlinkPath == null) xor (copyEntry.hardlinkPath == null))
        assertTrue(dataEntry.hardlinkPath == copyEntry.path || copyEntry.hardlinkPath == dataEntry.path)
        assertFalse(manifest.entries.any { it.path.startsWith("runtime/") })
        assertTrue(manifest.entries.any { it.path == "rootfs/root/auth.json" })
        assertFalse(manifest.entries.any { it.path == "dsh_home/credentials.json" })
        assertTrue(manifest.entries.any { it.path == "dsh_home/plugins/node_modules/example/providers/index.js" })

        val staged = HarnessTransferArchive.stage(archive, stagingRoot = temporary.root)
        try {
            assertEquals(0x3FF, (Files.getAttribute(File(staged.rootfs, "var/tmp").toPath(), "unix:mode") as Number).toInt() and 0xFFF)
            val stagedExecutable = File(staged.rootfs, "bin/tool")
            assertTrue(stagedExecutable.canExecute())
            val stagedBroken = File(staged.rootfs, "bin/broken")
            assertTrue(Files.isSymbolicLink(stagedBroken.toPath()))
            assertEquals("missing-tool", Files.readSymbolicLink(stagedBroken.toPath()).toString())
            val stagedAbsolute = File(staged.rootfs, "bin/absolute")
            assertEquals("../usr/bin/tool", Files.readSymbolicLink(stagedAbsolute.toPath()).toString())
            val stagedTarget = File(staged.rootfs, "share/data.txt")
            val stagedLink = File(staged.rootfs, "share/data-copy.txt")
            assertTrue(Files.isSameFile(stagedTarget.toPath(), stagedLink.toPath()))
            assertTrue(File(staged.rootfs, "root/auth.json").isFile)
        } finally {
            staged.stagingDirectory.deleteRecursively()
        }
    }

    private fun fixture(): Fixture {
        val root = temporary.newFolder("fixture")
        val dsh = File(root, "dsh_home").apply { mkdirs() }
        val projects = File(root, "projects").apply { mkdirs() }
        val rootfs = File(root, "rootfs").apply { mkdirs() }
        val project = File(projects, "project-a").apply { mkdirs() }
        File(project, "README.md").writeText("workspace")
        val cwd = "/workspace/projects/project-a"
        val id = "old-id"
        val projectKey = HarnessTransferSessionCodec.projectKey(cwd)
        val archivePath = "dsh_home/sessions/$projectKey/${HarnessTransferSessionCodec.encodeSegment(id)}/session.v3.jsonl.zstd"
        val session = File(dsh, archivePath.removePrefix("dsh_home/")).apply {
            requireNotNull(parentFile).mkdirs()
        }
        FileOutputStream(session).use { output ->
            writeFrame(output, JSONObject().put("type", "session").put("version", 3)
                .put("id", id).put("createdAt", 1).put("isSeeded", false)
                .put("delegationDepth", 0).put("cwd", cwd).toString() + "\n")
            val delegation = JSONObject().put("type", "delegation").put("data", JSONObject()
                .put("childSessionId", id)
                .put("cwd", cwd)
                .put("model", "source-model")
                .put("tool", JSONObject().put("arguments", JSONObject()
                    .put("sessionId", id).put("cwd", cwd).put("model", "source-model"))))
            writeFrame(output, delegation.toString() + "\n")
        }
        return Fixture(
            dsh = dsh,
            rootfs = rootfs,
            sessionArchivePath = archivePath,
            source = TransferSource("source-runtime", dsh, projects, rootfs = rootfs),
        )
    }

    private fun writeFrame(output: java.io.OutputStream, text: String) {
        val compressed = ByteArrayOutputStream()
        ZstdOutputStream(compressed).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        output.write(compressed.toByteArray())
    }

    private fun scheduleCreate(
        sequence: Int,
        id: String,
        kind: String,
        prompt: String,
        scheduledAt: String,
        everySeconds: Int? = null,
    ): String {
        val schedule = JSONObject()
            .put("id", id)
            .put("kind", kind)
            .put("prompt", prompt)
            .put("scheduledAt", scheduledAt)
        when (kind) {
            "after" -> schedule.put("afterSeconds", everySeconds ?: 60)
            "every" -> schedule.put("everySeconds", everySeconds ?: 300)
        }
        return JSONObject().put("type", "schedule/change").put("seq", sequence).put("time", sequence + 1)
            .put("data", JSONObject().put("version", 1).put("operation", "create").put("schedule", schedule))
            .toString() + "\n"
    }

    private fun scheduleChange(sequence: Int, operation: String, id: String): String =
        JSONObject().put("type", "schedule/change").put("seq", sequence).put("time", sequence + 1)
            .put("data", JSONObject().put("version", 1).put("operation", operation).put("id", id))
            .toString() + "\n"

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { byte -> "%02x".format(byte) }

    private fun writeSession(dsh: File, cwd: String, id: String, parent: String?) {
        val projectKey = HarnessTransferSessionCodec.projectKey(cwd)
        val path = File(dsh, "sessions/$projectKey/${HarnessTransferSessionCodec.encodeSegment(id)}/session.v3.jsonl.zstd")
        requireNotNull(path.parentFile).mkdirs()
        FileOutputStream(path).use { output ->
            val header = JSONObject().put("type", "session").put("version", 3)
                .put("id", id).put("createdAt", 1).put("isSeeded", false)
                .put("delegationDepth", if (parent == null) 0 else 1).put("cwd", cwd)
            parent?.let { header.put("parentSession", it) }
            writeFrame(output, header.toString() + "\n")
        }
    }

    private data class Fixture(
        val dsh: File,
        val rootfs: File,
        val sessionArchivePath: String,
        val source: TransferSource,
    )
}
