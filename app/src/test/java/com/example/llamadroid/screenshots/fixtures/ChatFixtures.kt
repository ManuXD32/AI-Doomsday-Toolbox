package com.example.llamadroid.screenshots.fixtures

import android.app.Application
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.screenshots.harness.RunningServerFake
import com.example.llamadroid.screenshots.harness.ShotLocale
import com.example.llamadroid.service.NativeChatToolRuntime as Tools
import com.example.llamadroid.service.chat.NativeChatStep
import com.example.llamadroid.service.chat.NativeChatStepStatus
import com.example.llamadroid.service.chat.NativeChatStepType
import com.example.llamadroid.service.chat.NativeChatSteps
import com.example.llamadroid.service.chat.NativeChatStepsCodec
import kotlinx.coroutines.runBlocking

/** Native-chat conversations that use tools, seeded through the real message/steps columns. */
object ChatFixtures {
    data class Seeded(val chatId: Long, val serverRowId: Long, val cardId: Long)

    private const val HOUR = 3_600_000L

    private fun toolStep(round: Int, id: String, name: String, args: String, result: String, ms: Long) = NativeChatStep(
        type = NativeChatStepType.TOOL, round = round, text = result, toolCallId = id, toolName = name,
        arguments = args, status = NativeChatStepStatus.OK, durationMs = ms
    )

    private fun text(round: Int, text: String) = NativeChatStep(type = NativeChatStepType.TEXT, round = round, text = text)

    /** The water-plan turn: kiwix_search + calculator + a formatted answer. */
    fun waterPlanSteps(t: DemoText.ChatTexts) = NativeChatStepsCodec.encode(
        NativeChatSteps(
            steps = listOf(
                toolStep(0, "call_1", Tools.TOOL_KIWIX_SEARCH, """{"query":"${t.kiwixQuery}"}""", t.kiwixResult, 1_400),
                toolStep(1, "call_2", Tools.TOOL_CALCULATOR, """{"expression":"4 * 14 * 3.8"}""", t.calcResult, 12)
            ),
            complete = true
        )
    )

    fun imageSteps(t: DemoText.ChatTexts) = NativeChatStepsCodec.encode(
        NativeChatSteps(
            steps = listOf(
                toolStep(0, "call_3", Tools.TOOL_GENERATE_IMAGE, """{"prompt":"${t.imagePrompt}"}""", t.imageResult, 41_000)
            ),
            complete = true
        )
    )

    /**
     * Seeds the water-plan chat (and, when [withImageTurn], the poster turn with the generated image
     * inline) bound to [server]. Messages keep deterministic ids and timestamps.
     */
    fun seedChat(
        app: Application,
        locale: ShotLocale,
        server: ServerFixtures.Running,
        title: String? = null,
        withWaterPlan: Boolean = true,
        withImageTurn: Boolean,
        ageHours: Long = 1
    ): Seeded = runBlocking {
        val t = DemoText.ChatTexts(locale)
        val db = AppDatabase.getDatabase(app)
        val now = System.currentTimeMillis()
        val start = now - ageHours * HOUR
        val chatId = db.llamaChatDao().insertChat(
            LlamaChatEntity(title = title ?: t.title, serverId = server.rowId, lastModified = now - ageHours * HOUR + 200_000, contextSize = 8192)
        )
        val dao = db.llamaMessageDao()
        if (withWaterPlan) {
            dao.insertMessage(LlamaMessageEntity(chatId = chatId, role = "user", content = t.userWater, timestamp = start))
            dao.insertMessage(
                LlamaMessageEntity(
                    chatId = chatId, role = "assistant", content = t.waterAnswer, timestamp = start + 30_000,
                    promptTokens = 1_834, completionTokens = 214, tps = 9.8, generationTimeMs = 21_800,
                    stepsJson = waterPlanSteps(t)
                )
            )
        }
        if (withImageTurn) {
            val image = MediaFixtures.copy(app, "coral_tide", "sd_output", "chat_poster.jpg")
            dao.insertMessage(LlamaMessageEntity(chatId = chatId, role = "user", content = t.userImage, timestamp = start + 120_000))
            dao.insertMessage(
                LlamaMessageEntity(
                    chatId = chatId, role = "assistant", content = t.imageAnswer, timestamp = start + 170_000,
                    imagePath = image.absolutePath,
                    promptTokens = 2_210, completionTokens = 38, tps = 10.4, generationTimeMs = 3_700,
                    stepsJson = imageSteps(t)
                )
            )
        }
        Seeded(chatId, server.rowId, server.cardId)
    }

    /** Extra titled chats (older, without tool steps) so the chat list looks used. */
    fun seedOtherChats(app: Application, locale: ShotLocale, server: ServerFixtures.Running) = runBlocking {
        val t = DemoText.ChatTexts(locale)
        val db = AppDatabase.getDatabase(app)
        val now = System.currentTimeMillis()
        t.otherChats.forEachIndexed { index, title ->
            val chatId = db.llamaChatDao().insertChat(
                LlamaChatEntity(title = title, serverId = server.rowId, lastModified = now - (index + 2) * 5 * HOUR)
            )
            db.llamaMessageDao().insertMessage(
                LlamaMessageEntity(chatId = chatId, role = "user", content = title, timestamp = now - (index + 2) * 5 * HOUR)
            )
            db.llamaMessageDao().insertMessage(
                LlamaMessageEntity(chatId = chatId, role = "assistant", content = "…", timestamp = now - (index + 2) * 5 * HOUR + 20_000)
            )
        }
    }

    fun fake(): RunningServerFake = RunningServerFake()
}
