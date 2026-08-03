package com.example.llamadroid.service

/**
 * Validation shared by the distributed UI, previews, and the actual llama-server
 * command builder. A single fit target is deliberately preserved as a single
 * value because llama.cpp broadcasts it to every device.
 */
object DistributedLlamaArguments {
    fun normalizeFitTarget(raw: String?, deviceCount: Int): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        require(deviceCount > 0) { "At least one distributed device is required" }
        val values = value.split(',').map { token ->
            token.trim().also { tokenValue ->
                require(tokenValue.toIntOrNull()?.let { it > 0 } == true) {
                    "fit-target values must be positive MiB integers"
                }
            }
        }
        require(values.size == 1 || values.size == deviceCount) {
            "fit-target must contain one value or exactly $deviceCount device values"
        }
        return values.joinToString(",")
    }

    fun normalizeTensorSplit(raw: String?, deviceCount: Int): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        require(deviceCount > 0) { "At least one distributed device is required" }
        val values = value.split(',').map { token ->
            token.trim().also { tokenValue ->
                require(tokenValue.toFloatOrNull()?.let { it >= 0f } == true) {
                    "tensor-split values must be non-negative numbers"
                }
            }
        }
        require(values.size == deviceCount) {
            "tensor-split must contain exactly $deviceCount device values"
        }
        require(values.sumOf { it.toFloatOrNull()?.toDouble() ?: 0.0 } > 0.0) {
            "tensor-split must assign some model share"
        }
        return values.joinToString(",")
    }

    fun validate(deviceCount: Int, fitEnabled: Boolean, fitTargetMiB: String?, tensorSplit: String?) {
        if (fitEnabled) normalizeFitTarget(fitTargetMiB, deviceCount)
        normalizeTensorSplit(tensorSplit, deviceCount)
    }
}
