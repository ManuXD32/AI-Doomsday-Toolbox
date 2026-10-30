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

// Gold actions stay visible on both the dark popup overlay and the cream section cards; disabled is a
// muted taupe so it reads as less prominent (see TamaLcdThemeContrastTest).
internal val FarmPopupButtonEnabled = Color(0xFFC0841F)
internal val FarmPopupButtonOnEnabled = Color.Black
internal val FarmPopupButtonDisabled = Color(0xFF666253)
internal val FarmPopupButtonOnDisabled = Color(0xFFE8E8D0)

@Composable
internal fun farmPopupButtonColors() = ButtonDefaults.buttonColors(
    containerColor = FarmPopupButtonEnabled,
    contentColor = FarmPopupButtonOnEnabled,
    disabledContainerColor = FarmPopupButtonDisabled,
    disabledContentColor = FarmPopupButtonOnDisabled
)

@Composable
internal fun FarmUtilityTileGrid(
    count: Int,
    content: @Composable (Int) -> Unit
) {
    val rows = remember(count) { (0 until count).toList().chunked(2) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                row.forEach { index ->
                    Box(modifier = Modifier.weight(1f)) {
                        content(index)
                    }
                }
                repeat(2 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun FarmUpgradeDialogFrame(
    title: String,
    backgroundAssetPath: String,
    onDismiss: () -> Unit,
    actions: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.74f)),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .fillMaxHeight(0.82f),
                shape = RoundedCornerShape(18.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = backgroundAssetPath,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        alpha = 0.9f,
                        filterQuality = FilterQuality.None
                    )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF101010).copy(alpha = 0.74f))
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.24f))
                )
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Text(
                            title,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 18.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            content = content
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        actions()
                        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                            Text(stringResource(R.string.action_close), color = Color.White)
                        }
                    }
                }
            }
            TamaDialogNoticeHost()
        }
    }
}

@Composable
internal fun FarmInfoSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
            color = Color(0xFFF9F8F3).copy(alpha = 0.99f),
            shape = RoundedCornerShape(14.dp)
        ) {
        // Light Tama scheme so chips, checkboxes, switches and menus inside the cream card keep AA contrast.
        TamaLcdSurface(dark = false) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(title, fontWeight = FontWeight.Bold, color = TamaDark, fontSize = 15.sp)
                content()
            }
        }
    }
}
