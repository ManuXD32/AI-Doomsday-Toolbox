package com.example.llamadroid.service.filestation

/** One shared folder. [id] matches `[a-z0-9_-]+`; exactly one volume has [isRoot] (the main shared folder). */
data class VolumeConfig(
    val id: String,
    val label: String,
    val treeUri: String,
    val readOnly: Boolean = false,
    val isRoot: Boolean = false,
)

/** Immutable snapshot of the persisted File Station settings (the password itself is never exposed). */
data class FileStationSettings(
    val volumes: List<VolumeConfig> = emptyList(),
    val port: Int,
    val lanVisible: Boolean = true,
    val hasPassword: Boolean = false,
    val trashRetentionDays: Int = 30,
    val showHidden: Boolean = false,
) {
    val rootVolume: VolumeConfig? get() = volumes.firstOrNull { it.isRoot }
    val extraVolumes: List<VolumeConfig> get() = volumes.filterNot { it.isRoot }
    /** True when the server would be reachable on the LAN without a password. */
    val unprotected: Boolean get() = lanVisible && !hasPassword
}
