package com.example.llamadroid.harness

import android.content.Context
import androidx.navigation.NavController
import com.example.llamadroid.service.AgentService
import com.example.llamadroid.ui.navigation.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opens a Harness project's file browser for any runtime, focused or not. The workspace screen
 * resolves its runtime from the conversation captured by [AgentService.selectHarnessWorkspace],
 * so the runtime does not need to be the one currently focused in the Harness UI.
 */
object HarnessProjectFilesNavigator {
    /** Returns false when the project cannot be opened; callers then offer Runtime files only. */
    suspend fun openProjectFiles(
        context: Context,
        navController: NavController,
        runtimeId: String,
        workspaceId: String,
    ): Boolean = try {
        val runtime = HarnessAppRuntime.forRuntime(context.applicationContext, runtimeId)
        val captured = withContext(Dispatchers.IO) { runtime.workspaces.openWorkspace(workspaceId) }
        if (captured.conversation.runtimeId != runtimeId) {
            false
        } else {
            withContext(Dispatchers.Main) {
                AgentService.selectHarnessWorkspace(captured.conversation)
                navController.currentBackStackEntry?.savedStateHandle?.set("harness_workspace_files", true)
                navController.navigate(Screen.AgentWorkspace.route)
            }
            true
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}
