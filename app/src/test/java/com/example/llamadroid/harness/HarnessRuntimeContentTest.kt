package com.example.llamadroid.harness

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityOptionsCompat
import com.example.llamadroid.harness.runtime.HarnessRuntimeScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext

/** Exercises real Compose registration and Android document contracts without starting PRoot. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class HarnessRuntimeContentTest {
    private val application: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun applicationContextReplacementWithoutOwnerBridgeReproducesTheCrash() {
        val host = RegistryContext(application)
        val exception = assertThrows(IllegalStateException::class.java) {
            withComposition { composition ->
                composition.setContent {
                    CompositionLocalProvider(LocalContext provides host) {
                        CompositionLocalProvider(
                            LocalContext provides HarnessRuntimeScope.context(host, "runtime-a"),
                        ) {
                            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { }
                        }
                    }
                }
            }
        }
        assertEquals(
            "No ActivityResultRegistryOwner was provided via LocalActivityResultRegistryOwner",
            exception.message,
        )
    }

    @Test
    fun contextDiscoveredOwnerRegistersAttachmentImportAndExportPickers() = withComposition { composition ->
        val host = RegistryContext(application)
        lateinit var captured: Context
        lateinit var attachment: ManagedActivityResultLauncher<Array<String>, Uri?>
        lateinit var importZip: ManagedActivityResultLauncher<Array<String>, Uri?>
        lateinit var exportZip: ManagedActivityResultLauncher<String, Uri?>
        val results = mutableListOf<Uri?>()
        composition.setContent {
            CompositionLocalProvider(LocalContext provides ContextWrapper(host)) {
                HarnessRuntimeContent("runtime-a") {
                    captured = LocalContext.current
                    assertSame(host, LocalActivityResultRegistryOwner.current)
                    attachment = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { results += it }
                    importZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { results += it }
                    exportZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { results += it }
                }
            }
        }

        assertEquals("runtime-a", HarnessRuntimeScope.id(captured))
        assertSame(captured, captured.applicationContext)
        assertSame(application, HarnessRuntimeScope.host(captured))
        assertNotSame(host, captured)
        attachment.launch(arrayOf("*/*"))
        importZip.launch(arrayOf("application/zip"))
        exportZip.launch("projects.zip")
        val launches = host.activityResultRegistry.launches.toList()
        assertEquals(3, launches.map { it.requestCode }.distinct().size)
        assertEquals(
            listOf(Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_CREATE_DOCUMENT),
            launches.map { it.intent.action },
        )
        assertEquals("application/zip", launches.last().intent.type)
        assertEquals("projects.zip", launches.last().intent.getStringExtra(Intent.EXTRA_TITLE))
        val uris = launches.indices.map { Uri.parse("content://documents/result/$it") }
        launches.zip(uris).forEach { (launch, uri) ->
            assertTrue(host.activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_OK, Intent().setData(uri)))
        }
        assertEquals(uris, results)
        composition.dispose()
        launches.forEach { launch ->
            assertFalse(host.activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_CANCELED, null))
        }
    }

    @Test
    fun explicitCompositionOwnerTakesPrecedenceOverContextOwner() = withComposition { composition ->
        val host = RegistryContext(application)
        val explicit = RegistryContext(application)
        lateinit var picker: ManagedActivityResultLauncher<Array<String>, Uri?>
        var result: Uri? = null
        composition.setContent {
            CompositionLocalProvider(
                LocalContext provides host,
                LocalActivityResultRegistryOwner provides explicit,
            ) {
                HarnessRuntimeContent("runtime-a") {
                    assertSame(explicit, LocalActivityResultRegistryOwner.current)
                    picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { result = it }
                }
            }
        }
        picker.launch(arrayOf("application/zip"))
        assertTrue(host.activityResultRegistry.launches.isEmpty())
        val uri = Uri.parse("content://documents/import.zip")
        assertTrue(explicit.activityResultRegistry.dispatchResult(
            explicit.activityResultRegistry.launches.single().requestCode, Activity.RESULT_OK, Intent().setData(uri),
        ))
        assertEquals(uri, result)
    }

    @Test
    fun switchingRuntimeDisposesOldPickerAndDoesNotRedirectLateResults() = withComposition { composition ->
        val host = RegistryContext(application)
        lateinit var picker: ManagedActivityResultLauncher<Array<String>, Uri?>
        val callbacks = mutableListOf<Pair<String, Uri?>>()
        val contexts = mutableMapOf<String, Context>()
        fun select(runtimeId: String) {
            composition.setContent {
                CompositionLocalProvider(LocalContext provides host) {
                    key(runtimeId) {
                        HarnessRuntimeContent(runtimeId) {
                            val captured = LocalContext.current
                            contexts[runtimeId] = captured
                            picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
                                callbacks += HarnessRuntimeScope.id(captured) to it
                            }
                        }
                    }
                }
            }
        }
        select("runtime-a")
        picker.launch(arrayOf("*/*"))
        val oldRequest = host.activityResultRegistry.launches.last().requestCode
        select("runtime-b")
        picker.launch(arrayOf("application/zip"))
        val newRequest = host.activityResultRegistry.launches.last().requestCode
        assertEquals("runtime-a", HarnessRuntimeScope.id(contexts.getValue("runtime-a")))
        assertEquals("runtime-b", HarnessRuntimeScope.id(contexts.getValue("runtime-b")))
        host.activityResultRegistry.dispatchResult(oldRequest, Activity.RESULT_OK, Intent().setData(Uri.parse("content://documents/old")))
        assertTrue(callbacks.isEmpty())
        val uri = Uri.parse("content://documents/new")
        assertTrue(host.activityResultRegistry.dispatchResult(newRequest, Activity.RESULT_OK, Intent().setData(uri)))
        assertEquals(listOf("runtime-b" to uri), callbacks)
    }

    private fun withComposition(block: (Composition) -> Unit) {
        val recomposer = Recomposer(EmptyCoroutineContext)
        val composition = Composition(NoUiApplier(), recomposer)
        try {
            block(composition)
        } finally {
            composition.dispose()
            recomposer.cancel()
        }
    }

    private class RegistryContext(base: Context) : ContextWrapper(base), ActivityResultRegistryOwner {
        override val activityResultRegistry = RecordingRegistry(base)
    }

    private class RecordingRegistry(private val context: Context) : ActivityResultRegistry() {
        data class Launch(val requestCode: Int, val intent: Intent)
        val launches = mutableListOf<Launch>()
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            launches += Launch(requestCode, contract.createIntent(context, input))
        }
    }

    private class NoUiApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = error("No UI nodes expected")
        override fun insertBottomUp(index: Int, instance: Unit) = error("No UI nodes expected")
        override fun remove(index: Int, count: Int) = error("No UI nodes expected")
        override fun move(from: Int, to: Int, count: Int) = error("No UI nodes expected")
        override fun onClear() = Unit
    }
}
