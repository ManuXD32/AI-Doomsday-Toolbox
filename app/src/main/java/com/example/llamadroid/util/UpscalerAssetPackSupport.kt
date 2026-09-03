package com.example.llamadroid.util

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.example.llamadroid.service.GenerationDiagnosticsStore
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object UpscalerAssetPackSupport {
    private const val TAG = "UpscalerAssetPack"
    private const val DIAGNOSTIC_SOURCE = "upscaler_pack"
    private const val MODELS_DIR_NAME = "upscaler_models"
    private const val VERSION_MARKER_NAME = ".version_code"
    private const val PACK_NAME = "asset_upscaler"

    sealed interface PreparationState {
        data object Pending : PreparationState
        data object InstallingFeature : PreparationState
        data class Downloading(val progress: Int) : PreparationState
        data object Extracting : PreparationState
        data object RemovingPack : PreparationState
        data object Completed : PreparationState
        data class Failed(val error: String) : PreparationState
    }

    fun getModelsDir(context: Context): File = File(context.filesDir, MODELS_DIR_NAME)

    fun estimatedDownloadSizeMb(): Int = 220

    fun areModelsReady(context: Context): Boolean {
        val modelsDir = getModelsDir(context)
        val storedVersionCode = readStoredVersionCode(modelsDir)
        return !shouldRefreshExtractedModels(
            hasExtractedModels = hasExtractedModels(modelsDir),
            storedVersionCode = storedVersionCode,
            currentVersionCode = appVersionCode(context)
        )
    }

    fun needsRefresh(context: Context): Boolean {
        val modelsDir = getModelsDir(context)
        return shouldRefreshExtractedModels(
            hasExtractedModels = hasExtractedModels(modelsDir),
            storedVersionCode = readStoredVersionCode(modelsDir),
            currentVersionCode = appVersionCode(context)
        )
    }

    fun prepareForUse(context: Context): Flow<PreparationState> = flow {
        emit(PreparationState.Pending)

        if (!DynamicFeatureManager.isModuleInstalled(context, DynamicFeatureManager.MODULE_UPSCALER)) {
            GenerationDiagnosticsStore.recordBreadcrumb(
                source = DIAGNOSTIC_SOURCE,
                event = "upscaler_feature_install_requested",
                details = "module=${DynamicFeatureManager.MODULE_UPSCALER}"
            )
            emit(PreparationState.InstallingFeature)
            var featureInstallFailed = false
            DynamicFeatureManager
                .monitorModuleInstallation(context, DynamicFeatureManager.MODULE_UPSCALER)
                .collect { status ->
                    when (status) {
                        SplitInstallSessionStatus.FAILED,
                        SplitInstallSessionStatus.CANCELED -> {
                            featureInstallFailed = true
                        }

                        else -> Unit
                    }
                }

            if (featureInstallFailed || !DynamicFeatureManager.isModuleInstalled(context, DynamicFeatureManager.MODULE_UPSCALER)) {
                emit(PreparationState.Failed("Feature install failed"))
                return@flow
            }
        }

        if (!needsRefresh(context)) {
            removeInstalledPackIfPresent(context)?.let { error ->
                emit(PreparationState.Failed(error))
                return@flow
            }
            emit(PreparationState.Completed)
            return@flow
        }

        GenerationDiagnosticsStore.recordBreadcrumb(
            source = DIAGNOSTIC_SOURCE,
            event = "upscaler_pack_refresh_needed",
            details = buildRefreshDetails(context)
        )

        if (ensureLocalModelsAvailable(context).isSuccess) {
            emit(PreparationState.Extracting)
            emit(PreparationState.RemovingPack)
            removeInstalledPackIfPresent(context)?.let { error ->
                emit(PreparationState.Failed(error))
                return@flow
            }
            emit(PreparationState.Completed)
            return@flow
        }

        GenerationDiagnosticsStore.recordBreadcrumb(
            source = DIAGNOSTIC_SOURCE,
            event = "upscaler_pack_download_requested",
            details = "pack=$PACK_NAME"
        )

        var terminalError: String? = null
        AssetPackManagerUtil.downloadPack(context, AssetPackManagerUtil.AssetPack.UPSCALER).collect { state ->
            when (state) {
                is AssetPackManagerUtil.InstallState.Pending -> emit(PreparationState.Pending)
                is AssetPackManagerUtil.InstallState.Downloading -> emit(PreparationState.Downloading(state.progress))
                is AssetPackManagerUtil.InstallState.Extracting -> emit(PreparationState.Extracting)
                is AssetPackManagerUtil.InstallState.Completed -> {
                    emit(PreparationState.Extracting)
                    val extractResult = ensureLocalModelsAvailable(context)
                    if (extractResult.isFailure) {
                        terminalError = extractResult.exceptionOrNull()?.message ?: "Extraction failed"
                    } else {
                        emit(PreparationState.RemovingPack)
                        terminalError = removeInstalledPackIfPresent(context)
                    }
                }

                is AssetPackManagerUtil.InstallState.Failed -> {
                    terminalError = state.error
                }
            }
        }

        val failure = terminalError
        if (failure != null) {
            emit(PreparationState.Failed(failure))
        } else {
            emit(PreparationState.Completed)
        }
    }

    suspend fun cleanupRetainedPackIfPresent(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        GenerationDiagnosticsStore.recordBreadcrumb(
            source = DIAGNOSTIC_SOURCE,
            event = "upscaler_pack_startup_cleanup_requested",
            details = "hasExtractedModels=${hasExtractedModels(getModelsDir(context))} storedVersion=${readStoredVersionCode(getModelsDir(context))} currentVersion=${appVersionCode(context)}"
        )

        val error = removeInstalledPackIfPresent(context)
        if (error != null) {
            return@withContext Result.failure(IllegalStateException(error))
        }

        Result.success(Unit)
    }

    suspend fun ensureLocalModelsAvailable(context: Context): Result<File> = withContext(Dispatchers.IO) {
        val modelsDir = getModelsDir(context)
        if (!needsRefresh(context)) {
            return@withContext Result.success(modelsDir)
        }

        val currentVersionCode = appVersionCode(context)
        val sourceDir = findAvailableSourceDirectory(context)
        val extracted = runCatching {
            GenerationDiagnosticsStore.recordBreadcrumb(
                source = DIAGNOSTIC_SOURCE,
                event = "upscaler_pack_extract_started",
                details = "source=${sourceDir?.absolutePath ?: "bundled_assets"} versionCode=$currentVersionCode"
            )

            if (modelsDir.exists()) {
                modelsDir.deleteRecursively()
            }
            modelsDir.mkdirs()

            if (sourceDir != null) {
                sourceDir.copyRecursively(modelsDir, overwrite = true)
            } else if (hasBundledAssets(context.assets)) {
                copyBundledAssets(context.assets, modelsDir)
            } else {
                error("No upscaler asset source is available")
            }

            writeStoredVersionCode(modelsDir, currentVersionCode)
            cleanupLegacyExtractedPackCache(context)
            GenerationDiagnosticsStore.recordBreadcrumb(
                source = DIAGNOSTIC_SOURCE,
                event = "upscaler_pack_extract_finished",
                details = "versionCode=$currentVersionCode modelsDir=${modelsDir.absolutePath}"
            )
            modelsDir
        }

        extracted.onFailure { error ->
            Log.e(TAG, "Failed to refresh extracted upscaler models", error)
        }

        return@withContext extracted
    }

    internal fun shouldRefreshExtractedModels(
        hasExtractedModels: Boolean,
        storedVersionCode: Long?,
        currentVersionCode: Long
    ): Boolean {
        return !hasExtractedModels
    }

    internal fun hasExtractedModels(modelsDir: File): Boolean {
        return modelsDir.exists() &&
            modelsDir.walkTopDown().any { file ->
                file.isFile && file.name != VERSION_MARKER_NAME
            }
    }

    internal fun readStoredVersionCode(modelsDir: File): Long? {
        val markerFile = File(modelsDir, VERSION_MARKER_NAME)
        return markerFile
            .takeIf { it.exists() }
            ?.readText()
            ?.trim()
            ?.toLongOrNull()
    }

    private fun writeStoredVersionCode(modelsDir: File, versionCode: Long) {
        File(modelsDir, VERSION_MARKER_NAME).writeText(versionCode.toString())
    }

    private fun buildRefreshDetails(context: Context): String {
        val modelsDir = getModelsDir(context)
        return "storedVersion=${readStoredVersionCode(modelsDir)} currentVersion=${appVersionCode(context)} hasExtractedModels=${hasExtractedModels(modelsDir)}"
    }

    private fun appVersionCode(context: Context): Long {
        return AppVersionCodeCompat.read(
            context.packageManager.getPackageInfo(context.packageName, 0)
        )
    }

    private fun findAvailableSourceDirectory(context: Context): File? {
        val legacyExtractedDir = File(AssetPackManagerUtil.getBinariesDir(context), MODELS_DIR_NAME)
        if (legacyExtractedDir.exists()) {
            return legacyExtractedDir
        }

        val packAssetsPath = AssetPackManagerFactory
            .getInstance(context)
            .getPackLocation(PACK_NAME)
            ?.assetsPath()
            ?.let(::File)
            ?.resolve(MODELS_DIR_NAME)
        if (packAssetsPath != null && packAssetsPath.exists()) {
            return packAssetsPath
        }

        return null
    }

    private fun hasBundledAssets(assetManager: AssetManager): Boolean {
        return try {
            !assetManager.list(MODELS_DIR_NAME).isNullOrEmpty()
        } catch (_: Exception) {
            false
        }
    }

    private fun copyBundledAssets(assetManager: AssetManager, destinationDir: File) {
        val modelDirs = assetManager.list(MODELS_DIR_NAME).orEmpty()
        for (modelDir in modelDirs) {
            val targetDir = File(destinationDir, modelDir).apply { mkdirs() }
            val modelFiles = assetManager.list("$MODELS_DIR_NAME/$modelDir").orEmpty()
            for (filename in modelFiles) {
                assetManager.open("$MODELS_DIR_NAME/$modelDir/$filename").use { input ->
                    File(targetDir, filename).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun cleanupLegacyExtractedPackCache(context: Context) {
        val binariesDir = AssetPackManagerUtil.getBinariesDir(context)
        val legacyModelsDir = File(binariesDir, MODELS_DIR_NAME)
        if (legacyModelsDir.exists()) {
            legacyModelsDir.deleteRecursively()
        }
        File(binariesDir, ".$PACK_NAME" + "_extracted").delete()
    }

    private suspend fun removeInstalledPackIfPresent(context: Context): String? = withContext(Dispatchers.IO) {
        val manager = AssetPackManagerFactory.getInstance(context)
        val packLocation = manager.getPackLocation(PACK_NAME) ?: return@withContext null

        val latch = CountDownLatch(1)
        var failureMessage: String? = null
        manager.removePack(PACK_NAME)
            .addOnSuccessListener {
                GenerationDiagnosticsStore.recordBreadcrumb(
                    source = DIAGNOSTIC_SOURCE,
                    event = "upscaler_pack_removed",
                    details = "pack=$PACK_NAME location=${packLocation.assetsPath()}"
                )
                latch.countDown()
            }
            .addOnFailureListener { error ->
                failureMessage = error.message ?: error.javaClass.simpleName
                latch.countDown()
            }

        val finished = latch.await(30, TimeUnit.SECONDS)
        if (!finished) {
            val timeoutMessage = "Timed out removing asset pack $PACK_NAME"
            Log.w(TAG, timeoutMessage)
            return@withContext timeoutMessage
        }

        failureMessage?.let { message ->
            Log.e(TAG, "Failed to remove asset pack $PACK_NAME: $message")
            return@withContext message
        }

        null
    }
}
