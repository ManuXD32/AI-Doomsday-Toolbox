package com.example.llamadroid.tama.transfer

import kotlinx.serialization.Serializable

/** Which side of a transfer this device plays; persisted as the enum name. */
enum class TransferRole { SENDER, RECEIVER, ORIGIN, GUEST_HOST }

/** Persisted transfer progress; combined with [TransferRole] it fully describes the row. */
enum class TransferStatus { OUTBOUND_PENDING, AWAY, OUTCOME_UNKNOWN, HOSTING_GUEST, RETURN_PENDING }

/** Receiver-side answers for [StatusReply.state]. */
object TransferWireState {
    const val NONE = "NONE"
    const val STAGED = "STAGED"
    const val COMMITTED = "COMMITTED"
}

/** First request of a transfer. [hmac] is computed by [PetTransferAuth] over every other field. */
@Serializable
data class Hello(
    val transferId: String,
    val petId: String,
    val petName: String,
    val species: String,
    val stage: String,
    val zipBytes: Long,
    val zipSha256: String,
    val bundleVersion: Int,
    val originDeviceId: String,
    val senderDeviceName: String,
    val isReturn: Boolean = false,
    val hmac: String = "",
    /** Pairing offer (optional; old peers ignore it): sender install id, ECDH public key, code-derived tag. */
    val deviceId: String = "",
    val pairPub: String = "",
    val pairMac: String = ""
)

@Serializable
data class HelloReply(
    val accepted: Boolean,
    val reason: String? = null,
    /** Pairing answer (optional): receiver install id/name, ECDH public key, code-derived tag. */
    val deviceId: String = "",
    val deviceName: String = "",
    val pairPub: String = "",
    val pairMac: String = ""
)

@Serializable
data class StagedReply(val staged: Boolean, val reason: String? = null)

@Serializable
data class CommitReply(val committed: Boolean, val reason: String? = null)

@Serializable
data class StatusReply(val state: String)
