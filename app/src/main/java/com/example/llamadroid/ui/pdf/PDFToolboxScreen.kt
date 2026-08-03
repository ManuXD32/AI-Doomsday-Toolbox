package com.example.llamadroid.ui.pdf

import android.content.Intent
import android.net.Uri
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
import com.example.llamadroid.data.PdfOcrProvider
import com.example.llamadroid.data.PdfTranslationOptionsSnapshot
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.service.*
import com.example.llamadroid.ui.components.RemoteSummaryBackendEditor
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.util.FormatUtils
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

    // State - using rememberSaveable for persistence across tab changes
    var selectedTool by rememberSaveable { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) } // Not saveable - reset on return
    var currentJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var splitPageRange by rememberSaveable { mutableStateOf("") }
    var ocrResult by rememberSaveable { mutableStateOf("") }
    var ocrProgressMessage by rememberSaveable { mutableStateOf("") }
    var ocrProgressDetails by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(
        pdfTranslationJobState.successMessage,
        pdfTranslationJobState.errorMessage,
        pdfTranslationJobState.errorDetails,
        pdfTranslationJobState.cancelled
    ) {
        pdfTranslationJobState.successMessage?.let { message ->
            ocrProgressMessage = message
            ocrProgressDetails = ""
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            PDFTranslationJobService.clearTerminalMessages()
        }
        pdfTranslationJobState.errorMessage?.let { message ->
            ocrProgressMessage = message
            ocrProgressDetails = pdfTranslationJobState.errorDetails.orEmpty()
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            PDFTranslationJobService.clearTerminalMessages()
        }
        if (pdfTranslationJobState.cancelled) {
            ocrProgressMessage = context.getString(R.string.action_cancelled)
            ocrProgressDetails = ""
            Toast.makeText(context, context.getString(R.string.action_cancelled), Toast.LENGTH_SHORT).show()
            PDFTranslationJobService.clearTerminalMessages()
        }
    }
    
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
        } else if (selectedTool == "ocr") {
            GuidedPdfOcrTool(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                settingsRepo = settingsRepo,
                pdfService = pdfService,
                selectedPdf = selectedPdfs.firstOrNull(),
                selectedImage = selectedImages.firstOrNull(),
                onPickPdf = { singlePdfPicker.launch(arrayOf("application/pdf")) },
                onPickImage = { singleImagePicker.launch(arrayOf("image/*")) },
                onClearSource = {
                    selectedPdfStrings = emptyList()
                    selectedImageStrings = emptyList()
                },
                onOpenModels = { navController.navigate(Screen.LLMModels.route) },
                jobState = pdfTranslationJobState
            )
        } else if (selectedTool == "translate_ocr_pdf") {
            GuidedSearchablePdfTranslationTool(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                settingsRepo = settingsRepo,
                selectedPdfs = selectedPdfs,
                onPickPdfs = { pdfPicker.launch(arrayOf("application/pdf")) },
                onRemovePdf = { index ->
                    selectedPdfStrings = selectedPdfStrings.toMutableList().apply { removeAt(index) }
                },
                jobState = pdfTranslationJobState
            )
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
                                PdfTranslationStatusCard(
                                    message = visibleOcrProgressMessage,
                                    details = if (pdfTranslationJobState.isRunning) "" else ocrProgressDetails,
                                    isRunning = pdfTranslationJobState.isRunning && pdfTranslationJobState.kind != PdfTranslationJobKind.MANGA_BATCH,
                                    onCancel = { PDFTranslationJobService.cancel() }
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
                        Text(stringResource(R.string.pdf_translate_ocr_help))
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
                                PdfTranslationStatusCard(
                                    message = visibleOcrProgressMessage,
                                    details = if (pdfTranslationJobState.isRunning) "" else ocrProgressDetails,
                                    isRunning = pdfTranslationJobState.isRunning && pdfTranslationJobState.kind != PdfTranslationJobKind.MANGA_BATCH,
                                    onCancel = { PDFTranslationJobService.cancel() }
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
private fun GuidedPdfOcrTool(
    modifier: Modifier,
    settingsRepo: SettingsRepository,
    pdfService: PDFService,
    selectedPdf: Uri?,
    selectedImage: Uri?,
    onPickPdf: () -> Unit,
    onPickImage: () -> Unit,
    onClearSource: () -> Unit,
    onOpenModels: () -> Unit,
    jobState: PdfTranslationJobState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val installedModels by db.modelDao().getAllModels().collectAsState(initial = emptyList())
    val ocrModels = remember(installedModels) { MangaTranslationSupport.installedOcrModels(installedModels) }
    val projectors = remember(installedModels) { MangaTranslationSupport.installedProjectors(installedModels) }
    var options by remember { mutableStateOf(settingsRepo.pdfTranslationOptionsSnapshot()) }
    var translationSettings by remember { mutableStateOf(settingsRepo.pdfTranslationSettings.snapshot()) }
    var resultAction by remember { mutableStateOf(PdfOcrResultAction.EXTRACT_TEXT_TO_NOTES) }
    var isWorking by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }
    var outputUri by remember { mutableStateOf<Uri?>(null) }
    var showExpert by rememberSaveable { mutableStateOf(false) }
    val source = selectedPdf ?: selectedImage
    val translationRequired = resultAction == PdfOcrResultAction.TRANSLATE_SCANNED_PDF
    val ocrReady = options.ocrProvider == PdfOcrProvider.ML_KIT ||
        (!options.llamaOcr.modelPath.isNullOrBlank() && !options.llamaOcr.mmprojPath.isNullOrBlank())
    val translationReady = !translationRequired ||
        (
            selectedPdf != null &&
                translationSettings.targetLanguage.isNotBlank() &&
                translationSettings.ollamaUrl.isNotBlank()
            )
    val running = isWorking || (jobState.isRunning && jobState.kind == PdfTranslationJobKind.OCR_PDF)

    LaunchedEffect(jobState.outputUris, jobState.kind) {
        if (jobState.kind == PdfTranslationJobKind.OCR_PDF && jobState.outputUris.isNotEmpty()) {
            outputUri = jobState.outputUris.last()
            settingsRepo.pdfTranslationSettings.applySnapshot(translationSettings)
            persistPdfTranslationOptions(settingsRepo, options)
            persistPdfOcrOptions(settingsRepo, options)
        }
    }

    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            item {
                GuidedPdfStepCard(1, stringResource(R.string.pdf_guided_source)) {
                    if (source == null) {
                        OutlinedButton(onClick = onPickPdf, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.PictureAsPdf, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.pdf_select_pdf_for_ocr))
                        }
                        OutlinedButton(onClick = onPickImage, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Image, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.pdf_select_image))
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                source.lastPathSegment ?: stringResource(R.string.pdf_selected_file),
                                modifier = Modifier.weight(1f),
                                maxLines = 1
                            )
                            IconButton(onClick = onClearSource, enabled = !running) {
                                Icon(Icons.Default.Close, stringResource(R.string.action_remove))
                            }
                        }
                    }
                }
            }
            item {
                GuidedPdfStepCard(2, stringResource(R.string.pdf_guided_recognition)) {
                    PdfOcrProvider.entries.forEach { provider ->
                        PdfGuidedChoice(
                            text = if (provider == PdfOcrProvider.ML_KIT) {
                                stringResource(R.string.pdf_ocr_provider_mlkit)
                            } else {
                                stringResource(R.string.pdf_ocr_provider_llama_cpp)
                            },
                            selected = options.ocrProvider == provider,
                            onClick = { options = options.copy(ocrProvider = provider) }
                        )
                    }
                    if (options.ocrProvider == PdfOcrProvider.LLAMA_CPP_GGUF) {
                        PdfInstalledModelPicker(
                            label = stringResource(R.string.workflow_manga_ocr_model),
                            models = ocrModels,
                            selectedPath = options.llamaOcr.modelPath,
                            onSelected = { model ->
                                val preset = MangaTranslationSupport.inferOcrPreset(model.filename, model.repoId)
                                val projector = MangaTranslationSupport.matchProjector(model, projectors)
                                options = options.copy(
                                    llamaOcr = options.llamaOcr.copy(
                                        modelPath = model.path,
                                        mmprojPath = projector?.path ?: options.llamaOcr.mmprojPath,
                                        promptPreset = preset,
                                        customFlags = preset.recommendedFlags
                                    )
                                )
                            }
                        )
                        PdfInstalledModelPicker(
                            label = stringResource(R.string.workflow_manga_projector_model),
                            models = projectors,
                            selectedPath = options.llamaOcr.mmprojPath,
                            onSelected = { model ->
                                options = options.copy(llamaOcr = options.llamaOcr.copy(mmprojPath = model.path))
                            }
                        )
                        if (ocrModels.isEmpty() || projectors.isEmpty()) {
                            OutlinedButton(onClick = onOpenModels, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.ViewInAr, null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.workflow_manga_open_models))
                            }
                        }
                        TextButton(
                            onClick = { showExpert = !showExpert },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(if (showExpert) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.workflow_manga_expert_settings))
                        }
                        if (showExpert) {
                            OutlinedTextField(
                                value = options.llamaOcr.port.toString(),
                                onValueChange = { value ->
                                    value.toIntOrNull()?.let {
                                        options = options.copy(llamaOcr = options.llamaOcr.copy(port = it))
                                    }
                                },
                                label = { Text(stringResource(R.string.pdf_ocr_llama_port_label)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = options.llamaOcr.customFlags.orEmpty(),
                                onValueChange = {
                                    options = options.copy(
                                        llamaOcr = options.llamaOcr.copy(customFlags = it.ifBlank { null })
                                    )
                                },
                                label = { Text(stringResource(R.string.pdf_ocr_llama_custom_flags_label)) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
            item {
                GuidedPdfStepCard(3, stringResource(R.string.pdf_guided_result)) {
                    PdfOcrResultAction.entries.forEach { action ->
                        PdfGuidedChoice(
                            text = when (action) {
                                PdfOcrResultAction.EXTRACT_TEXT_TO_NOTES ->
                                    stringResource(R.string.pdf_guided_result_notes)
                                PdfOcrResultAction.SEARCHABLE_PDF ->
                                    stringResource(R.string.pdf_guided_result_searchable)
                                PdfOcrResultAction.TRANSLATE_SCANNED_PDF ->
                                    stringResource(R.string.pdf_guided_result_translated)
                            },
                            selected = resultAction == action,
                            onClick = { resultAction = action }
                        )
                    }
                }
            }
            if (translationRequired) {
                item {
                    GuidedPdfStepCard(4, stringResource(R.string.pdf_guided_translation)) {
                        ControlledPdfTranslationEditor(
                            settings = translationSettings,
                            options = options,
                            onSettingsChange = { translationSettings = it },
                            onOptionsChange = { options = it }
                        )
                    }
                }
            }
            item {
                PdfReadinessSummary(
                    sourceReady = source != null,
                    recognitionReady = ocrReady,
                    translationReady = translationReady
                )
            }
            outputUri?.let { uri ->
                item { PdfOutputActions(uri) }
            }
            if (progressText.isNotBlank()) {
                item { Text(progressText, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Surface(
            tonalElevation = 3.dp,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (running) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(jobState.progressMessage.ifBlank { progressText }, maxLines = 2)
                    OutlinedButton(
                        onClick = {
                            PDFTranslationJobService.cancel()
                            isWorking = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.action_cancel)) }
                }
            } else {
                Button(
                    enabled = source != null && ocrReady && translationReady,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    onClick = {
                        val selectedSource = source ?: return@Button
                        when (resultAction) {
                            PdfOcrResultAction.TRANSLATE_SCANNED_PDF -> {
                                val sourceSpec = MangaTranslationSource(
                                    uri = selectedSource,
                                    displayName = selectedSource.lastPathSegment ?: "document.pdf",
                                    mimeType = "application/pdf"
                                )
                                PDFTranslationJobService.startOcrPdfTranslation(
                                    context,
                                    PdfOcrJobSpec(
                                        source = sourceSpec,
                                        resultAction = resultAction,
                                        ocrConfig = DocumentOcrRunConfig(
                                            provider = options.ocrProvider,
                                            llamaOcr = options.llamaOcr
                                        ),
                                        translationConfig = DocumentTranslationRunConfig(
                                            settings = translationSettings,
                                            usePageImageContext = options.usePageScreenshotContext,
                                            pageImageMaxSide = options.screenshotMaxSide,
                                            pageImageJpegQuality = options.screenshotJpegQuality,
                                            textOnlyFallbackEnabled = options.textOnlyFallbackEnabled,
                                            qualityMode = options.qualityMode
                                        )
                                    )
                                )
                            }
                            else -> scope.launch {
                                isWorking = true
                                progressText = ""
                                val result = if (resultAction == PdfOcrResultAction.SEARCHABLE_PDF) {
                                    requireNotNull(selectedPdf)
                                    pdfService.exportSearchableOcrPdf(
                                        selectedPdf,
                                        optionsOverride = options
                                    ) { progress ->
                                        progressText = context.getString(
                                            R.string.pdf_ocr_progress_pages,
                                            progress.processedPages,
                                            progress.totalPages,
                                            progress.ocrPages,
                                            progress.emptyPages
                                        )
                                    }.map { uri ->
                                        outputUri = uri
                                        uri.toString()
                                    }
                                } else {
                                    val textResult = if (selectedPdf != null) {
                                        pdfService.performOcrOnPdf(
                                            selectedPdf,
                                            optionsOverride = options
                                        ) { progress ->
                                            progressText = context.getString(
                                                R.string.pdf_ocr_progress_pages,
                                                progress.processedPages,
                                                progress.totalPages,
                                                progress.ocrPages,
                                                progress.emptyPages
                                            )
                                        }.map { it.text }
                                    } else {
                                        pdfService.performOCR(requireNotNull(selectedImage), options)
                                    }
                                    textResult.map { text ->
                                        db.noteDao().insert(
                                            com.example.llamadroid.data.db.NoteEntity(
                                                title = context.getString(
                                                    R.string.pdf_ocr_note_title,
                                                    selectedSource.lastPathSegment ?: context.getString(R.string.pdf_ocr_default_source_name)
                                                ),
                                                content = text,
                                                type = com.example.llamadroid.data.db.NoteType.PDF_SUMMARY,
                                                sourceFile = selectedSource.toString()
                                            )
                                        )
                                        text
                                    }
                                }
                                result.onSuccess {
                                    persistPdfOcrOptions(settingsRepo, options)
                                    progressText = context.getString(R.string.pdf_guided_completed)
                                }.onFailure { progressText = it.message.orEmpty() }
                                isWorking = false
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.pdf_guided_start))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun GuidedSearchablePdfTranslationTool(
    modifier: Modifier,
    settingsRepo: SettingsRepository,
    selectedPdfs: List<Uri>,
    onPickPdfs: () -> Unit,
    onRemovePdf: (Int) -> Unit,
    jobState: PdfTranslationJobState
) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(settingsRepo.pdfTranslationSettings.snapshot()) }
    var options by remember { mutableStateOf(settingsRepo.pdfTranslationOptionsSnapshot()) }
    val running = jobState.isRunning && jobState.kind == PdfTranslationJobKind.TEXT_LAYER_PDF
    val translationReady = settings.targetLanguage.isNotBlank() && settings.ollamaUrl.isNotBlank()

    LaunchedEffect(jobState.outputUris, jobState.kind) {
        if (jobState.kind == PdfTranslationJobKind.TEXT_LAYER_PDF && jobState.outputUris.isNotEmpty()) {
            settingsRepo.pdfTranslationSettings.applySnapshot(settings)
            persistPdfTranslationOptions(settingsRepo, options)
        }
    }

    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            item {
                GuidedPdfStepCard(1, stringResource(R.string.pdf_guided_files)) {
                    OutlinedButton(onClick = onPickPdfs, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.pdf_select_multiple))
                    }
                    selectedPdfs.forEachIndexed { index, uri ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                uri.lastPathSegment ?: stringResource(R.string.pdf_selected_file),
                                modifier = Modifier.weight(1f),
                                maxLines = 1
                            )
                            IconButton(onClick = { onRemovePdf(index) }, enabled = !running) {
                                Icon(Icons.Default.Close, stringResource(R.string.action_remove))
                            }
                        }
                    }
                }
            }
            item {
                GuidedPdfStepCard(2, stringResource(R.string.pdf_guided_translation)) {
                    ControlledPdfTranslationEditor(
                        settings = settings,
                        options = options,
                        onSettingsChange = { settings = it },
                        onOptionsChange = { options = it }
                    )
                }
            }
            item {
                GuidedPdfStepCard(3, stringResource(R.string.pdf_guided_output)) {
                    Text(
                        stringResource(R.string.pdf_guided_output_folder),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            item {
                PdfReadinessSummary(
                    sourceReady = selectedPdfs.isNotEmpty(),
                    recognitionReady = true,
                    translationReady = translationReady
                )
            }
            jobState.outputUris.forEach { uri ->
                item(key = uri.toString()) { PdfOutputActions(uri) }
            }
        }
        Surface(
            tonalElevation = 3.dp,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (running) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        progress = { jobState.progressFraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(jobState.progressMessage, maxLines = 2)
                    OutlinedButton(
                        onClick = PDFTranslationJobService::cancel,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.action_cancel)) }
                }
            } else {
                Button(
                    enabled = selectedPdfs.isNotEmpty() && translationReady,
                    onClick = {
                        val translationConfig = DocumentTranslationRunConfig(
                            settings = settings,
                            usePageImageContext = options.usePageScreenshotContext,
                            pageImageMaxSide = options.screenshotMaxSide,
                            pageImageJpegQuality = options.screenshotJpegQuality,
                            textOnlyFallbackEnabled = options.textOnlyFallbackEnabled,
                            qualityMode = options.qualityMode
                        )
                        PDFTranslationJobService.startTextLayerPdfTranslationBatch(
                            context,
                            PdfTextTranslationJobSpec(
                                sources = selectedPdfs.map { uri ->
                                    MangaTranslationSource(
                                        uri = uri,
                                        displayName = uri.lastPathSegment ?: "document.pdf",
                                        mimeType = "application/pdf"
                                    )
                                },
                                translationConfig = translationConfig
                            )
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Text(stringResource(R.string.pdf_guided_translate_files, selectedPdfs.size))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ControlledPdfTranslationEditor(
    settings: com.example.llamadroid.data.RemoteSummarySettingsSnapshot,
    options: PdfTranslationOptionsSnapshot,
    onSettingsChange: (com.example.llamadroid.data.RemoteSummarySettingsSnapshot) -> Unit,
    onOptionsChange: (PdfTranslationOptionsSnapshot) -> Unit
) {
    val context = LocalContext.current
    OutlinedTextField(
        value = settings.targetLanguage,
        onValueChange = { onSettingsChange(settings.copy(targetLanguage = it)) },
        label = { Text(stringResource(R.string.pdf_translation_language_label)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    RemoteSummaryBackendEditor(
        title = stringResource(R.string.workflow_manga_translation_provider),
        backend = settings.backend,
        onBackendChange = { onSettingsChange(settings.copy(backend = it)) },
        ollamaUrl = settings.ollamaUrl,
        onOllamaUrlChange = { onSettingsChange(settings.copy(ollamaUrl = it)) },
        llamaServerUrl = settings.llamaServerUrl,
        onLlamaServerUrlChange = { onSettingsChange(settings.copy(llamaServerUrl = it)) },
        llamaSwapUrl = settings.llamaSwapUrl,
        onLlamaSwapUrlChange = { onSettingsChange(settings.copy(llamaSwapUrl = it)) },
        ollamaModel = settings.ollamaModel,
        onOllamaModelSelected = { onSettingsChange(settings.copy(ollamaModel = it)) },
        llamaSwapModel = settings.llamaSwapModel,
        onLlamaSwapModelSelected = { onSettingsChange(settings.copy(llamaSwapModel = it)) },
        llamaServerModelLabel = settings.llamaServerModelLabel,
        llamaServerContextLabel = settings.llamaServerContextLabel,
        llamaServerContextTokens = settings.llamaServerContextTokens,
        requestedContextForWarning = settings.chunkContext,
        liteRtModelId = settings.liteRtModelId,
        onLiteRtModelSelected = { onSettingsChange(settings.copy(liteRtModelId = it)) },
        liteRtBackend = settings.liteRtBackend,
        onLiteRtBackendChange = { onSettingsChange(settings.copy(liteRtBackend = it)) },
        liteRtMtpEnabled = settings.liteRtMtpEnabled,
        onLiteRtMtpEnabledChange = { onSettingsChange(settings.copy(liteRtMtpEnabled = it)) },
        liteRtThinkingEnabled = settings.thinkingEnabled,
        onLiteRtThinkingEnabledChange = { onSettingsChange(settings.copy(thinkingEnabled = it)) },
        fetchMetadata = { RemoteSummaryClientFactory.fromSnapshot(context, settings).fetchMetadata() },
        onMetadataLoaded = { metadata ->
            onSettingsChange(
                settings.copy(
                    llamaServerModelLabel = metadata.serverModelLabel,
                    llamaServerContextTokens = metadata.serverContextTokens ?: settings.llamaServerContextTokens,
                    llamaServerContextLabel = metadata.serverContextLabel
                )
            )
        }
    )
    Text(stringResource(R.string.workflow_manga_profile_title), fontWeight = FontWeight.Bold)
    com.example.llamadroid.data.PdfTranslationQualityMode.entries.forEach { mode ->
        PdfGuidedChoice(
            text = when (mode) {
                com.example.llamadroid.data.PdfTranslationQualityMode.BEST_QUALITY ->
                    stringResource(R.string.pdf_translation_quality_best)
                com.example.llamadroid.data.PdfTranslationQualityMode.BALANCED ->
                    stringResource(R.string.pdf_translation_quality_balanced)
                com.example.llamadroid.data.PdfTranslationQualityMode.FASTER ->
                    stringResource(R.string.pdf_translation_quality_faster)
            },
            selected = options.qualityMode == mode,
            onClick = { onOptionsChange(options.copy(qualityMode = mode)) }
        )
    }
}

@Composable
private fun GuidedPdfStepCard(
    number: Int,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("$number. $title", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun PdfGuidedChoice(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.width(8.dp))
            Text(text)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfInstalledModelPicker(
    label: String,
    models: List<ModelEntity>,
    selectedPath: String?,
    onSelected: (ModelEntity) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = models.firstOrNull { it.path == selectedPath }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = selected?.filename
                ?: selectedPath?.let { stringResource(R.string.workflow_manga_legacy_model_unavailable) }
                ?: stringResource(R.string.workflow_manga_choose_installed_model),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(model.filename, maxLines = 1)
                            Text(
                                "${FormatUtils.Display.formatBytes(LocalContext.current, model.sizeBytes)} • ${model.repoId}",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        }
                    },
                    onClick = {
                        onSelected(model)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun PdfReadinessSummary(
    sourceReady: Boolean,
    recognitionReady: Boolean,
    translationReady: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.workflow_manga_readiness_title), fontWeight = FontWeight.Bold)
            Text(if (sourceReady) "✓ ${stringResource(R.string.pdf_guided_source_ready)}" else "• ${stringResource(R.string.pdf_guided_source_missing)}")
            Text(if (recognitionReady) "✓ ${stringResource(R.string.pdf_guided_recognition_ready)}" else "• ${stringResource(R.string.pdf_guided_recognition_missing)}")
            Text(if (translationReady) "✓ ${stringResource(R.string.pdf_guided_translation_ready)}" else "• ${stringResource(R.string.pdf_guided_translation_missing)}")
        }
    }
}

@Composable
private fun PdfOutputActions(uri: Uri) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/pdf")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    )
                },
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.action_open)) }
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "application/pdf"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            },
                            context.getString(R.string.action_share)
                        )
                    )
                },
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.action_share)) }
        }
    }
}

