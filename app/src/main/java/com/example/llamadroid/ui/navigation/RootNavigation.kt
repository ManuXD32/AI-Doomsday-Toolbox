package com.example.llamadroid.ui.navigation

import androidx.navigation.NavController

/**
 * Switches to a root destination exactly like the shell's bar/rail/drawer does.
 *
 * No back-stack state is saved or restored: restoring a saved stack could resurrect a stale deep
 * screen (for example the chat that was open when the user left Chats), so Home "did nothing" and
 * the other roots reopened deep inside. Home pops to the dashboard; any other root is popped back
 * to when it is already on the stack (second tap = its root) or opened above the dashboard.
 */
fun NavController.navigateToRoot(route: String) {
    val startId = graph.startDestinationId
    if (route == Screen.Dashboard.route) {
        if (!popBackStack(startId, inclusive = false)) {
            navigate(route) {
                popUpTo(startId) { inclusive = true }
                launchSingleTop = true
            }
        }
        return
    }
    val onStack = runCatching { getBackStackEntry(route) }.getOrNull() != null
    if (onStack && popBackStack(route, inclusive = false)) return
    navigate(route) {
        popUpTo(startId) { saveState = false }
        launchSingleTop = true
        restoreState = false
    }
}
