package com.example.llamadroid.ui.walkthrough

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppScreenScaffold

/** Missing companion state is navigable; never strand a direct entry on an endless spinner. */
@Composable
internal fun TamaSetupState(onBack: () -> Unit, onOpenTama: () -> Unit) {
    AppScreenScaffold(title = stringResource(R.string.tour_tama_missing_title), onBack = onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).testTag("tour_tama_prerequisite"),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.tour_tama_missing_body))
            Button(onClick = onOpenTama) { Text(stringResource(R.string.tour_tama_setup)) }
        }
    }
}
