package com.example.llamadroid.data.model

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Installed-state checks compare the on-disk length with [CuratedBundleFile.sizeBytes] exactly,
 * so a rounded catalog size (for example 3_110_000_000) makes a finished download look absent.
 * Verify real sizes with `tools/check_curated_sizes.py`.
 */
class CuratedExactSizeGateTest {
    /** File ids whose size is intentionally approximate. Keep empty unless justified. */
    private val approximate = emptySet<String>()

    @Test fun `llama and quick setup curated sizes are exact byte counts`() {
        val rounded = LlamaCuratedBundleCatalog.bundles
            .flatMap { it.files }
            .filter { it.sizeBytes % 1000L == 0L && it.id !in approximate }
            .map { "${it.id}=${it.sizeBytes}" }
            .distinct()
        assertTrue("Rounded curated sizes (run tools/check_curated_sizes.py): $rounded", rounded.isEmpty())
    }
}
