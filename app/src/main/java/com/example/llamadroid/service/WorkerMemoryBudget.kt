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
    val contributionMiB: Long
) {
    /** A worker with less than this contribution is not viable to launch. */
    val canLaunch: Boolean
        get() = maximumMiB >= MINIMUM_VIABLE_MIB && contributionMiB >= MINIMUM_VIABLE_MIB

    fun withRequested(requestedMiB: Long): WorkerMemoryBudget = calculate(
        totalMiB = totalMiB,
        availableMiB = availableMiB,
        requestedMiB = requestedMiB
    )

    fun sanitizedContribution(requestedMiB: Long = this.requestedMiB): Long =
        requestedMiB.coerceAtLeast(0L).coerceAtMost(maximumMiB)

    companion object {
        const val MIB_BYTES: Long = 1024L * 1024L
        const val RESERVED_FLOOR_MIB: Long = 512L
        const val MINIMUM_VIABLE_MIB: Long = 256L

        /**
         * Calculates the safe contribution in MiB.
         *
         * The OS reservation is the greater of 512 MiB and the ceiling of 10% of total RAM.
         * The contribution cannot exceed either total-minus-reserved or available-minus-reserved.
         */
        fun calculate(
            totalMiB: Long,
            availableMiB: Long,
            requestedMiB: Long
        ): WorkerMemoryBudget {
            val safeTotalMiB = totalMiB.coerceAtLeast(0L)
            val safeAvailableMiB = availableMiB.coerceAtLeast(0L)
            val reservedMiB = maxOf(RESERVED_FLOOR_MIB, ceilTenPercent(safeTotalMiB))
            return calculateWithReservation(
                totalMiB = safeTotalMiB,
                availableMiB = safeAvailableMiB,
                requestedMiB = requestedMiB,
                reservedMiB = reservedMiB
            )
        }

        fun fromBytes(
            totalBytes: Long,
            availableBytes: Long,
            requestedMiB: Long
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
                reservedMiB = reservedMiB
            )
        }

        private fun calculateWithReservation(
            totalMiB: Long,
            availableMiB: Long,
            requestedMiB: Long,
            reservedMiB: Long
        ): WorkerMemoryBudget {
            val maximumMiB = min(
                totalMiB - reservedMiB,
                availableMiB - reservedMiB
            ).coerceAtLeast(0L)
            val contributionMiB = requestedMiB
                .coerceAtLeast(0L)
                .coerceAtMost(maximumMiB)

            return WorkerMemoryBudget(
                totalMiB = totalMiB,
                availableMiB = availableMiB,
                reservedMiB = reservedMiB,
                maximumMiB = maximumMiB,
                requestedMiB = requestedMiB,
                contributionMiB = contributionMiB
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
