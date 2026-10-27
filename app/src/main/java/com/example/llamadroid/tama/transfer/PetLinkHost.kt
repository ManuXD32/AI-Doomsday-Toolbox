package com.example.llamadroid.tama.transfer

/**
 * What the paired-device endpoints of [PetTransferServer] need from the app. All calls come from an
 * already authenticated paired device ([peerDeviceId]); implementations must still check that the
 * request matches the active visit.
 */
interface PetLinkHost {
    /** Pairing key of [deviceId], or null when it is not a paired device. */
    suspend fun keyFor(deviceId: String): ByteArray?

    /** Ping from a paired device; returns what to tell it about this device. */
    suspend fun onPing(peerDeviceId: String, remoteHost: String?, ping: LinkPing): LinkPong

    /** Remote reclaim: true when the guest will be sent home (the caller answers 202). */
    suspend fun onReclaim(peerDeviceId: String, remoteHost: String?, request: ReclaimRequest): ReclaimReply

    /** A paired hello arrived; true to accept it without asking the user. */
    suspend fun shouldAutoAccept(peerDeviceId: String, hello: Hello): Boolean

    /** A paired transfer committed on this device. */
    fun onCommitted(peerDeviceId: String, hello: Hello)
}
