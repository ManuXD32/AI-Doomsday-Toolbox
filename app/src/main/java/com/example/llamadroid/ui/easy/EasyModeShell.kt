package com.example.llamadroid.ui.easy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.easy.EasyModelKind
import com.example.llamadroid.ui.library.AllMediaGalleryScreen
import com.example.llamadroid.ui.navigation.navigateToSettings

private enum class EasyTab(val labelRes: Int, val icon: ImageVector) {
    CHAT(R.string.easy_mode_shell_tab_chat, Icons.AutoMirrored.Filled.Chat),
    IMAGES(R.string.easy_mode_shell_tab_images, Icons.Default.Image),
    GALLERY(R.string.easy_mode_shell_tab_gallery, Icons.Default.PhotoLibrary)
}

/**
 * Easy mode root. Rendered in place of the tabbed Dashboard inside the app's single NavHost, so
 * deep routes (chat, gallery items, settings) keep working and pop back here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EasyModeShell(navController: NavController, modifier: Modifier = Modifier) {
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var modelsKind by rememberSaveable { mutableStateOf<EasyModelKind?>(null) }
    var showSheet by rememberSaveable { mutableStateOf(false) }
    var showIntro by rememberSaveable { mutableStateOf(false) }
    val tab = EasyTab.entries[tabIndex.coerceIn(0, EasyTab.entries.lastIndex)]
    val openModels: (EasyModelKind) -> Unit = { modelsKind = it }

    Box(modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = {},
                    navigationIcon = { EasyModeToggle(Modifier.padding(start = 12.dp)) },
                    actions = {
                        EasyDarkModeToggle()
                        IconButton(onClick = { showSheet = true }, modifier = Modifier.testTag("easy_settings_gear")) {
                            Icon(Icons.Default.Settings, stringResource(R.string.easy_mode_shell_settings))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            },
            bottomBar = {
                NavigationBar {
                    EasyTab.entries.forEachIndexed { index, entry ->
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = { tabIndex = index },
                            icon = { Icon(entry.icon, null) },
                            label = { Text(stringResource(entry.labelRes), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.testTag("easy_tab_${entry.name.lowercase()}")
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (tab) {
                    EasyTab.CHAT -> EasyChatTab(navController, onOpenModels = { openModels(EasyModelKind.CHAT) })
                    EasyTab.IMAGES -> EasyImagesTab(
                        onOpenModels = { openModels(EasyModelKind.IMAGE) },
                        onOpenGallery = { tabIndex = EasyTab.GALLERY.ordinal }
                    )
                    EasyTab.GALLERY -> AllMediaGalleryScreen(navController, showBack = false, easyOnly = true)
                }
            }
        }
        modelsKind?.let { kind ->
            BackHandler { modelsKind = null }
            // Opaque and touch-blocking: the screen brings its own top bar and fully covers the shell chrome.
            Surface(
                modifier = Modifier.fillMaxSize().testTag("easy_models_overlay").pointerInput(Unit) {},
                color = MaterialTheme.colorScheme.surface
            ) {
                EasyModelsScreen(initialKind = kind, onBack = { modelsKind = null })
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(stringResource(R.string.easy_mode_shell_models_header), Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                SheetRow(Icons.AutoMirrored.Filled.Chat, R.string.easy_mode_shell_models_chat) { showSheet = false; openModels(EasyModelKind.CHAT) }
                SheetRow(Icons.Default.Image, R.string.easy_mode_shell_models_images) { showSheet = false; openModels(EasyModelKind.IMAGE) }
                SheetRow(Icons.Default.Mic, R.string.easy_mode_shell_models_voice) { showSheet = false; openModels(EasyModelKind.VOICE) }
                SheetRow(Icons.Default.Info, R.string.easy_mode_shell_show_intro) { showSheet = false; showIntro = true }
                SheetRow(Icons.Default.Storage, R.string.easy_mode_shell_all_settings) { showSheet = false; navController.navigateToSettings() }
            }
        }
    }
    if (showIntro) EasyModeIntroDialog(onDone = { showIntro = false })
}

@Composable
private fun SheetRow(icon: ImageVector, labelRes: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(labelRes), Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
