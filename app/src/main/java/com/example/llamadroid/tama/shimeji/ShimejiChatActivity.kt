package com.example.llamadroid.tama.shimeji

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.service.OllamaService
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.game.FarmEngine
import com.example.llamadroid.tama.game.FarmRepository
import com.example.llamadroid.tama.game.TamaAgentService
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.ui.TamaChatScreen
import com.example.llamadroid.ui.theme.AppThemeFromSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Floating chat opened by triple-tapping the desktop pet. Hosts the regular [TamaChatScreen]
 * (same engine/agent wiring as `LlamaApp`) in a rounded panel over a translucent window.
 */
class ShimejiChatActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: TamaGameEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appContext = applicationContext
        val settingsRepo = SettingsRepository(appContext)
        val database = TamaDatabase.getInstance(appContext)
        val farmRepository = FarmRepository(database.farmDao(), appContext)
        val gameEngine = TamaGameEngine(
            context = appContext,
            dao = database.tamaDao(),
            farmEngine = FarmEngine(farmRepository),
            farmRepository = farmRepository,
            settingsRepo = settingsRepo
        )
        engine = gameEngine
        val agentService = TamaAgentService(
            context = appContext,
            dao = database.tamaDao(),
            settingsRepo = settingsRepo,
            ollamaService = OllamaService(appContext),
            scope = scope
        )
        setContent {
            AppThemeFromSettings {
                LaunchedEffect(Unit) { gameEngine.loadPet() }
                // Tapping the dimmed area outside the panel closes the chat.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { finish() }
                        )
                        .imePadding(),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .fillMaxHeight(0.75f)
                            .clip(RoundedCornerShape(24.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {}
                            ),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        TamaChatScreen(
                            navController = null,
                            gameEngine = gameEngine,
                            agentService = agentService,
                            settingsRepo = settingsRepo,
                            onClose = { finish() }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        engine?.close()
        engine = null
        scope.cancel()
        super.onDestroy()
    }
}
