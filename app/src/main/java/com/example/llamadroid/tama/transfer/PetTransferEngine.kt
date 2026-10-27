package com.example.llamadroid.tama.transfer

import com.example.llamadroid.tama.game.TamaGameEngine
import java.io.File

/** Identity of the pet currently held by the engine. */
data class PetSummary(val petId: String, val name: String, val species: String, val stage: String)

/** Everything the transfer coordinator needs from the game engine; faked in JVM tests. */
interface PetTransferEngine {
    suspend fun currentPetSummary(): PetSummary?

    /** Writes the current pet as a backup ZIP; false on failure (no partial file left). */
    suspend fun exportPetZip(file: File): Boolean

    /** Dry-run parse of [file]; nothing is committed. */
    suspend fun validateZip(file: File): Boolean

    /** Replaces the current pet with the one in [file] atomically. */
    suspend fun importPetZip(file: File, applySettings: Boolean = false): Boolean

    suspend fun deletePetData(petId: String)

    /** Stops stat decay and canonical actions while the pet is away or a hand-off is pending. */
    fun setFrozen(frozen: Boolean)
}

class TamaGameEnginePetTransferEngine(private val engine: TamaGameEngine) : PetTransferEngine {
    override suspend fun currentPetSummary(): PetSummary? {
        val pet = engine.pet.value ?: engine.loadPet() ?: return null
        return PetSummary(pet.id, pet.name, pet.species, pet.stage.name)
    }

    override suspend fun exportPetZip(file: File): Boolean {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        val ok = try {
            tmp.outputStream().use { engine.exportToBackupZip(it) }
        } catch (e: Exception) {
            false
        }
        if (!ok || !tmp.exists() || tmp.length() == 0L) {
            tmp.delete()
            return false
        }
        file.delete()
        if (!tmp.renameTo(file)) {
            tmp.delete()
            return false
        }
        return true
    }

    override suspend fun validateZip(file: File): Boolean = engine.validateBackupZip(file)

    override suspend fun importPetZip(file: File, applySettings: Boolean): Boolean =
        try {
            file.inputStream().buffered().use { engine.importFromBackup(it, applySettings) }
        } catch (e: Exception) {
            false
        }

    override suspend fun deletePetData(petId: String) = engine.deletePetForTransfer(petId)

    override fun setFrozen(frozen: Boolean) {
        engine.transferFrozen = frozen // delegates to the process-wide TamaTransferFreeze
    }
}
