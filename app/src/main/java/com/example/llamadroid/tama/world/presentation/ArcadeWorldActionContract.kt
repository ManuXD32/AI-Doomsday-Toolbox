package com.example.llamadroid.tama.world.presentation

/**
 * Metrics submitted when a local arcade session reaches a terminal state.
 * [sessionId] is generated once when play starts and is also the idempotency
 * key for the durable world receipt. The UI never calculates or commits the
 * reward; the engine owns that decision.
 */
data class ArcadeSessionRequest(
    val petId: String,
    val sessionId: String,
    val gameId: String,
    val score: Int,
    val catches: Int = 0,
    val misses: Int = 0,
    val totalObjects: Int = 0,
    val pairsMatched: Int = 0,
    val turnsUsed: Int = 0
)

/**
 * Durable lease state for a game that has started but has not reached a
 * terminal receipt yet. QUEUED/RUNNING must keep the canonical actor at the
 * arcade; RECONCILE_REQUIRED is used after process death until the host has
 * reconciled or cancelled the persisted receipt.
 */
enum class ArcadeSessionLeaseStatus {
    QUEUED,
    RUNNING,
    RECONCILE_REQUIRED,
    TERMINAL,
    UNAVAILABLE
}

data class ArcadeSessionLease(
    val petId: String,
    val sessionId: String,
    val gameId: String,
    val status: ArcadeSessionLeaseStatus,
    val receiptId: String = sessionId
) {
    val keepsPetAtArcade: Boolean
        get() = status == ArcadeSessionLeaseStatus.QUEUED ||
            status == ArcadeSessionLeaseStatus.RUNNING ||
            status == ArcadeSessionLeaseStatus.RECONCILE_REQUIRED
}

enum class ArcadeReceiptStatus {
    SUBMITTING,
    QUEUED,
    SUCCEEDED,
    REJECTED,
    FAILED,
    UNAVAILABLE
}

/** Terminal or durable outcome returned by the canonical world action. */
data class ArcadeSessionReceipt(
    val sessionId: String,
    val receiptId: String = sessionId,
    val status: ArcadeReceiptStatus,
    val coins: Int = 0,
    val happiness: Int = 0
)

enum class ArcadeSessionRecoveryStatus {
    NONE,
    ACTIVE,
    TERMINAL,
    UNAVAILABLE
}

/**
 * Durable state discovered when the Arcade host is recreated. The terminal
 * payload contains the canonical metrics from the receipt, so a result dialog
 * can be rebuilt without trusting transient Compose state.
 */
data class ArcadeSessionTerminal(
    val request: ArcadeSessionRequest,
    val receipt: ArcadeSessionReceipt
)

data class ArcadeSessionRecovery(
    val status: ArcadeSessionRecoveryStatus = ArcadeSessionRecoveryStatus.NONE,
    val activeLease: ArcadeSessionLease? = null,
    val terminal: ArcadeSessionTerminal? = null
)

/**
 * Android adapters implement this with the engine's idempotent receipt API.
 *
 * [begin] creates (or reuses) the durable RUNNING lease before local play
 * starts. [submit] completes that same receipt with terminal metrics and is
 * the only operation that may apply the canonical reward. [cancel] releases
 * an abandoned lease without a reward. [reconcile] returns a persisted lease
 * after process death so the host can keep the actor at the arcade until the
 * receipt is resolved. Implementations must key all four operations by
 * `(petId, sessionId)` and replay an existing terminal result. [recover]
 * returns either the active lease or one committed terminal result. [acknowledge]
 * marks that terminal result as consumed after its dialog has been dismissed.
 */
fun interface ArcadeWorldActionBridge {
    suspend fun submit(request: ArcadeSessionRequest): ArcadeSessionReceipt

    suspend fun begin(request: ArcadeSessionRequest): ArcadeSessionLease =
        ArcadeSessionLease(
            petId = request.petId,
            sessionId = request.sessionId,
            gameId = request.gameId,
            status = ArcadeSessionLeaseStatus.UNAVAILABLE
        )

    suspend fun cancel(request: ArcadeSessionRequest): ArcadeSessionReceipt =
        ArcadeSessionReceipt(request.sessionId, status = ArcadeReceiptStatus.REJECTED)

    suspend fun reconcile(petId: String): ArcadeSessionLease? = null

    suspend fun recover(petId: String): ArcadeSessionRecovery =
        reconcile(petId)?.let {
            ArcadeSessionRecovery(ArcadeSessionRecoveryStatus.ACTIVE, activeLease = it)
        } ?: ArcadeSessionRecovery()

    suspend fun acknowledge(request: ArcadeSessionRequest): Boolean = false

    companion object {
        /**
         * Builds the app-shell adapter from the canonical controller methods.
         * Keeping this wiring here prevents a screen from reaching into Room
         * or inventing a second reward path.
         */
        fun from(
            beginSession: suspend (ArcadeSessionRequest) -> ArcadeSessionLease,
            submitSession: suspend (ArcadeSessionRequest) -> ArcadeSessionReceipt,
            cancelSession: suspend (ArcadeSessionRequest) -> ArcadeSessionReceipt,
            reconcileSession: suspend (String) -> ArcadeSessionLease?,
            recoverSession: suspend (String) -> ArcadeSessionRecovery = { ArcadeSessionRecovery() },
            acknowledgeSession: suspend (ArcadeSessionRequest) -> Boolean = { false }
        ): ArcadeWorldActionBridge = object : ArcadeWorldActionBridge {
            override suspend fun begin(request: ArcadeSessionRequest): ArcadeSessionLease = beginSession(request)
            override suspend fun submit(request: ArcadeSessionRequest): ArcadeSessionReceipt = submitSession(request)
            override suspend fun cancel(request: ArcadeSessionRequest): ArcadeSessionReceipt = cancelSession(request)
            override suspend fun reconcile(petId: String): ArcadeSessionLease? = reconcileSession(petId)
            override suspend fun recover(petId: String): ArcadeSessionRecovery = recoverSession(petId)
            override suspend fun acknowledge(request: ArcadeSessionRequest): Boolean = acknowledgeSession(request)
        }

        /** Used only until the host supplies the canonical engine adapter. */
        val Unavailable: ArcadeWorldActionBridge = object : ArcadeWorldActionBridge {
            override suspend fun submit(request: ArcadeSessionRequest): ArcadeSessionReceipt =
                ArcadeSessionReceipt(request.sessionId, status = ArcadeReceiptStatus.UNAVAILABLE)

            override suspend fun begin(request: ArcadeSessionRequest): ArcadeSessionLease =
                ArcadeSessionLease(request.petId, request.sessionId, request.gameId,
                    ArcadeSessionLeaseStatus.UNAVAILABLE)

            override suspend fun recover(petId: String): ArcadeSessionRecovery =
                ArcadeSessionRecovery(ArcadeSessionRecoveryStatus.UNAVAILABLE)
        }
    }
}
