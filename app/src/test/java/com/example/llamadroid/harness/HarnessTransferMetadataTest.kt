package com.example.llamadroid.harness

import com.example.llamadroid.data.db.AgentConversationEntity
import com.example.llamadroid.data.db.AgentMessageEntity
import com.example.llamadroid.data.db.AgentMessagePartEntity
import com.example.llamadroid.data.db.HarnessRuntimeIds
import com.example.llamadroid.data.db.HarnessWorkspaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HarnessTransferMetadataTest {
    @Test
    fun `document round trips selected parts and attachment linkage`() {
        val conversation = AgentConversationEntity(
            id = 7L,
            title = "Legacy project",
            runtimeId = HarnessRuntimeIds.LEGACY,
        )
        val workspace = HarnessWorkspaceEntity(
            id = "legacy-workspace",
            backend = "LOCAL_PROOT",
            projectFolder = "legacy",
            title = "Legacy workspace",
            guestPath = "/workspace/projects/legacy",
            runtimeId = HarnessRuntimeIds.LEGACY,
        )
        val message = AgentMessageEntity(
            id = 9L,
            originalId = "legacy-message-1",
            conversationId = conversation.id,
            role = "assistant",
            content = "Generated image",
            imagePath = "/data/user/0/example/files/image.png",
        )
        val part = AgentMessagePartEntity(
            id = "legacy-part-1",
            conversationId = conversation.id,
            messageOriginalId = message.originalId,
            position = 0,
            type = "FILE",
            contentRef = "/data/user/0/example/files/part.bin",
        )
        val attachment = HarnessTransferMetadata.AttachmentDescriptor(
            key = "attachment-1",
            conversationId = conversation.id,
            messageOriginalId = message.originalId,
            sourcePath = message.imagePath!!,
            archivePath = "attachments/abc/image.png",
            sizeBytes = 12L,
            lastModifiedEpochMs = 34L,
        )
        val partAttachment = HarnessTransferMetadata.AttachmentDescriptor(
            key = "attachment-part-1",
            conversationId = conversation.id,
            messageOriginalId = "",
            sourcePath = part.contentRef!!,
            archivePath = "attachments/part/image.bin",
            sizeBytes = 8L,
            lastModifiedEpochMs = 35L,
            partId = part.id,
        )
        val document = HarnessTransferMetadata.Document(
            transferId = "transfer-1",
            sourceRuntimeId = HarnessRuntimeIds.LEGACY,
            workspaces = listOf(workspace),
            conversations = listOf(conversation),
            messages = listOf(message),
            parts = listOf(part),
            attachments = listOf(attachment, partAttachment),
        )

        val decoded = HarnessTransferMetadata.decode(document.toJson())

        assertEquals(listOf(part), decoded.parts)
        assertEquals(listOf(attachment, partAttachment), decoded.attachments)
        assertEquals(message.originalId, decoded.attachments.first().messageOriginalId)
        assertTrue(decoded.toJson().contains("attachments/abc/image.png"))

        val invalid = document.toJson().replace(
            "\"messageOriginalId\":\"legacy-message-1\"",
            "\"messageOriginalId\":\"missing-message\"",
        )
        try {
            HarnessTransferMetadata.decode(invalid)
            fail("Gson decoding must invoke Document validation")
        } catch (_: IllegalArgumentException) {
            // Expected: the part/attachment links no longer resolve to a message.
        }

        val json = document.toJson()
        assertThrows(IllegalArgumentException::class.java) {
            HarnessTransferMetadata.decode(json.replace("\"title\":\"Legacy project\"", "\"title\":null"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HarnessTransferMetadata.decode(json.replace("\"title\":\"Legacy workspace\"", "\"title\":null"))
        }
    }
}
