package com.example.llamadroid.harness

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.example.llamadroid.harness.runtime.HarnessRuntimeScope

/** Keeps runtime storage ownership separate from the Activity that owns document pickers. */
@Composable
internal fun HarnessRuntimeContent(runtimeId: String, content: @Composable () -> Unit) {
    val host = LocalContext.current
    // Resolve before replacing LocalContext: AndroidX otherwise loses its Activity fallback.
    val activityResultOwner = checkNotNull(LocalActivityResultRegistryOwner.current) {
        "No ActivityResultRegistryOwner was provided via LocalActivityResultRegistryOwner"
    }
    val captured = remember(host, runtimeId) { HarnessRuntimeScope.context(host, runtimeId) }
    CompositionLocalProvider(
        LocalContext provides captured,
        LocalActivityResultRegistryOwner provides activityResultOwner,
        content = content,
    )
}
