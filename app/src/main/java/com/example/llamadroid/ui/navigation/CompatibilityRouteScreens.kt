package com.example.llamadroid.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController

/**
 * Historical ImageGen upscale links are redirects only. Keeping the old destination out of the
 * ImageGen composition avoids recreating the dynamic task list while Navigation is transitioning.
 */
@Composable
fun ImageGenUpscaleCompatibilityRedirect(navController: NavHostController) {
    LaunchedEffect(Unit) {
        navController.navigate(Screen.ImageGen.createRoute(startMode = 2)) {
            popUpTo(Screen.ImageGenUpscale.route) { inclusive = true }
            launchSingleTop = true
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}
