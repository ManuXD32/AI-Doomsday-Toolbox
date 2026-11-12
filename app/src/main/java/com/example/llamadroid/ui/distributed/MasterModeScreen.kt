package com.example.llamadroid.ui.distributed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppAdvancedSection
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.distributed.master.MasterAdvancedSection
import com.example.llamadroid.ui.distributed.master.MasterDialogs
import com.example.llamadroid.ui.distributed.master.MasterFooter
import com.example.llamadroid.ui.distributed.master.MasterHelpersSection
import com.example.llamadroid.ui.distributed.master.MasterHowItWorks
import com.example.llamadroid.ui.distributed.master.MasterMemorySection
import com.example.llamadroid.ui.distributed.master.MasterModelSection
import com.example.llamadroid.ui.distributed.master.MasterStatusSection
import com.example.llamadroid.ui.distributed.master.MasterToolsRow
import com.example.llamadroid.ui.distributed.master.MasterTechnicalLogSection
import com.example.llamadroid.ui.distributed.master.RemoteMasterCard
import com.example.llamadroid.ui.distributed.master.rememberMasterScreenState
import com.example.llamadroid.ui.walkthrough.walkthroughTarget

/**
 * Main device (master) screen. The state lives in [rememberMasterScreenState]; each section is a
 * composable under ui/distributed/master. Order: how it works, status, helpers, model, memory and
 * placement, advanced groups, remote control, technical log; the footer holds Start/Stop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MasterModeScreen(navController: NavController) {
    val s = rememberMasterScreenState(navController)

    AppScreenScaffold(
        title = stringResource(R.string.dist_ui_master_title),
        onBack = { navController.popBackStack() },
        bottomBar = { MasterFooter(s) }
    ) { _ ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .walkthroughTarget("distributed.master"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item(key = "how") { MasterHowItWorks() }
            item(key = "status") { MasterStatusSection(s) }
            item(key = "tools") { MasterToolsRow(s) }
            item(key = "helpers") { MasterHelpersSection(s) }
            item(key = "model") { MasterModelSection(s) }
            item(key = "memory") { MasterMemorySection(s) }
            item(key = "advanced") { MasterAdvancedSection(s) }
            // Remote administration is lazy: the panel owns many network flows and must not affect
            // scrolling unless the user opens it.
            item(key = "remote") {
                AppAdvancedSection(title = stringResource(R.string.dist_ui_remote_title)) {
                    RemoteMasterCard(modifier = Modifier.fillMaxWidth())
                }
            }
            item(key = "log") { MasterTechnicalLogSection(s) }
        }
    }

    MasterDialogs(s)
}
