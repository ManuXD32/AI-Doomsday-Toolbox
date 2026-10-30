package com.example.llamadroid.ui.ai.llama.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.ui.ai.llama.chat.ChatDelegationSettingsSection
import com.example.llamadroid.ui.ai.llama.chat.ChatSettingsGroupList
import com.example.llamadroid.ui.ai.llama.chat.ChatSettingsState
import com.example.llamadroid.ui.ai.llama.chat.rememberDefaultsSettingsEnv
import com.example.llamadroid.ui.components.AppAdvancedSection

/**
 * "Defaults for new chats" of a server: the chat's own settings groups ([ChatSettingsGroupList])
 * rendered in defaults mode on a [ChatSettingsState], so every per-chat option appears here
 * automatically and is stored under exactly the keys a chat would store.
 *
 * @param navController null when the host is a bottom sheet (remote server form): the buttons that
 *        navigate away from the editor are hidden.
 */
@Composable
internal fun NativeServerChatDefaultsSection(
    state: ChatSettingsState,
    registryViewModel: ServerRegistryViewModel,
    navController: NavController?
) {
    val env = rememberDefaultsSettingsEnv()
    // A chat re-picks the first voice when its saved one vanished; keep a stale default honest too.
    LaunchedEffect(env.ttsVoiceOptions, state.assistantTtsVoiceName) {
        val voice = state.assistantTtsVoiceName
        if (env.ttsVoiceOptions.isNotEmpty() && voice.isNotBlank() && voice !in env.ttsVoiceOptions) {
            state.assistantTtsVoiceName = env.ttsVoiceOptions.first()
        }
    }
    AppAdvancedSection(
        title = stringResource(R.string.server_defaults_title),
        modifier = Modifier.fillMaxWidth()
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.server_defaults_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ChatSettingsGroupList(
                    state = state,
                    env = env,
                    delegationSection = {
                        ChatDelegationSettingsSection(
                            settings = state,
                            chat = null,
                            chatServer = null,
                            registryViewModel = registryViewModel,
                            navController = navController,
                            onSettingsChanged = {},
                            defaultsMode = true
                        )
                    }
                )
            }
        }
    }
}

/**
 * State for [NativeServerChatDefaultsSection], filled from the server's stored defaults JSON.
 *
 * @param savedJson JSON the editor keeps across configuration changes (blank = every default)
 * @param onJsonChange when non-null, receives the rebuilt JSON after every edit so the caller can
 *        keep [savedJson] current (a rotation then restores the edits)
 * @param liteRt the engine being edited when it is not saved yet; null asks the saved server
 */
@Composable
internal fun rememberServerDefaultsState(
    savedJson: String,
    onJsonChange: ((String) -> Unit)? = null,
    liteRt: Boolean? = null
): ChatSettingsState {
    val state = remember { ChatSettingsState().apply { loadFromDefaults(savedJson) } }
    // Apply after composition instead of writing snapshot state while composing.
    SideEffect { state.liteRtOverride = liteRt }
    if (onJsonChange != null) {
        LaunchedEffect(state) { snapshotFlow { state.buildApiParamsJson() }.collect(onJsonChange) }
    }
    return state
}

@Composable
internal fun LlamaServerBadge(
    label: String,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}
