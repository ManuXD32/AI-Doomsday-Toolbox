package com.example.llamadroid.ui.navigation

import androidx.navigation.NavController

/**
 * Switches to a root tab exactly like the shell's bottom bar/rail/drawer does. Opening a root
 * route with a plain navigate() stacks it above the current root, which leaves the shell unable
 * to switch tabs until the user presses Back.
 */
fun NavController.navigateToRoot(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
