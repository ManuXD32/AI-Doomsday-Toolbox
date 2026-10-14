package com.example.llamadroid.service.delegation

/** Decides whether a DeepSeek subagent and its main chat compete for the same llama-server. */
internal object DelegateEndpointSharing {
    /**
     * The same server row, or two rows backed by the same managed server card, means one physical
     * llama-server (and its slots). Unknown main server: assume they do not share.
     */
    fun sameEndpoint(mainServerId: Long?, mainCardId: Long?, subServerId: Long?, subCardId: Long?): Boolean {
        if (mainServerId != null && mainServerId == subServerId) return true
        return mainCardId != null && mainCardId == subCardId
    }
}
