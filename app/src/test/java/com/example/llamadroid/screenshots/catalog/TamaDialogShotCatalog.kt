package com.example.llamadroid.screenshots.catalog

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.rememberNavController
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.screenshots.fixtures.TamaFixtures
import com.example.llamadroid.screenshots.harness.ShotLocale
import com.example.llamadroid.screenshots.harness.ShotProfile
import com.example.llamadroid.screenshots.harness.ShotSpec
import com.example.llamadroid.tama.data.WellUpgradeState
import com.example.llamadroid.tama.db.FarmUpgradeEntity
import com.example.llamadroid.tama.game.TamaAgentService
import com.example.llamadroid.tama.rpg.AdventureGateProfile
import com.example.llamadroid.tama.shimeji.ShimejiSettingsCard
import com.example.llamadroid.tama.transfer.PetTransferUiState
import com.example.llamadroid.tama.ui.AdventureGateLoadoutDialog
import com.example.llamadroid.tama.ui.FeedingDialog
import com.example.llamadroid.tama.ui.StudySetupDialog
import com.example.llamadroid.tama.ui.TamaChatSettingsDialog
import com.example.llamadroid.tama.ui.TamaMenuDialog
import com.example.llamadroid.tama.ui.TamaPopupDialog
import com.example.llamadroid.tama.ui.TamaSettingsDialog
import com.example.llamadroid.tama.ui.WellDialog
import com.example.llamadroid.tama.ui.transfer.TamaAwayScreen
import com.example.llamadroid.tama.ui.transfer.TamaTransferHolder
import com.example.llamadroid.tama.ui.transfer.TamaTransferScreen
import com.example.llamadroid.ui.walkthrough.LocalInlineModalHost
import io.mockk.mockk

/**
 * Dialog and popup bodies rendered inline in the activity window (PixelCopy cannot read dialog windows).
 * `LocalInlineModalHost` makes `WalkthroughDialog`/`WalkthroughAlertDialog` (used by every Tama popup)
 * compose their content in place; [DialogShotHost] adds a dimmed scrim like a real dialog. Entries that
 * are full screens (transfer hub/away) are shown without scrim.
 */
object TamaDialogShotCatalog {
    private class Entry(
        val seed: (Application) -> Unit = {},
        val scrim: Boolean = true,
        val content: @Composable () -> Unit
    )

    private val entries: Map<String, Entry> = linkedMapOf(
        "tama_settings" to Entry {
            val context = LocalContext.current.applicationContext
            TamaSettingsDialog(rememberNavController(), SettingsRepository(context), onDismiss = {})
        },
        "tama_feeding" to Entry {
            FeedingDialog(TamaFixtures.demoPet(), { _, _, _ -> }, {}, {}, {})
        },
        "tama_menu" to Entry {
            TamaMenuDialog({}, {}, {}, {}, {}, {}, {}, {}, onTransfer = {})
        },
        "tama_study_setup" to Entry {
            StudySetupDialog(emptyList(), emptyList(), { _, _ -> }, { _, _, _ -> }, {})
        },
        "shimeji_settings_card" to Entry {
            TamaPopupDialog(
                title = "Shimeji",
                backgroundAsset = "tama/backgrounds/popup_generic.png",
                onDismissRequest = {},
                bodyContent = { ShimejiSettingsCard() },
                footerContent = {}
            )
        },
        "tama_chat_settings" to Entry {
            val context = LocalContext.current.applicationContext
            TamaChatSettingsDialog(
                navController = rememberNavController(),
                settingsRepo = SettingsRepository(context),
                agentService = remember { mockk<TamaAgentService>(relaxed = true) },
                petId = "shot-pet",
                onDismiss = {}
            )
        },
        "transfer_hub" to Entry(scrim = false) {
            val handle = TamaTransferHolder.get(LocalContext.current)
            TamaTransferScreen(handle, PetTransferUiState.Idle, onClose = {})
        },
        "transfer_away" to Entry(scrim = false) {
            val handle = TamaTransferHolder.get(LocalContext.current)
            val away = PetTransferUiState.Away("Ember", "Pixel 8 (living room)", System.currentTimeMillis() - 3 * 3_600_000L)
            TamaAwayScreen(handle, away, away, TamaFixtures.demoPet())
        },
        "farm_upgrade" to Entry {
            WellDialog(
                upgrade = FarmUpgradeEntity(type = "well", petId = "demo-tama-pet", isPurchased = true, level = 2),
                state = WellUpgradeState(speedLevel = 1),
                now = System.currentTimeMillis(),
                money = 500L,
                onDismiss = {}, onCollectTile = {}, onUpgradeCapacity = {}, onUpgradeSpeed = {}
            )
        },
        "adventure_gate_dialog" to Entry {
            AdventureGateLoadoutDialog(AdventureGateProfile(petId = "demo-tama-pet", level = 5), {}, { _, _ -> })
        }
    )

    val keys: List<String> get() = entries.keys.toList()

    fun spec(key: String, profile: ShotProfile, locale: ShotLocale): ShotSpec {
        val entry = entries[key] ?: error("Unknown dialog shot '$key'; use $keys")
        return ShotSpec(
            key = key,
            route = "dialog:$key",
            profile = profile,
            locale = locale,
            seed = { app -> TamaFixtures.seedHealthyPet(app); entry.seed(app) },
            substitute = true,
            content = { DialogShotHost(entry.scrim, entry.content) }
        )
    }
}

/** Fills the shot box with the theme background, enables inline dialogs and dims it like a dialog scrim. */
@Composable
private fun DialogShotHost(scrim: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalInlineModalHost provides true) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (scrim) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
            content()
        }
    }
}
