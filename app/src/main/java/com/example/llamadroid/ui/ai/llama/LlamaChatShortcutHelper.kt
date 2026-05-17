package com.example.llamadroid.ui.ai.llama

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.widget.Toast
import com.example.llamadroid.MainActivity
import com.example.llamadroid.R
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaChatFolderEntity
import com.example.llamadroid.ui.navigation.Screen

object LlamaChatShortcutHelper {
    fun requestPinShortcut(context: Context, chat: LlamaChatEntity) {
        requestPinRouteShortcut(
            context = context,
            shortcutId = "llama_chat_${chat.id}",
            shortLabel = chat.title.take(24).ifBlank {
                context.getString(R.string.llama_chat_shortcut_label)
            },
            longLabel = chat.title.ifBlank {
                context.getString(R.string.llama_chat_shortcut_label)
            },
            route = Screen.LlamaChat.createRoute(chat.id, -1)
        )
    }

    fun requestPinFolderShortcut(context: Context, folder: LlamaChatFolderEntity) {
        requestPinRouteShortcut(
            context = context,
            shortcutId = "llama_folder_${folder.id}",
            shortLabel = folder.name.take(24).ifBlank {
                context.getString(R.string.llama_folder_shortcut_label)
            },
            longLabel = folder.name.ifBlank {
                context.getString(R.string.llama_folder_shortcut_label)
            },
            route = Screen.LlamaChatList.createFolderRoute(folder.id)
        )
    }

    private fun requestPinRouteShortcut(
        context: Context,
        shortcutId: String,
        shortLabel: String,
        longLabel: String,
        route: String
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(
                context,
                context.getString(R.string.llama_shortcut_unavailable),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val shortcutManager = context.getSystemService(ShortcutManager::class.java)
        if (shortcutManager == null || !shortcutManager.isRequestPinShortcutSupported) {
            Toast.makeText(
                context,
                context.getString(R.string.llama_shortcut_unavailable),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(MainActivity.EXTRA_OPEN_ROUTE, route)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val shortcut = ShortcutInfo.Builder(context, shortcutId)
            .setShortLabel(shortLabel)
            .setLongLabel(longLabel)
            .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()

        shortcutManager.requestPinShortcut(shortcut, null)
        Toast.makeText(
            context,
            context.getString(R.string.llama_shortcut_requested),
            Toast.LENGTH_SHORT
        ).show()
    }
}
