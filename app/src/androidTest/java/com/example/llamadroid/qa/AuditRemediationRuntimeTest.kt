package com.example.llamadroid.qa

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.MainActivity
import com.example.llamadroid.R
import com.example.llamadroid.data.SharedFileHolder
import com.example.llamadroid.data.SharedFileTarget
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.BundleProgressEntry
import com.example.llamadroid.data.model.calculateBundleProgressSnapshot
import com.example.llamadroid.service.MangaTranslationSupport
import com.example.llamadroid.service.WorkerMemoryBudget
import com.example.llamadroid.ui.ai.MicrophonePermissionState
import com.example.llamadroid.ui.ai.classifyMicrophonePermission
import com.example.llamadroid.ui.components.isCompactAppNavigation
import com.example.llamadroid.ui.navigation.ExternalRouteResolution
import com.example.llamadroid.ui.navigation.ExternalRouteResolver
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.util.ContentUriMetadataResolver
import java.io.File
import java.io.IOException
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Small on-device contract suite for the audit fixes which do not require a model download.
 *
 * These checks intentionally exercise Android's real ContentResolver, resources and filesystem
 * in addition to the pure calculators already covered by the JVM suite. They are suitable for
 * the API 26/28/30 smoke matrix when those emulator images are available.
 */
@RunWith(AndroidJUnit4::class)
class AuditRemediationRuntimeTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() {
        SharedFileHolder.clear()
        context.cacheDir.listFiles()
            ?.filter { it.name.startsWith(TEST_FILE_PREFIX) }
            ?.forEach(File::delete)
    }

    @Test
    fun mainActivityUsesSingleTopSoWarmExternalRoutesReachOnNewIntent() {
        val activityInfo = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            0
        )

        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, activityInfo.launchMode)
    }

    @Test
    fun externalRoutesAcceptAliasesAndEveryImageModeButRejectMalformedInput() {
        val accepted = mapOf(
            "models" to Screen.ModelHub.route,
            "stats" to Screen.Stats.route,
            "kiwix_hub" to Screen.ZimManager.route,
            "image_gen_upscale" to Screen.ImageGen.createRoute(2)
        ) + (0..4).associate { mode ->
            Screen.ImageGen.createRoute(mode) to Screen.ImageGen.createRoute(mode)
        }

        accepted.forEach { (input, expected) ->
            assertEquals(expected, ExternalRouteResolver.resolveRoute(input))
        }
        listOf("", "image_gen?startMode=", "image_gen?startMode=5", "models/extra")
            .forEach { input ->
                assertEquals(ExternalRouteResolution.Rejected, ExternalRouteResolver.resolve(input))
            }
    }

    @Test
    fun pdfShareTargetsRemainOwnedAndRepeatedUrisStayDistinct() {
        val uri = Uri.parse("content://qa.provider/document/msf%3A84")
        val first = SharedFileHolder.setPendingFile(
            uri = uri,
            mimeType = PDF_MIME_TYPE,
            target = SharedFileTarget.PDF_TOOLBOX
        )

        assertEquals(null, SharedFileHolder.consumeFor(SharedFileTarget.PDF_SUMMARY))
        assertEquals(first.id, SharedFileHolder.consumeFor(SharedFileTarget.PDF_TOOLBOX)?.id)

        val second = SharedFileHolder.setPendingFile(
            uri = uri,
            mimeType = PDF_MIME_TYPE,
            target = SharedFileTarget.PDF_SUMMARY
        )
        val third = SharedFileHolder.setPendingFile(
            uri = uri,
            mimeType = PDF_MIME_TYPE,
            target = SharedFileTarget.PDF_SUMMARY
        )
        assertNotEquals(second.id, third.id)
        assertEquals(third.id, SharedFileHolder.consumeFor(SharedFileTarget.PDF_SUMMARY)?.id)

        SharedFileHolder.setPendingFile(uri, PDF_MIME_TYPE, SharedFileTarget.PDF_TOOLBOX)
        SharedFileHolder.clear()
        assertEquals(null, SharedFileHolder.consumeFor(SharedFileTarget.PDF_TOOLBOX))
    }

    @Test
    fun providerMetadataAndCacheImportPreserveUnicodePdfName() {
        val source = File(context.cacheDir, "${TEST_FILE_PREFIX}_Informe_ñ_漢字.pdf")
        val bytes = "%PDF-1.4\nqa".toByteArray()
        source.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            source
        )
        val metadata = ContentUriMetadataResolver.resolve(context, uri)
        assertEquals(source.name, metadata.displayName)
        assertEquals(bytes.size.toLong(), metadata.sizeBytes)

        val request = SharedFileHolder.setPendingFile(
            uri = uri,
            mimeType = PDF_MIME_TYPE,
            target = SharedFileTarget.PDF_SUMMARY
        )
        val imported = SharedFileHolder.importToCache(
            context = context,
            pendingFile = request,
            fallbackDisplayName = "Document.pdf",
            filePrefix = TEST_FILE_PREFIX
        )

        assertEquals(source.name, imported.displayName)
        assertTrue(File(requireNotNull(imported.uri.path)).readBytes().contentEquals(bytes))
    }

    @Test
    fun unreadableProviderUriProducesRecoverableImportFailure() {
        val request = SharedFileHolder.setPendingFile(
            uri = Uri.parse("content://com.example.missing.provider/document/84"),
            mimeType = PDF_MIME_TYPE,
            target = SharedFileTarget.PDF_TOOLBOX
        )

        var failure: Throwable? = null
        try {
            SharedFileHolder.importToCache(
                context = context,
                pendingFile = request,
                fallbackDisplayName = "Document.pdf",
                filePrefix = TEST_FILE_PREFIX
            )
        } catch (error: Throwable) {
            failure = error
        }
        assertTrue("Expected an unreadable grant to fail with IOException", failure is IOException)
    }

    @Test
    fun microphonePermissionClassifierCoversRetryRationaleSettingsAndGrantedStates() {
        assertEquals(
            MicrophonePermissionState.Requestable,
            classifyMicrophonePermission(false, false, false)
        )
        assertEquals(
            MicrophonePermissionState.RationaleRequired,
            classifyMicrophonePermission(false, true, true)
        )
        assertEquals(
            MicrophonePermissionState.PermanentlyDenied,
            classifyMicrophonePermission(false, true, false)
        )
        assertEquals(
            MicrophonePermissionState.Granted,
            classifyMicrophonePermission(true, true, false)
        )
    }

    @Test
    fun legacyImportedOcrAndProjectorRowsResolveFromRealFiles() {
        fun modelFile(name: String): String = File(context.cacheDir, "${TEST_FILE_PREFIX}_$name")
            .apply { writeText("gguf") }
            .absolutePath

        val visionModel = ModelEntity(
            filename = "unlimited-ocr.gguf",
            path = modelFile("unlimited-ocr.gguf"),
            sizeBytes = 4L,
            type = ModelType.LLM,
            repoId = "local-import",
            isDownloaded = false,
            isVision = true
        )
        val projector = ModelEntity(
            filename = "mmproj-unlimited.gguf",
            path = modelFile("mmproj-unlimited.gguf"),
            sizeBytes = 4L,
            type = ModelType.MMPROJ,
            repoId = "local-import",
            isDownloaded = false
        )
        val missing = visionModel.copy(
            filename = "missing.gguf",
            path = File(context.cacheDir, "${TEST_FILE_PREFIX}_missing.gguf").absolutePath
        )

        assertEquals(listOf(visionModel), MangaTranslationSupport.installedOcrModels(listOf(missing, visionModel)))
        assertEquals(listOf(projector), MangaTranslationSupport.installedProjectors(listOf(projector)))
    }

    @Test
    fun responsiveBoundaryMatrixAndEnglishSpanishLabelsResolveOnDevice() {
        assertTrue(isCompactAppNavigation(widthDp = 320, fontScale = 1f))
        assertFalse(isCompactAppNavigation(widthDp = 360, fontScale = 1f))
        assertFalse(isCompactAppNavigation(widthDp = 411, fontScale = 1f))
        assertFalse(isCompactAppNavigation(widthDp = 600, fontScale = 1f))
        listOf(320, 360, 411, 600).forEach { width ->
            assertTrue(isCompactAppNavigation(widthDp = width, fontScale = 2f))
        }

        fun localizedString(locale: Locale, resourceId: Int): String {
            val configuration = Configuration(context.resources.configuration)
            configuration.setLocale(locale)
            return context.createConfigurationContext(configuration).getString(resourceId)
        }
        assertEquals("Home", localizedString(Locale.ENGLISH, R.string.responsive_nav_home))
        assertEquals("More", localizedString(Locale.ENGLISH, R.string.responsive_nav_more))
        val spanish = Locale.forLanguageTag("es")
        assertEquals("Inicio", localizedString(spanish, R.string.responsive_nav_home))
        assertEquals("Más", localizedString(spanish, R.string.responsive_nav_more))
    }

    @Test
    fun progressAndWorkerBudgetsRecalculateFromRuntimeSnapshots() {
        val first = calculateBundleProgressSnapshot(
            listOf(
                BundleProgressEntry("large", 1_000L, active = true, liveFraction = 0.4f),
                BundleProgressEntry("small", 100L, completed = true)
            )
        )
        val advanced = calculateBundleProgressSnapshot(
            listOf(
                BundleProgressEntry("large", 1_000L, active = true, liveFraction = 0.6f),
                BundleProgressEntry("small", 100L, completed = true)
            ),
            previousSnapshot = first
        )
        assertEquals(500L, first.downloadedBytes)
        assertEquals(400L, advanced.remainingBytes)
        assertTrue(advanced.downloadedBytes > first.downloadedBytes)

        val stopped = WorkerMemoryBudget.calculate(4_096L, 3_000L, 2_000L)
        val launch = WorkerMemoryBudget.calculate(4_096L, 600L, stopped.contributionMiB)
        assertEquals(2_000L, stopped.contributionMiB)
        assertEquals(88L, launch.contributionMiB)
        assertFalse(launch.canLaunch)
    }

    private companion object {
        const val PDF_MIME_TYPE = "application/pdf"
        const val TEST_FILE_PREFIX = "audit_runtime"
    }
}
