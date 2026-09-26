package com.example.llamadroid.ui.agent

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import com.example.llamadroid.harness.HarnessAgentRoute

/** Canonical navigation now enters the shared DeepSeek Harness client. */
@Composable
fun AgentScreen(navController: NavController, initialConversationId: Long? = null, initialAttentionTab: String? = null) {
    HarnessAgentRoute(navController, initialConversationId, initialAttentionTab)
}
