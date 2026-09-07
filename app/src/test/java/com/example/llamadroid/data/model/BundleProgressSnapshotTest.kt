package com.example.llamadroid.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BundleProgressSnapshotTest {

    @Test
    fun `unequal files use declared byte weighting and continuously update remaining`() {
        val first = calculateBundleProgressSnapshot(
            entries = listOf(
                BundleProgressEntry("large", declaredBytes = 1_000L, liveFraction = 0.50f),
                BundleProgressEntry("small", declaredBytes = 100L, liveFraction = 0f)
            )
        )
        val second = calculateBundleProgressSnapshot(
            entries = listOf(
                BundleProgressEntry("large", declaredBytes = 1_000L, liveFraction = 0.60f),
                BundleProgressEntry("small", declaredBytes = 100L, liveFraction = 0f)
            ),
            previousSnapshot = first
        )

        assertEquals(1_100L, first.totalBytes)
        assertEquals(500L, first.downloadedBytes)
        assertEquals(600L, first.remainingBytes)
        assertEquals(5f / 11f, first.progress, 0.0001f)
        assertEquals(600L, second.downloadedBytes)
        assertEquals(500L, second.remainingBytes)
        assertTrue(second.progress > first.progress)
    }

    @Test
    fun `resume uses greatest valid task and part observation`() {
        val snapshot = calculateBundleProgressSnapshot(
            listOf(
                BundleProgressEntry(
                    key = "model",
                    declaredBytes = 1_000L,
                    persistedTaskBytes = 420L,
                    partBytes = 460L,
                    liveFraction = 0.30f
                ),
                BundleProgressEntry(
                    key = "projector",
                    declaredBytes = 100L,
                    persistedTaskBytes = 900L,
                    partBytes = -1L,
                    liveFraction = 0.10f
                )
            )
        )

        assertEquals(470L, snapshot.downloadedBytes)
        assertEquals(630L, snapshot.remainingBytes)
        assertEquals(460L, snapshot.bytesByFile["model"])
        assertEquals(10L, snapshot.bytesByFile["projector"])
    }

    @Test
    fun `indeterminate live progress falls back to persisted bytes while live fractions are weighted`() {
        val snapshot = calculateBundleProgressSnapshot(
            listOf(
                BundleProgressEntry(
                    key = "resumed",
                    declaredBytes = 1_000L,
                    active = true,
                    persistedTaskBytes = 275L,
                    partBytes = 250L,
                    liveFraction = -2f
                ),
                BundleProgressEntry(
                    key = "live",
                    declaredBytes = 500L,
                    active = true,
                    liveFraction = 0.40f
                )
            )
        )

        assertEquals(475L, snapshot.downloadedBytes)
        assertEquals(1_025L, snapshot.remainingBytes)
        assertEquals(275L, snapshot.bytesByFile["resumed"])
        assertEquals(200L, snapshot.bytesByFile["live"])
        assertTrue(snapshot.hasActiveDownloads)
        assertTrue(snapshot.hasIndeterminateLiveTask)
    }

    @Test
    fun `installed and completed files are fully complete regardless of live value`() {
        val snapshot = calculateBundleProgressSnapshot(
            listOf(
                BundleProgressEntry(
                    key = "installed",
                    declaredBytes = 700L,
                    installed = true,
                    liveFraction = 0f
                ),
                BundleProgressEntry(
                    key = "completed",
                    declaredBytes = 300L,
                    completed = true,
                    liveFraction = 0f
                )
            )
        )

        assertEquals(1_000L, snapshot.downloadedBytes)
        assertEquals(0L, snapshot.remainingBytes)
        assertEquals(1f, snapshot.progress, 0f)
        assertEquals(2, snapshot.completedFileCount)
        assertTrue(snapshot.isComplete)
        assertFalse(snapshot.hasActiveDownloads)
    }

    @Test
    fun `cancellation resets that file to persisted partial bytes instead of live value`() {
        val beforeCancellation = calculateBundleProgressSnapshot(
            listOf(
                BundleProgressEntry(
                    key = "cancelled",
                    declaredBytes = 1_000L,
                    active = true,
                    persistedTaskBytes = 300L,
                    partBytes = 280L,
                    liveFraction = 0.80f
                ),
                BundleProgressEntry(
                    key = "other",
                    declaredBytes = 100L,
                    active = true,
                    liveFraction = 0.20f
                )
            )
        )
        val afterCancellation = calculateBundleProgressSnapshot(
            listOf(
                BundleProgressEntry(
                    key = "cancelled",
                    declaredBytes = 1_000L,
                    cancelled = true,
                    persistedTaskBytes = 300L,
                    partBytes = 280L,
                    liveFraction = 0.05f
                ),
                BundleProgressEntry(
                    key = "other",
                    declaredBytes = 100L,
                    active = true,
                    liveFraction = 0.30f
                )
            ),
            previousSnapshot = beforeCancellation
        )

        assertEquals(300L, afterCancellation.bytesByFile["cancelled"])
        assertEquals(330L, afterCancellation.downloadedBytes)
        assertEquals(770L, afterCancellation.remainingBytes)
        assertTrue(afterCancellation.hasActiveDownloads)
    }

    @Test
    fun `live regressions are clamped per file but a later increase still advances`() {
        val first = calculateBundleProgressSnapshot(
            listOf(BundleProgressEntry("model", 1_000L, liveFraction = 0.40f))
        )
        val regressed = calculateBundleProgressSnapshot(
            listOf(BundleProgressEntry("model", 1_000L, liveFraction = 0.20f)),
            previousSnapshot = first
        )
        val advanced = calculateBundleProgressSnapshot(
            listOf(BundleProgressEntry("model", 1_000L, liveFraction = 0.70f)),
            previousSnapshot = regressed
        )

        assertEquals(400L, first.downloadedBytes)
        assertEquals(400L, regressed.downloadedBytes)
        assertEquals(600L, regressed.remainingBytes)
        assertEquals(700L, advanced.downloadedBytes)
        assertEquals(300L, advanced.remainingBytes)
    }

    @Test
    fun `explicit reset ignores live values for a cancelled bundle`() {
        val previous = calculateBundleProgressSnapshot(
            listOf(BundleProgressEntry("model", 1_000L, liveFraction = 0.75f))
        )
        val reset = calculateBundleProgressSnapshot(
            listOf(
                BundleProgressEntry(
                    key = "model",
                    declaredBytes = 1_000L,
                    persistedTaskBytes = 225L,
                    partBytes = 200L,
                    liveFraction = 0.90f
                )
            ),
            previousSnapshot = previous,
            resetToPersisted = true
        )

        assertEquals(225L, reset.downloadedBytes)
        assertEquals(775L, reset.remainingBytes)
    }
}
