package com.example.llamadroid.ui.agent

import androidx.navigation.NavController
import com.example.llamadroid.ui.navigation.Screen

/** The result is useful to keep the route transition policy testable without starting Agent. */
internal enum class AgentBackNavigationResult {
    RETURNED_TO_PROJECT_DASHBOARD,
    POPPED_PREVIOUS,
    FELL_BACK_TO_TOOLS
}

/**
 * Agent has an in-route dashboard. Back from a selected project should return to that dashboard
 * before the real navigation stack is touched. Keeping this policy pure makes the two-step exit
 * behavior easy to exercise without creating a NavController or starting Agent.
 */
internal fun performAgentBackNavigationFromProject(
    hasActiveProject: Boolean,
    returnToProjectDashboard: () -> Unit,
    popBackStack: () -> Boolean,
    navigateToTools: () -> Unit
): AgentBackNavigationResult {
    if (hasActiveProject) {
        returnToProjectDashboard()
        return AgentBackNavigationResult.RETURNED_TO_PROJECT_DASHBOARD
    }
    return performAgentBackNavigation(
        popBackStack = popBackStack,
        navigateToTools = navigateToTools
    )
}

/**
 * Variant for callers that already own the complete NavController back operation. This keeps
 * the project-first branch reusable without invoking a route pop twice on the dashboard path.
 */
internal fun performAgentBackNavigationFromProject(
    hasActiveProject: Boolean,
    returnToProjectDashboard: () -> Unit,
    navigateBackToPreviousPage: () -> AgentBackNavigationResult
): AgentBackNavigationResult {
    if (hasActiveProject) {
        returnToProjectDashboard()
        return AgentBackNavigationResult.RETURNED_TO_PROJECT_DASHBOARD
    }
    return navigateBackToPreviousPage()
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
