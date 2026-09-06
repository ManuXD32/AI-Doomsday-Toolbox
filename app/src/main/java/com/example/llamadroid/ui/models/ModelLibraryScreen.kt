package com.example.llamadroid.ui.models

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.testTag
import com.example.llamadroid.ui.components.AppAdvancedSection
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.io.File
import com.example.llamadroid.R
import com.example.llamadroid.data.api.HfTreeItemDto
import com.example.llamadroid.data.db.ModelBundleEntity
import com.example.llamadroid.data.db.ModelBundleItemEntity
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelProvenanceEntity
import com.example.llamadroid.data.db.ModelSourceEntity
import com.example.llamadroid.data.db.PendingModelArtifactEntity
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.data.model.PortableModelMetadata
import com.example.llamadroid.data.model.library.HfFolderListing
import com.example.llamadroid.data.model.library.InstalledModelAsset
import com.example.llamadroid.data.model.library.ModelFamily
import com.example.llamadroid.data.model.library.ModelLibraryErrorCode
import com.example.llamadroid.data.model.library.ModelSourceKind
import com.example.llamadroid.data.model.library.ModelSourceRepository
import com.example.llamadroid.data.model.library.ModelLibraryRepositoryFactory
import com.example.llamadroid.data.model.library.PendingArtifactStatus
import com.example.llamadroid.data.model.library.ModelArtifactDiscardPolicy
import com.example.llamadroid.data.model.library.incompleteBundleGroupIds
import com.example.llamadroid.data.model.library.resolvedDownloadUrl
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppContentColumn
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.AppSectionCard
import org.json.JSONObject

