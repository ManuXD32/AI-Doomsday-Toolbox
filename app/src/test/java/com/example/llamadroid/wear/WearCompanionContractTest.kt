package com.example.llamadroid.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WearCompanionContractTest {
    @Test
    fun chatMessagesPathIncludesChatId() {
        assertEquals("/wear/chat/42/messages", WearCompanionContract.chatMessagesPath(42L))
        assertEquals("/wear/active_turn/request-1", WearCompanionContract.activeTurnPath("request-1"))
        assertEquals(
            "/wear/audio/chat/request-1/42/7/1234",
            WearCompanionContract.chatAudioChannelPath("request-1", chatId = 42L, serverId = 7L, durationMs = 1234L)
        )
        assertEquals(
            "/wear/audio/tama/request-2/pet-1/2500",
            WearCompanionContract.tamaAudioChannelPath("request-2", petId = "pet-1", durationMs = 2500L)
        )
        assertEquals("/wear/audio/reply/request-3", WearCompanionContract.replyAudioChannelPath("request-3"))
        assertEquals("/wear/command_ack/request-4", WearCompanionContract.commandAckPath("request-4"))
        assertEquals("/wear/command_outbox/request-5", WearCompanionContract.commandOutboxPath("request-5"))
    }

    @Test
    fun homeSnapshotRoundTripsThroughJson() {
        val snapshot = WearHomeSnapshot(
            llamaServerState = WearLlamaServerState(
                state = "running",
                label = "Running",
                port = 8080
            ),
            chatsAvailable = true,
            tamaAvailable = true,
            updatedAt = 123L
        )

        val encoded = WearCompanionContract.json.encodeToString(WearHomeSnapshot.serializer(), snapshot)
        val decoded = WearCompanionContract.json.decodeFromString(WearHomeSnapshot.serializer(), encoded)

        assertEquals(snapshot, decoded)
    }

    @Test
    fun tamaSnapshotCarriesStatsAndAssetPaths() {
        val snapshot = WearTamaPetSnapshot(
            hasPet = true,
            petId = "pet-1",
            name = "Tama",
            backgroundAssetPath = "tama/backgrounds/bedroom.png",
            spriteAssetPath = "tama/pets/dragon/baby/idle_0.png",
            hunger = 80,
            happiness = 70,
            health = 90,
            energy = 60,
            hygiene = 50
        )

        val encoded = WearCompanionContract.json.encodeToString(WearTamaPetSnapshot.serializer(), snapshot)

        assertTrue(encoded.contains("bedroom.png"))
        assertTrue(encoded.contains("idle_0.png"))
        assertEquals(snapshot, WearCompanionContract.json.decodeFromString(WearTamaPetSnapshot.serializer(), encoded))
    }

    @Test
    fun commandAckRoundTripsThroughJson() {
        val command = WearSimpleCommand(
            requestId = "request-1",
            command = WearCompanionContract.COMMAND_SYNC_PHONE,
            createdAt = 100L
        )
        val ack = WearCommandAck(
            requestId = command.requestId,
            command = command.command,
            status = "success",
            localizedMessage = "Phone data synced.",
            updatedAt = 200L
        )

        val encodedCommand = WearCompanionContract.json.encodeToString(WearSimpleCommand.serializer(), command)
        val encodedAck = WearCompanionContract.json.encodeToString(WearCommandAck.serializer(), ack)

        assertEquals(command, WearCompanionContract.json.decodeFromString(WearSimpleCommand.serializer(), encodedCommand))
        assertEquals(ack, WearCompanionContract.json.decodeFromString(WearCommandAck.serializer(), encodedAck))
    }

    @Test
    fun routedCommandRoundTripsThroughJson() {
        val command = WearRoutedCommand(
            requestId = "request-1",
            messagePath = WearCompanionContract.MESSAGE_REQUEST_REFRESH,
            payload = """{"requestId":"request-1","command":"sync_phone"}""",
            createdAt = 100L
        )

        val encoded = WearCompanionContract.json.encodeToString(WearRoutedCommand.serializer(), command)

        assertEquals(command, WearCompanionContract.json.decodeFromString(WearRoutedCommand.serializer(), encoded))
    }

    @Test
    fun legacyMessageCommandsCarryRequestIdsForAcks() {
        val openChat = WearOpenChatCommand(chatId = 42L, requestId = "open-1")
        val pinChat = WearChatPinCommand(chatId = 42L, serverId = 7L, requestId = "pin-1")
        val server = WearSelectServerCommand(serverId = 7L, requestId = "server-1")

        assertEquals(
            openChat,
            WearCompanionContract.json.decodeFromString(
                WearOpenChatCommand.serializer(),
                WearCompanionContract.json.encodeToString(WearOpenChatCommand.serializer(), openChat)
            )
        )
        assertEquals(
            pinChat,
            WearCompanionContract.json.decodeFromString(
                WearChatPinCommand.serializer(),
                WearCompanionContract.json.encodeToString(WearChatPinCommand.serializer(), pinChat)
            )
        )
        assertEquals(
            server,
            WearCompanionContract.json.decodeFromString(
                WearSelectServerCommand.serializer(),
                WearCompanionContract.json.encodeToString(WearSelectServerCommand.serializer(), server)
            )
        )
    }

    @Test
    fun bridgeRequestAndResponseRoundTripThroughJson() {
        val request = WearBridgeRequest(
            requestId = "request-rpc",
            command = WearCompanionContract.COMMAND_START_SERVER,
            payload = "{}",
            protocolVersion = WearCompanionContract.PROTOCOL_VERSION,
            watchVersionCode = 94851,
            createdAt = 300L
        )
        val response = WearBridgeResponse(
            requestId = request.requestId,
            command = request.command,
            status = "accepted",
            localizedMessage = "Command received by phone.",
            bridgeState = "starting",
            phoneVersionCode = 94850,
            snapshotRevision = 12L,
            updatedAt = 400L
        )

        val encodedRequest = WearCompanionContract.json.encodeToString(WearBridgeRequest.serializer(), request)
        val encodedResponse = WearCompanionContract.json.encodeToString(WearBridgeResponse.serializer(), response)

        assertEquals(request, WearCompanionContract.json.decodeFromString(WearBridgeRequest.serializer(), encodedRequest))
        assertEquals(response, WearCompanionContract.json.decodeFromString(WearBridgeResponse.serializer(), encodedResponse))
    }

    @Test
    fun v2BridgeContractUsesStrictRpcCapabilityAndPath() {
        assertEquals(2, WearCompanionContract.PROTOCOL_VERSION)
        assertEquals("adt_phone_bridge_v2", WearCompanionContract.CAPABILITY_PHONE_BRIDGE_V2)
        assertEquals("/wear/rpc/command", WearCompanionContract.PATH_RPC_COMMAND)
        assertEquals("ping_bridge", WearCompanionContract.COMMAND_PING_BRIDGE)
    }

    @Test
    fun statsSnapshotRoundTripsAndStaysWithinWearPayloadBudget() {
        val snapshot = WearStatsSnapshot(
            revisioned = Revisioned(4L, 2_000L, "phone"),
            enabled = true,
            sampledAtEpochMs = 2_000L,
            summary = mapOf("cpu" to "42.0%", "ram" to "58.0%"),
            series = listOf(
                WearStatsSeries(
                    id = "cpu",
                    unit = "%",
                    points = (0 until 15).map { WearStatsPoint(1_000L + it * 60_000L, it.toFloat()) }
                ),
                WearStatsSeries(
                    id = "temperature",
                    unit = "°C",
                    points = (0 until 15).map { WearStatsPoint(1_000L + it * 60_000L, 30f + it) }
                )
            ),
            availability = mapOf("gpu" to "unavailable")
        )
        val encoded = AdtWearProtocol.json.encodeToString(WearStatsSnapshot.serializer(), snapshot)
        assertTrue(encoded.toByteArray(Charsets.UTF_8).size < AdtWearProtocol.MAX_DATA_ITEM_BYTES)
        assertEquals(snapshot, AdtWearProtocol.json.decodeFromString(WearStatsSnapshot.serializer(), encoded))
    }
}
