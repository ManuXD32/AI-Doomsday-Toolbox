package com.example.llamadroid.service

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import com.example.llamadroid.LlamaApplication
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AgentConversationEntity
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.AgentMessageEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Exercises the live approval CAS and Stop fence without connecting to a model endpoint.
 *
 * The service keeps this state in its companion for navigation continuity, so these tests
 * snapshot the fields touched by Stop and restore them after each case. The Stop case also wires
 * the service to an isolated Room database so the durable cancellation receipt is checked beside
 * the in-memory card that a stale UI callback would otherwise be able to approve.
 */
@RunWith(RobolectricTestRunner::class)
class AgentToolApprovalTest {
    private lateinit var context: Context
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var ollamaService: OllamaService
    private lateinit var agentService: AgentService
    private lateinit var database: AppDatabase
    private var snapshot: StaticState? = null
    private var applicationInstanceField: Field? = null
    private var previousLlamaApplication: LlamaApplication? = null
    private var databaseInstanceField: Field? = null
    private var databaseInstanceReceiver: Any? = null
    private var previousDatabase: AppDatabase? = null

    @Before
    fun setUp() {
        val runtimeApplication = RuntimeEnvironment.getApplication()
        context = runtimeApplication
        applicationInstanceField = LlamaApplication::class.java
            .getDeclaredField("instance")
            .apply { isAccessible = true }
        previousLlamaApplication = applicationInstanceField?.get(null) as? LlamaApplication
        // The default unit-test application is a plain Application. Attach a test-only production
        // application wrapper to its context without invoking onCreate or starting application
        // workers, then seed the singleton used by the Stop path.
        val llamaApplication = LlamaApplication()
        ContextWrapper::class.java.getDeclaredMethod(
            "attachBaseContext",
            Context::class.java
        ).apply { isAccessible = true }.invoke(llamaApplication, runtimeApplication)
        applicationInstanceField?.set(null, llamaApplication)
        val databaseField = runCatching {
            AppDatabase::class.java.getDeclaredField("INSTANCE")
        }.getOrElse {
            AppDatabase.Companion::class.java.getDeclaredField("INSTANCE")
        }.apply { isAccessible = true }
        databaseInstanceField = databaseField
        databaseInstanceReceiver = if (Modifier.isStatic(databaseField.modifiers)) {
            null
        } else {
            AppDatabase.Companion
        }
        previousDatabase = databaseField.get(databaseInstanceReceiver) as? AppDatabase
        database = Room.inMemoryDatabaseBuilder(
            runtimeApplication,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        databaseField.set(databaseInstanceReceiver, database)
        snapshot = captureState()

        // Keep this test independent of a service left by another static-state test. The
        // approval calls below only need a service argument; the Stop case uses the isolated
        // in-memory database above and never starts model execution.
        AgentService.activeInstance = null
        agentService = AgentService(context, isRuntimeOwner = false)
        AgentService.activeInstance = null
        companionFlow<List<AgentService.Companion.ChatMessage>>("_messages").value = emptyList()
        companionFlow<Long?>("_activeConversationId").value = null
        companionFlow<Long?>("_preferredConversationId").value = null
        companionFlow<Boolean>("_isLoading").value = false
        companionFlow<String>("_statusText").value = ""
        companionFlow<AgentService.Companion.AgentRole>("_currentAgent").value =
            AgentService.Companion.AgentRole.ORCHESTRATOR
        companionFlow<String?>("_currentTask").value = null
        companionFlow<Int>("_pendingQuestionCount").value = 0
        companionFlow<String?>("_pendingPlanApprovalId").value = null
        companionFlow<String>("_streamingContent").value = ""
        companionFlow<String>("_streamingThinking").value = ""
        companionFlow<String?>("_streamingMessageId").value = null
        companionFieldValue("automaticContinuationBlocked")
            .let { (it as AtomicBoolean).set(false) }
        setCompanionFieldValue("ownedConversationId", null)
        setCompanionFieldValue("activeInvocationId", null)
        (companionFieldValue("loadingRefCount") as AtomicInteger).set(0)
        settingsRepository = SettingsRepository(context)
        ollamaService = OllamaService(context)
    }

    @After
    fun tearDown() {
        runBlocking {
            (companionFieldValue("pendingStopCommit") as? Job)?.join()
        }
        databaseInstanceField?.set(databaseInstanceReceiver, previousDatabase)
        database.close()
        snapshot?.let(::restoreState)
        applicationInstanceField?.set(null, previousLlamaApplication)
    }

    @Test
    fun `unknown and already decided cards never start tool execution`() {
        val approved = approvalMessage("already-approved", isApproved = true)
        val denied = approvalMessage("already-denied", isApproved = false)
        AgentService.setMessages(listOf(approved, denied))

        assertNull(
            AgentService.approvePendingTool(
                context,
                ollamaService,
                settingsRepository,
                agentService,
                "missing-card"
            )
        )
        assertNull(
            AgentService.approvePendingTool(
                context,
                ollamaService,
                settingsRepository,
                agentService,
                approved.id
            )
        )
        assertNull(
            AgentService.approvePendingTool(
                context,
                ollamaService,
                settingsRepository,
                agentService,
                denied.id
            )
        )

        assertEquals(listOf(approved, denied), AgentService.messages.value)
        assertFalse(AgentService.isLoading.value)
    }

    @Test
    fun `Stop writes one cancellation receipt per live card and stale approval cannot start generation`() = runBlocking {
        val pending = listOf(
            approvalMessage("pending-card-one"),
            approvalMessage("pending-card-two")
        )
        val conversationId = database.agentChatDao().insertConversation(
            AgentConversationEntity(title = "Approval stop test")
        )
        database.agentChatDao().insertMessages(
            pending.map { AgentService.chatMessageToEntity(it, conversationId) }
        )
        companionFlow<Long?>("_activeConversationId").value = conversationId
        companionFlow<Long?>("_preferredConversationId").value = conversationId
        AgentService.setMessages(pending)

        AgentService.activeInstance = null
        AgentService.stopAllJobs()

        val firstReceipts = awaitCancellationReceipts(conversationId, pending)
        assertEquals(2, firstReceipts.size)
        assertTrue(firstReceipts.all { it.content.contains("ACTION_CANCELLED") })
        pending.forEach { card ->
            val stopped = AgentService.messages.value.first { it.id == card.id }
            assertFalse(stopped.needsApproval)
            assertEquals(false, stopped.isApproved)
            assertNull(
                AgentService.approvePendingTool(
                    context,
                    ollamaService,
                    settingsRepository,
                    agentService,
                    card.id
                )
            )
        }
        assertFalse(AgentService.isLoading.value)

        // Repeating Stop must reuse the already-cancelled cards and never append another
        // durable result for either tool call.
        AgentService.stopAllJobs()
        val secondReceipts = awaitCancellationReceipts(conversationId, pending)
        assertEquals(firstReceipts.map { it.originalId }.toSet(), secondReceipts.map { it.originalId }.toSet())
        assertEquals(2, secondReceipts.size)
    }

    private suspend fun awaitCancellationReceipts(
        conversationId: Long,
        pending: List<AgentService.Companion.ChatMessage>
    ): List<AgentMessageEntity> = withTimeout(5_000L) {
        var completedReceipts: List<AgentMessageEntity>? = null
        while (completedReceipts == null) {
            val receipts = database.agentChatDao().getMessagesForConversationSync(conversationId)
                .filter { message ->
                    message.role == "tool" &&
                        message.toolCallId in pending.mapNotNull { it.toolCallId }
                }
            if (receipts.size == pending.size) {
                (companionFieldValue("pendingStopCommit") as? Job)?.join()
                completedReceipts = receipts
            } else {
                delay(20L)
            }
        }
        completedReceipts
    }

    private fun approvalMessage(id: String, isApproved: Boolean? = null) =
        AgentService.Companion.ChatMessage(
            id = id,
            role = "assistant",
            content = "Approve $id",
            toolName = "read_file",
            toolCallId = "call-$id",
            toolArgs = mapOf("path" to "index.html"),
            needsApproval = true,
            isApproved = isApproved,
            pendingToolCall = OllamaService.ToolCall(
                name = "read_file",
                arguments = mapOf("path" to "index.html"),
                id = "call-$id"
            )
        )

    private data class StaticState(
        val activeInstance: AgentService?,
        val messages: List<AgentService.Companion.ChatMessage>,
        val activeConversationId: Long?,
        val preferredConversationId: Long?,
        val automaticContinuationBlocked: Boolean,
        val ownedConversationId: Long?,
        val activeRunEpoch: Long,
        val loadingRefCount: Int,
        val isLoading: Boolean,
        val statusText: String,
        val currentAgent: AgentService.Companion.AgentRole,
        val currentTask: String?,
        val pendingQuestionCount: Int,
        val pendingPlanApprovalId: String?,
        val streamingContent: String,
        val streamingThinking: String,
        val streamingMessageId: String?,
        val activeInvocationId: String?,
        val pendingStopCommit: Job?
    )

    private fun captureState(): StaticState {
        return StaticState(
            activeInstance = AgentService.activeInstance,
            messages = AgentService.messages.value,
            activeConversationId = AgentService.activeConversationId.value,
            preferredConversationId = AgentService.preferredConversationId.value,
            automaticContinuationBlocked =
                (companionFieldValue("automaticContinuationBlocked") as AtomicBoolean).get(),
            ownedConversationId = companionFieldValue("ownedConversationId") as Long?,
            activeRunEpoch =
                (companionFieldValue("activeRunEpoch") as AtomicLong).get(),
            loadingRefCount =
                (companionFieldValue("loadingRefCount") as AtomicInteger).get(),
            isLoading = AgentService.isLoading.value,
            statusText = AgentService.statusText.value,
            currentAgent = AgentService.currentAgent.value,
            currentTask = AgentService.currentTask.value,
            pendingQuestionCount = AgentService.pendingQuestionCount.value,
            pendingPlanApprovalId = AgentService.pendingPlanApprovalId.value,
            streamingContent = AgentService.streamingContent.value,
            streamingThinking = AgentService.streamingThinking.value,
            streamingMessageId = AgentService.streamingMessageId.value,
            activeInvocationId = companionFieldValue("activeInvocationId") as String?,
            pendingStopCommit = companionFieldValue("pendingStopCommit") as Job?
        )
    }

    private fun restoreState(state: StaticState) {
        AgentService.activeInstance = null
        companionFlow<List<AgentService.Companion.ChatMessage>>("_messages").value = state.messages
        companionFlow<Long?>("_activeConversationId").value = state.activeConversationId
        companionFlow<Long?>("_preferredConversationId").value = state.preferredConversationId
        companionFlow<Boolean>("_isLoading").value = state.isLoading
        companionFlow<String>("_statusText").value = state.statusText
        companionFlow<AgentService.Companion.AgentRole>("_currentAgent").value = state.currentAgent
        companionFlow<String?>("_currentTask").value = state.currentTask
        companionFlow<Int>("_pendingQuestionCount").value = state.pendingQuestionCount
        companionFlow<String?>("_pendingPlanApprovalId").value = state.pendingPlanApprovalId
        companionFlow<String>("_streamingContent").value = state.streamingContent
        companionFlow<String>("_streamingThinking").value = state.streamingThinking
        companionFlow<String?>("_streamingMessageId").value = state.streamingMessageId
        (companionFieldValue("automaticContinuationBlocked") as AtomicBoolean)
            .set(state.automaticContinuationBlocked)
        setCompanionFieldValue("ownedConversationId", state.ownedConversationId)
        (companionFieldValue("activeRunEpoch") as AtomicLong).set(state.activeRunEpoch)
        (companionFieldValue("loadingRefCount") as AtomicInteger).set(state.loadingRefCount)
        setCompanionFieldValue("activeInvocationId", state.activeInvocationId)
        setCompanionFieldValue("pendingStopCommit", state.pendingStopCommit)
        AgentService.activeInstance = state.activeInstance
    }

    private fun companionField(name: String): Field =
        runCatching { AgentService::class.java.getDeclaredField(name) }
            .getOrElse { AgentService.Companion::class.java.getDeclaredField(name) }
            .apply { isAccessible = true }

    private fun companionFieldValue(name: String): Any? {
        val field = companionField(name)
        val receiver = if (Modifier.isStatic(field.modifiers)) null else AgentService.Companion
        return field.get(receiver)
    }

    private fun setCompanionFieldValue(name: String, value: Any?) {
        val field = companionField(name)
        val receiver = if (Modifier.isStatic(field.modifiers)) null else AgentService.Companion
        field.set(receiver, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> companionFlow(name: String): MutableStateFlow<T> =
        companionFieldValue(name) as MutableStateFlow<T>
}
