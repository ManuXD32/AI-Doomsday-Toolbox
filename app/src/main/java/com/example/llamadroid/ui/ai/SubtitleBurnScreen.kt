package com.example.llamadroid.ui.ai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.documentfile.provider.DocumentFile
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.service.SubtitleBurnConfig
import com.example.llamadroid.service.SubtitleBurnService
import com.example.llamadroid.service.SubtitleBurnState

/**
 * Subtitle Burn Screen - Burns subtitles into videos using ffmpeg
 * Supports .srt, .ass, .ssa, .vtt formats with customizable styling
 * Uses foreground service for background processing
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleBurnScreen(navController: NavController) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val settingsRepo = remember { SettingsRepository(context) }
    
    // Service binding
    var service by remember { mutableStateOf<SubtitleBurnService?>(null) }
    val serviceState by SubtitleBurnService.state.collectAsState()
    
    DisposableEffect(Unit) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as? SubtitleBurnService.SubtitleBurnBinder)?.getService()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        
        val intent = Intent(context, SubtitleBurnService::class.java)
        context.startService(intent)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        
        onDispose {
            context.unbindService(connection)
        }
    }
    
    // File states
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var subtitleUri by remember { mutableStateOf<Uri?>(null) }
    var videoName by remember { mutableStateOf("") }
    var subtitleName by remember { mutableStateOf("") }
    
    // Styling options
    var fontSize by remember { mutableIntStateOf(24) }
    var alignment by remember { mutableIntStateOf(2) }
    var marginV by remember { mutableIntStateOf(20) }
    var marginL by remember { mutableIntStateOf(0) }
    var primaryColor by remember { mutableStateOf(Color.White) }
    var fontName by remember { mutableStateOf("Default") }
    
    // UI states
    var showInfoCard by remember { mutableStateOf(false) }
    
    val isProcessing = serviceState is SubtitleBurnState.Processing
    
    // Show toast on completion
    LaunchedEffect(serviceState) {
        when (val state = serviceState) {
            is SubtitleBurnState.Complete -> {
                Toast.makeText(context, resources.getString(R.string.subtitle_saved_path, state.outputPath), Toast.LENGTH_LONG).show()
                SubtitleBurnService.resetState()
            }
            is SubtitleBurnState.Error -> {
                Toast.makeText(context, resources.getString(R.string.subtitle_error_message, state.message), Toast.LENGTH_LONG).show()
                SubtitleBurnService.resetState()
            }
            else -> {}
        }
    }
    
    // Get fonts from Android system
    val fonts = remember { SubtitleBurnService.getSystemFonts() }
    
    val colorPresets = listOf(
        Color.White to "White",
        Color.Yellow to "Yellow", 
        Color.Cyan to "Cyan",
        Color.Green to "Green",
        Color.Magenta to "Magenta",
        Color(0xFFFF6B6B) to "Coral",
        Color(0xFF4ECDC4) to "Teal",
        Color(0xFFFFE66D) to "Gold"
    )
    
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            videoUri = it
            try { context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            videoName = DocumentFile.fromSingleUri(context, it)?.name ?: "Video"
        }
    }
    
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            subtitleUri = it
            try { context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            subtitleName = DocumentFile.fromSingleUri(context, it)?.name ?: "Subtitle"
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.subtitle_burn_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showInfoCard = !showInfoCard }) {
                        Icon(
                            Icons.Default.Info,
                            stringResource(R.string.subtitle_toggle_info),
                            tint = if (showInfoCard) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Info Card
            if (showInfoCard) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.subtitle_styling_guide_title), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.subtitle_guide_font_size), fontSize = 13.sp)
                            Text(stringResource(R.string.subtitle_guide_alignment), fontSize = 13.sp)
                            Text(stringResource(R.string.subtitle_guide_align_top), fontSize = 12.sp, color = Color.Gray)
                            Text(stringResource(R.string.subtitle_guide_align_mid), fontSize = 12.sp, color = Color.Gray)
                            Text(stringResource(R.string.subtitle_guide_align_bot), fontSize = 12.sp, color = Color.Gray)
                            Text(stringResource(R.string.subtitle_guide_margin_v), fontSize = 13.sp)
                            Text(stringResource(R.string.subtitle_guide_margin_l), fontSize = 13.sp)
                            Text(stringResource(R.string.subtitle_guide_color), fontSize = 13.sp)
                            Text(stringResource(R.string.subtitle_guide_font), fontSize = 13.sp)
                        }
                    }
                }
            }
            
            // File Selection
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.subtitle_files_title), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.height(12.dp))
                        
                        OutlinedButton(onClick = { videoPicker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (videoName.isNotEmpty()) videoName else stringResource(R.string.subtitle_select_video), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        
                        Spacer(Modifier.height(8.dp))
                        
                        OutlinedButton(onClick = { subtitlePicker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Edit, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (subtitleName.isNotEmpty()) subtitleName else stringResource(R.string.subtitle_select_subtitle), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            
            // Styling Options
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.subtitle_styling_title), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.height(16.dp))
                        
                        // Font Size
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.subtitle_font_size_label))
                            Text("$fontSize", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(value = fontSize.toFloat(), onValueChange = { fontSize = it.toInt() }, valueRange = 12f..60f)
                        
                        Spacer(Modifier.height(12.dp))
                        
                        // Alignment Grid
                        Text(stringResource(R.string.subtitle_alignment_label))
                        Spacer(Modifier.height(8.dp))
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(7 to "↖", 8 to "↑", 9 to "↗").forEach { (v, i) -> AlignmentButton(v, i, alignment == v) { alignment = v } }
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(4 to "←", 5 to "●", 6 to "→").forEach { (v, i) -> AlignmentButton(v, i, alignment == v) { alignment = v } }
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(1 to "↙", 2 to "↓", 3 to "↘").forEach { (v, i) -> AlignmentButton(v, i, alignment == v) { alignment = v } }
                            }
                        }
                        
                        Spacer(Modifier.height(16.dp))
                        
                        // Margins
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.subtitle_margin_v_label))
                            Text("$marginV", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(value = marginV.toFloat(), onValueChange = { marginV = it.toInt() }, valueRange = 0f..100f)
                        
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.subtitle_margin_l_label))
                            Text("$marginL", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(value = marginL.toFloat(), onValueChange = { marginL = it.toInt() }, valueRange = 0f..200f)
                        
                        Spacer(Modifier.height(16.dp))
                        
                        // Color
                        Text(stringResource(R.string.subtitle_color_label))
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            colorPresets.forEach { (color, _) ->
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(color)
                                        .border(if (primaryColor == color) 3.dp else 1.dp, if (primaryColor == color) MaterialTheme.colorScheme.primary else Color.Gray, RoundedCornerShape(8.dp))
                                        .clickable { primaryColor = color }
                                )
                            }
                        }
                        
                        Spacer(Modifier.height(16.dp))
                        
                        // Font
                        Text(stringResource(R.string.subtitle_font_label))
                        Spacer(Modifier.height(8.dp))
                        var fontExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(expanded = fontExpanded, onExpandedChange = { fontExpanded = !fontExpanded }) {
                            OutlinedTextField(
                                value = fontName,
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = fontExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(expanded = fontExpanded, onDismissRequest = { fontExpanded = false }) {
                                fonts.forEach { font ->
                                    DropdownMenuItem(text = { Text(font) }, onClick = { fontName = font; fontExpanded = false })
                                }
                            }
                        }
                    }
                }
            }
            
            // Progress
            if (isProcessing) {
                item {
                    val state = serviceState as SubtitleBurnState.Processing
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(state.status, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { service?.cancel() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    }
                }
            }
            
            // Burn Button
            item {
                Button(
                    onClick = {
                        if (videoUri != null && subtitleUri != null && service != null) {
                            service?.startBurn(SubtitleBurnConfig(
                                videoUri = videoUri!!,
                                subtitleUri = subtitleUri!!,
                                fontSize = fontSize,
                                alignment = alignment,
                                marginV = marginV,
                                marginL = marginL,
                                primaryColorRed = primaryColor.red,
                                primaryColorGreen = primaryColor.green,
                                primaryColorBlue = primaryColor.blue,
                                fontName = fontName,
                                outputFolderUri = settingsRepo.outputFolderUri.value
                            ))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    enabled = videoUri != null && subtitleUri != null && !isProcessing,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(Icons.Default.Check, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.subtitle_burn_button), fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun AlignmentButton(value: Int, icon: String, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(44.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(icon, fontSize = 18.sp, color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
