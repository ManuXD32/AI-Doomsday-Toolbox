package com.example.llamadroid.ui.quicksetup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.model.QuickSetupBundleCatalog
import com.example.llamadroid.ui.components.CuratedModelBundleSection

/**
 * Contextual shortcut to the canonical "ADT starter bundle" (also listed under the LLM model
 * manager's Discover bundles). It renders the shared curated bundle card, so installed state,
 * progress and downloads are identical to every other entry point.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickSetupSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CuratedModelBundleSection(
                title = stringResource(R.string.quick_setup_title),
                description = stringResource(R.string.quick_setup_description),
                bundles = QuickSetupBundleCatalog.bundles
            )
            Text(
                text = stringResource(R.string.quick_setup_server_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
