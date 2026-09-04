package com.example.llamadroid.ui.pdf

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.SharedFileHolder
import com.example.llamadroid.data.SharedFileTarget
import com.example.llamadroid.service.PDFService
import com.example.llamadroid.service.PDFSummaryService
import com.example.llamadroid.service.PdfSummaryStateHolder
import com.example.llamadroid.ui.components.SummaryMarkdownCard
import com.example.llamadroid.ui.components.SummarySettingsChipCard
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.util.DocumentUriDisplayName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PDFSummaryScreen(navController: NavController) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val settingsRepo = remember { SettingsRepository(context) }
    val pdfService = remember { PDFService(context) }

    val selectedPdfString by PdfSummaryStateHolder.selectedPdfUri.collectAsState()
    val selectedPdfName by PdfSummaryStateHolder.selectedPdfName.collectAsState()
    val extractedText by PdfSummaryStateHolder.extractedText.collectAsState()
    val extractionDetails by PdfSummaryStateHolder.extractionDetails.collectAsState()
    val projectedChunkCount by PdfSummaryStateHolder.projectedChunkCount.collectAsState()
    val isExtracting by PdfSummaryStateHolder.isExtracting.collectAsState()
    val progressMessage by PdfSummaryStateHolder.progressMessage.collectAsState()
    val errorMessage by PdfSummaryStateHolder.error.collectAsState()
    val metadataMessage by PdfSummaryStateHolder.metadataMessage.collectAsState()
    val summary by PdfSummaryStateHolder.summary.collectAsState()
    val partialSummaries by PdfSummaryStateHolder.partialSummaries.collectAsState()
    val isSummarizing by PdfSummaryStateHolder.isSummarizing.collectAsState()
    val currentChunk by PdfSummaryStateHolder.currentChunk.collectAsState()
    val totalChunks by PdfSummaryStateHolder.totalChunks.collectAsState()
    val cancelled by PdfSummaryStateHolder.cancelled.collectAsState()

    val backend by settingsRepo.pdfSummaryBackend.collectAsState()
    val ollamaUrl by settingsRepo.pdfSummaryOllamaUrl.collectAsState()
    val llamaServerUrl by settingsRepo.pdfSummaryLlamaServerUrl.collectAsState()
    val llamaSwapUrl by settingsRepo.pdfSummaryLlamaSwapUrl.collectAsState()
    val ollamaModel by settingsRepo.pdfSummaryOllamaModel.collectAsState()
    val llamaSwapModel by settingsRepo.pdfSummaryLlamaSwapModel.collectAsState()
    val thinkingEnabled by settingsRepo.pdfSummaryThinkingEnabled.collectAsState()
    val contextSize by settingsRepo.pdfContextSize.collectAsState()
    val maxTokens by settingsRepo.pdfMaxTokens.collectAsState()
    val mergeContext by settingsRepo.pdfMergeContextSize.collectAsState()
    val mergeMaxTokens by settingsRepo.pdfMergeMaxTokens.collectAsState()
    val temperature by settingsRepo.pdfTemperature.collectAsState()
    val timeoutMinutes by settingsRepo.pdfSummaryTimeoutMinutes.collectAsState()
    val targetLanguage by settingsRepo.pdfSummaryTargetLanguage.collectAsState()
    val serverModelLabel by settingsRepo.pdfSummaryLlamaServerModelLabel.collectAsState()
    val serverContextLabel by settingsRepo.pdfSummaryLlamaServerContextLabel.collectAsState()
    val serverContextTokens by settingsRepo.pdfSummaryLlamaServerContextTokens.collectAsState()

    val selectedPdf = selectedPdfString?.let(Uri::parse)

    // Consume PDFs selected through the Activity share chooser. The target guard prevents a
    // pending document intended for another destination from being stolen by this screen.
    LaunchedEffect(Unit) {
        val pendingFile = SharedFileHolder.consumeFor(SharedFileTarget.PDF_SUMMARY)
        if (pendingFile?.mimeType == "application/pdf") {
            runCatching {
                withContext(Dispatchers.IO) {
                    SharedFileHolder.importToCache(
                        context = context,
                        pendingFile = pendingFile,
                        fallbackDisplayName = resources.getString(R.string.file_type_pdf),
                        filePrefix = "shared_pdf_summary"
                    )
                }
            }.onSuccess { imported ->
                PdfSummaryStateHolder.reset()
                PdfSummaryStateHolder.setSelectedPdfUri(imported.uri.toString())
                PdfSummaryStateHolder.setSelectedPdfName(imported.displayName)
                PDFSummaryService.clearResult()
                PDFSummaryService.clearPartialChunkSummaries()
            }.onFailure { error ->
                android.util.Log.w("PDFSummaryScreen", "Shared PDF import failed", error)
                PdfSummaryStateHolder.setError(
                    resources.getString(R.string.pdf_shared_file_import_failed)
                )
            }
        }
    }

    val pdfPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            if (isSummarizing) return@let
            PdfSummaryStateHolder.reset()
            PdfSummaryStateHolder.setSelectedPdfUri(it.toString())
            PdfSummaryStateHolder.setSelectedPdfName(
                DocumentUriDisplayName.resolve(
                    context,
                    it,
                    resources.getString(R.string.file_type_pdf)
                )
            )
            PDFSummaryService.clearResult()
            PDFSummaryService.clearPartialChunkSummaries()
        }
    }

    val normalizedBackend = SettingsRepository.normalizeOllamaOrLlamaBackend(backend)
    val backendReady = when (normalizedBackend) {
        SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> llamaServerUrl.isNotBlank()
        SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> llamaSwapUrl.isNotBlank() && !llamaSwapModel.isNullOrBlank()
        else -> ollamaUrl.isNotBlank() && !ollamaModel.isNullOrBlank()
    }

    val warningMessage = when {
        !backendReady && normalizedBackend == SettingsRepository.PDF_BACKEND_LLAMA_SERVER ->
            stringResource(R.string.pdf_missing_llama_server_url)
        !backendReady && normalizedBackend == SettingsRepository.PDF_BACKEND_LLAMA_SWAP ->
            if (llamaSwapUrl.isBlank()) {
                stringResource(R.string.pdf_missing_llama_swap_url)
            } else {
                stringResource(R.string.pdf_error_missing_llama_swap_model)
            }
        !backendReady -> stringResource(R.string.pdf_error_missing_ollama_model)
        normalizedBackend == SettingsRepository.PDF_BACKEND_LLAMA_SERVER && serverContextTokens > 0 && mergeContext > serverContextTokens ->
            stringResource(R.string.pdf_context_warning, mergeContext, serverContextTokens)
        else -> null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pdf_ai_summary_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch { PDFSummaryService.refreshBackendMetadata(context) }
                        }
                    ) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.pdf_refresh_backend_info))
                    }
                    IconButton(onClick = { navController.navigate(Screen.PDFSettings.route) }) {
                        Icon(Icons.Default.Settings, stringResource(R.string.nav_settings))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SummarySettingsChipCard(
                title = stringResource(R.string.pdf_active_settings_title),
                supportingText = metadataMessage,
                chips = listOf(
                    when (normalizedBackend) {
                        SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> stringResource(R.string.pdf_backend_llama_server)
                        SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> stringResource(R.string.pdf_backend_llama_swap)
                        else -> stringResource(R.string.pdf_backend_ollama)
                    },
                    when (normalizedBackend) {
                        SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> serverModelLabel ?: stringResource(R.string.pdf_server_value_unavailable)
                        SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> llamaSwapModel ?: stringResource(R.string.pdf_select_llama_swap_model)
                        else -> ollamaModel ?: stringResource(R.string.pdf_select_ollama_model)
                    },
                    stringResource(R.string.pdf_target_language_chip, targetLanguage),
                    stringResource(R.string.pdf_chunk_context_chip, contextSize),
                    stringResource(R.string.pdf_chunk_max_chip, maxTokens),
                    stringResource(R.string.pdf_merge_context_chip, mergeContext),
                    stringResource(R.string.pdf_merge_max_chip, mergeMaxTokens),
                    if (timeoutMinutes == SettingsRepository.PDF_TIMEOUT_DISABLED) {
                        stringResource(R.string.pdf_timeout_off)
                    } else {
                        stringResource(R.string.pdf_timeout_value_minutes, timeoutMinutes)
                    },
                    if (thinkingEnabled) stringResource(R.string.action_enabled) else stringResource(R.string.action_disabled)
                )
            )

            warningMessage?.let {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
                    )
                ) {
                    Text(
                        text = it,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            // Import failures happen before a usable PDF URI exists, so keep the error visible
            // above the picker instead of hiding it inside the selected-document branch.
            if (selectedPdf == null) {
                errorMessage?.let {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
                        )
                    ) {
                        Text(
                            text = it,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            if (selectedPdf == null) {
                OutlinedButton(
                    onClick = { pdfPicker.launch(arrayOf("application/pdf")) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSummarizing
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.pdf_select_pdf))
                }
            } else {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    androidx.compose.foundation.layout.Row(modifier = Modifier.padding(16.dp)) {
                        Text(
                            selectedPdfName ?: stringResource(R.string.pdf_selected_file),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                if (!isSummarizing) {
                                    PdfSummaryStateHolder.reset()
                                    PDFSummaryService.clearResult()
                                }
                            },
                            enabled = !isSummarizing
                        ) {
                            Icon(Icons.Default.Close, stringResource(R.string.action_remove))
                        }
                    }
                }

                if (extractedText.isBlank()) {
                    Button(
                        onClick = {
                            scope.launch {
                                PdfSummaryStateHolder.setIsExtracting(true)
                                PdfSummaryStateHolder.setProgressMessage(resources.getString(R.string.pdf_extracting_progress))
                                PdfSummaryStateHolder.setError(null)
                                val result = pdfService.extractTextDetailed(selectedPdf)
                                result.onSuccess { extraction ->
                                    PdfSummaryStateHolder.setExtractedText(extraction.text)
                                    val details = resources.getString(
                                        R.string.pdf_extract_success_pages,
                                        extraction.text.length,
                                        extraction.totalPages,
                                        extraction.textLayerPages,
                                        extraction.ocrPages,
                                        extraction.emptyPages
                                    )
                                    PdfSummaryStateHolder.setExtractionDetails(details)
                                    PDFSummaryService.estimateChunkCount(context, extraction.text)
                                        .onSuccess { estimate ->
                                            val estimateLabel = if (estimate.tokenCountMode.name == "EXACT") {
                                                resources.getString(R.string.pdf_chunk_count_exact, estimate.chunkCount)
                                            } else {
                                                resources.getString(R.string.pdf_chunk_count_estimated, estimate.chunkCount)
                                            }
                                            PdfSummaryStateHolder.setExtractionDetails("$details\n$estimateLabel")
                                        }
                                }.onFailure {
                                    PdfSummaryStateHolder.setError(
                                        resources.getString(
                                            R.string.pdf_extract_failed,
                                            it.message ?: resources.getString(R.string.error_generic)
                                        )
                                    )
                                }
                                PdfSummaryStateHolder.setIsExtracting(false)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isExtracting && !isSummarizing
                    ) {
                        if (isExtracting) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(8.dp))
                        }
                        Text(stringResource(R.string.pdf_extract_text_step))
                    }
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.pdf_text_extracted_title), fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                stringResource(
                                    R.string.pdf_text_extracted_stats,
                                    extractedText.length,
                                    extractedText.split("\\s+".toRegex()).count { it.isNotBlank() },
                                    projectedChunkCount
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                            extractionDetails?.let {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (summary.isBlank()) {
                        Button(
                            onClick = {
                                PdfSummaryStateHolder.setProgressMessage(resources.getString(R.string.pdf_summarizing_ai))
                                PdfSummaryStateHolder.setError(null)
                                PDFSummaryService.startSummarization(
                                    context = context,
                                    text = extractedText,
                                    pdfFileName = selectedPdfName ?: resources.getString(R.string.file_type_pdf)
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSummarizing && backendReady
                        ) {
                            if (isSummarizing) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(8.dp))
                            }
                            Text(stringResource(R.string.pdf_generate_summary_step_remote))
                        }
                    }
                }

                if (isSummarizing) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(progressMessage.ifBlank { stringResource(R.string.pdf_summarizing_ai) }, fontWeight = FontWeight.Bold)
                            if (totalChunks > 0) {
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = {
                                        if (currentChunk > 0 && totalChunks > 0) {
                                            currentChunk.toFloat() / totalChunks
                                        } else {
                                            0f
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { PDFSummaryService.cancel() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }

                errorMessage?.let {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
                        )
                    ) {
                        Text(
                            text = it,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                if (cancelled && !isSummarizing && errorMessage == null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.summary_cancelled_message),
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                if (partialSummaries.isNotEmpty()) {
                    SummaryMarkdownCard(
                        title = stringResource(R.string.pdf_partial_results_title),
                        markdown = partialSummaries.mapIndexed { index, summaryPart ->
                            "### ${resources.getString(R.string.summary_partial_item_label, index + 1)}\n$summaryPart"
                        }.joinToString("\n\n")
                    )
                }

                if (summary.isNotBlank()) {
                    SummaryMarkdownCard(
                        title = stringResource(R.string.pdf_summary_result_label),
                        markdown = summary
                    )
                }
            }
        }
    }
}