/** Persistent source, bundle and staged-artifact management surface. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelLibraryScreen(navController: NavController, initialFamily: String? = null, initialTab: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val walkthroughTargets = LocalWalkthroughTargets.current
    val repository = rememberModelLibraryRepository(context)
    val viewModel: ModelLibraryViewModel = viewModel(
        factory = ModelLibraryViewModelFactory(repository, context)
    )
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val bundles by viewModel.bundles.collectAsStateWithLifecycle()
    val pending by viewModel.pendingArtifacts.collectAsStateWithLifecycle()
    val unknownArtifacts = remember(pending) { pending.filter { it.status != PendingArtifactStatus.PROMOTED.storedValue } }
    val installedModels by viewModel.installedModels.collectAsStateWithLifecycle()
    val installedLiteRtModels by viewModel.installedLiteRtModels.collectAsStateWithLifecycle()
    val provenance by viewModel.provenance.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val listing by viewModel.folderListing.collectAsStateWithLifecycle()
    val browsingSourceId by viewModel.browsingSourceId.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableIntStateOf(when (initialTab) { "unknown" -> 1; "bundles", "new_bundle" -> 2; else -> 0 }) }
    var family by rememberSaveable { mutableStateOf(ModelFamily.fromStoredValue(initialFamily)?.name ?: ModelFamily.LLM.name) }
    var url by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    var customUrl by rememberSaveable { mutableStateOf("") }
    var customLabel by rememberSaveable { mutableStateOf("") }
    var customRole by rememberSaveable { mutableStateOf("") }
    var editingSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceToDelete by remember { mutableStateOf<ModelSourceEntity?>(null) }
    var bundleToDelete by remember { mutableStateOf<ModelBundleEntity?>(null) }
    var artifactToPromote by remember { mutableStateOf<PendingModelArtifactEntity?>(null) }
    var artifactToDiscard by remember { mutableStateOf<PendingModelArtifactEntity?>(null) }
    var assetToLink by remember { mutableStateOf<InstalledModelAsset?>(null) }
    var bundleToEditId by rememberSaveable { mutableStateOf<String?>(null) }
    val bundleToEdit = bundles.firstOrNull { it.id == bundleToEditId }
    val bundleItemSnapshot by produceState<Pair<String?, List<ModelBundleItemEntity>>?>(null, bundleToEditId) {
        value = null
        val requestedId = bundleToEditId
        value = requestedId to (requestedId?.let { repository.observeBundleItems(it).first() } ?: emptyList())
    }
    val bundleToEditItems = bundleItemSnapshot?.takeIf { it.first == bundleToEditId }?.second
    var showNewBundle by rememberSaveable { mutableStateOf(initialTab == "new_bundle") }
    val scrollState = rememberScrollState()
    val screenScope = rememberCoroutineScope()
    val selectedFamily = ModelFamily.fromStoredValue(family) ?: ModelFamily.LLM

    AppScreenScaffold(
        title = androidx.compose.ui.res.stringResource(R.string.model_library_title),
        subtitle = androidx.compose.ui.res.stringResource(R.string.model_library_subtitle),
        onBack = { navController.popBackStack() },
        actions = {
            IconButton(onClick = {
                selectedTab = 0
                walkthroughTargets?.recordEvent("models.sources")
            }) {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = androidx.compose.ui.res.stringResource(R.string.model_library_sources_tab)
                )
            }
        }
    ) {
        AppContentColumn(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(AppChromeDefaults.SectionSpacing)
        ) {
            if (message != null) {
                ModelLibraryMessageBanner(message = message!!)
            }
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 0.dp,
                containerColor = Color.Transparent,
                divider = {}
            ) {
                listOf(
                    R.string.model_library_sources_tab,
                    R.string.model_library_pending_tab,
                    R.string.model_library_bundles_tab
                ).forEachIndexed { index, titleRes ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = {
                            selectedTab = index
                            walkthroughTargets?.recordEvent(
                                when (index) {
                                    1 -> "models.unknown"
                                    2 -> "models.bundles"
                                    else -> "models.sources"
                                }
                            )
                        },
                        modifier = if (index == 1) Modifier.walkthroughTarget("models.unknown") else Modifier,
                        text = { Text(androidx.compose.ui.res.stringResource(titleRes)) }
                    )
                }
            }

            when (selectedTab) {
                0 -> {
                    AppAdvancedSection(
                        title = androidx.compose.ui.res.stringResource(R.string.model_manager_shortcut_custom_download),
                        initiallyExpanded = initialTab == "download",
                        revealContent = walkthroughTargets?.requestedId == "models.download" || customUrl.isNotBlank()
                    ) {
                        CustomDownloadCard(
                            family = selectedFamily,
                            url = customUrl,
                            label = customLabel,
                            role = customRole,
                            busy = busy,
                            modifier = Modifier.walkthroughTarget("models.download"),
                            onUrlChange = { customUrl = it },
                            onLabelChange = { customLabel = it },
                            onRoleChange = { customRole = it },
                            onDownload = {
                                walkthroughTargets?.recordEvent("models.download")
                                viewModel.queueCustomDownload(selectedFamily, customUrl, customLabel, customRole)
                            },
                            onClear = {
                                customUrl = ""
                                customLabel = ""
                                customRole = ""
                            }
                        )
                    }
                    AppAdvancedSection(
                        title = androidx.compose.ui.res.stringResource(R.string.model_library_sources_heading),
                        revealContent = editingSourceId != null || walkthroughTargets?.requestedId == "models.sources"
                    ) {
                        ModelSourceEditor(
                            family = selectedFamily,
                            url = url,
                            label = label,
                            editing = editingSourceId != null,
                            busy = busy,
                            onFamilyChange = { family = it.name },
                            onUrlChange = { url = it },
                            onLabelChange = { label = it },
                            onSave = {
                                walkthroughTargets?.recordEvent("models.sources")
                                viewModel.saveSource(selectedFamily, url, label, editingSourceId)
                                url = ""
                                label = ""
                                editingSourceId = null
                            },
                            onCancelEdit = {
                                url = ""
                                label = ""
                                editingSourceId = null
                            }
                        )
                    }
                    if (sources.isEmpty()) {
                        EmptyLibraryCard(
                            icon = Icons.Default.Link,
                            text = androidx.compose.ui.res.stringResource(R.string.model_library_no_sources)
                        )
                    } else {
                        sources.forEach { source ->
                            ModelSourceCard(
                                source = source,
                                provenanceCount = provenance.count { it.sourceId == source.id },
                                busy = busy,
                                onVerify = { viewModel.verifySource(source.id) },
                                onBrowse = {
                                    walkthroughTargets?.recordEvent("models.browser")
                                    viewModel.browseSource(source)
                                },
                                onEdit = {
                                    family = (ModelFamily.fromStoredValue(source.family) ?: ModelFamily.LLM).name
                                    url = source.url
                                    label = source.label
                                    editingSourceId = source.id
                                    screenScope.launch { scrollState.animateScrollTo(0) }
                                },
                                onDelete = { sourceToDelete = source }
                            )
                        }
                    }
                    InstalledSourceLinksCard(
                        models = installedModels,
                        liteRtModels = installedLiteRtModels,
                        sources = sources,
                        provenance = provenance,
                        viewModel = viewModel,
                        busy = busy,
                        modifier = Modifier.walkthroughTarget("models.import"),
                        onLink = {
                            walkthroughTargets?.recordEvent("models.import")
                            assetToLink = it
                        }
                    )
                }

                1 -> {
                    AppSectionCard {
                        Text(
                            text = androidx.compose.ui.res.stringResource(R.string.model_library_pending_heading),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = androidx.compose.ui.res.stringResource(R.string.model_library_pending_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (unknownArtifacts.isEmpty()) {
                        EmptyLibraryCard(
                            icon = Icons.Default.PendingActions,
                            text = androidx.compose.ui.res.stringResource(R.string.model_library_no_pending)
                        )
                    } else {
                        unknownArtifacts.forEach { artifact ->
                            PendingArtifactCard(
                                artifact = artifact,
                                busy = busy,
                                onInspect = { viewModel.inspectPendingArtifact(artifact.id) },
                                onPromote = { artifactToPromote = artifact },
                                onCancel = { viewModel.cancelArtifact(artifact.id) },
                                onRetry = { viewModel.retryArtifact(artifact.id) },
                                onDiscard = { artifactToDiscard = artifact }
                            )
                        }
                    }
                }

                else -> {
                    AppSectionCard {
                        Text(
                            text = androidx.compose.ui.res.stringResource(R.string.model_library_bundles_heading),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = androidx.compose.ui.res.stringResource(R.string.model_library_bundles_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = {
                                walkthroughTargets?.recordEvent("models.bundles")
                                bundleToEditId = null
                                showNewBundle = true
                            },
                            modifier = Modifier.fillMaxWidth().walkthroughTarget("models.bundles")
                        ) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(8.dp))
                            Text(androidx.compose.ui.res.stringResource(R.string.model_library_create_bundle))
                        }
                    }
                    if (bundles.isEmpty()) {
                        EmptyLibraryCard(
                            icon = Icons.Default.Inventory2,
                            text = androidx.compose.ui.res.stringResource(R.string.model_library_no_bundles)
                        )
                    } else {
                        bundles.forEach { bundle ->
                            ModelBundleCard(
                                bundle = bundle,
                                repository = repository,
                                sources = sources,
                                busy = busy,
                                onStart = { viewModel.startBundle(bundle) },
                                onCancel = { viewModel.cancelBundle(bundle.id) },
                                onVerify = viewModel::verifySources,
                                onEdit = { selectedBundle, _ ->
                                    bundleToEditId = selectedBundle.id
                                    showNewBundle = true
                                },
                                onDelete = { bundleToDelete = bundle }
                            )
                        }
                    }
                }
            }
        }
    }

    listing?.let { currentListing ->
        HfFolderDialog(
            listing = currentListing,
            busy = busy,
            onDismiss = { viewModel.closeFolderListing() },
            canGoUp = currentListing.folderPath.isNotBlank(),
            onGoUp = {
                val parent = currentListing.folderPath.substringBeforeLast('/', "")
                browsingSourceId?.let { viewModel.browseFolder(it, parent) }
            },
            onOpenFolder = { path -> browsingSourceId?.let { viewModel.browseFolder(it, path) } },
            onLoadMore = { viewModel.loadMoreFolder() },
            onSelectFile = { item ->
                walkthroughTargets?.recordEvent("models.browser")
                val source = sources.firstOrNull { it.id == browsingSourceId }
                if (source != null) {
                    customUrl = hfSelectedFileUrl(currentListing.repositoryId, currentListing.revision, item.path)
                    customLabel = item.path.substringAfterLast('/')
                    customRole = ""
                    selectedTab = 0
                }
                viewModel.closeFolderListing()
            }
        )
    }
    sourceToDelete?.let { source ->
        AlertDialog(
            onDismissRequest = { sourceToDelete = null },
            title = { Text(androidx.compose.ui.res.stringResource(R.string.model_library_delete_source_title)) },
            text = { Text(androidx.compose.ui.res.stringResource(R.string.model_library_delete_source_body, source.label)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSource(source.id)
                    sourceToDelete = null
                }) { Text(androidx.compose.ui.res.stringResource(R.string.model_library_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { sourceToDelete = null }) {
                    Text(androidx.compose.ui.res.stringResource(R.string.model_library_cancel))
                }
            }
        )
    }
    bundleToDelete?.let { bundle ->
        AlertDialog(
            onDismissRequest = { bundleToDelete = null },
            title = { Text(androidx.compose.ui.res.stringResource(R.string.model_library_delete_bundle_title)) },
            text = { Text(androidx.compose.ui.res.stringResource(R.string.model_library_delete_bundle_body, bundle.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteBundle(bundle.id)
                    bundleToDelete = null
                }) { Text(androidx.compose.ui.res.stringResource(R.string.model_library_delete_definition)) }
            },
            dismissButton = {
                TextButton(onClick = { bundleToDelete = null }) {
                    Text(androidx.compose.ui.res.stringResource(R.string.model_library_cancel))
                }
            }
        )
    }
    artifactToPromote?.let { artifact ->
        val itemFlow: kotlinx.coroutines.flow.Flow<List<ModelBundleItemEntity>?> = remember(artifact.bundleId) {
            artifact.bundleId?.let(viewModel::observeBundleItems)
                ?: kotlinx.coroutines.flow.flowOf(emptyList())
        }
        val promotionItems by itemFlow.collectAsStateWithLifecycle(initialValue = null)
        if (promotionItems != null) {
        PromoteArtifactDialog(
            artifact = artifact,
            savedMetadataJson = promotionItems?.firstOrNull { it.id == artifact.bundleItemId }?.modelMetadataJson,
            busy = busy,
            onDismiss = { artifactToPromote = null },
            onPromote = { targetFamily, name, role, metadataJson ->
                viewModel.promotePendingArtifact(artifact, targetFamily, name, role, metadataJson)
                artifactToPromote = null
            }
        )
        }
    }
    artifactToDiscard?.let { artifact ->
        val exactPath = artifact.destinationPath?.trim()?.takeIf { it.isNotEmpty() }
            ?: artifact.stagingPath
        AlertDialog(
            onDismissRequest = { artifactToDiscard = null },
            title = { Text(androidx.compose.ui.res.stringResource(R.string.model_library_discard_artifact_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        androidx.compose.ui.res.stringResource(
                            R.string.model_library_discard_artifact_body,
                            artifact.filename
                        )
                    )
                    Text(
                        androidx.compose.ui.res.stringResource(R.string.model_library_discard_artifact_path),
                        style = MaterialTheme.typography.labelMedium
                    )
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(exactPath, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.discardArtifact(artifact.id)
                        artifactToDiscard = null
                    },
                    enabled = !busy
                ) {
                    Text(androidx.compose.ui.res.stringResource(R.string.model_library_discard_artifact_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { artifactToDiscard = null }) {
                    Text(androidx.compose.ui.res.stringResource(R.string.model_library_cancel))
                }
            }
        )
    }
    assetToLink?.let { asset ->
        ModelSourceAttachmentDialog(
            asset = asset,
            sources = sources,
            provenance = provenance,
            onDismiss = { assetToLink = null },
            onSave = { request ->
                viewModel.attachSource(request)
                assetToLink = null
            }
        )
    }
    if (showNewBundle && bundleToEditItems != null && (bundleToEditId == null || bundleToEdit != null)) {
        BundleEditorDialog(
            existing = bundleToEdit,
            initialFamily = selectedFamily,
            existingItems = bundleToEditItems.orEmpty(),
            installedModels = installedModels,
            installedLiteRtModels = installedLiteRtModels,
            sources = sources,
            provenance = provenance,
            viewModel = viewModel,
            busy = busy,
            onDismiss = { showNewBundle = false; bundleToEditId = null },
            onSave = { bundle, items ->
                viewModel.saveBundle(bundle, items)
                showNewBundle = false
                bundleToEditId = null
            }
        )
    }
}

@Composable
private fun rememberModelLibraryRepository(context: android.content.Context): ModelSourceRepository {
    val appContext = context.applicationContext
    return remember(appContext) { ModelLibraryRepositoryFactory.create(appContext) }
}

@Composable
private fun ModelLibraryMessageBanner(message: ModelLibraryMessage) {
    val container = if (message.success) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
    val content = if (message.success) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
    Card(colors = CardDefaults.cardColors(containerColor = container), shape = AppChromeDefaults.InnerCardShape) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(if (message.success) Icons.Default.CheckCircle else Icons.Default.WarningAmber, null, tint = content)
            Text(
                text = if (message.success) {
                    androidx.compose.ui.res.stringResource(R.string.model_library_operation_complete)
                } else {
                    modelLibraryErrorText(message.code)
                },
                color = content,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
internal fun modelLibraryErrorText(code: ModelLibraryErrorCode): String = when (code) {
    ModelLibraryErrorCode.INVALID_URL -> androidx.compose.ui.res.stringResource(R.string.model_library_error_invalid_url)
    ModelLibraryErrorCode.HTTPS_REQUIRED -> androidx.compose.ui.res.stringResource(R.string.model_library_error_https)
    ModelLibraryErrorCode.EMBEDDED_CREDENTIALS -> androidx.compose.ui.res.stringResource(R.string.model_library_error_credentials)
    ModelLibraryErrorCode.CREDENTIAL_QUERY_PARAMETER -> androidx.compose.ui.res.stringResource(R.string.model_library_error_query_credentials)
    ModelLibraryErrorCode.UNSAFE_PATH -> androidx.compose.ui.res.stringResource(R.string.model_library_error_unsafe_path)
    ModelLibraryErrorCode.INVALID_HF_REPOSITORY -> androidx.compose.ui.res.stringResource(R.string.model_library_error_hf_repository)
    ModelLibraryErrorCode.INVALID_HF_FILE_PATH -> androidx.compose.ui.res.stringResource(R.string.model_library_error_hf_file)
    ModelLibraryErrorCode.UNSUPPORTED_HF_PATH -> androidx.compose.ui.res.stringResource(R.string.model_library_error_hf_path)
    ModelLibraryErrorCode.WEBPAGE_LINK -> androidx.compose.ui.res.stringResource(R.string.model_library_error_webpage)
    ModelLibraryErrorCode.AUTHENTICATION_REQUIRED -> androidx.compose.ui.res.stringResource(R.string.model_library_error_auth_required)
    ModelLibraryErrorCode.AUTHENTICATION_REJECTED -> androidx.compose.ui.res.stringResource(R.string.model_library_error_auth_rejected)
    ModelLibraryErrorCode.HTTP_FAILURE -> androidx.compose.ui.res.stringResource(R.string.model_library_error_http)
    ModelLibraryErrorCode.NETWORK_FAILURE -> androidx.compose.ui.res.stringResource(R.string.model_library_error_network)
    ModelLibraryErrorCode.REQUEST_TIMEOUT -> androidx.compose.ui.res.stringResource(R.string.model_library_error_timeout)
    ModelLibraryErrorCode.SOURCE_NOT_FOUND -> androidx.compose.ui.res.stringResource(R.string.model_library_error_source_missing)
    ModelLibraryErrorCode.SOURCE_ALREADY_SAVED -> androidx.compose.ui.res.stringResource(R.string.model_library_error_source_duplicate)
    ModelLibraryErrorCode.SOURCE_HAS_PENDING_DOWNLOAD -> androidx.compose.ui.res.stringResource(R.string.model_library_error_source_pending)
    ModelLibraryErrorCode.SOURCE_NOT_VERIFIED -> androidx.compose.ui.res.stringResource(R.string.model_library_error_not_verified)
    ModelLibraryErrorCode.RECOGNITION_FAILED -> androidx.compose.ui.res.stringResource(R.string.model_library_error_recognition)
    ModelLibraryErrorCode.MANUAL_PROMOTION_REQUIRED -> androidx.compose.ui.res.stringResource(R.string.model_library_error_manual)
    ModelLibraryErrorCode.BUNDLE_INVALID -> androidx.compose.ui.res.stringResource(R.string.model_library_error_bundle)
    ModelLibraryErrorCode.BUNDLE_ITEM_SOURCE_MISSING -> androidx.compose.ui.res.stringResource(R.string.model_library_error_bundle_source)
    ModelLibraryErrorCode.BUNDLE_ITEM_PATH_INVALID -> androidx.compose.ui.res.stringResource(R.string.model_library_error_bundle_path)
    ModelLibraryErrorCode.DOWNLOAD_FAILED -> androidx.compose.ui.res.stringResource(R.string.model_library_error_download)
    ModelLibraryErrorCode.DOWNLOAD_TIMEOUT -> androidx.compose.ui.res.stringResource(R.string.model_library_error_download_timeout)
    ModelLibraryErrorCode.GROUPED_ARTIFACT_RENAME_UNSUPPORTED -> androidx.compose.ui.res.stringResource(R.string.model_library_error_grouped_rename)
    ModelLibraryErrorCode.ARTIFACT_DISCARD_UNSAFE_PATH -> androidx.compose.ui.res.stringResource(R.string.model_library_error_artifact_discard_unsafe)
    ModelLibraryErrorCode.ARTIFACT_DISCARD_PROTECTED -> androidx.compose.ui.res.stringResource(R.string.model_library_error_artifact_discard_protected)
    ModelLibraryErrorCode.ARTIFACT_DISCARD_PROMOTED -> androidx.compose.ui.res.stringResource(R.string.model_library_error_artifact_discard_promoted)
    ModelLibraryErrorCode.ARTIFACT_DISCARD_FAILED -> androidx.compose.ui.res.stringResource(R.string.model_library_error_artifact_discard_failed)
}

@Composable
fun CustomDownloadCard(
    family: ModelFamily,
    url: String,
    label: String,
    role: String,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onUrlChange: (String) -> Unit,
    onLabelChange: (String) -> Unit,
    onRoleChange: (String) -> Unit,
    onDownload: () -> Unit,
    onClear: () -> Unit
) {
    AppSectionCard(modifier = modifier, containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = RoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    Icons.Default.CloudDownload,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.model_library_custom_download_heading),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.model_library_custom_download_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(androidx.compose.ui.res.stringResource(R.string.model_library_custom_url)) },
            placeholder = { Text(androidx.compose.ui.res.stringResource(R.string.model_library_custom_url_hint)) },
            singleLine = true,
            supportingText = { Text(androidx.compose.ui.res.stringResource(R.string.model_library_custom_url_help)) }
        )
        AppAdvancedSection(
            title = androidx.compose.ui.res.stringResource(R.string.model_library_optional_details),
            initiallyExpanded = label.isNotBlank() || role.isNotBlank()
        ) {
            OutlinedTextField(
                value = label,
                onValueChange = onLabelChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(androidx.compose.ui.res.stringResource(R.string.model_library_label)) },
                singleLine = true
            )
            ModelComponentPicker(family = family, role = role, onSelect = onRoleChange, enabled = !busy)
            Text(
                androidx.compose.ui.res.stringResource(R.string.model_library_role_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = androidx.compose.ui.res.stringResource(R.string.model_library_custom_download_validation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onClear, enabled = !busy && (url.isNotBlank() || label.isNotBlank() || role.isNotBlank())) {
                Text(androidx.compose.ui.res.stringResource(R.string.model_library_clear))
            }
            Button(onClick = onDownload, enabled = url.isNotBlank() && !busy) {
                Icon(Icons.Default.CloudDownload, null)
                Spacer(Modifier.width(8.dp))
                Text(androidx.compose.ui.res.stringResource(R.string.model_library_download))
            }
        }
    }
}

@Composable
private fun ModelSourceEditor(
    family: ModelFamily,
    url: String,
    label: String,
    editing: Boolean,
    busy: Boolean,
    onFamilyChange: (ModelFamily) -> Unit,
    onUrlChange: (String) -> Unit,
    onLabelChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancelEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    var familyMenuExpanded by remember { mutableStateOf(false) }
    AppSectionCard(modifier = modifier.walkthroughTarget("models.sources")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = RoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) { Icon(Icons.Default.Link, null, modifier = Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.primary) }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.model_library_sources_heading),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.model_library_sources_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(androidx.compose.ui.res.stringResource(R.string.model_library_family), style = MaterialTheme.typography.labelLarge)
        androidx.compose.foundation.layout.Box {
            OutlinedButton(onClick = { familyMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(modelFamilyLabel(family), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = familyMenuExpanded, onDismissRequest = { familyMenuExpanded = false }) {
                ModelFamily.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(modelFamilyLabel(option)) },
                        onClick = {
                            onFamilyChange(option)
                            familyMenuExpanded = false
                        }
                    )
                }
            }
        }
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(androidx.compose.ui.res.stringResource(R.string.model_library_url)) },
            placeholder = { Text(androidx.compose.ui.res.stringResource(R.string.model_library_url_hint)) },
            singleLine = true,
            supportingText = { Text(androidx.compose.ui.res.stringResource(R.string.model_library_url_help)) }
        )
        OutlinedTextField(
            value = label,
            onValueChange = onLabelChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(androidx.compose.ui.res.stringResource(R.string.model_library_label)) },
            placeholder = { Text(androidx.compose.ui.res.stringResource(R.string.model_library_label_optional)) },
            singleLine = true
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (editing) {
                TextButton(onClick = onCancelEdit, enabled = !busy) {
                    Text(androidx.compose.ui.res.stringResource(R.string.model_library_cancel))
                }
            }
            Button(onClick = onSave, enabled = url.isNotBlank() && !busy) {
                Icon(if (editing) Icons.Default.Save else Icons.Default.Link, null)
                Spacer(Modifier.width(8.dp))
                Text(androidx.compose.ui.res.stringResource(if (editing) R.string.model_library_update_source else R.string.model_library_save_source))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelSourceCard(
    source: ModelSourceEntity,
    provenanceCount: Int,
    busy: Boolean,
    onVerify: () -> Unit,
    onBrowse: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val kind = ModelSourceKind.fromStoredValue(source.kind)
    val verified = source.isVerifiedForDisplay()
    val statusRes = when {
        verified -> R.string.model_library_source_verified
        source.authRequired -> R.string.model_library_source_auth_required
        source.validationStatus == "unavailable" -> R.string.model_library_source_unavailable
        source.validationStatus == "authentication" -> R.string.model_library_source_auth_required
        else -> R.string.model_library_source_unverified
    }
    AppSectionCard(modifier = Modifier.testTag("model_source_card")) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                imageVector = if (verified) Icons.Default.Verified else Icons.Default.Public,
                contentDescription = null,
                tint = if (verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(source.label, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    text = "${modelFamilyLabel(ModelFamily.fromStoredValue(source.family) ?: ModelFamily.LLM)} · ${modelSourceKindLabel(kind)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(source.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(androidx.compose.ui.res.stringResource(statusRes), style = MaterialTheme.typography.labelMedium, color = if (verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                if (provenanceCount > 0) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.model_library_source_linked_models, provenanceCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TextButton(onClick = onVerify, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(R.string.model_library_verify)) }
            if (kind == ModelSourceKind.HUGGING_FACE_REPOSITORY) {
                TextButton(onClick = onBrowse, enabled = !busy) {
                    Icon(Icons.Default.Search, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        androidx.compose.ui.res.stringResource(R.string.model_library_browse),
                        modifier = Modifier.walkthroughTarget("models.browser")
                    )
                }
            }
            TextButton(onClick = onEdit, enabled = !busy) {
                Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(androidx.compose.ui.res.stringResource(R.string.model_library_edit))
            }
            TextButton(onClick = onDelete, enabled = !busy) {
                Icon(Icons.Default.DeleteOutline, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(androidx.compose.ui.res.stringResource(R.string.model_library_delete))
            }
        }
    }
}

/** Old rows predate validationStatus; their verified boolean remains authoritative. */
private fun ModelSourceEntity.isVerifiedForDisplay(): Boolean =
    verified && (validationStatus == "verified" || (validationStatus == "needs_check" && checkedAt == null && lastErrorCode == null))

