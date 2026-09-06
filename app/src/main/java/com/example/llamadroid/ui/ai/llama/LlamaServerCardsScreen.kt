package com.example.llamadroid.ui.ai.llama

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppContentColumn
import com.example.llamadroid.ui.components.AppPageHeader
import com.example.llamadroid.ui.components.AppScreenScaffold

/**
 * Dedicated workspace for independently managed llama.cpp server cards.
 *
 * The server section owns its bounded log viewers, while this page owns the outer scroll so a
 * growing set of cards remains usable on small phones.
 */
@Composable
fun LlamaServerCardsScreen(navController: NavController) {
    AppScreenScaffold(
        title = stringResource(R.string.ai_chat_manage_servers),
        subtitle = stringResource(R.string.llama_cards_subtitle),
        onBack = { navController.popBackStack() }
    ) {
        AppContentColumn(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LlamaServerCardsSection()
        }
    }
}
