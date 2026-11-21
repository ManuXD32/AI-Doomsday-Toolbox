package com.example.llamadroid.ui.distributed.sd

import com.example.llamadroid.data.db.ModelEntity

/**
 * Everything the main-device step builders need from the hosting screen that is not part of
 * [SdMasterUiState]: the installed model list and the navigation hooks.
 *
 * [onOpenBinarySettings] is optional; when null the video options editor hides its
 * "open SD binary settings" shortcut.
 */
internal class SdMasterEnv(
    val installedModels: List<ModelEntity>,
    val onOpenModelManager: (bundleId: String?) -> Unit,
    val onOpenHelperGuide: () -> Unit,
    val onOpenBinarySettings: (() -> Unit)? = null
)
