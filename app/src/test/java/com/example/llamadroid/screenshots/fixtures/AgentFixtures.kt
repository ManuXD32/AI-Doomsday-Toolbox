package com.example.llamadroid.screenshots.fixtures

import android.app.Application
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaMessageAttachmentEntity
import com.example.llamadroid.data.model.LlamaMessageAttachmentSource
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.data.model.LlamaMessageKind
import com.example.llamadroid.data.model.NativeChatDelegateMessageEntity
import com.example.llamadroid.data.model.NativeChatDelegateMessageRole
import com.example.llamadroid.data.model.NativeChatDelegateTaskEntity
import com.example.llamadroid.data.model.NativeChatDelegateTaskState
import com.example.llamadroid.screenshots.harness.ShotLocale
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A finished DeepSeek delegation task (brief, subagent progress, result files) for the viewer shot. */
object AgentFixtures {
    data class Seeded(val chatId: Long, val taskId: Long)

    private const val MIN = 60_000L

    fun seedDelegation(app: Application, locale: ShotLocale, server: ServerFixtures.Running): Seeded = runBlocking {
        val t = AgentTexts(locale)
        val db = AppDatabase.getDatabase(app)
        val now = System.currentTimeMillis()
        val chatId = db.llamaChatDao().insertChat(
            LlamaChatEntity(title = t.chatTitle, serverId = server.rowId, lastModified = now - 4 * MIN)
        )
        val msgs = db.llamaMessageDao()
        msgs.insertMessage(LlamaMessageEntity(chatId = chatId, role = "user", content = t.userAsk, timestamp = now - 28 * MIN))
        val noticeId = msgs.insertMessage(
            LlamaMessageEntity(chatId = chatId, role = "user", content = t.notice, timestamp = now - 4 * MIN, kind = LlamaMessageKind.DELEGATION_NOTICE)
        )
        // Result files delivered into the chat (real attachment rows, tiny real files).
        val dir = File(app.filesDir, "llama_chat_files/$chatId").apply { mkdirs() }
        val files = listOf(
            Triple(t.zipName, "application/zip", 88_064L),
            Triple("index.html", "text/html", 4_210L)
        )
        val attachmentIds = files.map { (name, mime, size) ->
            val file = File(dir, name).apply { writeBytes(ByteArray(64)) }
            db.llamaMessageAttachmentDao().let { dao ->
                dao.insert(
                    LlamaMessageAttachmentEntity(
                        messageId = noticeId, chatId = chatId, path = file.absolutePath, displayName = name,
                        mimeType = mime, sizeBytes = size, source = LlamaMessageAttachmentSource.DEEPSEEK
                    )
                )
            }
        }
        val resultFiles = JSONArray().also { arr ->
            files.forEachIndexed { i, (name, _, _) ->
                arr.put(JSONObject().put("path", File(dir, name).absolutePath).put("name", name).put("attachmentId", attachmentIds[i]))
            }
        }
        val taskId = db.nativeChatDelegateTaskDao().insert(
            NativeChatDelegateTaskEntity(
                chatId = chatId, state = NativeChatDelegateTaskState.COMPLETED, title = t.taskTitle, request = t.brief,
                runtimeId = "deepseek-harness", resultStatus = t.resultStatus, resultSummary = t.resultSummary,
                resultFilesJson = resultFiles.toString(), progressNote = t.progress2,
                ctxUsedTokens = 21_300, ctxWindowTokens = 32_768, subagentModelCalls = 17, harnessPrompts = 5,
                createdAt = now - 26 * MIN, updatedAt = now - 4 * MIN, finishedAt = now - 4 * MIN
            )
        )
        val dao = db.nativeChatDelegateMessageDao()
        listOf(
            NativeChatDelegateMessageRole.MAIN_TO_SUB to t.brief,
            NativeChatDelegateMessageRole.SUB_TO_MAIN to t.progress2
        ).forEachIndexed { index, (role, text) ->
            dao.insert(
                NativeChatDelegateMessageEntity(
                    taskId = taskId, seq = index + 1, role = role, content = text,
                    createdAt = now - (26 - index * 20) * MIN
                )
            )
        }
        Seeded(chatId, taskId)
    }
}
