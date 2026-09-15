package com.example.llamadroid.tama.world.presentation

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import com.example.llamadroid.R
import com.example.llamadroid.tama.world.training.BrainRuntimeCheckpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LocalizedJournalLabelsTest {
    @Test
    fun englishLabelsResolveCanonicalFactsAndKeepUsefulNumbers() {
        val context = localeContext("en")
        val labels = localizedJournalLabels(context, petId = "pet-1", petName = "Nova")

        assertEquals(
            context.getString(R.string.tama_world_event_item_bought),
            labels.eventTitle("ITEM_BOUGHT")
        )
        assertEquals("Wheat", labels.resultValue("cropId", "wheat"))
        assertEquals("Wheat", labels.resultValue("itemId", "crop_wheat"))
        assertEquals(
            context.getString(R.string.tama_world_journal_stage_baby),
            labels.resultValue("stage", "BABY")
        )
        assertEquals("42.0", labels.resultValue("hungerBefore", "42.0"))
        assertEquals("Nova", labels.actorName("pet"))
        assertEquals("Doctor Pip", labels.actorName("hospital_doctor"))
        assertNull(labels.resultValue("vendorId", "fixed_1_0"))
        assertNull(labels.resultValue("stage", "NOT_A_STAGE"))
    }

    @Test
    fun spanishLabelsLocalizeEventsCropsStagesStatesAndFactKeys() {
        val context = localeContext("es")
        val labels = localizedJournalLabels(context, petId = "pet-1", petName = "Nova")

        assertEquals(
            context.getString(R.string.tama_world_event_item_bought),
            labels.eventTitle("item_bought")
        )
        assertEquals("Trigo", labels.resultValue("cropId", "wheat"))
        assertEquals(
            context.getString(R.string.tama_world_journal_stage_baby),
            labels.resultValue("stage", "BABY")
        )
        assertEquals("Agotado", labels.resultValue("state", "DEPLETED"))
        assertEquals(
            context.getString(R.string.tama_world_journal_result_hunger_before),
            labels.resultLabel("hungerBefore")
        )
        assertEquals("Sí", labels.resultValue("ready", "true"))
        assertNotEquals(
            localizedJournalLabels(localeContext("en"), "pet-1", "Nova").eventTitle("item_bought"),
            labels.eventTitle("item_bought")
        )
        assertNull(labels.resultValue("status", "opaque_internal_state"))
    }

    @Test
    fun journalTimeAndBrainCheckpointLabelsHideRawEpochAndUuidInEnglish() {
        val context = localeContext("en")
        val labels = localizedJournalLabels(
            context,
            petId = "pet-1",
            petName = "Nova",
            worldTimezoneOffsetMinutes = 120
        )
        val timestamp = 1_789_434_251_089L

        assertEquals(
            context.getString(R.string.tama_world_journal_episode_summary_pending),
            labels.openEpisodeSummary
        )
        assertNotEquals(
            context.getString(R.string.tama_world_journal_episode_no_events),
            labels.openEpisodeSummary
        )
        assertEquals("03:04", labels.time(timestamp))
        assertEquals("03:04–03:05", labels.timeRange(timestamp, timestamp + 60_000L))
        assertFalse(labels.time(timestamp).contains(timestamp.toString()))

        val checkpoint = BrainRuntimeCheckpoint(
            id = "4f2e8a1c-raw-internal-id",
            createdAt = timestamp,
            episodes = 42,
            curriculumId = 0,
            modelHash = "hash",
            profile = "ECO",
            policyVersion = "candidate-17"
        )
        val checkpointLabel = localizedBrainUiLabels(context, "Nova").checkpointName(checkpoint)
        assertEquals("Checkpoint Version 17", checkpointLabel)
        assertFalse(checkpointLabel.contains(checkpoint.id))

        val rewardConfiguration = localizedBrainUiLabels(context, "Nova").checkpointRewardConfiguration(
            "decomposed-v1;catalog-v1;curriculum=2;guard.socialCooldownSteps=12;terms=internal"
        )
        assertEquals("Reward rules v1 · curriculum Level 2 · social cooldown 12 steps", rewardConfiguration)
        assertFalse(rewardConfiguration.contains("decomposed"))
        assertEquals(
            context.getString(R.string.tama_world_brain_reward_configuration_saved),
            localizedBrainUiLabels(context, "Nova").checkpointRewardConfiguration("legacy-internal-config")
        )
    }

    @Test
    fun spanishJournalTimeAndBrainCheckpointLabelsRemainLocalized() {
        val context = localeContext("es")
        val labels = localizedJournalLabels(
            context,
            petId = "pet-1",
            petName = "Nova",
            worldTimezoneOffsetMinutes = 120
        )
        val timestamp = 1_789_434_251_089L

        assertEquals(
            context.getString(R.string.tama_world_journal_episode_summary_pending),
            labels.openEpisodeSummary
        )
        assertNotEquals(
            context.getString(R.string.tama_world_journal_episode_no_events),
            labels.openEpisodeSummary
        )
        assertEquals("03:04", labels.time(timestamp))
        assertEquals("03:04–03:05", labels.timeRange(timestamp, timestamp + 60_000L))

        val checkpoint = BrainRuntimeCheckpoint(
            id = "4f2e8a1c-raw-internal-id",
            createdAt = timestamp,
            episodes = 42,
            curriculumId = 0,
            modelHash = "hash",
            profile = "ECO",
            policyVersion = "candidate-17"
        )
        val checkpointLabel = localizedBrainUiLabels(context, "Nova").checkpointName(checkpoint)
        assertEquals("Punto de control Versión 17", checkpointLabel)
        assertFalse(checkpointLabel.contains(checkpoint.id))

        val rewardConfiguration = localizedBrainUiLabels(context, "Nova").checkpointRewardConfiguration(
            "decomposed-v1;catalog-v1;curriculum=2;guard.socialCooldownSteps=12;terms=internal"
        )
        assertEquals("Reglas de recompensa v1 · currículo Nivel 2 · enfriamiento social 12 pasos", rewardConfiguration)
        assertFalse(rewardConfiguration.contains("decomposed"))
        assertEquals(
            context.getString(R.string.tama_world_brain_reward_configuration_saved),
            localizedBrainUiLabels(context, "Nova").checkpointRewardConfiguration("legacy-internal-config")
        )
    }

    private fun localeContext(language: String): Context {
        val base = RuntimeEnvironment.getApplication()
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return base.createConfigurationContext(configuration)
    }
}
