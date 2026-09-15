package com.example.llamadroid.tama.game

import java.io.File
import java.io.InputStream
import java.util.LinkedHashMap
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** ZipFile validates the directory, but does not itself verify entry CRC values. */
internal fun <T> ZipFile.readVerifiedTamaEntry(entry: ZipEntry, read: (InputStream) -> T): T =
    getInputStream(entry).use { raw ->
        val checked = CheckedInputStream(raw, CRC32())
        val result = read(checked)
        require(checked.read() == -1) { "Transfer entry was not fully read" }
        require(checked.checksum.value == entry.crc) { "Transfer entry checksum mismatch" }
        result
    }

/**
 * Keeps backup ZIP bytes outside the live files tree until the database
 * replacement has succeeded. Installed files can be rolled back while the
 * surrounding Room transaction is still open.
 */
internal class TamaTransferImportStaging(
    private val root: File
) : AutoCloseable {
    private data class Install(
        val target: File,
        val backup: File?
    )

    private val entries = LinkedHashMap<String, File>()
    private val claimedTargets = LinkedHashMap<String, File>()
    private val installed = mutableListOf<Install>()
    private var committed = false

    init {
        require(root.mkdirs() || root.isDirectory) { "Cannot create transfer staging directory" }
    }

    fun stageArchive(input: InputStream): File {
        val archive = File(root, "archive.zip")
        archive.outputStream().use { output -> input.copyTo(output) }
        return archive
    }

    fun stageZipEntry(name: String, input: InputStream) {
        require(isSafeEntryName(name)) { "Unsafe transfer ZIP entry" }
        require(entries[name] == null) { "Duplicate transfer ZIP entry" }
        val file = File(root, "entries/${entries.size}.bin")
        require(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true) {
            "Cannot create transfer staging entry directory"
        }
        file.outputStream().use { output -> input.copyTo(output) }
        entries[name] = file
    }

    fun claim(name: String?, target: File): Boolean {
        if (name.isNullOrBlank()) return false
        require(isSafeEntryName(name)) { "Unsafe transfer ZIP reference" }
        val staged = entries[name] ?: return false
        val key = target.absolutePath
        require(claimedTargets[key] == null) { "Multiple transfer entries target one file" }
        require(claimedTargets.values.none { it == staged }) { "Multiple transfer references target one entry" }
        claimedTargets[key] = staged
        return true
    }

    fun install() {
        check(!committed) { "Transfer staging has already been committed" }
        check(installed.isEmpty()) { "Transfer staging was installed twice" }
        try {
            claimedTargets.entries.forEachIndexed { index, (targetPath, staged) ->
                val target = File(targetPath)
                require(!target.isDirectory) { "Transfer target is a directory" }
                target.parentFile?.mkdirs()
                val backup = if (target.exists()) {
                    File(root, "backups/$index.bin").also { backupFile ->
                        backupFile.parentFile?.mkdirs()
                        target.copyTo(backupFile, overwrite = true)
                    }
                } else {
                    null
                }
                // Register before removing the old target. A failed rename/copy
                // must still restore the file on the caller's Room rollback path.
                installed += Install(target = target, backup = backup)
                val temporaryTarget = File(root, "installed/$index.bin")
                temporaryTarget.parentFile?.mkdirs()
                staged.copyTo(temporaryTarget, overwrite = true)
                if (target.exists() && !target.delete()) {
                    error("Cannot replace transfer target")
                }
                if (!temporaryTarget.renameTo(target)) {
                    temporaryTarget.copyTo(target, overwrite = false)
                    temporaryTarget.delete()
                }
            }
        } catch (failure: Throwable) {
            runCatching { rollback() }
                .onFailure { rollbackFailure -> failure.addSuppressed(rollbackFailure) }
            throw failure
        }
    }

    fun commit() {
        check(!committed) { "Transfer staging has already been committed" }
        installed.forEach { it.backup?.delete() }
        committed = true
        root.deleteRecursively()
    }

    fun rollback() {
        if (committed) return
        var rollbackFailure: Throwable? = null
        installed.asReversed().forEach { item ->
            try {
                if (item.backup != null) {
                    require(item.backup.exists()) { "Transfer rollback backup is missing" }
                    if (item.target.exists() && !item.target.delete()) {
                        error("Cannot remove failed transfer target")
                    }
                    item.backup.copyTo(item.target, overwrite = false)
                } else if (item.target.exists() && !item.target.delete()) {
                    error("Cannot remove failed transfer target")
                }
            } catch (failure: Throwable) {
                val previousFailure = rollbackFailure
                if (previousFailure == null) {
                    rollbackFailure = failure
                } else {
                    previousFailure.addSuppressed(failure)
                }
            }
        }
        rollbackFailure?.let { throw it }
        installed.clear()
    }

    override fun close() {
        if (!committed) rollback()
        root.deleteRecursively()
    }

    private fun isSafeEntryName(name: String): Boolean {
        return name.isNotBlank() &&
            !name.startsWith('/') &&
            !name.contains('\\') &&
            !name.contains('\u0000') &&
            name.split('/').none { it == ".." }
    }
}

