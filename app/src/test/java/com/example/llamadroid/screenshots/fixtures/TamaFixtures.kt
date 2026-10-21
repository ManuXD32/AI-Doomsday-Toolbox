package com.example.llamadroid.screenshots.fixtures

import android.app.Application
import com.example.llamadroid.tama.data.GeneticTraits
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.Mood
import com.example.llamadroid.tama.data.PetSpeciesLine
import com.example.llamadroid.tama.data.Personality
import com.example.llamadroid.tama.data.PetStats
import com.example.llamadroid.tama.data.TamaDecorCatalog
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.data.TamaRoomCatalog
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.game.PetMapper
import kotlinx.coroutines.runBlocking

/** A healthy, happy adult pet in a decorated home room, written straight into the real Tama database. */
object TamaFixtures {
    fun seedHealthyPet(app: Application, name: String = "Ember", species: PetSpeciesLine = PetSpeciesLine.DRAGON) {
        val now = System.currentTimeMillis()
        val pet = TamaPet(
            id = "demo-tama-pet",
            name = name,
            species = species.id,
            birthTimestamp = now - 6L * 86_400_000L,
            stageProgressStartTime = now - 3_600_000L,
            lastDecayTime = now,
            stage = GrowthStage.ADULT,
            stats = PetStats(hunger = 92f, happiness = 96f, health = 100f, energy = 88f, hygiene = 94f, hydration = 90f, social = 85f, curiosity = 80f),
            mood = Mood.HAPPY,
            personality = Personality.CHEERFUL,
            genetics = GeneticTraits(bodyStyle = species.ordinal),
            ownerBondLevel = 82f,
            educationLevel = 35f,
            exerciseLevel = 40f,
            homeRoomId = TamaRoomCatalog.PRINCIPAL_ROOM_ID,
            leftDecorationId = TamaDecorCatalog.MUSIC_BOX_ID,
            rightDecorationId = TamaDecorCatalog.MOON_LANTERN_ID,
            money = 1_240
        )
        runBlocking { TamaDatabase.getInstance(app).tamaDao().savePet(PetMapper.toEntity(pet)) }
    }

    fun reset() {
        runCatching { TamaDatabase.closeInstance() }
    }
}
