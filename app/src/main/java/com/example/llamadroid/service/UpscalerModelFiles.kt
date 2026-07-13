package com.example.llamadroid.service

import java.io.File

data class UpscalerResolvedModelFiles(
    val modelDir: File,
    val paramFile: File,
    val binFile: File
)

sealed interface UpscalerModelValidationResult {
    data class Success(val files: UpscalerResolvedModelFiles) : UpscalerModelValidationResult
    data class MissingModelDirectory(val modelDir: File) : UpscalerModelValidationResult
    data class MissingModelFiles(val files: UpscalerResolvedModelFiles) : UpscalerModelValidationResult
}

object UpscalerModelFiles {

    fun availableScales(
        modelsRoot: File,
        model: UpscalerModelCapability,
        denoise: Int = -1
    ): List<Int> = model.scales.filter { scale ->
        when (validate(modelsRoot, model, scale, denoise)) {
            is UpscalerModelValidationResult.Success -> true
            else -> false
        }
    }

    fun validate(
        modelsRoot: File,
        model: UpscalerModelCapability,
        scale: Int,
        denoise: Int = -1
    ): UpscalerModelValidationResult {
        val resolved = resolve(modelsRoot, model, scale, denoise)
        if (!resolved.modelDir.exists() || !resolved.modelDir.isDirectory) {
            return UpscalerModelValidationResult.MissingModelDirectory(resolved.modelDir)
        }
        if (!resolved.paramFile.exists() || !resolved.binFile.exists()) {
            return UpscalerModelValidationResult.MissingModelFiles(resolved)
        }
        return UpscalerModelValidationResult.Success(resolved)
    }

    fun resolve(
        modelsRoot: File,
        model: UpscalerModelCapability,
        scale: Int,
        denoise: Int = -1
    ): UpscalerResolvedModelFiles {
        val modelDir = File(modelsRoot, model.name)
        val baseName = when (model.engine) {
            UpscalerEngine.REALSR -> "x${scale}"
            UpscalerEngine.REALCUGAN -> when {
                denoise < 0 -> "up${scale}x-conservative"
                denoise == 0 -> "up${scale}x-no-denoise"
                else -> "up${scale}x-denoise${denoise}x"
            }
        }
        return UpscalerResolvedModelFiles(
            modelDir = modelDir,
            paramFile = File(modelDir, "$baseName.param"),
            binFile = File(modelDir, "$baseName.bin")
        )
    }
}
