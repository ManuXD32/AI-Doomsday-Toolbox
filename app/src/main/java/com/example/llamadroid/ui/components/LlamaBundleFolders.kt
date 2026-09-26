package com.example.llamadroid.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.model.AudioCuratedBundleCatalog
import com.example.llamadroid.data.model.BundleCategory
import com.example.llamadroid.data.model.CuratedModelBundle
import com.example.llamadroid.data.model.LlamaCuratedBundleCatalog
import com.example.llamadroid.data.model.folderCategory

@Composable
fun LlamaBundleFolders(onUseBundle: (CuratedModelBundle, List<ModelEntity>, String) -> Unit) {
    val groups = remember {
        (LlamaCuratedBundleCatalog.bundles + AudioCuratedBundleCatalog.bundles)
            .groupBy { it.folderCategory() }
    }
    val folders = BundleCategory.entries.mapNotNull { category ->
        groups[category]?.let { BundleFolder(category.name, category.titleRes, it.size) }
    }
    BundleFolderBrowser(folders) { id ->
        val category = BundleCategory.valueOf(id)
        CuratedModelBundleSection(
            title = stringResource(category.titleRes),
            description = stringResource(R.string.bundle_folder_description),
            bundles = groups[category].orEmpty(),
            onUseBundle = onUseBundle.takeIf { category !in setOf(BundleCategory.SPEECH, BundleCategory.MUSIC) }
        )
    }
}