internal fun validateTamaTransferOwnership(bundle: TamaTransferBundle, petId: String) {
    requireSafeTamaTransferPathSegment(petId, "pet")
    require(bundle.pet.id == petId) { "Transfer pet identity changed during import" }
    bundle.artworks.forEach { requireSafeTamaTransferPathSegment(it.id, "artwork") }
    bundle.chatMessages.forEach { requireSafeTamaTransferPathSegment(it.id, "chat message") }
    bundle.adventureSessions.forEach { requireSafeTamaTransferPathSegment(it.id, "adventure session") }
    bundle.adventureStages.forEach {
        requireSafeTamaTransferPathSegment(it.id, "adventure stage")
        requireSafeTamaTransferPathSegment(it.sessionId, "adventure session")
    }
    require(bundle.artworks.all { it.petId == petId }) { "Artwork belongs to a different pet" }
    require(bundle.events.all { it.petId == petId }) { "Event belongs to a different pet" }
    require(bundle.chatMessages.all { it.petId == petId }) { "Chat message belongs to a different pet" }
    require(bundle.summaries.all { it.petId == petId }) { "Summary belongs to a different pet" }
    require(bundle.deepDreamRuns.all { it.petId == petId }) { "Dream run belongs to a different pet" }
    require(bundle.farmTiles.all { it.petId == petId }) { "Farm tile belongs to a different pet" }
    require(bundle.farmUpgrades.all { it.petId == petId }) { "Farm upgrade belongs to a different pet" }
    require(bundle.farmLivestock.all { it.petId == petId }) { "Farm livestock belongs to a different pet" }
    require(bundle.quests.all { it.petId == petId }) { "Quest belongs to a different pet" }
    require(bundle.questChecklist.all { it.petId == petId }) { "Quest checklist belongs to a different pet" }
    require(bundle.studyLabels.all { it.petId == petId }) { "Study label belongs to a different pet" }
    require(bundle.studySessions.all { it.petId == petId }) { "Study session belongs to a different pet" }
    require(bundle.marketQuotes.all { it.petId == petId }) { "Market quote belongs to a different pet" }
    require(bundle.adventureSessions.all { it.petId == petId }) { "Adventure session belongs to a different pet" }
    require(bundle.dungeonProgress == null || bundle.dungeonProgress.petId == petId) {
        "Dungeon progress belongs to a different pet"
    }
    require(bundle.adventureGateProfile == null || bundle.adventureGateProfile.petId == petId) {
        "Adventure gate profile belongs to a different pet"
    }
    require(bundle.adventureGateWorldProgress.all { it.petId == petId }) {
        "Adventure gate world progress belongs to a different pet"
    }
    require(bundle.adventureGateBattleState == null || bundle.adventureGateBattleState.petId == petId) {
        "Adventure gate battle state belongs to a different pet"
    }
    require(bundle.adventureGateNightArenaRun == null || bundle.adventureGateNightArenaRun.petId == petId) {
        "Adventure gate run belongs to a different pet"
    }
    val sessionIds = bundle.adventureSessions.map { it.id }.toSet()
    require(bundle.adventureStages.all { it.sessionId in sessionIds }) {
        "Adventure stage belongs to a different session"
    }
}

private fun requireSafeTamaTransferPathSegment(value: String, label: String) {
    require(
        value.isNotBlank() && value != "." && value != ".." &&
            value.none { it == '/' || it == '\\' || it == '\u0000' }
    ) { "Invalid $label identity" }
}