private fun persistPdfOcrOptions(
    settingsRepo: SettingsRepository,
    options: PdfTranslationOptionsSnapshot
) {
    settingsRepo.setPdfOcrProvider(options.ocrProvider)
    settingsRepo.setPdfOcrBubbleGuided(options.bubbleGuidedOcrEnabled)
    settingsRepo.setPdfOcrLlamaModelPath(options.llamaOcr.modelPath)
    settingsRepo.setPdfOcrLlamaMmprojPath(options.llamaOcr.mmprojPath)
    settingsRepo.setPdfOcrLlamaPromptPreset(options.llamaOcr.promptPreset)
    settingsRepo.setPdfOcrLlamaCustomPrompt(options.llamaOcr.customPrompt)
    settingsRepo.setPdfOcrLlamaContextSize(options.llamaOcr.contextSize)
    settingsRepo.setPdfOcrLlamaMaxTokens(options.llamaOcr.maxTokens)
    settingsRepo.setPdfOcrLlamaPort(options.llamaOcr.port)
    settingsRepo.setPdfOcrLlamaFlashAttention(options.llamaOcr.flashAttention)
    settingsRepo.setPdfOcrLlamaCacheRam(options.llamaOcr.cacheRam)
    settingsRepo.setPdfOcrLlamaParallel(options.llamaOcr.parallel)
    settingsRepo.setPdfOcrLlamaCustomFlags(options.llamaOcr.customFlags)
    settingsRepo.setPdfOcrLlamaCommandTemplate(options.llamaOcr.commandTemplate)
    settingsRepo.setPdfOcrLlamaReplaceRunningServer(options.llamaOcr.temporarilyReplaceRunningServer)
}

private fun persistPdfTranslationOptions(
    settingsRepo: SettingsRepository,
    options: PdfTranslationOptionsSnapshot
) {
    settingsRepo.setPdfTranslationScreenshotContext(options.usePageScreenshotContext)
    settingsRepo.setPdfTranslationScreenshotMaxSide(options.screenshotMaxSide)
    settingsRepo.setPdfTranslationScreenshotJpegQuality(options.screenshotJpegQuality)
    settingsRepo.setPdfTranslationTextFallback(options.textOnlyFallbackEnabled)
    settingsRepo.setPdfTranslationQualityMode(options.qualityMode)
}

@Composable
private fun PdfTranslationStatusCard(
    message: String,
    details: String,
    isRunning: Boolean,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (details.isNotBlank()) {
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isRunning) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.action_cancel), maxLines = 1)
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
