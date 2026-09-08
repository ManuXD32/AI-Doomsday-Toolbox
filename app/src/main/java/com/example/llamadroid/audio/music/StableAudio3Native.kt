package com.example.llamadroid.audio.music

import java.util.Locale

/**
 * JNI boundary for the Stable Audio 3 graph runner.
 *
 * The worker process owns this object. It is intentionally separate from the
 * chat LiteRT-LM service: Stable Audio loads several large graphs and its
 * cancellation/restart policy must not affect an active chat session.
 */
internal object StableAudio3Native {
    interface ProgressSink {
        fun onProgress(stage: String, completed: Int, total: Int)
    }

    @Volatile
    private var loaded = false

    @Volatile
    private var loadFailure: Throwable? = null

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loadFailure?.let { throw it }
        try {
            System.loadLibrary("stableaudio_worker")
            loaded = true
        } catch (error: Throwable) {
            loadFailure = error
            throw error
        }
    }

    fun run(request: StableAudio3Request, progressSink: ProgressSink): String {
        ensureLoaded()
        val loraPaths = request.loras.map { it.path }.toTypedArray()
        val loraStrengths = request.loras.map { it.strength }.toFloatArray()
        // ModelEntity.sourceIdentity is normally a sha256:<digest>; it cannot
        // identify SAME-L/S. Prefer the materialized path (the curated bundle
        // directory carries the codec family), and only use a known family in
        // sourceIdentity when a caller has no useful path marker.
        val codecPath = request.components.codecDecoder.path.lowercase(Locale.US)
        val codecFamily = when {
            "same-l" in codecPath || "same_l" in codecPath -> "same-l"
            "same-s" in codecPath || "same_s" in codecPath -> "same-s"
            else -> request.components.codecDecoder.sourceIdentity.orEmpty()
                .lowercase(Locale.US)
                .takeIf { it.contains("same-l") || it.contains("same_l") ||
                    it.contains("same-s") || it.contains("same_s") }
                ?: codecPath
        }
        return nativeRun(
            tokenizerPath = request.components.tokenizer.path,
            textEncoderPath = request.components.textEncoder.path,
            ditPath = request.components.dit.path,
            decoderPath = request.components.codecDecoder.path,
            encoderPath = request.components.codecEncoder?.path.orEmpty(),
            prompt = request.prompt,
            negativePrompt = request.negativePrompt.orEmpty(),
            durationSeconds = request.durationSeconds,
            steps = request.steps,
            seed = request.seed,
            initNoiseLevel = request.initNoiseLevel,
            cfg = request.cfg,
            apg = request.apg,
            cfgBatched = request.cfgBatched,
            initAudioPath = request.initAudioPath.orEmpty(),
            maskStartSeconds = request.maskStartSeconds ?: Double.NaN,
            maskEndSeconds = request.maskEndSeconds ?: Double.NaN,
            threads = request.threads,
            freeModels = request.freeModels,
            ditPrecision = request.ditPrecision.wireValue,
            decoderPrecision = request.decoderPrecision.wireValue,
            encoderPrecision = request.encoderPrecision.wireValue,
            maxRung = request.maxRung ?: 0,
            codecFamily = codecFamily,
            loraPaths = loraPaths,
            loraStrengths = loraStrengths,
            outputPath = request.outputPath,
            progressSink = progressSink
        )
    }

    fun cancel() {
        if (!loaded) return
        runCatching { nativeCancel() }
    }

    @JvmStatic
    private external fun nativeRun(
        tokenizerPath: String,
        textEncoderPath: String,
        ditPath: String,
        decoderPath: String,
        encoderPath: String,
        prompt: String,
        negativePrompt: String,
        durationSeconds: Double,
        steps: Int,
        seed: Long,
        initNoiseLevel: Float,
        cfg: Float,
        apg: Float,
        cfgBatched: Boolean,
        initAudioPath: String,
        maskStartSeconds: Double,
        maskEndSeconds: Double,
        threads: Int,
        freeModels: Boolean,
        ditPrecision: String,
        decoderPrecision: String,
        encoderPrecision: String,
        maxRung: Int,
        codecFamily: String,
        loraPaths: Array<String>,
        loraStrengths: FloatArray,
        outputPath: String,
        progressSink: ProgressSink
    ): String

    @JvmStatic
    private external fun nativeCancel()
}
