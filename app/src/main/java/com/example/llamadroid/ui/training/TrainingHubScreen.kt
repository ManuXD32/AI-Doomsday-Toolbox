package com.example.llamadroid.ui.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material.icons.filled.Storage
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppContentColumn
import com.example.llamadroid.ui.components.AppHubRow
import com.example.llamadroid.ui.components.AppHubRowStyle
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.navigation.Screen

/** Entry point of model training: Quadtrix, Branchy and the shared dataset library. */
@Composable
fun TrainingHubScreen(navController: NavController) {
    AppScreenScaffold(
        title = stringResource(R.string.training_title),
        subtitle = stringResource(R.string.training_hub_subtitle),
        onBack = { navController.popBackStack() }
    ) {
        AppContentColumn(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            AppHubRow(
                title = stringResource(R.string.training_hub_quadtrix_title),
                subtitle = stringResource(R.string.training_hub_quadtrix_desc),
                icon = Icons.Filled.ModelTraining,
                style = AppHubRowStyle.Large,
                onClick = { navController.navigate(Screen.QuadtrixTrainer.route) }
            )
            AppHubRow(
                title = stringResource(R.string.training_hub_branchy_title),
                subtitle = stringResource(R.string.training_hub_branchy_desc),
                icon = Icons.Filled.AccountTree,
                style = AppHubRowStyle.Large,
                onClick = { navController.navigate(Screen.BranchyTrainer.route) }
            )
            BranchyStorageCard(onManage = { navController.navigate(Screen.BranchyTrainer.route) })
            AppHubRow(
                title = stringResource(R.string.training_hub_datasets_title),
                subtitle = stringResource(R.string.training_hub_datasets_desc),
                icon = Icons.Filled.Storage,
                onClick = { navController.navigate(Screen.DatasetLibrary.route) }
            )
        }
    }
}
