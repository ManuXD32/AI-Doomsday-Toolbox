package com.example.llamadroid.tama.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.tama.adventure.DungeonType
import com.example.llamadroid.tama.adventure.localizedName
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.data.SettingsRepository
import kotlinx.coroutines.launch

// Dark fantasy color scheme
private val DungeonDark = Color(0xFF1A0F0F)
private val DungeonAccent = Color(0xFF8B0000)
private val DungeonGold = Color(0xFFDAA520)

/**
 * Dungeon selection screen - shows available dungeons for text adventures.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DungeonScreen(
    navController: NavController,
    database: TamaDatabase,
    settingsRepository: SettingsRepository
) {
    val scope = rememberCoroutineScope()
    var completedDungeonCount by remember { mutableStateOf(0) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    
    // Load dungeon progress
    LaunchedEffect(Unit) {
        scope.launch {
            val pet = database.tamaDao().getActivePet()
            if (pet != null) {
                val progress = database.tamaDao().getDungeonProgress(pet.id)
                completedDungeonCount = progress?.completedDungeonCount ?: 0
            }
        }
    }
    
    val unlockedDungeons = DungeonType.getUnlockedDungeons(completedDungeonCount)
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        stringResource(R.string.adventure_dungeons_title),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DungeonDark,
                    titleContentColor = DungeonGold
                )
            )
        },
        containerColor = DungeonDark
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Header
            Text(
                text = stringResource(R.string.adventure_choose_your_fate),
                color = DungeonGold.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            // Dungeon list
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(DungeonType.entries.toList()) { dungeon ->
                    val isUnlocked = dungeon in unlockedDungeons
                    DungeonCard(
                        dungeon = dungeon,
                        isUnlocked = isUnlocked,
                        onClick = {
                            if (isUnlocked) {
                                navController.navigate(Screen.Adventure.createRoute(dungeon.name))
                            }
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Progress indicator
            Text(
                text = stringResource(
                    R.string.adventure_dungeons_completed,
                    completedDungeonCount,
                    DungeonType.entries.size - 1
                ),
                color = Color.Gray,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    
    // Settings dialog
    if (showSettingsDialog) {
        AdventureSettingsDialog(
            onDismiss = { showSettingsDialog = false },
            settingsRepository = settingsRepository
        )
    }
}

@Composable
fun DungeonCard(
    dungeon: DungeonType,
    isUnlocked: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .heightIn(min = 64.dp)
            .clickable(enabled = isUnlocked) { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isUnlocked) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TamaUiIcon(
                    emoji = dungeon.emoji,
                    fontSize = 32.sp,
                    modifier = Modifier.padding(end = 16.dp)
                )
                
                // Info
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = dungeon.localizedName(context),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    
                    Text(
                        text = if (dungeon.isRandom) {
                            stringResource(R.string.adventure_random_theme_each_time)
                        } else {
                            stringResource(R.string.adventure_unlock_order, dungeon.unlockOrder)
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                    

                }
                
                // Lock indicator
                if (!isUnlocked) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = stringResource(R.string.adventure_locked),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
