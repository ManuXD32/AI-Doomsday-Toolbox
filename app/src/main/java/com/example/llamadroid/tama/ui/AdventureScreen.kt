package com.example.llamadroid.tama.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.service.AdventureForegroundService
import com.example.llamadroid.tama.adventure.AdventureSession
import com.example.llamadroid.tama.adventure.AdventureStage
import com.example.llamadroid.tama.adventure.DungeonType
import com.example.llamadroid.tama.adventure.StorySchematic
import com.example.llamadroid.tama.adventure.localizedName
import com.example.llamadroid.tama.db.AdventureSessionEntity
import com.example.llamadroid.tama.db.AdventureStageEntity
import com.example.llamadroid.tama.db.TamaDatabase
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import androidx.compose.ui.window.Dialog

private val AdventureDark = Color(0xFF0D0D0D)
private val AdventureText = Color(0xFFE0E0E0)
private val AdventureAccent = Color(0xFFDAA520)
private val AdventureInput = Color(0xFF1A1A2E)
private val UserBubble = Color(0xFF2C5530)
private val StoryBubble = Color(0xFF1C1C2E)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdventureScreen(
    navController: NavController,
    dungeonTypeName: String,
    database: TamaDatabase,
    settingsRepository: SettingsRepository
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tamaDao = database.tamaDao()
    val dungeonType = remember(dungeonTypeName) {
        runCatching { DungeonType.valueOf(dungeonTypeName) }.getOrDefault(DungeonType.CHAOS_REALM)
    }
    val activePet by tamaDao.observeActivePet().collectAsState(initial = null)
    val latestSessionFlow = remember(activePet?.id, dungeonType.name) {
        activePet?.id?.let { petId ->
            tamaDao.observeLatestAdventureSessionForDungeon(petId, dungeonType.name)
        } ?: flowOf(null)
    }
    val sessionEntity by latestSessionFlow.collectAsState(initial = null)
    val stagesFlow = remember(sessionEntity?.id) {
        sessionEntity?.id?.let(tamaDao::observeAdventureStages) ?: flowOf(emptyList())
    }
    val stageEntities by stagesFlow.collectAsState(initial = emptyList())
    val generationState by AdventureForegroundService.generationState.collectAsState()

    var playerInput by rememberSaveable { mutableStateOf("") }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var localErrorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedImagePath by rememberSaveable { mutableStateOf<String?>(null) }

    val session = remember(sessionEntity, stageEntities) {
        sessionEntity?.toDomain(stageEntities, dungeonType)
    }
    val storyStages = session?.stages.orEmpty()
    val isGenerating = generationState?.petId == activePet?.id &&
        generationState?.dungeonTypeName == dungeonType.name
    val listState = rememberLazyListState()

    LaunchedEffect(activePet?.id, sessionEntity?.id, isGenerating, dungeonType.name) {
        val petId = activePet?.id ?: return@LaunchedEffect
        if (sessionEntity == null && !isGenerating) {
            AdventureForegroundService.startWorldBuild(context, petId, dungeonType.name)
        }
    }

    LaunchedEffect(storyStages.size, isGenerating) {
        if (storyStages.isNotEmpty()) {
            listState.animateScrollToItem(storyStages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TamaUiIcon(dungeonType.emoji, fontSize = 16.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                dungeonType.localizedName(context),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        session?.let {
                            Text(
                                stringResource(
                                    R.string.adventure_stage_counter,
                                    it.currentStage,
                                    it.schematic.totalStages
                                ),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                val petId = activePet?.id ?: return@launch
                                localErrorMessage = null
                                com.example.llamadroid.tama.adventure.AdventureService(
                                    database = database,
                                    settingsRepository = settingsRepository,
                                    context = context
                                ).resetAdventure(petId, dungeonType)
                                AdventureForegroundService.startWorldBuild(context, petId, dungeonType.name)
                            }
                        },
                        enabled = !isGenerating && activePet != null
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_reset), tint = Color.Red)
                    }
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AdventureDark,
                    titleContentColor = AdventureAccent
                )
            )
        },
        containerColor = AdventureDark
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                when {
                    activePet == null -> {
                        AdventureInfoMessage(stringResource(R.string.adventure_error_no_pet))
                    }
                    localErrorMessage != null -> {
                        AdventureInfoMessage(localErrorMessage!!)
                    }
                    storyStages.isEmpty() && isGenerating -> {
                        AdventureLoadingMessage(
                            message = generationState?.status ?: stringResource(R.string.adventure_status_building_world)
                        )
                    }
                    storyStages.isEmpty() -> {
                        AdventureInfoMessage(stringResource(R.string.adventure_waiting_for_world))
                    }
                    else -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
                        ) {
                            session?.schematic?.worldImagePath?.takeIf { it.isNotBlank() }?.let { imagePath ->
                                item {
                                    File(imagePath).takeIf(File::exists)?.let { imageFile ->
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(StoryBubble)
                                                .padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                stringResource(R.string.adventure_world_image_title),
                                                color = AdventureAccent,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold
                                            )
                                            AsyncImage(
                                                model = imageFile,
                                                contentDescription = stringResource(R.string.adventure_world_image_title),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(min = 220.dp, max = 320.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .clickable { expandedImagePath = imagePath },
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                    }
                                }
                            }

                            items(storyStages) { stage ->
                                StageItem(
                                    stage = stage,
                                    onImageClick = { expandedImagePath = it }
                                )
                            }

                            if (isGenerating) {
                                item {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            color = AdventureAccent,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            generationState?.status ?: stringResource(R.string.adventure_status_generating),
                                            color = AdventureText,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }

                            if (session?.isCompleted == true) {
                                item {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            stringResource(R.string.adventure_complete_title),
                                            color = AdventureAccent,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp
                                        )
                                        Text(
                                            stringResource(R.string.adventure_complete_body),
                                            color = Color.Gray,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AdventureInput)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = playerInput,
                    onValueChange = { playerInput = it },
                    placeholder = {
                        Text(
                            if (session?.isCompleted == true) {
                                stringResource(R.string.adventure_input_completed)
                            } else {
                                stringResource(R.string.adventure_input_placeholder)
                            },
                            color = Color.Gray
                        )
                    },
                    modifier = Modifier.weight(1f),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AdventureText,
                        unfocusedTextColor = AdventureText,
                        focusedBorderColor = AdventureAccent,
                        unfocusedBorderColor = Color.Gray
                    ),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    enabled = !isGenerating && session != null && session.isCompleted.not()
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val petId = activePet?.id ?: return@Button
                        val inputText = playerInput.trim()
                        if (inputText.isBlank()) return@Button
                        playerInput = ""
                        AdventureForegroundService.submitChoice(context, petId, dungeonType.name, inputText)
                    },
                    enabled = !isGenerating && playerInput.isNotBlank() && session != null && session.isCompleted.not(),
                    colors = ButtonDefaults.buttonColors(containerColor = AdventureAccent)
                ) {
                    Text(stringResource(R.string.action_send))
                }
            }
        }
    }

    if (showSettingsDialog) {
        AdventureSettingsDialog(
            onDismiss = { showSettingsDialog = false },
            settingsRepository = settingsRepository
        )
    }

    expandedImagePath?.let { imagePath ->
        AdventureImagePreviewDialog(
            imagePath = imagePath,
            onDismiss = { expandedImagePath = null }
        )
    }
}

