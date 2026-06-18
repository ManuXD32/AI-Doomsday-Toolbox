package com.example.llamadroid.ui.pdf

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.llamadroid.service.PDFService
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.service.PdfTranslationJobKind
import com.example.llamadroid.service.PDFTranslationJobService
import com.example.llamadroid.ui.settings.PDFTranslationEmbeddedSettings
import kotlinx.coroutines.launch

/**
 * PDF Toolbox Screen - Merge, Split, Extract tools
 * Uses rememberSaveable for state persistence across navigation
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PDFToolboxScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pdfService = remember { PDFService(context) }
    val settingsRepo = remember { SettingsRepository(context) }
    val pdfTranslationJobState by PDFTranslationJobService.state.collectAsState()

    LaunchedEffect(pdfTranslationJobState.successMessage, pdfTranslationJobState.errorMessage) {
        pdfTranslationJobState.successMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            PDFTranslationJobService.clearTerminalMessages()
        }
        pdfTranslationJobState.errorMessage?.let { message ->
            Toast.makeText(context, context.getString(R.string.error_param, message), Toast.LENGTH_LONG).show()
            PDFTranslationJobService.clearTerminalMessages()
        }
    }
    
    // State - using rememberSaveable for persistence across tab changes
    var selectedTool by rememberSaveable { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) } // Not saveable - reset on return
    var currentJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var splitPageRange by rememberSaveable { mutableStateOf("") }
    var ocrResult by rememberSaveable { mutableStateOf("") }
    var ocrProgressMessage by rememberSaveable { mutableStateOf("") }
    
    // Uri lists cannot be saved directly - store as strings
    var selectedPdfStrings by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var selectedImageStrings by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    
    // Convert strings back to Uris
    val selectedPdfs = selectedPdfStrings.map { android.net.Uri.parse(it) }
    val selectedImages = selectedImageStrings.map { android.net.Uri.parse(it) }
    val visibleOcrProgressMessage = if (
        pdfTranslationJobState.isRunning &&
        pdfTranslationJobState.kind != PdfTranslationJobKind.MANGA_BATCH &&
        pdfTranslationJobState.progressMessage.isNotBlank()
    ) {
        pdfTranslationJobState.progressMessage
    } else {
        ocrProgressMessage
    }

    // PDF picker
    val pdfPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            // Take persistent permission
            uris.forEach { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // Permission might already be granted
                }
            }
            selectedPdfStrings = uris.map { it.toString() }
        }
    }
    
    val singlePdfPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) { }
            selectedPdfStrings = listOf(it.toString())
        }
    }
    
    // Image picker for OCR and Images-to-PDF
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) { }
            }
            selectedImageStrings = uris.map { it.toString() }
        }
    }
    
    val singleImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) { }
            selectedImageStrings = listOf(it.toString())
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pdf_toolbox_title)) },
                navigationIcon = {
                    IconButton(onClick = { 
                        if (selectedTool != null) {
                            selectedTool = null
                            selectedPdfStrings = emptyList()
                            selectedImageStrings = emptyList()
                            ocrResult = ""
                            ocrProgressMessage = ""
                        } else {
                            navController.popBackStack() 
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        if (selectedTool == null) {
            // Tool selection
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        stringResource(R.string.pdf_tools_header),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                item {
                    PDFToolCard(
                        icon = "🔗",
                        title = stringResource(R.string.pdf_merge),
                        description = stringResource(R.string.pdf_merge_desc),
                        gradientColors = listOf(
                            Color(0xFF4CAF50).copy(alpha = 0.15f),
                            Color(0xFF388E3C).copy(alpha = 0.3f)
                        ),
                        onClick = { selectedTool = "merge" }
                    )
                }
                
                item {
                    PDFToolCard(
                        icon = "✂️",
                        title = stringResource(R.string.pdf_split),
                        description = stringResource(R.string.pdf_split_desc),
                        gradientColors = listOf(
                            Color(0xFF2196F3).copy(alpha = 0.15f),
                            Color(0xFF1976D2).copy(alpha = 0.3f)
                        ),
                        onClick = { selectedTool = "split" }
                    )
                }
                
                item {
                    PDFToolCard(
                        icon = "📝",
                        title = stringResource(R.string.pdf_extract_text),
                        description = stringResource(R.string.pdf_extract_text_desc),
                        gradientColors = listOf(
                            Color(0xFF9C27B0).copy(alpha = 0.15f),
                            Color(0xFF7B1FA2).copy(alpha = 0.3f)
                        ),
                        onClick = { selectedTool = "extract" }
                    )
                }
                
                item {
                    PDFToolCard(
                        icon = "🤖",
                        title = stringResource(R.string.pdf_ai_summary),
                        description = stringResource(R.string.pdf_ai_summary_desc),
                        gradientColors = listOf(
                            Color(0xFFFF9800).copy(alpha = 0.15f),
                            Color(0xFFF57C00).copy(alpha = 0.3f)
                        ),
                        onClick = { navController.navigate("pdf_summary") }
                    )
                }
                
                item {
                    PDFToolCard(
                        icon = "🔍",
                        title = stringResource(R.string.pdf_ocr_full),
                        description = stringResource(R.string.pdf_ocr_desc),
                        gradientColors = listOf(
                            Color(0xFF00BCD4).copy(alpha = 0.15f),
                            Color(0xFF0097A7).copy(alpha = 0.3f)
                        ),
                        onClick = { selectedTool = "ocr" }
                    )
                }

                item {
                    PDFToolCard(
                        icon = "🌐",
                        title = stringResource(R.string.pdf_translate_ocr_pdf),
                        description = stringResource(R.string.pdf_translate_ocr_pdf_desc),
                        gradientColors = listOf(
                            Color(0xFF3F51B5).copy(alpha = 0.15f),
                            Color(0xFF303F9F).copy(alpha = 0.3f)
                        ),
                        onClick = { selectedTool = "translate_ocr_pdf" }
                    )
                }
                
                item {
                    PDFToolCard(
                        icon = "🖼️",
                        title = stringResource(R.string.pdf_images_to_pdf),
                        description = stringResource(R.string.pdf_images_to_pdf_desc),
                        gradientColors = listOf(
                            Color(0xFF673AB7).copy(alpha = 0.15f),
                            Color(0xFF512DA8).copy(alpha = 0.3f)
                        ),
                        onClick = { selectedTool = "images_to_pdf" }
                    )
                }
                
                item {
                    PDFToolCard(
                        icon = "📦",
                        title = stringResource(R.string.pdf_compress),
                        description = stringResource(R.string.pdf_compress_desc),
                        gradientColors = listOf(
                            Color(0xFF607D8B).copy(alpha = 0.15f),
                            Color(0xFF455A64).copy(alpha = 0.3f)
                        ),
                        onClick = { selectedTool = "compress" }
                    )
                }
                
                item {
                    PDFToolCard(
                        icon = "📐",
                        title = stringResource(R.string.pdf_split_size),
                        description = stringResource(R.string.pdf_split_size_desc),
                        gradientColors = listOf(
                            Color(0xFF795548).copy(alpha = 0.15f),
                            Color(0xFF5D4037).copy(alpha = 0.3f)
                        ),
                        onClick = { selectedTool = "split_size" }
                    )
                }
            }
        } else {
            // Tool interface
            val toolScrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .then(
                        if (selectedTool == "translate_ocr_pdf") {
                            Modifier.verticalScroll(toolScrollState)
                        } else {
                            Modifier
                        }
                    )
            ) {
                when (selectedTool) {
                    "merge" -> {
                        Text(
                            stringResource(R.string.pdf_merge_header),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.pdf_merge_help),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        OutlinedButton(
                            onClick = { pdfPicker.launch(arrayOf("application/pdf")) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.pdf_select_multiple))
                        }
                        
                        if (selectedPdfs.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(stringResource(R.string.pdf_selected_count, selectedPdfs.size), fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                itemsIndexed(selectedPdfs) { index, uri ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("📄", style = MaterialTheme.typography.titleMedium)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                "${index + 1}. ${uri.lastPathSegment ?: stringResource(R.string.pdf_selected_file)}",
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1
                                            )
                                            IconButton(
                                                onClick = { 
                                                    selectedPdfStrings = selectedPdfStrings.toMutableList().apply { removeAt(index) }
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.Close, stringResource(R.string.action_remove), modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    scope.launch {
                                        isProcessing = true
                                        try {
                                            val result = pdfService.mergePdfs(selectedPdfs)
                                            result.fold(
                                                onSuccess = { 
                                                    Toast.makeText(context, context.getString(R.string.pdf_merged_success), Toast.LENGTH_SHORT).show()
                                                    selectedTool = null
                                                    selectedPdfStrings = emptyList()
                                                },
                                                onFailure = {
                                                    Toast.makeText(context, context.getString(R.string.error_param, it.message), Toast.LENGTH_LONG).show()
                                                }
                                            )
                                        } finally {
                                            isProcessing = false
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = selectedPdfs.size >= 2 && !isProcessing
                            ) {
                                if (isProcessing) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(stringResource(R.string.pdf_merge))
                            }
                        }
                    }
                    
                    "split" -> {
                        Text(
                            stringResource(R.string.pdf_split_header),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.pdf_split_help),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        if (selectedPdfs.isEmpty()) {
                            OutlinedButton(
                                onClick = { singlePdfPicker.launch(arrayOf("application/pdf")) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Add, null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.pdf_select))
                            }
                        } else {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("📄", style = MaterialTheme.typography.headlineSmall)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        selectedPdfs.first().lastPathSegment ?: stringResource(R.string.pdf_selected_file),
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = { selectedPdfStrings = emptyList() }) {
                                        Icon(Icons.Default.Close, stringResource(R.string.action_remove))
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            OutlinedTextField(
                                value = splitPageRange,
                                onValueChange = { splitPageRange = it },
                                label = { Text(stringResource(R.string.pdf_pages_to_extract)) },
                                placeholder = { Text(stringResource(R.string.pdf_pages_range_hint)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.pdf_split_range_help),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    scope.launch {
                                        isProcessing = true
                                        try {
                                            val result = pdfService.splitPdf(selectedPdfs.first(), splitPageRange)
                                            result.fold(
                                                onSuccess = {
                                                    Toast.makeText(context, context.getString(R.string.pdf_split_success), Toast.LENGTH_SHORT).show()
                                                    selectedTool = null
                                                    selectedPdfStrings = emptyList()
                                                    splitPageRange = ""
                                                },
                                                onFailure = {
                                                    Toast.makeText(context, context.getString(R.string.error_param, it.message), Toast.LENGTH_LONG).show()
                                                }
                                            )
                                        } finally {
                                            isProcessing = false
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = selectedPdfs.isNotEmpty() && splitPageRange.isNotBlank() && !isProcessing
                            ) {
                                if (isProcessing) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(stringResource(R.string.pdf_pages_to_extract))
                            }
                        }
                    }
                    
                    "extract" -> {
                        Text(
                            stringResource(R.string.pdf_extract_header),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.pdf_extract_help),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        if (selectedPdfs.isEmpty()) {
                            OutlinedButton(
                                onClick = { singlePdfPicker.launch(arrayOf("application/pdf")) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Add, null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.pdf_select))
                            }
                        } else {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("📄", style = MaterialTheme.typography.headlineSmall)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        selectedPdfs.first().lastPathSegment ?: stringResource(R.string.pdf_selected_file),
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = { selectedPdfStrings = emptyList() }) {
                                        Icon(Icons.Default.Close, stringResource(R.string.action_remove))
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    scope.launch {
                                        isProcessing = true
                                        try {
                                            val result = pdfService.extractText(selectedPdfs.first())
                                            result.fold(
                                                onSuccess = { text ->
                                                    // Save to notes
                                                    val db = com.example.llamadroid.data.db.AppDatabase.getDatabase(context)
                                                    db.noteDao().insert(
                                                        com.example.llamadroid.data.db.NoteEntity(
                                                            title = context.getString(
                                                                R.string.pdf_extract_note_title,
                                                                selectedPdfs.first().lastPathSegment ?: context.getString(R.string.pdf_extract_default_source_name)
                                                            ),
                                                            content = text,
                                                            type = com.example.llamadroid.data.db.NoteType.PDF_SUMMARY,
                                                            sourceFile = selectedPdfs.first().toString()
                                                        )
                                                    )
                                                    Toast.makeText(context, context.getString(R.string.pdf_extract_success), Toast.LENGTH_SHORT).show()
                                                    selectedTool = null
                                                    selectedPdfStrings = emptyList()
                                                },
                                                onFailure = {
                                                    Toast.makeText(context, context.getString(R.string.error_param, it.message), Toast.LENGTH_LONG).show()
                                                }
                                            )
                                        } finally {
                                            isProcessing = false
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = selectedPdfs.isNotEmpty() && !isProcessing
                            ) {
                                if (isProcessing) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(stringResource(R.string.pdf_extract_text))
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.pdf_extract_save_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    "ocr" -> {
                        val selectedOcrPdf = selectedPdfs.firstOrNull()
                        val selectedOcrImage = selectedImages.firstOrNull()
                        val hasOcrPdf = selectedOcrPdf != null
                        val hasOcrImage = selectedOcrImage != null
                        val sourceLabel = when {
                            hasOcrPdf -> selectedOcrPdf?.lastPathSegment ?: stringResource(R.string.pdf_selected_file)
                            hasOcrImage -> selectedOcrImage?.lastPathSegment ?: stringResource(R.string.pdf_selected_image)
                            else -> ""
                        }

                        Text(
                            stringResource(R.string.pdf_ocr_header),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.pdf_ocr_help),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        if (!hasOcrPdf && !hasOcrImage) {
                            OutlinedButton(
                                onClick = { singlePdfPicker.launch(arrayOf("application/pdf")) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.PictureAsPdf, null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.pdf_select_pdf_for_ocr))
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedButton(
                                onClick = { singleImagePicker.launch(arrayOf("image/*")) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Image, null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.pdf_select_image))
                            }
                        } else {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(if (hasOcrPdf) "📄" else "🖼️", style = MaterialTheme.typography.headlineSmall)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        sourceLabel,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1
                                    )
                                    IconButton(
                                        onClick = {
                                            selectedPdfStrings = emptyList()
                                            selectedImageStrings = emptyList()
                                            ocrResult = ""
                                            ocrProgressMessage = ""
                                        }
                                    ) {
                                        Icon(Icons.Default.Close, stringResource(R.string.action_remove))
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))

                            if (visibleOcrProgressMessage.isNotBlank()) {
                                Text(
                                    visibleOcrProgressMessage,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            if (ocrResult.isBlank()) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                isProcessing = true
                                                ocrProgressMessage = ""
                                                try {
                                                    val result = if (hasOcrPdf && selectedOcrPdf != null) {
                                                        pdfService.performOcrOnPdf(selectedOcrPdf) { progress ->
                                                            ocrProgressMessage = context.getString(
                                                                R.string.pdf_ocr_progress_pages,
                                                                progress.processedPages,
                                                                progress.totalPages,
                                                                progress.ocrPages,
                                                                progress.emptyPages
                                                            )
                                                        }.map { it.text }
                                                    } else if (selectedOcrImage != null) {
                                                        pdfService.performOCR(selectedOcrImage)
                                                    } else {
                                                        Result.failure(Exception(context.getString(R.string.pdf_ocr_select_source_first)))
                                                    }
                                                    result.fold(
                                                        onSuccess = { text ->
                                                            ocrResult = text
                                                            ocrProgressMessage = ""
                                                            Toast.makeText(context, context.getString(R.string.pdf_ocr_extract_success), Toast.LENGTH_SHORT).show()
                                                        },
                                                        onFailure = {
                                                            Toast.makeText(context, context.getString(R.string.error_param, it.message), Toast.LENGTH_LONG).show()
                                                        }
                                                    )
                                                } finally {
                                                    isProcessing = false
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !isProcessing
                                    ) {
                                        if (isProcessing) {
                                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        Text(stringResource(R.string.pdf_ocr_extract_text))
                                    }

                                    if (hasOcrPdf && selectedOcrPdf != null) {
                                        OutlinedButton(
                                            onClick = {
                                                scope.launch {
                                                    isProcessing = true
                                                    ocrProgressMessage = ""
                                                    try {
                                                        pdfService.exportSearchableOcrPdf(selectedOcrPdf) { progress ->
                                                            ocrProgressMessage = context.getString(
                                                                R.string.pdf_ocr_progress_pages,
                                                                progress.processedPages,
                                                                progress.totalPages,
                                                                progress.ocrPages,
                                                                progress.emptyPages
                                                            )
                                                        }.fold(
                                                            onSuccess = {
                                                                ocrProgressMessage = ""
                                                                Toast.makeText(context, context.getString(R.string.pdf_ocr_pdf_export_success), Toast.LENGTH_LONG).show()
                                                            },
                                                            onFailure = {
                                                                Toast.makeText(context, context.getString(R.string.error_param, it.message), Toast.LENGTH_LONG).show()
                                                            }
                                                        )
                                                    } finally {
                                                        isProcessing = false
                                                    }
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            enabled = !isProcessing
                                        ) {
                                            Icon(Icons.Default.PictureAsPdf, null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(stringResource(R.string.pdf_ocr_export_pdf))
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                ocrProgressMessage = context.getString(R.string.pdf_translation_background_started)
                                                if (!PDFTranslationJobService.startOcrPdfTranslation(context, selectedOcrPdf)) {
                                                    Toast.makeText(context, context.getString(R.string.pdf_translation_already_running), Toast.LENGTH_LONG).show()
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            enabled = !isProcessing && !pdfTranslationJobState.isRunning
                                        ) {
                                            Icon(Icons.Default.Translate, null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(stringResource(R.string.pdf_ocr_export_translated_pdf))
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    stringResource(R.string.pdf_ocr_result_title),
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .padding(16.dp)
                                            .fillMaxWidth()
                                            .heightIn(min = 100.dp, max = 240.dp)
                                            .verticalScroll(rememberScrollState())
                                    ) {
                                        Text(ocrResult)
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = {
                                        scope.launch {
                                            val sourceUri = selectedOcrPdf ?: selectedOcrImage
                                            val db = com.example.llamadroid.data.db.AppDatabase.getDatabase(context)
                                            db.noteDao().insert(
                                                com.example.llamadroid.data.db.NoteEntity(
                                                    title = context.getString(
                                                        R.string.pdf_ocr_note_title,
                                                        sourceLabel.ifBlank { context.getString(R.string.pdf_ocr_default_source_name) }
                                                    ),
                                                    content = ocrResult,
                                                    type = com.example.llamadroid.data.db.NoteType.PDF_SUMMARY,
                                                    sourceFile = sourceUri?.toString()
                                                )
                                            )
                                            Toast.makeText(context, context.getString(R.string.pdf_ocr_note_success), Toast.LENGTH_SHORT).show()
                                            selectedTool = null
                                            selectedPdfStrings = emptyList()
                                            selectedImageStrings = emptyList()
                                            ocrResult = ""
                                            ocrProgressMessage = ""
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Check, null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.pdf_ocr_save_btn))
                                }

                                if (hasOcrPdf && selectedOcrPdf != null) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                isProcessing = true
                                                ocrProgressMessage = ""
                                                try {
                                                    pdfService.exportSearchableOcrPdf(selectedOcrPdf) { progress ->
                                                        ocrProgressMessage = context.getString(
                                                            R.string.pdf_ocr_progress_pages,
                                                            progress.processedPages,
                                                            progress.totalPages,
                                                            progress.ocrPages,
                                                            progress.emptyPages
                                                        )
                                                    }.fold(
                                                        onSuccess = {
                                                            ocrProgressMessage = ""
                                                            Toast.makeText(context, context.getString(R.string.pdf_ocr_pdf_export_success), Toast.LENGTH_LONG).show()
                                                        },
                                                        onFailure = {
                                                            Toast.makeText(context, context.getString(R.string.error_param, it.message), Toast.LENGTH_LONG).show()
                                                        }
                                                    )
                                                } finally {
                                                    isProcessing = false
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !isProcessing
                                    ) {
                                        Icon(Icons.Default.PictureAsPdf, null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.pdf_ocr_export_pdf))
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = {
                                            ocrProgressMessage = context.getString(R.string.pdf_translation_background_started)
                                            if (!PDFTranslationJobService.startOcrPdfTranslation(context, selectedOcrPdf)) {
                                                Toast.makeText(context, context.getString(R.string.pdf_translation_already_running), Toast.LENGTH_LONG).show()
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !isProcessing && !pdfTranslationJobState.isRunning
                                    ) {
                                        Icon(Icons.Default.Translate, null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.pdf_ocr_export_translated_pdf))
                                    }
                                }
                            }
                        }
                    }
                    
                    "translate_ocr_pdf" -> {
                        Text(
                            stringResource(R.string.pdf_translate_ocr_header),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.pdf_translate_ocr_help),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        PDFTranslationEmbeddedSettings(settingsRepo = settingsRepo)
                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedButton(
                            onClick = { pdfPicker.launch(arrayOf("application/pdf")) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PictureAsPdf, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.pdf_select_multiple))
                        }

                        if (selectedPdfs.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(stringResource(R.string.pdf_selected_count, selectedPdfs.size), fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.height(8.dp))

                            selectedPdfs.forEachIndexed { index, uri ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 4.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("📄", style = MaterialTheme.typography.titleMedium)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            "${index + 1}. ${uri.lastPathSegment ?: stringResource(R.string.pdf_selected_file)}",
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1
                                        )
                                        IconButton(
                                            onClick = {
                                                selectedPdfStrings = selectedPdfStrings.toMutableList().apply { removeAt(index) }
                                                if (selectedPdfStrings.isEmpty()) ocrProgressMessage = ""
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.Close, stringResource(R.string.action_remove), modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            if (visibleOcrProgressMessage.isNotBlank()) {
                                Text(
                                    visibleOcrProgressMessage,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            Button(
                                onClick = {
                                    ocrProgressMessage = context.getString(R.string.pdf_translation_background_started)
                                    if (!PDFTranslationJobService.startTextLayerPdfTranslationBatch(context, selectedPdfs)) {
                                        Toast.makeText(context, context.getString(R.string.pdf_translation_already_running), Toast.LENGTH_LONG).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = selectedPdfs.isNotEmpty() && !isProcessing && !pdfTranslationJobState.isRunning
                            ) {
                                if (pdfTranslationJobState.isRunning) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Icon(Icons.Default.Translate, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.pdf_translate_ocr_export))
                            }
                        }
                    }

                    "images_to_pdf" -> {
                        Text(
                            stringResource(R.string.pdf_images_to_pdf_header),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.pdf_images_to_pdf_help),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        OutlinedButton(
                            onClick = { imagePicker.launch(arrayOf("image/*")) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.pdf_select_images))
                        }
                        
                        if (selectedImages.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(stringResource(R.string.pdf_selected_images_count, selectedImages.size), fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                itemsIndexed(selectedImages) { index, uri ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("🖼️", style = MaterialTheme.typography.titleMedium)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                "${index + 1}. ${uri.lastPathSegment ?: stringResource(R.string.pdf_selected_image)}",
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1
                                            )
                                            IconButton(
                                                onClick = { 
                                                    selectedImageStrings = selectedImageStrings.toMutableList().apply { removeAt(index) }
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.Close, stringResource(R.string.action_remove), modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    scope.launch {
                                        isProcessing = true
                                        try {
                                            val result = pdfService.imagesToPdf(selectedImages)
                                            result.fold(
                                                onSuccess = { 
                                                    Toast.makeText(context, context.getString(R.string.pdf_images_to_pdf_success), Toast.LENGTH_SHORT).show()
                                                    selectedTool = null
                                                    selectedImageStrings = emptyList()
                                                },
                                                onFailure = {
                                                    Toast.makeText(context, context.getString(R.string.error_param, it.message), Toast.LENGTH_LONG).show()
                                                }
                                            )
                                        } finally {
                                            isProcessing = false
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = selectedImages.isNotEmpty() && !isProcessing
                            ) {
                                if (isProcessing) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(stringResource(R.string.pdf_images_to_pdf))
                            }
                        }
                    }
                    
                    "compress" -> {
                        var compressionLevel by remember { mutableStateOf(5) }
                        
                        Text(
                            stringResource(R.string.pdf_compress_header),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.pdf_compress_help),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        if (selectedPdfs.isEmpty()) {
                            Button(
                                onClick = { pdfPicker.launch(arrayOf("application/pdf")) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.pdf_select))
                            }
                        } else {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("📄", style = MaterialTheme.typography.titleLarge)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        selectedPdfs.first().lastPathSegment ?: stringResource(R.string.pdf_selected_file),
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = { selectedPdfStrings = emptyList() }) {
                                        Icon(Icons.Default.Close, stringResource(R.string.action_remove))
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Text(
                                stringResource(
                                    R.string.pdf_compression_level, 
                                    compressionLevel, 
                                    if (compressionLevel <= 3) stringResource(R.string.pdf_quality_high) 
                                    else if (compressionLevel <= 6) stringResource(R.string.pdf_quality_medium) 
                                    else stringResource(R.string.pdf_quality_max)
                                ),
                                fontWeight = FontWeight.Medium
                            )
                            Slider(
                                value = compressionLevel.toFloat(),
                                onValueChange = { compressionLevel = it.toInt() },
                                valueRange = 1f..9f,
                                steps = 7,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                            Text(
                                stringResource(R.string.pdf_quality_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (isProcessing) {
                                    OutlinedButton(
                                        onClick = {
                                            currentJob?.cancel()
                                            isProcessing = false
                                            Toast.makeText(context, context.getString(R.string.action_cancelled), Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error
                                        )
                                    ) {
                                        Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(stringResource(R.string.action_cancel))
                                    }
                                }
                                
                                Button(
                                    onClick = {
                                        currentJob = scope.launch {
                                            isProcessing = true
                                            try {
                                                pdfService.compressPdf(selectedPdfs.first(), compressionLevel).fold(
                                                    onSuccess = { result ->
                                                        Toast.makeText(context, context.getString(R.string.pdf_compress_success), Toast.LENGTH_LONG).show()
                                                        selectedPdfStrings = emptyList()
                                                    },
                                                    onFailure = {
                                                        Toast.makeText(context, context.getString(R.string.error_param, it.message), Toast.LENGTH_LONG).show()
                                                    }
                                                )
                                            } finally {
                                                isProcessing = false
                                                currentJob = null
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = !isProcessing
                                ) {
                                    if (isProcessing) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text(stringResource(R.string.pdf_compress))
                                }
                            }
                        }
                    }
                    
                    "split_size" -> {
                        var sizeInput by remember { mutableStateOf("5") }
                        
                        Text(
                            stringResource(R.string.pdf_split_size_header),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.pdf_split_size_help),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        if (selectedPdfs.isEmpty()) {
                            Button(
                                onClick = { pdfPicker.launch(arrayOf("application/pdf")) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.pdf_select))
                            }
                        } else {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("📄", style = MaterialTheme.typography.titleLarge)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        selectedPdfs.first().lastPathSegment ?: stringResource(R.string.pdf_selected_file),
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = { selectedPdfStrings = emptyList() }) {
                                        Icon(Icons.Default.Close, stringResource(R.string.action_remove))
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            OutlinedTextField(
                                value = sizeInput,
                                onValueChange = { sizeInput = it.filter { c -> c.isDigit() } },
                                label = { Text(stringResource(R.string.pdf_max_size_label)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (isProcessing) {
                                    OutlinedButton(
                                        onClick = {
                                            currentJob?.cancel()
                                            isProcessing = false
                                            Toast.makeText(context, context.getString(R.string.action_cancelled), Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error
                                        )
                                    ) {
                                        Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(stringResource(R.string.action_cancel))
                                    }
                                }
                                
                                Button(
                                    onClick = {
                                        val sizeMb = sizeInput.toLongOrNull() ?: 5
                                        val sizeBytes = sizeMb * 1024 * 1024
                                        currentJob = scope.launch {
                                            isProcessing = true
                                            try {
                                                pdfService.splitBySize(selectedPdfs.first(), sizeBytes).fold(
                                                    onSuccess = { uris ->
                                                        Toast.makeText(context, context.getString(R.string.pdf_split_size_success, uris.size), Toast.LENGTH_LONG).show()
                                                        selectedPdfStrings = emptyList()
                                                    },
                                                    onFailure = {
                                                        Toast.makeText(context, context.getString(R.string.error_param, it.message), Toast.LENGTH_LONG).show()
                                                    }
                                                )
                                            } finally {
                                                isProcessing = false
                                                currentJob = null
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = !isProcessing && sizeInput.isNotEmpty()
                                ) {
                                    if (isProcessing) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text(stringResource(R.string.pdf_split_size))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PDFToolCard(
    icon: String,
    title: String,
    description: String,
    gradientColors: List<Color>,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(brush = Brush.horizontalGradient(gradientColors))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center
            ) {
                Text(icon, style = MaterialTheme.typography.headlineMedium)
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
