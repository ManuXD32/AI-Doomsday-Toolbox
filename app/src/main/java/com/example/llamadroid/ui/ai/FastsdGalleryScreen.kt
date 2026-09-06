package com.example.llamadroid.ui.ai

import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog

import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import com.example.llamadroid.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.ui.walkthrough.WalkthroughDialog as Dialog
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import com.example.llamadroid.service.SSHService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Image data from FastSD results folder
 */
data class FastsdImage(
    val filename: String,
    val path: String,
    val size: Long,
    val modifiedTime: String,
    var thumbnail: ByteArray? = null
)

/**
 * FastSD CPU Gallery Screen
 * Displays generated images from /root/fastsdcpu/results
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FastsdGalleryScreen(navController: NavController) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val walkthroughTargets = LocalWalkthroughTargets.current
    val sshService = remember { SSHService(context) }
    
    var images by remember { mutableStateOf<List<FastsdImage>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedImage by remember { mutableStateOf<FastsdImage?>(null) } // For full view
    var showDeleteDialog by remember { mutableStateOf<Boolean>(false) }
    
    // Multi-selection state
    var selectedImages by remember { mutableStateOf<Set<String>>(emptySet()) }
    val isSelectionMode = selectedImages.isNotEmpty()
    
    val resultsPath = "/root/fastsdcpu/results"
    
    // Load images on mount
    LaunchedEffect(Unit) {
        if (!SSHService.isConnected.value) {
            errorMessage = resources.getString(R.string.fastsd_ssh_error)
            isLoading = false
            return@LaunchedEffect
        }
        
        try {
            // List files in results directory
            val result = sshService.executeCommand("ls -la --time-style=long-iso $resultsPath 2>/dev/null | grep -E '\\.(png|jpg|jpeg|webp)$'")
            
            result.onSuccess { output ->
                if (output.isBlank()) {
                    images = emptyList()
                } else {
                    val imageList = output.lines()
                        .filter { it.isNotBlank() && !it.startsWith("total") }
                        .mapNotNull { line ->
                            // Parse ls -la output: -rw-r--r-- 1 root root 12345 2024-01-01 12:00 filename.png
                            val parts = line.trim().split("\\s+".toRegex())
                            if (parts.size >= 8) {
                                val size = parts[4].toLongOrNull() ?: 0L
                                val date = parts[5]
                                val time = parts[6]
                                val filename = parts.drop(7).joinToString(" ")
                                FastsdImage(
                                    filename = filename,
                                    path = "$resultsPath/$filename",
                                    size = size,
                                    modifiedTime = "$date $time"
                                )
                            } else null
                        }
                        .sortedByDescending { it.modifiedTime }
                    
                    images = imageList
                    
                    // Load thumbnails in background for ALL images
                    imageList.forEachIndexed { index, img ->
                        scope.launch {
                            try {
                                // Small delay to stagger requests and avoid overwhelming SSH
                                delay(index * 100L)
                                // Get full base64
                                val thumbResult = sshService.executeCommand("cat '${img.path}' | base64 -w0")
                                thumbResult.onSuccess { base64 ->
                                    if (base64.isNotBlank()) {
                                        try {
                                            val decoded = Base64.decode(base64, Base64.DEFAULT)
                                            images = images.toMutableList().also {
                                                if (index < it.size) {
                                                    it[index] = it[index].copy(thumbnail = decoded)
                                                }
                                            }
                                        } catch (e: Exception) {
                                            // Ignore decode errors
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // Ignore thumbnail errors
                            }
                        }
                    }
                }
            }.onFailure { e ->
                errorMessage = resources.getString(R.string.fastsd_load_error, e.message ?: "")
            }
        } catch (e: Exception) {
            errorMessage = resources.getString(R.string.fastsd_generic_error, e.message ?: "")
        }
        isLoading = false
    }
    
    // Delete selected images
    fun deleteSelectedImages() {
        scope.launch {
            try {
                val filesToDelete = images.filter { it.filename in selectedImages }
                // Batch delete logic
                // Construct command: rm -f 'file1' 'file2' ...
                val paths = filesToDelete.joinToString(" ") { "'${it.path}'" }
                if (paths.isNotEmpty()) {
                    sshService.executeCommand("rm -f $paths")
                    
                    // Update UI
                    images = images.filter { it.filename !in selectedImages }
                    Toast.makeText(context, resources.getString(R.string.fastsd_deleted_count, filesToDelete.size), Toast.LENGTH_SHORT).show()
                    selectedImages = emptySet()
                }
            } catch (e: Exception) {
                Toast.makeText(context, resources.getString(R.string.fastsd_delete_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    // Single image delete (from full view)
    fun deleteSingleImage(image: FastsdImage) {
        scope.launch {
            try {
                sshService.executeCommand("rm -f '${image.path}'")
                images = images.filter { it.filename != image.filename }
                Toast.makeText(context, resources.getString(R.string.fastsd_deleted_single, image.filename), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, resources.getString(R.string.fastsd_delete_failed_single, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    // Share image function
    var isSharing by remember { mutableStateOf(false) }
    
    fun shareImage(image: FastsdImage) {
        if (isSharing) return
        isSharing = true
        Toast.makeText(context, resources.getString(R.string.fastsd_downloading), Toast.LENGTH_SHORT).show()
        
        scope.launch {
            try {
                // Download image to cache via SSH
                val result = sshService.executeCommand("cat '${image.path}' | base64 -w0")
                result.onSuccess { base64 ->
                    if (base64.isNotBlank()) {
                        try {
                            val decoded = Base64.decode(base64, Base64.DEFAULT)
                            // Use subfolder to avoid filename conflicts
                            val shareDir = File(context.cacheDir, "fastsd_share")
                            shareDir.mkdirs()
                            val cacheFile = File(shareDir, image.filename)
                            withContext(Dispatchers.IO) {
                                cacheFile.writeBytes(decoded)
                            }
                            
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                cacheFile
                            )
                            
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "image/*"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, resources.getString(R.string.action_share)))
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, resources.getString(R.string.fastsd_decode_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, resources.getString(R.string.fastsd_empty_data), Toast.LENGTH_SHORT).show()
                        }
                    }
                }.onFailure { e ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, resources.getString(R.string.fastsd_download_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, resources.getString(R.string.fastsd_share_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            } finally {
                isSharing = false
            }
        }
    }
    
    // Refresh images function
    fun refreshImages() {
        isLoading = true
        selectedImages = emptySet()
        scope.launch {
            try {
                val result = sshService.executeCommand("ls -la --time-style=long-iso $resultsPath 2>/dev/null | grep -E '\\.(png|jpg|jpeg|webp)$'")
                result.onSuccess { output ->
                    if (output.isNotBlank()) {
                        val imageList = output.lines()
                            .filter { it.isNotBlank() && !it.startsWith("total") }
                            .mapNotNull { line ->
                                val parts = line.trim().split("\\s+".toRegex())
                                if (parts.size >= 8) {
                                    val size = parts[4].toLongOrNull() ?: 0L
                                    val date = parts[5]
                                    val time = parts[6]
                                    val filename = parts.drop(7).joinToString(" ")
                                    FastsdImage(
                                        filename = filename,
                                        path = "$resultsPath/$filename",
                                        size = size,
                                        modifiedTime = "$date $time"
                                    )
                                } else null
                            }
                            .sortedByDescending { it.modifiedTime }
                        images = imageList
                    } else {
                        images = emptyList()
                    }
                }
            } catch (e: Exception) {
                // Ignore refresh errors
            }
            isLoading = false
        }
    }
    
    Scaffold(
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = { Text(stringResource(R.string.fastsd_selected_count, selectedImages.size)) },
                    navigationIcon = {
                        IconButton(onClick = { selectedImages = emptySet() }) {
                            Icon(Icons.Default.Close, stringResource(R.string.action_cancel))
                        }
                    },
                    actions = {
                        com.example.llamadroid.ui.walkthrough.FeatureGuideAction()
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.fastsd_gallery_title)) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        com.example.llamadroid.ui.walkthrough.FeatureGuideAction()
                        // Refresh button
                        IconButton(onClick = { refreshImages() }) {
                            Icon(Icons.Default.Refresh, stringResource(R.string.action_refresh))
                        }
                    }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.fastsd_loading_text))
                    }
                }
                
                errorMessage != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.ErrorOutline, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(errorMessage!!, textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { navController.popBackStack() }) {
                            Text(stringResource(R.string.action_go_back))
                        }
                    }
                }
                
                images.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Collections, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.fastsd_no_images), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.fastsd_no_images_hint),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                else -> {
                    LazyVerticalGrid(
                        columns = if (LocalDensity.current.fontScale >= 1.3f) GridCells.Fixed(1) else GridCells.Adaptive(160.dp),
                        modifier = Modifier.walkthroughTarget("fastsd.gallery"),
                        contentPadding = PaddingValues(20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(images) { image ->
                            val isSelected = selectedImages.contains(image.filename)
                            ImageCard(
                                image = image,
                                isSelectionMode = isSelectionMode,
                                isSelected = isSelected,
                                onClick = {
                                    walkthroughTargets?.recordEvent("fastsd.gallery")
                                    if (isSelectionMode) {
                                        selectedImages = if (isSelected) {
                                            selectedImages - image.filename
                                        } else {
                                            selectedImages + image.filename
                                        }
                                    } else {
                                        selectedImage = image
                                    }
                                },
                                onLongClick = {
                                    if (!isSelectionMode) {
                                        selectedImages = selectedImages + image.filename
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    
    // Full image viewer dialog
    selectedImage?.let { image ->
        FullImageDialog(
            image = image,
            sshService = sshService,
            onDismiss = { selectedImage = null },
            onShare = { shareImage(image) },
            onDelete = { 
                deleteSingleImage(image)
                selectedImage = null
            }
        )
    }
    
    // Delete confirmation dialog (Multi-select)
    if (showDeleteDialog && isSelectionMode) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.fastsd_delete_confirm_title, selectedImages.size)) },
            text = { Text(stringResource(R.string.fastsd_delete_confirm_text)) },
            confirmButton = {
                Button(
                    onClick = {
                        deleteSelectedImages()
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageCard(
    image: FastsdImage,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .then(
                if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier
            ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Thumbnail or placeholder
            if (image.thumbnail != null) {
                val bitmap = remember(image.thumbnail) {
                    try {
                        BitmapFactory.decodeByteArray(image.thumbnail, 0, image.thumbnail!!.size)?.asImageBitmap()
                    } catch (e: Exception) {
                        null
                    }
                }
                bitmap?.let {
                    Image(
                        bitmap = it,
                        contentDescription = image.filename,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
            
            // Selection Overlay
            if (isSelectionMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent)
                ) {
                    if (isSelected) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = stringResource(R.string.fastsd_selected_content_desc),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .size(24.dp)
                                .background(Color.White, CircleShape)
                        )
                    } else {
                        // Empty circle for unselected state in selection mode
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .size(24.dp)
                                .border(2.dp, Color.White, CircleShape)
                                .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                        )
                    }
                }
            }
            
            // Bottom info overlay (hide in selection mode for cleaner look, or keep it)
            if (!isSelectionMode || !isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(4.dp)
                ) {
                    Text(
                        image.filename.take(15) + if (image.filename.length > 15) "..." else "",
                        color = Color.White,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun FullImageDialog(
    image: FastsdImage,
    sshService: SSHService,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    var fullImageData by remember { mutableStateOf<ByteArray?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    
    // Load full image
    LaunchedEffect(image) {
        try {
            val result = sshService.executeCommand("cat '${image.path}' | base64 -w0")
            result.onSuccess { base64 ->
                if (base64.isNotBlank()) {
                    fullImageData = Base64.decode(base64, Base64.DEFAULT)
                }
            }
        } catch (e: Exception) {
            // Error loading
        }
        isLoading = false
    }
    
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.fastsd_delete_single_title)) },
            text = { Text(stringResource(R.string.fastsd_delete_single_text, image.filename)) },
            confirmButton = {
                Button(
                    onClick = onDelete,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    } else {
        Dialog(onDismissRequest = onDismiss) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 600.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(image.filename, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, stringResource(R.string.action_close))
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Image
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp, max = 360.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator()
                        } else if (fullImageData != null) {
                            val bitmap = remember(fullImageData) {
                                try {
                                    BitmapFactory.decodeByteArray(fullImageData, 0, fullImageData!!.size)?.asImageBitmap()
                                } catch (e: Exception) {
                                    null
                                }
                            }
                            bitmap?.let {
                                Image(
                                    bitmap = it,
                                    contentDescription = image.filename,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        } else {
                            Text(stringResource(R.string.fastsd_load_failed))
                        }
                    }
                    
                    // Metadata
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FastsdMetadataRow(
                                icon = Icons.Default.CalendarToday,
                                label = stringResource(R.string.fastsd_created_label),
                                value = image.modifiedTime
                            )
                            FastsdMetadataRow(
                                icon = Icons.Default.Folder,
                                label = stringResource(R.string.fastsd_size_label),
                                value = formatFileSize(image.size)
                            )
                            if (fullImageData != null) {
                                val bitmap = remember(fullImageData) {
                                    BitmapFactory.decodeByteArray(fullImageData, 0, fullImageData!!.size)
                                }
                                bitmap?.let {
                                    FastsdMetadataRow(
                                        icon = Icons.Default.AspectRatio,
                                        label = stringResource(R.string.fastsd_resolution_label),
                                        value = "${it.width} × ${it.height}"
                                    )
                                }
                            }
                        }
                    }

                    // Actions
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onShare,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.action_share))
                        }
                        Button(
                            onClick = { showDeleteConfirm = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.action_delete))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FastsdMetadataRow(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