@Composable
private fun sourceStatusLabel(source: ModelSourceEntity): String = androidx.compose.ui.res.stringResource(
    when {
        source.isVerifiedForDisplay() -> R.string.model_library_source_verified
        source.authRequired || source.validationStatus == "authentication" -> R.string.model_library_source_auth_required
        source.validationStatus == "unavailable" -> R.string.model_library_source_unavailable
        else -> R.string.model_library_source_unverified_short
    }
)

/**
 * Keeps bundle rows distinguishable when two files share a basename. A source
 * label is user editable, so the repository/path is part of the identity shown
 * in the editor and on the saved bundle card.
 */
private fun bundleSourceLocation(source: ModelSourceEntity): String = when {
    source.repositoryId?.isNotBlank() == true && !source.filePath.isNullOrBlank() ->
        "${source.repositoryId}/${source.filePath}"
    source.repositoryId?.isNotBlank() == true -> source.repositoryId
    !source.filePath.isNullOrBlank() -> source.filePath.orEmpty()
    source.url.isNotBlank() -> source.url
    else -> source.normalizedKey
}

@Composable
private fun bundleSourceIdentity(source: ModelSourceEntity): String = androidx.compose.ui.res.stringResource(
    R.string.model_library_bundle_source_identity,
    source.label,
    bundleSourceLocation(source)
)

