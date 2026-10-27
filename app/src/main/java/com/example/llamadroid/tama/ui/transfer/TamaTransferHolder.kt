package com.example.llamadroid.tama.ui.transfer

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.game.FarmEngine
import com.example.llamadroid.tama.game.FarmRepository
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.transfer.NsdPetTransferDiscovery
import com.example.llamadroid.tama.transfer.AndroidKeystorePeerKeyStore
import com.example.llamadroid.tama.transfer.PetLinkService
import com.example.llamadroid.tama.transfer.PetTransferCoordinator
import com.example.llamadroid.tama.transfer.PetTransferPaths
import com.example.llamadroid.tama.transfer.PetTransferPeerStore
import com.example.llamadroid.tama.transfer.PetTransferStore
import com.example.llamadroid.tama.transfer.PrefsVisitPeerRegistry
import com.example.llamadroid.tama.transfer.PrefsDeviceIdentity
import com.example.llamadroid.tama.transfer.TamaGameEnginePetTransferEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide transfer session: the coordinator plus the scope that owns long-running work
 * (uploads, the receive server) so it survives leaving the screen or rotating the phone.
 */
class TamaTransferHandle internal constructor(
    val engine: TamaGameEngine,
    val coordinator: PetTransferCoordinator,
    val scope: CoroutineScope
) {
    private val recovered = AtomicBoolean(false)

    /** Runs crash recovery exactly once per process; incoming files must not be wiped mid-transfer. */
    fun recoverOnceAsync() {
        if (recovered.compareAndSet(false, true)) scope.launch { coordinator.recoverOnStart() }
    }
}

object TamaTransferHolder {
    private var current: TamaTransferHandle? = null

    /**
     * Builds the session once per process (never rebuilt, so a running server or upload is never
     * orphaned). It owns a private engine, like the Shimeji bridge; the UI engine reloads through
     * [PetTransferCoordinator.petChanged].
     */
    @Synchronized
    fun get(context: Context): TamaTransferHandle {
        current?.let { return it }
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("tama_transfer_prefs", Context.MODE_PRIVATE)
        val database = TamaDatabase.getInstance(appContext)
        val farmRepository = FarmRepository(database.farmDao(), appContext)
        val engine = TamaGameEngine(
            context = appContext,
            dao = database.tamaDao(),
            farmEngine = FarmEngine(farmRepository),
            farmRepository = farmRepository,
            settingsRepo = SettingsRepository(appContext)
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = PetTransferCoordinator(
            engine = TamaGameEnginePetTransferEngine(engine),
            store = PetTransferStore(database.transferStateDao()),
            identity = PrefsDeviceIdentity(prefs) {
                listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" ")
                    .ifBlank { "Android" }
            },
            paths = PetTransferPaths(File(appContext.filesDir, "tama_transfer")),
            scope = scope,
            discovery = NsdPetTransferDiscovery(appContext),
            freeSpace = { allocatableBytes(appContext, File(appContext.filesDir, "tama_transfer")) },
            peerStore = PetTransferPeerStore(database.transferPeerDao(), AndroidKeystorePeerKeyStore()),
            visitPeers = PrefsVisitPeerRegistry(prefs)
        )
        // Keep the paired link alive while a visit is active (new visit committed, or found at start-up).
        scope.launch {
            coordinator.state.distinctUntilChangedBy { it::class }.collect {
                runCatching { PetLinkService.startIfNeeded(appContext) }
            }
        }
        return TamaTransferHandle(engine, coordinator, scope).also { current = it }
    }
}

/** Free bytes for [dir]; on API 26+ uses StorageManager's allocatable figure (counts reclaimable cache). */
private fun allocatableBytes(context: Context, dir: File): Long {
    dir.mkdirs()
    runCatching {
        val sm = context.getSystemService(StorageManager::class.java)
        return sm.getAllocatableBytes(sm.getUuidForPath(dir))
    }
    return dir.freeSpace
}
