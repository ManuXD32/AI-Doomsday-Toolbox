package com.example.llamadroid.service

import com.example.llamadroid.data.model.library.PendingArtifactStatus
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_ACTIVE
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_COMPLETED
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_FAILED
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_RESUMABLE
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the ordering contract used by DownloadService without starting a
 * service or mutating the app's database/files.
 */
class DownloadRetryGateTest {
    @Test
    fun newerCancelSuppressesRetryClaimedBeforeIt() {
        assertTrue(
            DownloadRetryGate.newerCleanupWins(
                cleanupGeneration = 12L,
                retryGeneration = 11L
            )
        )
        assertFalse(
            DownloadRetryGate.cleanupStartedBeforeRetry(
                cleanupGeneration = 12L,
                retryGeneration = 11L
            )
        )
    }

    @Test
    fun retryClaimedAfterCleanupMayUseTheCleanupGate() {
        assertTrue(
            DownloadRetryGate.cleanupStartedBeforeRetry(
                cleanupGeneration = 21L,
                retryGeneration = 22L
            )
        )
        assertFalse(
            DownloadRetryGate.newerCleanupWins(
                cleanupGeneration = 21L,
                retryGeneration = 22L
            )
        )
    }

    @Test
    fun cancelAllEpochInvalidatesQueuedRetry() {
        assertTrue(
            DownloadRetryGate.requestInvalidated(
                requestEpoch = 4L,
                currentEpoch = 5L,
                cancelAllActive = false
            )
        )
        assertTrue(
            DownloadRetryGate.requestInvalidated(
                requestEpoch = 5L,
                currentEpoch = 5L,
                cancelAllActive = true
            )
        )
        assertFalse(
            DownloadRetryGate.requestInvalidated(
                requestEpoch = 5L,
                currentEpoch = 5L,
                cancelAllActive = false
            )
        )
    }

    @Test
    fun cancelAllLeavesCompletedUnknownAndManualRowsUntouched() {
        // Rows already classified as Unknown/manual or validated are durable
        // inspection records, not queued transfers. The service's cancel-all
        // transaction must leave them available for the editor/retry flow.
        val terminalArtifactStatuses = setOf(
            PendingArtifactStatus.NEEDS_MANUAL_PROMOTION.storedValue,
            PendingArtifactStatus.VALIDATED.storedValue,
            PendingArtifactStatus.FAILED.storedValue
        )
        assertTrue(
            terminalArtifactStatuses.none(DownloadCancelAllPolicy::isQueuedArtifactStatus)
        )
        assertTrue(
            DownloadCancelAllPolicy.isQueuedArtifactStatus(
                PendingArtifactStatus.STAGED.storedValue
            )
        )
        assertTrue(
            DownloadCancelAllPolicy.isQueuedArtifactStatus(
                PendingArtifactStatus.INSPECTING.storedValue
            )
        )
        assertFalse(
            DownloadCancelAllPolicy.isQueuedArtifactStatus(
                PendingArtifactStatus.NEEDS_MANUAL_PROMOTION.storedValue
            )
        )
        assertFalse(
            DownloadCancelAllPolicy.isQueuedArtifactStatus(
                PendingArtifactStatus.VALIDATED.storedValue
            )
        )
        assertFalse(
            DownloadCancelAllPolicy.isQueuedArtifactStatus(
                PendingArtifactStatus.FAILED.storedValue
            )
        )
        assertTrue(DownloadCancelAllPolicy.isActiveTaskStatus(DOWNLOAD_TASK_STATUS_ACTIVE))
        assertTrue(DownloadCancelAllPolicy.isActiveTaskStatus(DOWNLOAD_TASK_STATUS_RESUMABLE))
        assertFalse(DownloadCancelAllPolicy.isActiveTaskStatus(DOWNLOAD_TASK_STATUS_COMPLETED))
        assertFalse(DownloadCancelAllPolicy.isActiveTaskStatus(DOWNLOAD_TASK_STATUS_FAILED))
    }
}
