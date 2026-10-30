package com.example.llamadroid.ui.ai.llama

import androidx.compose.runtime.Composable
import androidx.navigation.NavController

/**
 * Stable entry point used by navigation. The implementation lives in
 * [com.example.llamadroid.ui.ai.llama.chat.LlamaChatScreen] and its sibling files.
 */
@Composable
fun LlamaChatScreen(
    navController: NavController,
    chatId: Long,
    initialServerId: Long
) {
    com.example.llamadroid.ui.ai.llama.chat.LlamaChatScreen(navController, chatId, initialServerId)
}
