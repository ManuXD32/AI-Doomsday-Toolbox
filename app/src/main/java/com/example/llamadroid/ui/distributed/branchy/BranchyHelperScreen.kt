package com.example.llamadroid.ui.distributed.branchy

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppScreenScaffold

/** Helper side of a Branchy training cluster; the content lives in [BranchyWorkerPanel]. */
@Composable
fun BranchyHelperScreen(navController: NavController) {
    val vm: BranchyHelperViewModel = viewModel()
    AppScreenScaffold(
        title = stringResource(R.string.branchy_cluster_title),
        subtitle = stringResource(R.string.branchy_cluster_subtitle),
        onBack = { navController.popBackStack() }
    ) { padding ->
        BranchyWorkerPanel(vm, Modifier.fillMaxSize().padding(padding))
    }
}
