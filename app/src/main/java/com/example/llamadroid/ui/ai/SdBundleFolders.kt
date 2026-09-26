package com.example.llamadroid.ui.ai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.example.llamadroid.data.model.AdetailerCuratedBundleCatalog
import com.example.llamadroid.data.model.BundleCategory
import com.example.llamadroid.data.model.SdCuratedBundleCatalog
import com.example.llamadroid.data.model.folderCategory
import com.example.llamadroid.ui.components.BundleFolder
import com.example.llamadroid.ui.components.BundleFolderBrowser
import com.example.llamadroid.ui.components.CuratedModelBundleSection

@Composable
fun SdBundleFolders() {
    val groups = remember { SdCuratedBundleCatalog.bundles.groupBy { it.folderCategory() } }
    val folders = BundleCategory.entries.mapNotNull { category ->
        if (category == BundleCategory.DETECTORS) {
            BundleFolder(category.name, category.titleRes, AdetailerCuratedBundleCatalog.bundles.size)
        } else groups[category]?.let { BundleFolder(category.name, category.titleRes, it.size) }
    }
    BundleFolderBrowser(folders) { id ->
        val category = BundleCategory.valueOf(id)
        if (category == BundleCategory.DETECTORS) {
            CuratedModelBundleSection(
                title = stringResource(R.string.phase_c_adetailer_bundles_title),
                description = stringResource(R.string.adetailer_bundles_desc),
                bundles = AdetailerCuratedBundleCatalog.bundles
            )
        } else {
            SdCuratedBundlesSection(bundles = groups[category].orEmpty())
        }
    }
}