@Composable
internal fun InstalledSourceLinksCard(
    models: List<ModelEntity>,
    liteRtModels: List<LiteRtModelEntity>,
    sources: List<ModelSourceEntity>,
    provenance: List<ModelProvenanceEntity>,
    viewModel: ModelLibraryViewModel,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onLink: (InstalledModelAsset) -> Unit
) {
    val assets = remember(models, liteRtModels) {
        models.map { model ->
            InstalledModelAsset.fromModel(
                model = model,
                family = viewModel.familyForModel(model),
                role = viewModel.roleForModel(model)
            )
        } + liteRtModels.map(InstalledModelAsset::fromLiteRt)
    }
    val assetDirectories by produceState<Map<String, Boolean?>>(emptyMap(), assets) {
        value = withContext(Dispatchers.IO) {
            assets.associate { asset ->
                asset.stableId to runCatching { File(asset.path).isDirectory }.getOrNull()
            }
        }
    }
    val assetCanonicalPaths by produceState<Map<String, String?>>(emptyMap(), assets) {
        value = withContext(Dispatchers.IO) {
            assets.associate { asset ->
                asset.stableId to runCatching { File(asset.path).canonicalPath }.getOrNull()
            }
        }
    }
    AppSectionCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Link, null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.model_library_installed_sources_heading),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.model_library_installed_sources_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (assets.isEmpty()) {
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.model_library_no_installed_models),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                assets.forEach { asset ->
                // Directory packages have one provenance edge per member. A
                // model-key fallback would show a sibling's source here.
                val link = findProvenanceForAsset(
                    asset = asset,
                    provenance = provenance,
                    requireExactPath = assetDirectories[asset.stableId] != false,
                    canonicalPath = assetCanonicalPaths[asset.stableId]
                )
                val source = link?.sourceId?.let { sourceId -> sources.firstOrNull { it.id == sourceId } }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(asset.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = if (source != null) {
                                androidx.compose.ui.res.stringResource(R.string.model_library_model_linked_to, source.label)
                            } else {
                                androidx.compose.ui.res.stringResource(R.string.model_library_model_needs_source)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (source?.isVerifiedForDisplay() == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = modelFamilyLabel(asset.family),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = { onLink(asset) }, enabled = !busy) {
                        Text(androidx.compose.ui.res.stringResource(if (source == null) R.string.model_library_link else R.string.model_library_edit))
                    }
                }
            }
            }
        }
    }
}

