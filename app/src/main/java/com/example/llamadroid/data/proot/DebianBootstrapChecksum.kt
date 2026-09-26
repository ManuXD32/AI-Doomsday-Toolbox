package com.example.llamadroid.data.proot

/** Resolve the signed bootstrap for new environments and the known pre-privacy template. */
internal object DebianBootstrapChecksum {
    private const val LEGACY_BOOTSTRAP_SHA256 =
        "4fca9c419bf46e2a639a635edd5f6c16da619a49ebb3b0d152a7378ea7cffeea"
    private val sha256 = Regex("[a-f0-9]{64}")

    fun expected(requested: String?, packaged: String?): String {
        val pinned = requested?.lowercase()
        // Existing ready rootfs copies return before this bootstrap path. A saved row
        // pinning the old generic template must still be able to initialize or repair.
        // Unknown explicit pins retain their original checksum-verification behavior.
        if (pinned != null && pinned != LEGACY_BOOTSTRAP_SHA256) return pinned
        return requireNotNull(packaged?.lowercase()?.takeIf { sha256.matches(it) }) {
            "The signed Debian rootfs checksum declaration is missing"
        }
    }
}
