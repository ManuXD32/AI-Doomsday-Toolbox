package com.example.llamadroid.wear

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdtWearProtocolTest {
    @Test
    fun pathsUseVersionedAdtPrefix() {
        assertEquals(1, AdtWearProtocol.VERSION)
        assertEquals("/adt/v1", AdtWearProtocol.PREFIX)
        assertEquals("adt_phone_v1", AdtWearProtocol.PHONE_CAPABILITY)
        assertEquals("adt_wear_v1", AdtWearProtocol.WATCH_CAPABILITY)
        assertEquals("/adt/v1/ping", AdtWearProtocol.PING)
        assertEquals("/adt/v1/generation/final/gen-1", AdtWearProtocol.generationFinalPath("gen-1"))
        assertEquals("/adt/v1/voice/upload/voice-1", AdtWearProtocol.voiceUploadPath("voice-1"))
        assertEquals("/adt/v1/voice/ack/voice-1", AdtWearProtocol.voiceAckPath("voice-1"))
        assertEquals("/adt/v1/tts/audio/tts-1", AdtWearProtocol.ttsAudioPath("tts-1"))
        assertEquals("/adt/v1/translator/turns/9", AdtWearProtocol.translatorTurnsPath(9L))
        assertEquals("/adt/v1/organizer/events/month", AdtWearProtocol.ORGANIZER_EVENTS_MONTH)
        assertEquals("/adt/v1/tama/hub", AdtWearProtocol.TAMA_HUB)
        assertEquals("/adt/v1/chats/quick/config", AdtWearProtocol.QUICK_CHAT_CONFIG)
        assertEquals("/adt/v1/chats/quick/create", AdtWearProtocol.QUICK_CHAT_CREATE)
        assertEquals("/adt/v1/tasks/active", AdtWearProtocol.ACTIVE_TASKS)
        assertEquals("/adt/v1/tasks/cancel", AdtWearProtocol.TASK_CANCEL)
        assertEquals("/adt/v1/capabilities", AdtWearProtocol.CAPABILITIES)
    }

    @Test
    fun rpcResponseRoundTrips() {
        val meta = RpcMeta(requestId = "req-1", watchVersionCode = 94901, createdAtEpochMs = 10L)
        val response = RpcResponse(
            meta = meta,
            status = "success",
            result = PingResult(
                applicationId = "com.manuxd32.aidoomsdaytoolbox",
                versionName = "0.9490",
                versionCode = 94900,
                certificateSha256Short = "abcdef1234567890",
                localNodeId = "node-phone",
                wearableApiAvailable = true
            ),
            phoneVersionCode = 94900,
            phoneVersionName = "0.9490",
            snapshotRevision = 4L,
            updatedAtEpochMs = 20L
        )

        val encoded = AdtWearProtocol.json.encodeToString(RpcResponse.serializer(PingResult.serializer()), response)
        val decoded = AdtWearProtocol.json.decodeFromString(RpcResponse.serializer(PingResult.serializer()), encoded)

        assertEquals(response, decoded)
    }

    @Test
    fun frozenPetSnapshotStillHasPet() {
        val snapshot = PetSnapshot(
            revisioned = Revisioned(revision = 8L, updatedAtEpochMs = 30L, sourceDeviceId = "phone"),
            hasPet = true,
            petId = "pet-1",
            name = "Tama",
            frozen = true,
            activity = "frozen",
            activityLabel = "Frozen"
        )

        val encoded = AdtWearProtocol.json.encodeToString(PetSnapshot.serializer(), snapshot)
        val decoded = AdtWearProtocol.json.decodeFromString<PetSnapshot>(encoded)

        assertTrue(decoded.hasPet)
        assertTrue(decoded.frozen)
        assertEquals("pet-1", decoded.petId)
    }

    @Test
    fun generationFinalAndVoiceMetadataRoundTrip() {
        val final = GenerationFinal(
            revisioned = Revisioned(revision = 12L, updatedAtEpochMs = 40L, sourceDeviceId = "phone"),
            generationId = "gen-1",
            targetType = "chat",
            targetId = "42",
            status = "complete",
            content = "hello"
        )
        val voice = VoiceAssetMetadata(
            meta = RpcMeta(requestId = "voice-1", watchVersionCode = 94901, createdAtEpochMs = 50L),
            targetType = "tama",
            targetId = "pet-1",
            mimeType = "audio/mp4",
            durationMs = 1500L,
            byteCount = 128L,
            sha256 = "0".repeat(64),
            languageHint = "en"
        )

        assertEquals(final, AdtWearProtocol.json.decodeFromString<GenerationFinal>(AdtWearProtocol.json.encodeToString(final)))
        assertEquals(voice, AdtWearProtocol.json.decodeFromString<VoiceAssetMetadata>(AdtWearProtocol.json.encodeToString(voice)))
    }

    @Test
    fun chatActionAndVoiceCommitRoundTrip() {
        val meta = RpcMeta(requestId = "retry-1", watchVersionCode = 94901, createdAtEpochMs = 60L)
        val retry = ChatMessageActionRequest(
            meta = meta,
            chatId = 42L,
            messageId = 100L,
            serverId = 7L,
            enableThinking = false
        )
        val commit = VoiceCommitRequest(
            meta = RpcMeta(requestId = "commit-1", watchVersionCode = 94901, createdAtEpochMs = 61L),
            uploadRequestId = "voice-1",
            enableThinking = true
        )
        val ack = VoiceUploadAck(
            requestId = "voice-1",
            targetType = "chat",
            targetId = "42",
            status = "accepted",
            localizedMessage = "Generation accepted by phone.",
            generation = GenerationAccepted("voice-1", "chat", "42", 62L),
            updatedAtEpochMs = 63L
        )

        assertEquals(retry, AdtWearProtocol.json.decodeFromString<ChatMessageActionRequest>(AdtWearProtocol.json.encodeToString(retry)))
        assertEquals(commit, AdtWearProtocol.json.decodeFromString<VoiceCommitRequest>(AdtWearProtocol.json.encodeToString(commit)))
        assertEquals(ack, AdtWearProtocol.json.decodeFromString<VoiceUploadAck>(AdtWearProtocol.json.encodeToString(ack)))
    }

    @Test
    fun organizerAndTranslatorDtosRoundTrip() {
        val revisioned = Revisioned(revision = 21L, updatedAtEpochMs = 70L, sourceDeviceId = "phone")
        val events = OrganizerEventPage(
            revisioned = revisioned,
            events = listOf(
                OrganizerEventSummary(
                    id = 1L,
                    title = "Review",
                    location = "Lab",
                    startAtEpochMs = 100L,
                    endAtEpochMs = 200L,
                    allDay = false,
                    alarmCount = 2
                )
            ),
            limit = 30
        )
        val notes = OrganizerNotePage(
            revisioned = revisioned,
            notes = listOf(OrganizerNoteSummary(id = 5, title = "Idea", preview = "Tiny preview", type = "note", updatedAtEpochMs = 150L)),
            totalCount = 1,
            limit = 20
        )
        val note = OrganizerNoteDetail(
            revisioned = revisioned,
            id = 5,
            title = "Idea",
            content = "Tiny preview with more detail",
            type = "note",
            updatedAtEpochMs = 150L
        )
        val templates = TranslatorTemplatePage(
            revisioned = revisioned,
            templates = listOf(TranslatorTemplateSummary(3L, "English / Spanish", "en", "es", "llama-server", "Llama server", "Qwen"))
        )
        val state = TranslatorStateSnapshot(
            revisioned = revisioned,
            isActive = true,
            sessionId = 9L,
            templateId = 3L,
            currentSpeaker = 2,
            phase = "TRANSLATING",
            status = "Translating",
            inputLevel = 0.5f,
            selectedTemplateId = 3L,
            selectedTemplateName = "English / Spanish",
            backendEngine = "llama-server",
            backendLabel = "Llama server",
            modelLabel = "Qwen",
            backendLoading = true,
            backendStatus = "Loading phone backend"
        )
        val turns = TranslatorTurnPage(
            revisioned = revisioned,
            sessionId = 9L,
            turns = listOf(TranslatorTurnSummary(1L, 2, "hola", "hello", "es", "en", 160L)),
            limit = 10
        )

        assertEquals(events, AdtWearProtocol.json.decodeFromString<OrganizerEventPage>(AdtWearProtocol.json.encodeToString(events)))
        assertEquals(notes, AdtWearProtocol.json.decodeFromString<OrganizerNotePage>(AdtWearProtocol.json.encodeToString(notes)))
        assertEquals(note, AdtWearProtocol.json.decodeFromString<OrganizerNoteDetail>(AdtWearProtocol.json.encodeToString(note)))
        assertEquals(templates, AdtWearProtocol.json.decodeFromString<TranslatorTemplatePage>(AdtWearProtocol.json.encodeToString(templates)))
        assertEquals(state, AdtWearProtocol.json.decodeFromString<TranslatorStateSnapshot>(AdtWearProtocol.json.encodeToString(state)))
        assertEquals(turns, AdtWearProtocol.json.decodeFromString<TranslatorTurnPage>(AdtWearProtocol.json.encodeToString(turns)))
    }

    @Test
    fun organizerMutationAndTamaDtosRoundTrip() {
        val meta = RpcMeta(requestId = "wear-new", watchVersionCode = 94911, createdAtEpochMs = 90L)
        val revisioned = Revisioned(revision = 40L, updatedAtEpochMs = 91L, sourceDeviceId = "phone")
        val month = OrganizerMonthPage(
            revisioned = revisioned,
            year = 2026,
            month = 7,
            zoneId = "Europe/Madrid",
            firstDayOfWeek = 3,
            daysInMonth = 31,
            selectedDayEpochMs = 100L,
            days = listOf(OrganizerMonthDay(23, 100L, eventCount = 2, hasAllDay = true)),
            selectedDayEvents = listOf(OrganizerEventSummary(1L, "Test", startAtEpochMs = 100L, timezoneId = "Europe/Madrid"))
        )
        val eventUpsert = OrganizerEventUpsertRequest(meta, title = "Test", startAtEpochMs = 100L, alarmAtEpochMs = 80L)
        val noteUpsert = OrganizerNoteUpsertRequest(meta, title = "Note", content = "Body")
        val pet = PetSnapshot(revisioned, hasPet = true, petId = "pet", name = "Tama")
        val hub = TamaHubSnapshot(
            revisioned = revisioned,
            pet = pet,
            coins = 12L,
            modules = listOf(TamaModuleSummary("farm", "Farm")),
            actions = listOf(TamaQuickAction("feed", "Feed", "care")),
            inventoryPreview = listOf(TamaInventoryItemSummary("seed_carrot", "Carrot seed", "SEED", 2))
        )
        val farm = TamaFarmSnapshot(revisioned, tiles = listOf(TamaFarmTileSummary("0", "Carrot", "Growing", 66, "Water")))
        val action = TamaActionRequest(meta, petId = "pet", action = "feed")

        assertEquals(month, AdtWearProtocol.json.decodeFromString<OrganizerMonthPage>(AdtWearProtocol.json.encodeToString(month)))
        assertEquals(eventUpsert, AdtWearProtocol.json.decodeFromString<OrganizerEventUpsertRequest>(AdtWearProtocol.json.encodeToString(eventUpsert)))
        assertEquals(noteUpsert, AdtWearProtocol.json.decodeFromString<OrganizerNoteUpsertRequest>(AdtWearProtocol.json.encodeToString(noteUpsert)))
        assertEquals(hub, AdtWearProtocol.json.decodeFromString<TamaHubSnapshot>(AdtWearProtocol.json.encodeToString(hub)))
        assertEquals(farm, AdtWearProtocol.json.decodeFromString<TamaFarmSnapshot>(AdtWearProtocol.json.encodeToString(farm)))
        assertEquals(action, AdtWearProtocol.json.decodeFromString<TamaActionRequest>(AdtWearProtocol.json.encodeToString(action)))
    }

    @Test
    fun serverSelectAndPhoneTtsRoundTrip() {
        val revisioned = Revisioned(revision = 30L, updatedAtEpochMs = 80L, sourceDeviceId = "phone")
        val serverPage = ServerListPage(
            revisioned = revisioned,
            servers = listOf(ServerSummary(id = 7L, name = "Local", engine = "llama.cpp", endpoint = "127.0.0.1", selected = true)),
            selectedServerId = 7L
        )
        val select = ServerSelectResult(selectedServerId = 7L, page = serverPage)
        val request = TtsGenerateRequest(
            meta = RpcMeta(requestId = "tts-1", watchVersionCode = 94901, createdAtEpochMs = 81L),
            text = "hello",
            languageHint = "en"
        )
        val result = TtsAudioResult(
            requestId = "tts-1",
            status = "ready",
            localizedMessage = "Phone TTS audio is ready.",
            mimeType = "audio/wav",
            byteCount = 128L,
            sha256 = "f".repeat(64),
            durationMs = 1000L
        )

        assertEquals(select, AdtWearProtocol.json.decodeFromString<ServerSelectResult>(AdtWearProtocol.json.encodeToString(select)))
        assertEquals(request, AdtWearProtocol.json.decodeFromString<TtsGenerateRequest>(AdtWearProtocol.json.encodeToString(request)))
        assertEquals(result, AdtWearProtocol.json.decodeFromString<TtsAudioResult>(AdtWearProtocol.json.encodeToString(result)))
    }

    @Test
    fun quickChatCapabilitiesAndTaskDtosRoundTrip() {
        val revisioned = Revisioned(revision = 50L, updatedAtEpochMs = 100L, sourceDeviceId = "phone")
        val config = QuickChatConfig(
            revisioned = revisioned,
            selectedServerId = 7L,
            selectedServerLabel = "Local Qwen",
            systemPrompt = "Be brief.",
            allowedTools = listOf("calendar", "notes"),
            confirmationRequiredTools = listOf("calendar"),
            autoStartServer = true,
            autoPlayTts = true
        )
        val capabilities = WearCapabilities(
            revisioned = revisioned,
            featureFlags = listOf("quick_chat", "active_tasks")
        )
        val tasks = ActiveTaskSnapshot(
            revisioned = revisioned,
            tasks = listOf(
                ActiveTaskSummary(
                    taskId = "101",
                    taskType = "IMAGE_GEN",
                    title = "Image generation",
                    subtitle = "Sampling",
                    state = "RUNNING",
                    progressPercent = 53,
                    updatedAtEpochMs = 101L,
                    canCancel = true
                )
            )
        )
        val command = TaskCommandRequest(
            meta = RpcMeta(requestId = "task-cancel", createdAtEpochMs = 102L),
            taskId = "101"
        )
        val ack = CommandAckDto(
            commandId = "task-cancel",
            accepted = true,
            status = "ACKNOWLEDGED",
            updatedAtEpochMs = 103L
        )

        assertEquals(config, AdtWearProtocol.json.decodeFromString<QuickChatConfig>(AdtWearProtocol.json.encodeToString(config)))
        assertEquals(capabilities, AdtWearProtocol.json.decodeFromString<WearCapabilities>(AdtWearProtocol.json.encodeToString(capabilities)))
        assertEquals(tasks, AdtWearProtocol.json.decodeFromString<ActiveTaskSnapshot>(AdtWearProtocol.json.encodeToString(tasks)))
        assertEquals(command, AdtWearProtocol.json.decodeFromString<TaskCommandRequest>(AdtWearProtocol.json.encodeToString(command)))
        assertEquals(ack, AdtWearProtocol.json.decodeFromString<CommandAckDto>(AdtWearProtocol.json.encodeToString(ack)))
    }
}
