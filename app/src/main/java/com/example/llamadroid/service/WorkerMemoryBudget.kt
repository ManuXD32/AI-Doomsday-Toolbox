package com.example.llamadroid.service

import kotlin.math.min

/**
 * Immutable memory snapshot used to size a local RPC worker.
 *
 * The worker contribution is always derived from the same snapshot as the UI labels and
 * launch request. Keeping this calculation free of Android APIs makes changes in available
 * memory and saved values deterministic to test.
 */
data class WorkerMemoryBudget(
    val totalMiB: Long,
    val availableMiB: Long,
    val reservedMiB: Long,
    val maximumMiB: Long,
    val requestedMiB: Long,
    val contributionMiB: Long,
    /** Hard cap for the manual override: total RAM minus [OVERRIDE_RESERVE_MIB] (never below [maximumMiB]). */
    val overrideCapMiB: Long = maximumMiB,
    /** The user explicitly accepted going above the safe maximum; only then is [overrideCapMiB] honoured. */
    val overrideAccepted: Boolean = false
) {
    /** Largest contribution currently allowed: the override cap only when explicitly accepted. */
    val effectiveCapMiB: Long
        get() = if (overrideAccepted) overrideCapMiB else maximumMiB

    /** The applied contribution is above the safe maximum (the user chose to risk more memory). */
    val exceedsSafeMaximum: Boolean
        get() = contributionMiB > maximumMiB

    /** A worker with less than this contribution is not viable to launch. */
    val canLaunch: Boolean
        get() = effectiveCapMiB >= MINIMUM_VIABLE_MIB && contributionMiB >= MINIMUM_VIABLE_MIB

    fun withRequested(requestedMiB: Long): WorkerMemoryBudget = calculate(
        totalMiB = totalMiB,
        availableMiB = availableMiB,
        requestedMiB = requestedMiB,
        overrideAccepted = overrideAccepted
    )

    fun sanitizedContribution(requestedMiB: Long = this.requestedMiB): Long =
        requestedMiB.coerceAtLeast(0L).coerceAtMost(effectiveCapMiB)

    companion object {
        const val MIB_BYTES: Long = 1024L * 1024L
        const val RESERVED_FLOOR_MIB: Long = 512L
        const val MINIMUM_VIABLE_MIB: Long = 256L

        /** RAM always kept for Android when the user overrides the safe maximum. */
        const val OVERRIDE_RESERVE_MIB: Long = 1024L

        /** Largest accepted contribution: total RAM minus 1024 MiB, never below the safe maximum. */
        fun overrideCap(totalMiB: Long, safeMaximumMiB: Long): Long =
            maxOf(safeMaximumMiB, totalMiB - OVERRIDE_RESERVE_MIB).coerceAtLeast(0L)

        /**
         * Calculates the safe contribution in MiB.
         *
         * The OS reservation is the greater of 512 MiB and the ceiling of 10% of total RAM.
         * The safe [WorkerMemoryBudget.maximumMiB] is the lesser of total-minus-reserved and
         * available-minus-reserved (the slider range). A manual override may go up to
         * total minus 1024 MiB ([WorkerMemoryBudget.overrideCapMiB]).
         */
        fun calculate(
            totalMiB: Long,
            availableMiB: Long,
            requestedMiB: Long,
            overrideAccepted: Boolean = false
        ): WorkerMemoryBudget {
            val safeTotalMiB = totalMiB.coerceAtLeast(0L)
            val safeAvailableMiB = availableMiB.coerceAtLeast(0L)
            val reservedMiB = maxOf(RESERVED_FLOOR_MIB, ceilTenPercent(safeTotalMiB))
            return calculateWithReservation(
                totalMiB = safeTotalMiB,
                availableMiB = safeAvailableMiB,
                requestedMiB = requestedMiB,
                reservedMiB = reservedMiB,
                overrideAccepted = overrideAccepted
            )
        }

        fun fromBytes(
            totalBytes: Long,
            availableBytes: Long,
            requestedMiB: Long,
            overrideAccepted: Boolean = false
        ): WorkerMemoryBudget {
            val safeTotalBytes = totalBytes.coerceAtLeast(0L)
            val safeAvailableBytes = availableBytes.coerceAtLeast(0L)
            val totalMiB = safeTotalBytes / MIB_BYTES
            val availableMiB = safeAvailableBytes / MIB_BYTES
            // Calculate the percentage from the original byte count before converting to the
            // integral MiB budget. This preserves the required ceiling at MiB boundaries.
            val reservedMiB = maxOf(
                RESERVED_FLOOR_MIB,
                ceilDivide(safeTotalBytes, 10L * MIB_BYTES)
            )
            return calculateWithReservation(
                totalMiB = totalMiB,
                availableMiB = availableMiB,
                requestedMiB = requestedMiB,
                reservedMiB = reservedMiB,
                overrideAccepted = overrideAccepted
            )
        }

        private fun calculateWithReservation(
            totalMiB: Long,
            availableMiB: Long,
            requestedMiB: Long,
            reservedMiB: Long,
            overrideAccepted: Boolean
        ): WorkerMemoryBudget {
            val maximumMiB = min(
                totalMiB - reservedMiB,
                availableMiB - reservedMiB
            ).coerceAtLeast(0L)
            val overrideCapMiB = overrideCap(totalMiB, maximumMiB)
            val contributionMiB = requestedMiB
                .coerceAtLeast(0L)
                .coerceAtMost(if (overrideAccepted) overrideCapMiB else maximumMiB)

            return WorkerMemoryBudget(
                totalMiB = totalMiB,
                availableMiB = availableMiB,
                reservedMiB = reservedMiB,
                maximumMiB = maximumMiB,
                requestedMiB = requestedMiB,
                contributionMiB = contributionMiB,
                overrideCapMiB = overrideCapMiB,
                overrideAccepted = overrideAccepted
            )
        }

        private fun ceilTenPercent(value: Long): Long =
            value / 10L + if (value % 10L == 0L) 0L else 1L

        private fun ceilDivide(value: Long, divisor: Long): Long =
            value / divisor + if (value % divisor == 0L) 0L else 1L
    }
}

/**
 * Keeps SharedPreferences writes proportional to effective user-visible changes. A changing
 * available-memory sample may update the in-memory budget without rewriting the same contribution.
 */
object WorkerRamPersistencePolicy {
    fun shouldPersist(
        previousContributionMiB: Long?,
        effectiveContributionMiB: Long,
        hasUsableSnapshot: Boolean
    ): Boolean = hasUsableSnapshot &&
        previousContributionMiB != effectiveContributionMiB.coerceAtLeast(0L)
}
