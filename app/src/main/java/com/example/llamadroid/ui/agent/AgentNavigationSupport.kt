package com.example.llamadroid.ui.agent

import androidx.navigation.NavController
import com.example.llamadroid.ui.navigation.Screen

/** The result is useful to keep the route transition policy testable without starting Agent. */
internal enum class AgentBackNavigationResult {
    POPPED_PREVIOUS,
    FELL_BACK_TO_TOOLS
}

/**
 * Runs the Agent exit policy against injected navigation operations.
 *
 * The Agent screen must leave its selected project and runtime continuation untouched when it
 * exits. Keeping this policy independent from AgentService makes that boundary explicit and
 * keeps regression tests from booting an LLM or touching the database.
 */
internal fun performAgentBackNavigation(
    popBackStack: () -> Boolean,
    navigateToTools: () -> Unit
): AgentBackNavigationResult {
    if (popBackStack()) {
        return AgentBackNavigationResult.POPPED_PREVIOUS
    }
    navigateToTools()
    return AgentBackNavigationResult.FELL_BACK_TO_TOOLS
}

/**
 * Dialogs and editors own their Back action. The screen-level handler is only active for the
 * normal Agent route exit, so modal dismissal and draft editing keep their existing behavior.
 */
internal fun shouldHandleAgentSystemBack(
    hasBlockingDialog: Boolean,
    hasEditor: Boolean
): Boolean = !hasBlockingDialog && !hasEditor

/**
 * Pops the prior route when possible. If Agent was entered as the only route (for example from
 * an external launch), replace that entry with the Tools root so Back never strands the user in
 * an empty Agent stack.
 */
internal fun NavController.navigateAgentBackToPreviousPage(): AgentBackNavigationResult {
    val currentDestinationId = currentDestination?.id
    return performAgentBackNavigation(
        popBackStack = { popBackStack() },
        navigateToTools = {
            navigate(Screen.AIHub.route) {
                currentDestinationId?.let { destinationId ->
                    popUpTo(destinationId) {
                        inclusive = true
                        saveState = true
                    }
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    )
}
