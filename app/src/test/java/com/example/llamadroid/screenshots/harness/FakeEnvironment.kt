package com.example.llamadroid.screenshots.harness

import android.app.ActivityManager
import android.app.Application
import android.content.ContextWrapper
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.work.testing.WorkManagerTestInitHelper
import coil.Coil
import coil.ImageLoader
import com.example.llamadroid.LlamaApplication
import com.example.llamadroid.data.AppThemeMode
import com.example.llamadroid.data.AppVisualStyle
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.service.GenerationDiagnosticsStore
import com.example.llamadroid.util.DynamicFeatureManager
import com.example.llamadroid.util.SystemMonitor
import com.example.llamadroid.util.SystemStats
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowStatFs
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.lang.reflect.Modifier

/**
 * Everything a real screen needs to render deterministically on the JVM: prefs that skip first-run
 * gates, the application singleton, fake host services and a dead network. Call [install] before
 * launching the activity and [uninstall] from `@After`.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FakeEnvironment {
    private var previousApplication: LlamaApplication? = null
    private var installed = false

    /** Deterministic host stats shown by the dashboard status row (never the real /proc values). */
    var demoStats: SystemStats = SystemStats(23, 54, 5.5f, 12f)

    fun install(app: Application, seedExtra: (Application) -> Unit = {}) {
        rebindMainDispatcher()
        resetViewModelFactoryCache()
        installLlamaApplication(app)
        installHostFacts(app)
        configureProxy()
        configureBuild()
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        grantPermissions(app)
        // Static theme flows survive across tests inside one sandbox: set every value every time.
        SettingsRepository(app).apply {
            setHasCompletedWelcome(true)
            setThemeMode(if (ShotRunOptions.dark) AppThemeMode.DARK else AppThemeMode.LIGHT)
            setVisualStyle(if (ShotRunOptions.pixelStyle) AppVisualStyle.PIXEL else AppVisualStyle.SOFT_STUDIO)
            setPixelVariant(ShotRunOptions.pixelVariant)
            setDynamicColor(false)
            setPixelFontEnabled(false)
        }
        mockkConstructor(SystemMonitor::class)
        every { anyConstructed<SystemMonitor>().observeStats() } returns flowOf(demoStats)
        mockkObject(DynamicFeatureManager)
        every { DynamicFeatureManager.isModuleInstalled(any(), any()) } returns true
        Coil.setImageLoader(ImageLoader.Builder(app).dispatcher(Dispatchers.Unconfined).crossfade(false).build())
        installed = true
        seedExtra(app)
    }

    fun uninstall() {
        if (!installed) return
        installed = false
        runCatching { Dispatchers.resetMain() }
        resetViewModelFactoryCache()
        runCatching { AppDatabase.closeInstance() }
        runCatching { unmockkAll() }
        runCatching { instanceField().set(null, previousApplication) }
    }

    /**
     * Robolectric reuses one sandbox for every test of the JVM, but `Dispatchers.Main` caches the first
     * test's main Looper; later tests would post viewModelScope work to a dead looper (screens then never
     * see flows built with stateIn(viewModelScope)). Rebind Main to the current test's looper.
     */
    private fun rebindMainDispatcher() {
        Dispatchers.setMain(Handler(Looper.getMainLooper()).asCoroutineDispatcher("main"))
    }

    /**
     * `AndroidViewModelFactory` caches the first Application it sees in a static; later tests would
     * build every AndroidViewModel against a stale Application (wrong filesDir, vanished session files).
     */
    private fun resetViewModelFactoryCache() {
        val factoryClass = ViewModelProvider.AndroidViewModelFactory::class.java
        var cleared = 0
        factoryClass.declaredFields.filter {
            Modifier.isStatic(it.modifiers) && factoryClass.isAssignableFrom(it.type)
        }.forEach { field ->
            field.isAccessible = true
            field.set(null, null)
            cleared++
        }
        check(cleared > 0) { "AndroidViewModelFactory static cache not found; update the reset" }
    }

    /** Facts Robolectric reports as zero/null: native lib dir, diagnostics store, device storage, RAM. */
    private fun installHostFacts(app: Application) {
        GenerationDiagnosticsStore.init(app)
        app.applicationInfo.nativeLibraryDir = File(app.cacheDir, "nativelibs").apply { mkdirs() }.path
        // Fake 256 GB device with 118 GB free for storage summaries.
        val block = 4096L
        val gib = 1024L * 1024 * 1024
        ShadowStatFs.registerStats(
            Environment.getDataDirectory(),
            (256 * gib / block).toInt(), (130 * gib / block).toInt(), (118 * gib / block).toInt()
        )
        // Robolectric reports 0 bytes of RAM, which makes RAM sliders read "0 MB".
        val memory = ActivityManager.MemoryInfo().apply { totalMem = 12L * gib; availMem = 7L * gib }
        shadowOf(app.getSystemService(ActivityManager::class.java)).setMemoryInfo(memory)
    }

    private fun instanceField() = LlamaApplication::class.java.getDeclaredField("instance").apply { isAccessible = true }

    /** Pattern from AgentToolApprovalTest: a production Application attached without onCreate. */
    private fun installLlamaApplication(app: Application) {
        previousApplication = instanceField().get(null) as? LlamaApplication
        val shim = LlamaApplication()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", android.content.Context::class.java)
            .apply { isAccessible = true }.invoke(shim, app)
        instanceField().set(null, shim)
    }

    private fun configureProxy() {
        listOf("http", "https").forEach { scheme ->
            if (System.getProperty("$scheme.proxyHost") == null) {
                System.setProperty("$scheme.proxyHost", "127.0.0.1")
                System.setProperty("$scheme.proxyPort", "9")
            }
        }
        System.setProperty("http.nonProxyHosts", "localhost|127.*|[::1]")
    }

    private fun configureBuild() {
        ShadowBuild.setManufacturer("Google")
        ShadowBuild.setBrand("google")
        ShadowBuild.setModel("Pixel 8")
        ShadowBuild.setDevice("shiba")
        runCatching {
            ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a"))
            ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_64_BIT_ABIS", arrayOf("arm64-v8a"))
        }
    }

    private fun grantPermissions(app: Application) {
        org.robolectric.Shadows.shadowOf(app).grantPermissions(
            android.Manifest.permission.POST_NOTIFICATIONS,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VIDEO,
            android.Manifest.permission.READ_MEDIA_AUDIO
        )
    }
}
