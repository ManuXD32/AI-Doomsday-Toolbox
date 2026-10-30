package com.example.llamadroid.tama.ui

import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import com.example.llamadroid.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.llamadroid.ui.walkthrough.WalkthroughDialog as Dialog
import coil.compose.AsyncImage
import com.example.llamadroid.tama.data.*
import com.example.llamadroid.tama.game.FarmRepository
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.game.WorldFarmMaintenance
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.example.llamadroid.tama.game.FarmPlotAction
import com.example.llamadroid.tama.game.ClassicFarmPlots
import com.example.llamadroid.tama.game.FARM_COMPOSTER_MAX_LEVEL
import com.example.llamadroid.tama.game.FARM_COMPOSTER_PROCESS_MS
import com.example.llamadroid.tama.game.FARM_WELL_MAX_LEVEL
import com.example.llamadroid.tama.game.FARM_WELL_MAX_SPEED_LEVEL
import com.example.llamadroid.tama.game.FARM_WELL_COST
import com.example.llamadroid.tama.game.composterCapacityUpgradeCostForLevel
import com.example.llamadroid.tama.game.composterSlotCapacityForLevel
import com.example.llamadroid.tama.game.wellCapacityUpgradeCostForLevel
import com.example.llamadroid.tama.game.wellIntervalHoursForSpeedLevel
import com.example.llamadroid.tama.game.wellProductionIntervalForSpeedLevel
import com.example.llamadroid.tama.game.wellSpeedUpgradeCostForLevel
import com.example.llamadroid.tama.db.FarmUpgradeEntity
import com.example.llamadroid.tama.game.wellCapacityForLevel
import com.example.llamadroid.ui.components.pressAndHoldRepeat
import com.example.llamadroid.ui.components.rememberPressAndHoldRepeatState
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val FARM_FIELD_BACKGROUND_ASSET = "file:///android_asset/farm/Others/farm_field_background.png"
internal const val WELL_TILE_EMPTY_ASSET = "file:///android_asset/farm/Others/well_tile_empty.png"
internal const val WELL_TILE_FULL_ASSET = "file:///android_asset/farm/Others/well_tile_full.png"
internal const val COMPOSTER_TILE_ASSET = "file:///android_asset/farm/Others/composter_tile.png"
internal const val FARM_SEED_TILE_SCALE = 0.68f
internal const val FARM_STAGE_1_TILE_SCALE = 0.80f
internal const val FARM_STAGE_2_TILE_SCALE = 0.94f
internal const val FARM_STAGE_FINAL_TILE_SCALE = 1.04f
internal const val FARM_DECAY_TILE_SCALE = 1.02f
internal const val FARM_OBSTACLE_TILE_SCALE = 0.94f
internal const val FARM_MATURE_PREVIEW_SCALE = 1.04f
internal const val FARM_UPGRADE_ICON_SCALE = 0.37f

/** Time left until the plot's crop is harvestable, from the moisture-aware [farmCropReadyAt] ("???" for unknown crops). */
fun calculateRemaining(tile: FarmTile, now: Long = System.currentTimeMillis()): String {
    val crop = tile.crop ?: return "???"
    if (CropDefinitions.CROPS[crop.type] == null) return "???"
    val readyAt = farmCropReadyAt(tile) ?: now
    return formatDurationToHoursMinutes(readyAt - now)
}

internal fun formatComposterRemaining(startTime: Long, now: Long): String {
    val remaining = maxOf(0L, FARM_COMPOSTER_PROCESS_MS - (now - startTime))
    return formatDurationToHoursMinutes(remaining)
}

internal fun formatDurationToHoursMinutes(durationMs: Long): String {
    val remaining = durationMs.coerceAtLeast(0L)
    val hours = remaining / 3_600_000L
    val minutes = (remaining % 3_600_000L) / 60_000L
    return String.format(java.util.Locale.getDefault(), "%02dh %02dm", hours, minutes)
}