@Composable
private fun AdventureLoadingMessage(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = AdventureAccent)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            message,
            color = AdventureText,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun AdventureInfoMessage(message: String) {
    Text(
        text = message,
        color = AdventureText,
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        modifier = Modifier.padding(16.dp)
    )
}

@Composable
fun StageItem(stage: AdventureStage, onImageClick: (String) -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        stage.imagePath?.takeIf { it.isNotBlank() }?.let { imagePath ->
            File(imagePath).takeIf(File::exists)?.let { imageFile ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(StoryBubble)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.adventure_world_image_title),
                        color = AdventureAccent,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                    AsyncImage(
                        model = imageFile,
                        contentDescription = stage.storyContent.take(60),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 190.dp, max = 300.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onImageClick(imagePath) },
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(StoryBubble)
                .padding(12.dp)
        ) {
            Column {
                Text(
                    text = stringResource(R.string.adventure_stage_label, stage.stageNumber),
                    color = AdventureAccent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stage.storyContent,
                    color = AdventureText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    lineHeight = 22.sp
                )
            }
        }

        if (!stage.userResponse.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .align(Alignment.End)
                    .clip(RoundedCornerShape(12.dp))
                    .background(UserBubble)
                    .padding(12.dp)
            ) {
                Column {
                    Text(
                        stringResource(R.string.adventure_you_label),
                        color = Color.White.copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stage.userResponse.orEmpty(),
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun AdventureImagePreviewDialog(
    imagePath: String,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(AdventureDark)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.adventure_world_image_title),
                color = AdventureAccent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            File(imagePath).takeIf(File::exists)?.let { imageFile ->
                AsyncImage(
                    model = imageFile,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 280.dp, max = 500.dp)
                        .clip(RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Fit
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
    }
}

private fun AdventureSessionEntity.toDomain(
    stages: List<AdventureStageEntity>,
    dungeonType: DungeonType
): AdventureSession {
    val json = Json { ignoreUnknownKeys = true }
    val schematic = runCatching {
        json.decodeFromString(StorySchematic.serializer(), schematicJson)
    }.getOrDefault(
        StorySchematic(
            totalStages = 10,
            storyThread = "Adventure continues...",
            keyEvents = emptyList(),
            possibleEndings = listOf("Victory", "Defeat"),
            tone = "dark fantasy",
            difficulty = "medium"
        )
    )
    return AdventureSession(
        id = id,
        petId = petId,
        dungeonType = dungeonType,
        schematic = schematic,
            stages = stages.map {
                AdventureStage(
                    stageNumber = it.stageNumber,
                    storyContent = it.storyContent,
                    userResponse = it.userResponse,
                    summary = it.stageSummary,
                    imagePath = it.imagePath,
                    imagePrompt = it.imagePrompt,
                    imageNegativePrompt = it.imageNegativePrompt
                )
            },
        currentStage = currentStage,
        isCompleted = isCompleted,
        cumulativeSummary = cumulativeSummary,
        createdAt = createdAt,
        lastPlayedAt = lastPlayedAt
    )
}