@Composable
internal fun LinkInstalledAssetDialog(
    asset: InstalledModelAsset,
    sources: List<ModelSourceEntity>,
    provenance: List<ModelProvenanceEntity>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String?) -> Unit
) {
    val defaultFamily = asset.family
    val defaultRole = asset.role
    val isDirectory by produceState<Boolean?>(null, asset.path) {
        value = withContext(Dispatchers.IO) {
            runCatching { File(asset.path).isDirectory }.getOrNull()
        }
    }
    val canonicalPath by produceState<String?>(null, asset.path) {
        value = withContext(Dispatchers.IO) {
            runCatching { File(asset.path).canonicalPath }.getOrNull()
        }
    }
    val compatibleSources = sources.filter { source ->
        ModelFamily.fromStoredValue(source.family) == defaultFamily ||
            (defaultFamily == ModelFamily.SD && ModelFamily.fromStoredValue(source.family) == ModelFamily.LLM)
    }
    var selectedSourceId by remember(asset.stableId, provenance, isDirectory, canonicalPath) {
        mutableStateOf(
            findProvenanceForAsset(
                asset = asset,
                provenance = provenance,
                requireExactPath = isDirectory != false,
                canonicalPath = canonicalPath
            )?.sourceId
        )
    }
    var role by remember(asset.stableId, defaultRole) { mutableStateOf(defaultRole.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(androidx.compose.ui.res.stringResource(R.string.model_library_link_source_title)) },
        text = {
                Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(asset.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.model_library_link_source_help, modelFamilyLabel(defaultFamily)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ModelComponentPicker(
                    family = defaultFamily, role = role, onSelect = { role = it }, enabled = !busy
                )
                if (compatibleSources.isEmpty()) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.model_library_no_compatible_sources),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    compatibleSources.forEach { source ->
                        val selected = selectedSourceId == source.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(role = androidx.compose.ui.semantics.Role.RadioButton) {
                                    selectedSourceId = source.id
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RadioButton(selected = selected, onClick = { selectedSourceId = source.id })
                            Column(modifier = Modifier.weight(1f)) {
                                Text(source.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    text = if (source.isVerifiedForDisplay()) {
                                        androidx.compose.ui.res.stringResource(R.string.model_library_source_verified)
                                    } else {
                                        androidx.compose.ui.res.stringResource(R.string.model_library_source_unverified_short)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (source.isVerifiedForDisplay()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { selectedSourceId?.let { onSave(it, role.trim().takeIf(String::isNotBlank)) } },
                enabled = !busy && selectedSourceId != null
            ) { Text(androidx.compose.ui.res.stringResource(R.string.model_library_save_link)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(R.string.model_library_cancel)) }
        }
    )
}

/** Compatibility wrapper retained for callers that only have a ModelEntity. */
@Composable
internal fun LinkInstalledModelDialog(
    model: ModelEntity,
    sources: List<ModelSourceEntity>,
    provenance: List<ModelProvenanceEntity>,
    defaultFamily: ModelFamily,
    defaultRole: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String?) -> Unit
) {
    LinkInstalledAssetDialog(
        asset = InstalledModelAsset.fromModel(model, defaultFamily, defaultRole),
        sources = sources,
        provenance = provenance,
        busy = busy,
        onDismiss = onDismiss,
        onSave = onSave
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PendingArtifactCard(
    artifact: PendingModelArtifactEntity,
    busy: Boolean,
    onInspect: () -> Unit,
    onPromote: () -> Unit,
    onCancel: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onDiscard: (() -> Unit)? = null
) {
    val status = PendingArtifactStatus.fromStoredValue(artifact.status)
    var showDetails by rememberSaveable(artifact.id) { mutableStateOf(false) }
    val sizeBytes by produceState<Long?>(null, artifact.id, artifact.updatedAt) {
        value = withContext(Dispatchers.IO) {
            sequenceOf(artifact.destinationPath, artifact.stagingPath, "${artifact.stagingPath}.part")
                .filterNotNull().map(::File).firstOrNull { it.isFile }?.length()
        }
    }
    if (showDetails) {
        val emptyRecord = androidx.compose.ui.res.stringResource(R.string.model_artifact_no_inspection)
        com.example.llamadroid.ui.components.AppTextDetailsDialog(
            title = androidx.compose.ui.res.stringResource(R.string.model_artifact_inspection_record),
            text = listOfNotNull(artifact.filename, artifact.stagingPath,
                artifact.validationMessage, artifact.validationJson).joinToString("\n\n") +
                if (artifact.validationMessage == null && artifact.validationJson == null) "\n\n$emptyRecord" else "",
            onDismiss = { showDetails = false }
        )
    }
    AppSectionCard {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.PendingActions, null, tint = MaterialTheme.colorScheme.secondary)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(artifact.filename, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                }
                Text(
                    text = "${pendingStatusLabel(status)} · ${modelFamilyLabel(ModelFamily.fromStoredValue(artifact.requestedFamily ?: artifact.detectedFamily) ?: ModelFamily.LLM)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(artifact.stagingPath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                artifact.requestedRole?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.model_library_requested_role, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                sizeBytes?.let { bytes ->
                    Text(com.example.llamadroid.util.FormatUtils.Display.formatBytes(
                        androidx.compose.ui.platform.LocalContext.current, bytes),
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TextButton(onClick = { showDetails = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(androidx.compose.ui.res.stringResource(R.string.model_artifact_details))
            }
            if (status !in setOf(PendingArtifactStatus.CANCELLED, PendingArtifactStatus.FAILED,
                    PendingArtifactStatus.PROMOTED, PendingArtifactStatus.STAGED, PendingArtifactStatus.INSPECTING)) {
                TextButton(onClick = onInspect, enabled = !busy) { Text(androidx.compose.ui.res.stringResource(R.string.model_library_inspect)) }
            }
            if (onCancel != null && status in setOf(PendingArtifactStatus.STAGED,
                    PendingArtifactStatus.INSPECTING)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(androidx.compose.ui.res.stringResource(R.string.action_cancel))
                }
            }
            if (onRetry != null && status in setOf(PendingArtifactStatus.CANCELLED, PendingArtifactStatus.FAILED)) {
                Button(onClick = onRetry, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(androidx.compose.ui.res.stringResource(R.string.action_retry))
                }
            }
            if (onDiscard != null &&
                ModelArtifactDiscardPolicy.isDiscardableStatus(artifact.status) &&
                status !in setOf(PendingArtifactStatus.STAGED, PendingArtifactStatus.INSPECTING)
            ) {
                OutlinedButton(onClick = onDiscard, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(androidx.compose.ui.res.stringResource(R.string.model_library_discard_artifact))
                }
            }
            if (status == PendingArtifactStatus.VALIDATED || status == PendingArtifactStatus.NEEDS_MANUAL_PROMOTION) {
                Button(onClick = onPromote, enabled = !busy) {
                    Text(androidx.compose.ui.res.stringResource(R.string.model_library_promote))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelBundleCard(
    bundle: ModelBundleEntity,
    repository: ModelSourceRepository,
    sources: List<ModelSourceEntity>,
    busy: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onVerify: (List<String>) -> Unit,
    onEdit: (ModelBundleEntity, List<ModelBundleItemEntity>) -> Unit,
    onDelete: () -> Unit
) {
    val items by repository.observeBundleItems(bundle.id).collectAsStateWithLifecycle(emptyList())
    val incompleteGroupIds = incompleteBundleGroupIds(items)
    val missingSourceCount = items.count { item ->
        if (!item.required) return@count false
        val source = item.sourceId?.let { sourceId -> sources.firstOrNull { it.id == sourceId } }
        source == null || source.resolvedDownloadUrl() == null
    }
    val unverifiedSourceIds = items.filter { it.required }.mapNotNull { it.sourceId }.distinct().filter { sourceId ->
        val source = sources.firstOrNull { it.id == sourceId }
        source?.resolvedDownloadUrl() != null && source.isVerifiedForDisplay() != true
    }
    val invalidRequiredCount = items.count { it.required && it.id in incompleteGroupIds }
    val ready = items.isNotEmpty() && missingSourceCount == 0 &&
        unverifiedSourceIds.isEmpty() && invalidRequiredCount == 0
    AppSectionCard {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Inventory2, null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(bundle.name, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(modelFamilyLabel(ModelFamily.fromStoredValue(bundle.family) ?: ModelFamily.LLM), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                bundle.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(
            text = androidx.compose.ui.res.stringResource(R.string.model_library_bundle_items, items.size),
            style = MaterialTheme.typography.labelLarge
        )
        when {
            missingSourceCount > 0 -> Text(
                text = androidx.compose.ui.res.stringResource(R.string.model_library_bundle_needs_source, missingSourceCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error
            )
            unverifiedSourceIds.isNotEmpty() -> Text(
                text = androidx.compose.ui.res.stringResource(R.string.model_library_bundle_needs_verification, unverifiedSourceIds.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error
            )
            invalidRequiredCount > 0 -> Text(
                text = androidx.compose.ui.res.stringResource(R.string.model_library_bundle_invalid_groups, invalidRequiredCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error
            )
            ready -> Text(
                text = androidx.compose.ui.res.stringResource(R.string.model_library_bundle_ready),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items.forEach { item ->
                val source = item.sourceId?.let { sourceId -> sources.firstOrNull { it.id == sourceId } }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = item.relativePath?.takeIf { it.isNotBlank() }
                                ?: item.localFilename?.takeIf { it.isNotBlank() }
                                ?: item.itemKey,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        source?.let {
                            Text(
                                text = bundleSourceIdentity(it),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = when {
                                source == null || source.resolvedDownloadUrl() == null -> androidx.compose.ui.res.stringResource(R.string.model_library_needs_source_short)
                                source.isVerifiedForDisplay() -> androidx.compose.ui.res.stringResource(R.string.model_library_source_verified)
                                else -> androidx.compose.ui.res.stringResource(R.string.model_library_source_unverified_short)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (source?.isVerifiedForDisplay() == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                    item.role?.takeIf { it.isNotBlank() }?.let { role ->
                        Text(role, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(onClick = onStart, enabled = !busy && ready) {
                Icon(Icons.Default.CloudDownload, null)
                Spacer(Modifier.width(8.dp))
                Text(androidx.compose.ui.res.stringResource(R.string.model_library_download_missing))
            }
            if (unverifiedSourceIds.isNotEmpty()) {
                TextButton(onClick = { onVerify(unverifiedSourceIds) }, enabled = !busy) {
                    Icon(Icons.Default.Verified, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(androidx.compose.ui.res.stringResource(R.string.model_library_verify_links))
                }
            }
            TextButton(onClick = onCancel, enabled = items.isNotEmpty()) {
                Text(androidx.compose.ui.res.stringResource(R.string.model_library_cancel_download))
            }
            TextButton(onClick = { onEdit(bundle, items) }, enabled = !busy) {
                Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(androidx.compose.ui.res.stringResource(R.string.model_library_edit))
            }
            TextButton(onClick = onDelete, enabled = !busy) {
                Icon(Icons.Default.DeleteOutline, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(androidx.compose.ui.res.stringResource(R.string.model_library_delete_definition))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BundleEditorDialog(
    existing: ModelBundleEntity?,
    existingItems: List<ModelBundleItemEntity>,
    installedModels: List<ModelEntity>,
    installedLiteRtModels: List<LiteRtModelEntity>,
    sources: List<ModelSourceEntity>,
    provenance: List<ModelProvenanceEntity>,
    viewModel: ModelLibraryViewModel,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (ModelBundleEntity, List<ModelBundleItemEntity>) -> Unit,
    initialFamily: ModelFamily = ModelFamily.LLM
) {
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var description by rememberSaveable(existing?.id) { mutableStateOf(existing?.description.orEmpty()) }
    var family by rememberSaveable(existing?.id, stateSaver = Saver<ModelFamily, String>(
        save = { it.storedValue }, restore = { ModelFamily.fromStoredValue(it) ?: ModelFamily.LLM })) {
        mutableStateOf(ModelFamily.fromStoredValue(existing?.family) ?: initialFamily)
    }
    var familyMenuExpanded by remember(existing?.id) { mutableStateOf(false) }
    var role by rememberSaveable(existing?.id) {
        mutableStateOf(existingItems.firstOrNull { !it.role.isNullOrBlank() }?.role.orEmpty())
    }
    var showAddLink by rememberSaveable(existing?.id) { mutableStateOf(false) }
    var newLinkUrl by rememberSaveable(existing?.id) { mutableStateOf("") }
    var newLinkLabel by rememberSaveable(existing?.id) { mutableStateOf("") }
    var linkError by rememberSaveable(existing?.id) { mutableStateOf(false) }
    val linkErrorText = androidx.compose.ui.res.stringResource(R.string.model_source_invalid_link)
    val installedAssets = remember(installedModels, installedLiteRtModels) {
        installedModels.map { model ->
            InstalledModelAsset.fromModel(
                model = model,
                family = viewModel.familyForModel(model),
                role = viewModel.roleForModel(model)
            )
        } + installedLiteRtModels.map(InstalledModelAsset::fromLiteRt)
    }
    var selectedModelKeys by rememberSaveable(existing?.id, stateSaver = Saver<Set<String>, List<String>>(
        save = { it.toList() }, restore = { it.toSet() })) {
        val selectedRows = existingItems.map { it.itemKey }.toSet()
        val selectedDirectoryRoots = installedAssets
            .filter { asset -> existingItems.any { item ->
                item.itemKey == asset.stableId || item.itemKey.startsWith("${asset.stableId}:")
            } }
            .map { it.stableId }
        mutableStateOf((selectedRows + selectedDirectoryRoots).toSet())
    }
    var selectedSourceIds by rememberSaveable(existing?.id, stateSaver = Saver<Set<String>, List<String>>(
        save = { it.toList() }, restore = { it.toSet() })) {
        mutableStateOf(existingItems.mapNotNull { it.sourceId }.toSet())
    }
    var sourceRoles by rememberSaveable(existing?.id, stateSaver = Saver<Map<String, String>, List<String>>(
        save = { map -> map.flatMap { (sourceId, sourceRole) -> listOf(sourceId, sourceRole) } },
        restore = { values -> values.chunked(2).mapNotNull { pair ->
            pair.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { key ->
                pair.getOrNull(1)?.let { value -> key to value }
            }
        }.toMap() }
    )) {
        val itemRoles = existingItems.mapNotNull { item ->
            item.sourceId?.let { sourceId ->
                item.role?.trim()?.takeIf { it.isNotBlank() }?.let { sourceId to it }
            }
        }.toMap()
        val inferredRoles = provenance
            .groupBy { it.sourceId }
            .mapNotNull { (sourceId, edges) ->
                edges.mapNotNull { it.role?.trim()?.takeIf(String::isNotBlank) }
                    .distinct()
                    .singleOrNull()
                    ?.let { sourceId to it }
            }
            .toMap()
        mutableStateOf(inferredRoles + itemRoles)
    }
    var sourceRelativePaths by rememberSaveable(existing?.id, stateSaver = Saver<Map<String, String>, List<String>>(
        save = { map -> map.flatMap { (sourceId, relativePath) -> listOf(sourceId, relativePath) } },
        restore = { values -> values.chunked(2).mapNotNull { pair ->
            pair.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { sourceId ->
                pair.getOrNull(1)?.let { relativePath -> sourceId to relativePath }
            }
        }.toMap() }
    )) {
        val inferredPaths = existingItems
            .mapNotNull { item ->
                item.sourceId?.let { sourceId ->
                    item.relativePath?.trim()?.takeIf { it.isNotBlank() }?.let { sourceId to it }
                }
            }
            .groupBy { it.first }
            .mapNotNull { (sourceId, entries) ->
                entries.map { it.second }.distinct().singleOrNull()?.let { sourceId to it }
            }
            .toMap()
        mutableStateOf(inferredPaths)
    }
    val availableAssets = installedAssets.filter { asset ->
        asset.family == family || (
            family == ModelFamily.SD &&
                asset.family == ModelFamily.LLM &&
                (asset.role == "llm" || asset.role == "llm_vision" ||
                    asset.role == "mmproj" || asset.role == "vision_projector")
            )
    }
    // Source family is provenance. The bundle item role is the explicit
    // compatibility decision, so every saved source remains selectable here.
    val availableSources = sources.filter { it.resolvedDownloadUrl() != null }
    var directoryMembers by remember(existing?.id, availableAssets) {
        mutableStateOf<Map<String, List<BundleDirectoryMember>>>(emptyMap())
    }
    var directoryRoots by remember(existing?.id, availableAssets) {
        mutableStateOf<Map<String, String>>(emptyMap())
    }
    var splitBundleMembers by remember(existing?.id, availableAssets) {
        mutableStateOf<Map<String, List<BundleSplitMember>>>(emptyMap())
    }
    var directoryScanComplete by remember(existing?.id, availableAssets) { mutableStateOf(false) }
    val directoryScanKey = remember(availableAssets) {
        availableAssets.map { asset -> "${asset.stableId}\u0000${asset.path}" }
    }
    LaunchedEffect(directoryScanKey) {
        directoryScanComplete = false
        val (scannedDirectories, scannedRoots, scannedSplits) = withContext(Dispatchers.IO) {
            val directories = mutableMapOf<String, List<BundleDirectoryMember>>()
            val roots = mutableMapOf<String, String>()
            val splits = mutableMapOf<String, List<BundleSplitMember>>()
            availableAssets.forEach { asset ->
                val root = installedAssetDirectoryRoot(asset)
                if (root != null) {
                    val canonicalRoot = runCatching { root.canonicalFile }.getOrNull()
                    if (canonicalRoot != null) roots[asset.stableId] = canonicalRoot.absolutePath
                    directories[asset.stableId] = listBundleDirectoryMembers(
                        root = root,
                        preferredEntry = asset.path.takeIf { asset.isLiteRt }
                    ).map { member ->
                        BundleDirectoryMember(
                            relativePath = member,
                            localPath = canonicalRoot?.let { directory ->
                                runCatching { File(directory, member).canonicalPath }.getOrNull()
                            }
                        )
                    }
                } else {
                    val relativePath = modelBundleRelativePath(asset.filename)
                    val parts = listExistingSplitBundleParts(asset.path, relativePath)
                    if (parts.isNotEmpty()) {
                        splits[asset.stableId] = parts.map { part ->
                            BundleSplitMember(
                                filePath = part.filePath,
                                relativePath = part.relativePath,
                                partGroup = part.partGroup,
                                partIndex = part.partIndex,
                                partCount = part.partCount
                            )
                        }
                    }
                }
            }
            Triple(directories, roots, splits)
        }
        directoryMembers = scannedDirectories
        directoryRoots = scannedRoots
        splitBundleMembers = scannedSplits
        directoryScanComplete = true
    }
    fun builderInput(bundleId: String): BundleItemBuilderInput = BundleItemBuilderInput(
        bundleId = bundleId,
        family = family,
        existingItems = existingItems,
        selectedModelKeys = selectedModelKeys,
        selectedSourceIds = selectedSourceIds,
        sourceRoles = sourceRoles,
        sourceRelativePaths = sourceRelativePaths,
        defaultRole = role.trim().takeIf { it.isNotBlank() },
        installedAssets = availableAssets,
        availableSources = availableSources,
        provenance = provenance,
        directoryRoots = directoryRoots,
        directoryMembers = directoryMembers,
        splitBundleMembers = splitBundleMembers
    )

    val bundleBuildPreview = buildBundleItems(builderInput(existing?.id ?: "draft"))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(androidx.compose.ui.res.stringResource(if (existing == null) R.string.model_library_create_bundle else R.string.model_library_edit_bundle))
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(androidx.compose.ui.res.stringResource(R.string.model_library_bundle_name)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(androidx.compose.ui.res.stringResource(R.string.model_library_bundle_description)) },
                    minLines = 2,
                    maxLines = 4
                )
                Text(androidx.compose.ui.res.stringResource(R.string.model_library_family), style = MaterialTheme.typography.labelLarge)
                androidx.compose.foundation.layout.Box {
                    OutlinedButton(
                        onClick = { familyMenuExpanded = true },
                        enabled = existing == null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(modelFamilyLabel(family), modifier = Modifier.weight(1f))
                    }
                    DropdownMenu(expanded = familyMenuExpanded, onDismissRequest = { familyMenuExpanded = false }) {
                        ModelFamily.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(modelFamilyLabel(option)) },
                                onClick = { family = option; familyMenuExpanded = false }
                            )
                        }
                    }
                }
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.model_library_bundle_installed_heading),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                if (availableAssets.isEmpty()) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.model_library_bundle_no_installed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    availableAssets.forEach { asset ->
                        val selected = asset.stableId in selectedModelKeys
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                selectedModelKeys = if (selected) selectedModelKeys - asset.stableId else selectedModelKeys + asset.stableId
                            },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(
                                checked = selected,
                                onCheckedChange = { checked ->
                                    selectedModelKeys = if (checked) selectedModelKeys + asset.stableId else selectedModelKeys - asset.stableId
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(asset.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    text = androidx.compose.ui.res.stringResource(
                                        R.string.model_library_bundle_model_metadata,
                                        asset.model?.type?.name ?: modelFamilyLabel(asset.family)
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                directoryMembers[asset.stableId]?.let { members ->
                                    Text(
                                        text = androidx.compose.ui.res.stringResource(
                                            R.string.model_library_bundle_directory_members,
                                            members.size
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.model_library_bundle_sources_heading),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                OutlinedButton(onClick = { showAddLink = !showAddLink }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(androidx.compose.ui.res.stringResource(R.string.model_bundle_add_link))
                }
                if (showAddLink) {
                    OptionalModelSourceFields(
                        family, newLinkUrl, { newLinkUrl = it; linkError = false },
                        newLinkLabel, { newLinkLabel = it }, error = linkErrorText.takeIf { linkError }
                    )
                    Button(onClick = {
                        val draft = optionalModelSourceDraft(family, newLinkUrl, newLinkLabel).getOrNull()
                        if (draft == null) {
                            linkError = true
                        } else {
                            viewModel.saveBundleSource(draft) { source ->
                                selectedSourceIds = selectedSourceIds + source.id
                                newLinkUrl = ""
                                newLinkLabel = ""
                                showAddLink = false
                            }
                        }
                    }, enabled = !busy && newLinkUrl.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(androidx.compose.ui.res.stringResource(R.string.model_bundle_save_check_link))
                    }
                }
                if (availableSources.isEmpty()) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.model_library_bundle_no_sources),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    availableSources.forEach { source ->
                        val selected = source.id in selectedSourceIds
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                selectedSourceIds = if (selected) selectedSourceIds - source.id else selectedSourceIds + source.id
                            },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(
                                checked = selected,
                                onCheckedChange = { checked ->
                                    selectedSourceIds = if (checked) selectedSourceIds + source.id else selectedSourceIds - source.id
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = bundleSourceIdentity(source),
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = sourceStatusLabel(source),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (source.isVerifiedForDisplay()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                                if (selected) {
                                    OutlinedTextField(
                                        value = sourceRelativePaths[source.id].orEmpty(),
                                        onValueChange = { value ->
                                            sourceRelativePaths = sourceRelativePaths + (source.id to value)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = {
                                            Text(androidx.compose.ui.res.stringResource(
                                                R.string.model_library_bundle_source_path_for,
                                                source.label
                                            ))
                                        },
                                        placeholder = {
                                            Text(androidx.compose.ui.res.stringResource(
                                                R.string.model_library_bundle_source_path_optional
                                            ))
                                        },
                                        singleLine = true
                                    )
                                    ModelComponentPicker(
                                        family = family,
                                        role = sourceRoles[source.id].orEmpty(),
                                        onSelect = { sourceRoles = sourceRoles + (source.id to it) },
                                        enabled = !busy,
                                        label = androidx.compose.ui.res.stringResource(
                                            R.string.model_library_bundle_source_role_for, source.label
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                ModelComponentPicker(
                    family = family, role = role, onSelect = { role = it }, enabled = !busy,
                    label = androidx.compose.ui.res.stringResource(R.string.model_library_bundle_source_role)
                )
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.model_library_bundle_draft_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                bundleBuildPreview.conflicts.forEach { conflict ->
                    val message = when (conflict.code) {
                        BundleItemBuildConflictCode.AMBIGUOUS_SOURCE_TARGET ->
                            androidx.compose.ui.res.stringResource(
                                R.string.model_library_bundle_source_ambiguous,
                                conflict.relativePath.orEmpty(),
                                conflict.candidateCount
                            )
                        BundleItemBuildConflictCode.SOURCE_PATH_COLLISION ->
                            androidx.compose.ui.res.stringResource(
                                R.string.model_library_bundle_source_collision,
                                conflict.relativePath.orEmpty()
                            )
                        BundleItemBuildConflictCode.INCOMPATIBLE_SOURCE_FAMILY ->
                            androidx.compose.ui.res.stringResource(R.string.model_library_bundle_source_incompatible)
                        BundleItemBuildConflictCode.SOURCE_ROLE_REQUIRED ->
                            androidx.compose.ui.res.stringResource(R.string.model_library_bundle_source_role_required)
                        BundleItemBuildConflictCode.INVALID_SOURCE_PATH ->
                            androidx.compose.ui.res.stringResource(R.string.model_library_bundle_source_path_invalid)
                        BundleItemBuildConflictCode.DUPLICATE_ITEM_KEY ->
                            androidx.compose.ui.res.stringResource(R.string.model_library_bundle_duplicate_item_key)
                        BundleItemBuildConflictCode.DUPLICATE_RELATIVE_PATH ->
                            androidx.compose.ui.res.stringResource(
                                R.string.model_library_bundle_duplicate_relative_path,
                                conflict.relativePath.orEmpty()
                            )
                    }
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val bundle = existing?.copy(
                        name = name.trim(),
                        description = description.trim().takeIf { it.isNotBlank() },
                        family = family.storedValue,
                        updatedAt = System.currentTimeMillis()
                    ) ?: ModelBundleEntity(
                        name = name.trim(),
                        family = family.storedValue,
                        description = description.trim().takeIf { it.isNotBlank() }
                    )
                    onSave(bundle, buildBundleItems(builderInput(bundle.id)).items)
                },
                enabled = !busy && directoryScanComplete && name.isNotBlank() && bundleBuildPreview.isValid
            ) { Text(androidx.compose.ui.res.stringResource(R.string.model_library_save_bundle)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(R.string.model_library_cancel)) }
        }
    )
}

private data class BundlePart(
    val group: String,
    val index: Int,
    val count: Int
)

/** One real shard found beside an installed split model. */
internal data class ExistingSplitBundlePart(
    val filePath: String,
    val relativePath: String,
    val partGroup: String,
    val partIndex: Int,
    val partCount: Int
)

/** Keeps nested HF paths and directory names portable without retaining device roots. */
private fun modelBundleRelativePath(raw: String): String = raw
    .trim()
    .replace('\\', '/')
    .trim('/')
    .split('/')
    .filter { it.isNotBlank() && it != "." && it != ".." }
    .joinToString("/") { it.replace('\u0000', '_') }

/**
 * Resolves an installed package root without turning the global LiteRT model
 * directory into one bundle. LiteRT rows point at their engine file, so only
 * its immediate known bundle directory is eligible for sibling discovery.
 */
private fun installedAssetDirectoryRoot(asset: InstalledModelAsset): File? {
    val target = runCatching { File(asset.path).canonicalFile }.getOrNull() ?: return null
    if (target.isDirectory) return target
    if (!asset.isLiteRt) return null
    val parent = target.parentFile?.canonicalFile ?: return null
    return parent.takeIf { it.parentFile?.name == "litert_models" }
}

/** Reads directory-package members off the main thread before the editor persists them. */
private fun listBundleDirectoryMembers(root: File, preferredEntry: String? = null): List<String> {
    val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return emptyList()
    val members = canonicalRoot.walkTopDown()
        .filter { it.isFile && !it.name.endsWith(".part") }
        .mapNotNull { file ->
            val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return@mapNotNull null
            if (canonical.absolutePath == canonicalRoot.absolutePath ||
                !canonical.absolutePath.startsWith(canonicalRoot.absolutePath + File.separator)
            ) return@mapNotNull null
            runCatching { canonical.relativeTo(canonicalRoot).invariantSeparatorsPath }.getOrNull()
        }
        .map { path -> modelBundleRelativePath(path) }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
        .toMutableList()
    val preferredRelative = preferredEntry?.let { path ->
        val preferred = runCatching { File(path).canonicalFile }.getOrNull() ?: return@let null
        if (!preferred.absolutePath.startsWith(canonicalRoot.absolutePath + File.separator)) return@let null
        runCatching { preferred.relativeTo(canonicalRoot).invariantSeparatorsPath }
            .getOrNull()
            ?.let(::modelBundleRelativePath)
    }
    if (!preferredRelative.isNullOrBlank() && members.remove(preferredRelative)) {
        members.add(0, preferredRelative)
    }
    return members
}

/**
 * Enumerates only existing sibling files for a conventional GGUF shard name.
 * Missing declared parts stay missing through [partCount], so the bundle card
 * remains a draft instead of silently treating one shard as a runnable model.
 */
internal fun listExistingSplitBundleParts(
    assetPath: String,
    relativePath: String
): List<ExistingSplitBundlePart> {
    val target = runCatching { File(assetPath).canonicalFile }.getOrNull() ?: return emptyList()
    val targetPart = splitBundlePart(modelBundleRelativePath(relativePath)) ?: return emptyList()
    val parent = target.parentFile ?: return emptyList()
    val relativeParent = modelBundleRelativePath(relativePath.substringBeforeLast('/', ""))
    return parent.listFiles()
        .orEmpty()
        .asSequence()
        .filter { it.isFile }
        .mapNotNull { candidate ->
            val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return@mapNotNull null
            val candidateRelative = modelBundleRelativePath(
                if (relativeParent.isBlank()) candidate.name else "$relativeParent/${candidate.name}"
            )
            val candidatePart = splitBundlePart(candidateRelative) ?: return@mapNotNull null
            if (candidatePart.group != targetPart.group || candidatePart.count != targetPart.count) {
                return@mapNotNull null
            }
            ExistingSplitBundlePart(
                filePath = canonical.absolutePath,
                relativePath = candidateRelative,
                partGroup = candidatePart.group,
                partIndex = candidatePart.index,
                partCount = candidatePart.count
            )
        }
        // Prefer the installed row's exact file when duplicate zero-padded
        // spellings describe the same shard, then keep one path per index.
        .sortedWith(compareBy<ExistingSplitBundlePart> {
            if (it.filePath == target.absolutePath) 0 else 1
        }.thenBy { it.partIndex }.thenBy { it.relativePath })
        .distinctBy { it.partIndex }
        .sortedBy { it.partIndex }
        .toList()
}

/** Recognizes the conventional GGUF shard suffix while retaining its relationship. */
private fun splitBundlePart(relativePath: String): BundlePart? {
    val filename = relativePath.substringAfterLast('/')
    val match = Regex("^(.*?)[-_](\\d{1,6})-of-(\\d{1,6})(\\.[^/]*)?$").matchEntire(filename)
        ?: return null
    val index = match.groupValues[2].toIntOrNull()?.minus(1) ?: return null
    val count = match.groupValues[3].toIntOrNull() ?: return null
    if (count <= 0 || index !in 0 until count) return null
    val parent = relativePath.substringBeforeLast('/', "")
    val extension = match.groupValues[4]
    val groupName = match.groupValues[1] + extension
    return BundlePart(
        group = if (parent.isBlank()) groupName else "$parent/$groupName",
        index = index,
        count = count
    )
}

@Composable
internal fun HfFolderDialog(
    listing: HfFolderListing,
    busy: Boolean = false,
    onDismiss: () -> Unit,
    canGoUp: Boolean,
    onGoUp: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onLoadMore: () -> Unit = {},
    onSelectFile: (HfTreeItemDto) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(androidx.compose.ui.res.stringResource(R.string.model_library_hf_browser_title, listing.repositoryId)) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.model_library_browser_path, listing.folderPath.ifBlank { "/" }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                item {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.model_library_browser_pages, listing.pagesFetched),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (canGoUp) {
                    item {
                        OutlinedButton(onClick = onGoUp, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Icon(Icons.Default.ArrowBack, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(androidx.compose.ui.res.stringResource(R.string.model_library_browser_parent))
                        }
                    }
                }
                if (listing.nextCursor != null) {
                    item {
                        OutlinedButton(
                            onClick = onLoadMore,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(androidx.compose.ui.res.stringResource(R.string.model_library_browser_load_more))
                        }
                    }
                } else if (listing.truncated) {
                    item {
                        Text(
                            text = androidx.compose.ui.res.stringResource(R.string.model_library_browser_truncated),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                if (listing.items.isEmpty()) {
                    item { Text(androidx.compose.ui.res.stringResource(R.string.model_library_browser_empty)) }
                } else {
                    items(listing.items, key = { it.path }) { item ->
                        HfTreeItemRow(item, onOpenFolder, onSelectFile)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(R.string.model_library_close)) }
        }
    )
}

@Composable
private fun HfTreeItemRow(
    item: HfTreeItemDto,
    onOpenFolder: (String) -> Unit,
    onSelectFile: (HfTreeItemDto) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (item.type == "directory") Icons.Default.FolderOpen else Icons.Default.Link, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            item.path,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (item.type == "directory") {
            TextButton(onClick = { onOpenFolder(item.path) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(16.dp))
                Text(androidx.compose.ui.res.stringResource(R.string.model_library_browser_open))
            }
        } else {
            Column(horizontalAlignment = Alignment.End) {
                if (item.size > 0L) {
                    Text(formatBytes(item.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { onSelectFile(item) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(androidx.compose.ui.res.stringResource(R.string.model_library_browser_use_file))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PromoteArtifactDialog(
    artifact: PendingModelArtifactEntity,
    busy: Boolean,
    onDismiss: () -> Unit,
    onPromote: (ModelFamily, String, String?, String) -> Unit,
    savedMetadataJson: String? = null
) {
    val savedMetadata = remember(artifact.id, savedMetadataJson) {
        JSONObject(PortableModelMetadata.sanitize(savedMetadataJson))
    }
    var family by remember(artifact.id) {
        mutableStateOf(ModelFamily.fromStoredValue(if (artifact.bundleId != null)
            artifact.requestedFamily ?: artifact.detectedFamily else artifact.detectedFamily ?: artifact.requestedFamily) ?: ModelFamily.LLM)
    }
    var name by remember(artifact.id) { mutableStateOf(artifact.filename.substringBeforeLast('.')) }
    val typeChoices = remember(family) { modelPromotionChoices(family) }
    var choiceId by remember(artifact.id, family, savedMetadataJson) {
        mutableStateOf(initialModelPromotionChoice(family, artifact, savedMetadataJson).id)
    }
    val selectedType = typeChoices.firstOrNull { it.id == choiceId } ?: typeChoices.first()
    val inspected = remember(artifact.id) { com.example.llamadroid.sd.SdArtifactInspection.fromJson(artifact.validationJson) }
    var sdFamily by remember(artifact.id, savedMetadataJson) { mutableStateOf(savedMetadata.optString("sdFamily").ifBlank { inspected?.detectedFamily?.storedValue.orEmpty() }) }
    var sdVariant by remember(artifact.id, savedMetadataJson) { mutableStateOf(savedMetadata.optString("sdVariant").ifBlank { inspected?.detectedVariant.orEmpty() }) }
    var compatibility by remember(artifact.id, savedMetadataJson) { mutableStateOf(savedMetadata.optString("sdCompatProfiles")) }
    var onnxPipeline by remember(artifact.id, savedMetadataJson) { mutableStateOf(savedMetadata.optString("onnxPipelineFamily")) }
    var liteRtBackend by remember(artifact.id, savedMetadataJson) { mutableStateOf(savedMetadata.optString("liteRtBackend").takeIf { it in setOf("auto", "cpu", "gpu") } ?: "auto") }
    var supportsVision by remember(artifact.id, savedMetadataJson) { mutableStateOf(savedMetadata.optBoolean("supportsVision", false)) }
    var supportsAudio by remember(artifact.id, savedMetadataJson) { mutableStateOf(savedMetadata.optBoolean("supportsAudio", false)) }
    var supportsEmbedding by remember(artifact.id, savedMetadataJson) { mutableStateOf(savedMetadata.optBoolean("supportsEmbedding", false)) }
    var whisperVariant by remember(artifact.id, savedMetadataJson) { mutableStateOf(savedMetadata.optString("whisperVariant")) }
    var familyMenuExpanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(androidx.compose.ui.res.stringResource(R.string.model_library_promote_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(artifact.filename, style = MaterialTheme.typography.labelLarge)
                }
                androidx.compose.foundation.layout.Box {
                    OutlinedButton(onClick = { familyMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(modelFamilyLabel(family), modifier = Modifier.weight(1f))
                    }
                    DropdownMenu(expanded = familyMenuExpanded, onDismissRequest = { familyMenuExpanded = false }) {
                        ModelFamily.entries.forEach { option ->
                            DropdownMenuItem(text = { Text(modelFamilyLabel(option)) }, onClick = { family = option; familyMenuExpanded = false })
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(androidx.compose.ui.res.stringResource(R.string.model_library_promote_name)) },
                    placeholder = { Text(androidx.compose.ui.res.stringResource(R.string.model_library_promote_name_hint)) },
                    singleLine = true
                )
                ModelPromotionDropdown(
                    label = androidx.compose.ui.res.stringResource(R.string.model_library_promote_type),
                    selectedLabel = androidx.compose.ui.res.stringResource(selectedType.labelRes),
                    choices = typeChoices.map { it.id to androidx.compose.ui.res.stringResource(it.labelRes) },
                    onSelect = { choiceId = it }
                )
                OutlinedTextField(
                    value = compatibility,
                    onValueChange = { compatibility = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(androidx.compose.ui.res.stringResource(R.string.model_library_compatibility)) },
                    placeholder = { Text(androidx.compose.ui.res.stringResource(R.string.model_library_compatibility_hint)) },
                    singleLine = true,
                    supportingText = { Text(androidx.compose.ui.res.stringResource(R.string.model_library_compatibility_help)) }
                )
                if (family == ModelFamily.SD) {
                    val families = com.example.llamadroid.sd.SdModelFamily.entries.map {
                        it.storedValue to androidx.compose.ui.res.stringResource(com.example.llamadroid.ui.ai.sdFamilyLabelRes(it))
                    }
                    ModelPromotionDropdown(androidx.compose.ui.res.stringResource(R.string.model_library_sd_family),
                        families.firstOrNull { it.first == sdFamily }?.second
                            ?: androidx.compose.ui.res.stringResource(R.string.model_promote_detect_family),
                        families) { sdFamily = it }
                    OutlinedTextField(
                        value = sdVariant,
                        onValueChange = { sdVariant = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(androidx.compose.ui.res.stringResource(R.string.model_library_sd_variant)) },
                        singleLine = true
                    )
                }
                if (family == ModelFamily.ONNX) {
                    OutlinedTextField(
                        value = onnxPipeline,
                        onValueChange = { onnxPipeline = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(androidx.compose.ui.res.stringResource(R.string.model_library_onnx_pipeline)) },
                        singleLine = true
                    )
                }
                if (family == ModelFamily.LITERT) {
                    val backends = listOf("auto" to androidx.compose.ui.res.stringResource(R.string.model_promote_auto),
                        "cpu" to androidx.compose.ui.res.stringResource(R.string.model_promote_cpu),
                        "gpu" to androidx.compose.ui.res.stringResource(R.string.litert_backend_gpu))
                    ModelPromotionDropdown(androidx.compose.ui.res.stringResource(R.string.model_library_litert_backend),
                        backends.first { it.first == liteRtBackend }.second, backends) { liteRtBackend = it }
                    ModelPromotionToggle(
                        androidx.compose.ui.res.stringResource(R.string.litert_models_modality_vision), supportsVision) { supportsVision = it }
                    ModelPromotionToggle(
                        androidx.compose.ui.res.stringResource(R.string.litert_models_modality_audio), supportsAudio) { supportsAudio = it }
                    ModelPromotionToggle(
                        androidx.compose.ui.res.stringResource(R.string.litert_models_modality_embedding), supportsEmbedding) { supportsEmbedding = it }
                }
                if (family == ModelFamily.WHISPER) {
                    OutlinedTextField(
                        value = whisperVariant,
                        onValueChange = { whisperVariant = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(androidx.compose.ui.res.stringResource(R.string.model_library_whisper_variant)) },
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val metadata = JSONObject(PortableModelMetadata.sanitize(savedMetadataJson)).apply {
                        put("modelType", selectedType.type.name)
                        put("sdCapabilities", selectedType.sdCapabilities ?: savedMetadata.optString("sdCapabilities").takeIf { it.isNotBlank() })
                        put("sdFamily", sdFamily.trim().takeIf { it.isNotBlank() })
                        put("sdVariant", sdVariant.trim().takeIf { it.isNotBlank() })
                        put("sdCompatProfiles", compatibility.trim().takeIf { it.isNotBlank() })
                        put("onnxPipelineFamily", onnxPipeline.trim().takeIf { it.isNotBlank() })
                        put("liteRtBackend", liteRtBackend.trim().takeIf { it.isNotBlank() })
                        put("supportsVision", supportsVision)
                        put("supportsAudio", supportsAudio)
                        put("supportsEmbedding", supportsEmbedding)
                        put("whisperVariant", whisperVariant.trim().takeIf { it.isNotBlank() })
                    }.toString()
                    onPromote(family, name.trim(), selectedType.role, PortableModelMetadata.sanitize(metadata))
                },
                enabled = !busy && name.isNotBlank()
            ) { Text(androidx.compose.ui.res.stringResource(R.string.model_library_promote_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(R.string.model_library_cancel)) }
        }
    )
}

@Composable
private fun EmptyLibraryCard(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)), shape = AppChromeDefaults.InnerCardShape) {
        Row(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun modelFamilyLabel(family: ModelFamily): String = when (family) {
    ModelFamily.LLM -> androidx.compose.ui.res.stringResource(R.string.model_library_family_llm)
    ModelFamily.SD -> androidx.compose.ui.res.stringResource(R.string.model_library_family_sd)
    ModelFamily.ONNX -> androidx.compose.ui.res.stringResource(R.string.model_library_family_onnx)
    ModelFamily.LITERT -> androidx.compose.ui.res.stringResource(R.string.model_library_family_litert)
    ModelFamily.WHISPER -> androidx.compose.ui.res.stringResource(R.string.model_library_family_whisper)
}

@Composable
private fun modelSourceKindLabel(kind: ModelSourceKind?): String = when (kind) {
    ModelSourceKind.HUGGING_FACE_REPOSITORY -> androidx.compose.ui.res.stringResource(R.string.model_library_source_kind_hf_repository)
    ModelSourceKind.HUGGING_FACE_FILE -> androidx.compose.ui.res.stringResource(R.string.model_library_source_kind_hf_file)
    ModelSourceKind.HTTPS, null -> androidx.compose.ui.res.stringResource(R.string.model_library_source_kind_https)
}

@Composable
private fun pendingStatusLabel(status: PendingArtifactStatus?): String = when (status) {
    PendingArtifactStatus.STAGED -> androidx.compose.ui.res.stringResource(R.string.model_library_status_staged)
    PendingArtifactStatus.INSPECTING -> androidx.compose.ui.res.stringResource(R.string.model_library_status_inspecting)
    PendingArtifactStatus.NEEDS_MANUAL_PROMOTION -> androidx.compose.ui.res.stringResource(R.string.model_library_status_manual)
    PendingArtifactStatus.VALIDATED -> androidx.compose.ui.res.stringResource(R.string.model_library_status_validated)
    PendingArtifactStatus.PROMOTED -> androidx.compose.ui.res.stringResource(R.string.model_library_status_promoted)
    PendingArtifactStatus.REJECTED -> androidx.compose.ui.res.stringResource(R.string.model_library_status_rejected)
    PendingArtifactStatus.CANCELLED -> androidx.compose.ui.res.stringResource(R.string.model_library_status_cancelled)
    PendingArtifactStatus.FAILED, null -> androidx.compose.ui.res.stringResource(R.string.model_library_status_failed)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(java.util.Locale.US, bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(java.util.Locale.US, bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(java.util.Locale.US, bytes / 1024.0)
    else -> "$bytes B"
}
