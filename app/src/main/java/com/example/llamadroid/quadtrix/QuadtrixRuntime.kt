package com.example.llamadroid.quadtrix

import com.example.llamadroid.data.db.QuadtrixMetricEntity
import com.example.llamadroid.data.db.QuadtrixProfileEntity
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class QuadtrixCommandSpec(
    val args: List<String>,
    val modelPath: File,
    val modelDir: File,
    val ggufPath: File?,
    val workersFile: File
)

object QuadtrixOptionKeys {
    const val PROFILE_NAME = "profile-name"
    const val MODEL_PATH = "model-path"
    const val ARCH = "arch"
    const val TOKENIZER = "tokenizer"
    const val QWEN_TOKENIZER_JSON = "qwen-tokenizer-json"
    const val BATCH_SIZE = "batch-size"
    const val GRAD_ACCUM_STEPS = "grad-accum-steps"
    const val BLOCK_SIZE = "block-size"
    const val MAX_ITERS = "max-iters"
    const val EVAL_INTERVAL = "eval-interval"
    const val EVAL_ITERS = "eval-iters"
    const val LOG_INTERVAL = "log-interval"
    const val THREADS = "threads"
    const val LEARNING_RATE = "learning-rate"
    const val GRAD_CLIP = "grad-clip"
    const val OPTIMIZER = "optimizer"
    const val WEIGHT_STORAGE = "weight-storage"
    const val ACTIVATION_QUANT_BITS = "activation-quant-bits"
    const val OPTIMIZER_STATE_BITS = "optimizer-state-bits"
    const val STRICT_QUANTIZED_WEIGHTS = "strict-quantized-weights"
    const val MATH_BACKEND = "math-backend"
    const val DROPOUT = "dropout"
    const val TRAIN_SPLIT = "train-split"
    const val N_EMBD = "n-embd"
    const val N_HEAD = "n-head"
    const val N_LAYER = "n-layer"
    const val SEED = "seed"
    const val CHECKPOINT_EVERY = "checkpoint-every"
    const val SKIP_INITIAL_EVAL = "skip-initial-eval"
    const val RESUME = "resume"
    const val RESUME_FROM = "resume-from"
    const val N_KV_HEAD = "n-kv-head"
    const val HEAD_DIM = "head-dim"
    const val INTERMEDIATE_SIZE = "intermediate-size"
    const val ROPE_THETA = "rope-theta"
    const val RMS_NORM_EPS = "rms-norm-eps"
    const val TIE_WORD_EMBEDDINGS = "tie-word-embeddings"
    const val TOKEN_CACHE = "token-cache"
    const val TOKEN_CACHE_DIR = "token-cache-dir"
    const val TOKENIZE_LOG_INTERVAL_SEC = "tokenize-log-interval-sec"
    const val TOKENIZATION_MODE = "tokenization-mode"
    const val PARQUET_TEXT_COLUMN = "parquet-text-column"
    const val PARQUET_INSTRUCTION_COLUMN = "parquet-instruction-column"
    const val PARQUET_INPUT_COLUMN = "parquet-input-column"
    const val PARQUET_OUTPUT_COLUMN = "parquet-output-column"
    const val DIST_MODE = "dist-mode"
    const val DIST_ROLE = "dist-role"
    const val WORKER_HOST = "worker-host"
    const val WORKER_PORT = "worker-port"
    const val WORKER_TOKEN = "worker-token"
    const val DIST_WORKERS = "dist-workers"
    const val DIST_SYNC_INTERVAL = "dist-sync-interval"
    const val DIST_GRADIENT_BITS = "dist-gradient-bits"
    const val DIST_SHARDS = "dist-shards"
    const val DIST_COORDINATOR_COMPUTE = "dist-coordinator-compute"
    const val DIST_COORDINATOR_ONLY = "dist-coordinator-only"
    const val DIST_RPC_TIMEOUT_SEC = "dist-rpc-timeout-sec"
    const val DIST_REPROBE_INTERVAL = "dist-reprobe-interval"
    const val PRINT_SYSTEM_INFO = "print-system-info"
    const val EXPORT_GGUF = "export-gguf"
    const val SAVE_GGUF_AFTER_TRAIN = "save-gguf-after-train"
    const val GGUF_OUTTYPE = "gguf-outtype"
    const val GGUF_NAME = "gguf-name"
    const val WEB_HOST = "web-host"
    const val WEB_PORT = "web-port"
    const val NO_GENERATE_AFTER_TRAIN = "no-generate-after-train"

    val defaultEnabled: Set<String> = setOf(
        PROFILE_NAME,
        MODEL_PATH,
        ARCH,
        TOKENIZER,
        NO_GENERATE_AFTER_TRAIN
    )

    val defaultCsv: String = serialize(defaultEnabled)

    fun parse(csv: String): Set<String> =
        csv.split(',', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
            .ifEmpty { defaultEnabled }

    fun serialize(keys: Set<String>): String =
        keys.toSortedSet().joinToString(",")
}

data class QuadtrixWorkerSpec(
    val enabled: Boolean = true,
    val name: String = "",
    val endpoint: String = ""
)

object QuadtrixWorkerSpecs {
    fun parse(raw: String): List<QuadtrixWorkerSpec> {
        var index = 0
        return raw.split('\n', ',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                index += 1
                parseLine(line, index)
            }
            .distinctBy { it.endpoint }
    }

    fun serialize(workers: List<QuadtrixWorkerSpec>): String =
        workers
            .filter { it.endpoint.isNotBlank() }
            .joinToString("\n") { worker ->
                "${if (worker.enabled) "1" else "0"}|${worker.name.sanitizeField()}|${worker.endpoint.trim()}"
            }

    fun enabledEndpoints(raw: String): List<String> =
        parse(raw).filter { it.enabled }.map { it.endpoint }.filter { it.isNotBlank() }.distinct()

    fun workerFileText(raw: String): String =
        parse(raw)
            .filter { it.endpoint.isNotBlank() }
            .joinToString("\n") { "${if (it.enabled) "1" else "0"} ${it.endpoint}" }

    private fun parseLine(line: String, index: Int): QuadtrixWorkerSpec? {
        val pipeParts = line.split('|', limit = 3)
        if (pipeParts.size == 3) {
            val enabled = pipeParts[0].trim() != "0"
            val name = pipeParts[1].trim()
            val endpoint = pipeParts[2].trim()
            return endpoint.takeIf { it.isNotBlank() }?.let {
                QuadtrixWorkerSpec(enabled = enabled, name = name.ifBlank { "Worker $index" }, endpoint = it)
            }
        }
        val enabled = !line.startsWith("0 ")
        val cleaned = line.removePrefix("1 ").removePrefix("0 ").trim()
        val endpoint = cleaned.substringAfterLast(' ').trim()
        return endpoint.takeIf { it.isNotBlank() }?.let {
            QuadtrixWorkerSpec(enabled = enabled, name = "Worker $index", endpoint = it)
        }
    }

    private fun String.sanitizeField(): String =
        replace('|', ' ').replace(',', ' ').replace('\n', ' ').trim()
}

object QuadtrixPaths {
    const val ROOT = "quadtrix"
    const val MODELS = "models"
    const val PROFILES = "profiles"
    const val LOGS = "logs"
    const val EXPORTS = "exports"
    const val DATA = "data"
    const val TOKEN_CACHE = "token_cache"

    fun safeName(value: String): String =
        value.trim().ifBlank { "default" }
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "default" }
}

object QuadtrixCommandBuilder {
    fun trainingArgs(
        executable: File,
        rootDir: File,
        profile: QuadtrixProfileEntity,
        workerOnly: Boolean = false,
        tokenizeOnly: Boolean = false
    ): QuadtrixCommandSpec {
        val profileName = QuadtrixPaths.safeName(profile.name)
        val modelDir = File(rootDir, "${QuadtrixPaths.MODELS}/$profileName").apply { mkdirs() }
        val workersDir = File(rootDir, QuadtrixPaths.PROFILES).apply { mkdirs() }
        val modelFile = resolveModelPath(modelDir, profile)
        val ggufFile = resolveGgufPath(modelDir, profile)
        val workersFile = File(workersDir, "$profileName.workers.txt")
        val enabled = QuadtrixOptionKeys.parse(profile.enabledOptions)
        val args = mutableListOf(executable.absolutePath)
        fun has(key: String): Boolean = key in enabled
        fun addEnabled(flag: String, key: String, value: String) {
            if (has(key)) args += listOf(flag, value)
        }
        fun addEnabledOptional(flag: String, key: String, value: String?) {
            if (has(key)) addOptional(args, flag, value)
        }
        fun addEnabledPositive(flag: String, key: String, value: Int) {
            if (has(key)) addPositive(args, flag, value)
        }

        if (workerOnly) {
            args += "--worker-only"
            addEnabled("--worker-host", QuadtrixOptionKeys.WORKER_HOST, profile.workerHost)
            addEnabled("--worker-port", QuadtrixOptionKeys.WORKER_PORT, profile.workerPort.toString())
            addEnabledOptional("--worker-token", QuadtrixOptionKeys.WORKER_TOKEN, profile.workerToken)
            addEnabled("--threads", QuadtrixOptionKeys.THREADS, profile.threads.toString())
            return QuadtrixCommandSpec(args, modelFile, modelDir, ggufFile, workersFile)
        }

        val normalizedArch = profile.arch.ifBlank { "qwen3" }
        val normalizedTokenizer = if (normalizedArch == "qwen3") "qwen3" else profile.tokenizer.ifBlank { "char" }

        args += profile.datasetPath
        addEnabled("--arch", QuadtrixOptionKeys.ARCH, normalizedArch)
        addEnabled("--tokenizer", QuadtrixOptionKeys.TOKENIZER, normalizedTokenizer)
        addEnabled("--profile-name", QuadtrixOptionKeys.PROFILE_NAME, profileName)
        addEnabled("--model-path", QuadtrixOptionKeys.MODEL_PATH, modelFile.absolutePath)
        addEnabled("--batch-size", QuadtrixOptionKeys.BATCH_SIZE, profile.batchSize.toString())
        addEnabled("--grad-accum-steps", QuadtrixOptionKeys.GRAD_ACCUM_STEPS, profile.gradAccumSteps.toString())
        addEnabled("--block-size", QuadtrixOptionKeys.BLOCK_SIZE, profile.blockSize.toString())
        addEnabled("--max-iters", QuadtrixOptionKeys.MAX_ITERS, profile.maxIters.toString())
        addEnabled("--eval-interval", QuadtrixOptionKeys.EVAL_INTERVAL, profile.evalInterval.toString())
        addEnabled("--eval-iters", QuadtrixOptionKeys.EVAL_ITERS, profile.evalIters.toString())
        addEnabled("--log-interval", QuadtrixOptionKeys.LOG_INTERVAL, profile.logInterval.toString())
        addEnabled("--threads", QuadtrixOptionKeys.THREADS, profile.threads.toString())
        addEnabled("--learning-rate", QuadtrixOptionKeys.LEARNING_RATE, profile.learningRate)
        addEnabled("--grad-clip", QuadtrixOptionKeys.GRAD_CLIP, profile.gradClip)
        addEnabled("--optimizer", QuadtrixOptionKeys.OPTIMIZER, profile.optimizer)
        addEnabled("--weight-storage", QuadtrixOptionKeys.WEIGHT_STORAGE, profile.weightStorage)
        addEnabled("--activation-quant-bits", QuadtrixOptionKeys.ACTIVATION_QUANT_BITS, profile.activationQuantBits.toString())
        addEnabled("--optimizer-state-bits", QuadtrixOptionKeys.OPTIMIZER_STATE_BITS, profile.optimizerStateBits.toString())
        addEnabled("--math-backend", QuadtrixOptionKeys.MATH_BACKEND, profile.mathBackend)
        addEnabled("--dropout", QuadtrixOptionKeys.DROPOUT, profile.dropout)
        addEnabled("--train-split", QuadtrixOptionKeys.TRAIN_SPLIT, profile.trainSplit)
        addEnabled("--n-embd", QuadtrixOptionKeys.N_EMBD, profile.nEmbd.toString())
        addEnabled("--n-head", QuadtrixOptionKeys.N_HEAD, profile.nHead.toString())
        addEnabled("--n-layer", QuadtrixOptionKeys.N_LAYER, profile.nLayer.toString())
        addEnabled("--seed", QuadtrixOptionKeys.SEED, profile.seed.toString())

        addEnabledOptional("--qwen-tokenizer-json", QuadtrixOptionKeys.QWEN_TOKENIZER_JSON, profile.qwenTokenizerJsonPath)
        if (normalizedArch == "qwen3") {
            addEnabledPositive("--n-kv-head", QuadtrixOptionKeys.N_KV_HEAD, profile.nKvHead)
            addEnabledPositive("--head-dim", QuadtrixOptionKeys.HEAD_DIM, profile.headDim)
            addEnabledPositive("--intermediate-size", QuadtrixOptionKeys.INTERMEDIATE_SIZE, profile.intermediateSize)
            addEnabledOptional("--rope-theta", QuadtrixOptionKeys.ROPE_THETA, profile.ropeTheta)
            addEnabledOptional("--rms-norm-eps", QuadtrixOptionKeys.RMS_NORM_EPS, profile.rmsNormEps)
            if (has(QuadtrixOptionKeys.TIE_WORD_EMBEDDINGS)) {
                args += if (profile.tieWordEmbeddings) "--tie-word-embeddings" else "--no-tie-word-embeddings"
            }
            if (has(QuadtrixOptionKeys.TOKEN_CACHE)) {
                args += listOf("--token-cache", profile.tokenCacheMode.ifBlank { "auto" })
            }
            args += listOf("--token-cache-dir", resolveTokenCacheDir(rootDir, profile).absolutePath)
            addEnabled("--tokenization-mode", QuadtrixOptionKeys.TOKENIZATION_MODE, profile.tokenizationMode.ifBlank { "records" })
            addEnabled("--tokenize-log-interval-sec", QuadtrixOptionKeys.TOKENIZE_LOG_INTERVAL_SEC, profile.tokenizeLogIntervalSec.coerceAtLeast(1).toString())
            if (tokenizeOnly) args += "--tokenize-only"
        }
        if (has(QuadtrixOptionKeys.CHECKPOINT_EVERY)) addOptional(args, "--checkpoint-every", profile.checkpointEvery.takeIf { it > 0 }?.toString())
        addEnabledOptional("--parquet-text-column", QuadtrixOptionKeys.PARQUET_TEXT_COLUMN, profile.parquetTextColumn)
        addEnabledOptional("--parquet-instruction-column", QuadtrixOptionKeys.PARQUET_INSTRUCTION_COLUMN, profile.parquetInstructionColumn)
        addEnabledOptional("--parquet-input-column", QuadtrixOptionKeys.PARQUET_INPUT_COLUMN, profile.parquetInputColumn)
        addEnabledOptional("--parquet-output-column", QuadtrixOptionKeys.PARQUET_OUTPUT_COLUMN, profile.parquetOutputColumn)

        if (has(QuadtrixOptionKeys.STRICT_QUANTIZED_WEIGHTS) && profile.strictQuantizedWeights) args += "--strict-quantized-weights"
        if (has(QuadtrixOptionKeys.SKIP_INITIAL_EVAL) && profile.skipInitialEval) args += "--skip-initial-eval"
        if (has(QuadtrixOptionKeys.PRINT_SYSTEM_INFO) && profile.printSystemInfo) args += "--print-system-info"
        if (has(QuadtrixOptionKeys.RESUME) && profile.resume) {
            if (profile.resumePath.isNotBlank() && has(QuadtrixOptionKeys.RESUME_FROM)) {
                args += listOf("--resume-from", resolveResumePath(modelDir, profile.resumePath))
            } else {
                args += "--resume"
            }
        }

        if (has(QuadtrixOptionKeys.DIST_MODE) && profile.distMode != "none") {
            workersFile.writeText(QuadtrixWorkerSpecs.workerFileText(profile.distWorkers))
            args += listOf("--dist-mode", profile.distMode)
            addEnabled("--dist-role", QuadtrixOptionKeys.DIST_ROLE, profile.distRole.ifBlank { "coordinator" })
            addEnabled("--worker-host", QuadtrixOptionKeys.WORKER_HOST, profile.workerHost)
            addEnabled("--worker-port", QuadtrixOptionKeys.WORKER_PORT, profile.workerPort.toString())
            addEnabledOptional("--worker-token", QuadtrixOptionKeys.WORKER_TOKEN, profile.workerToken)
            args += listOf("--dist-workers-file", workersFile.absolutePath)
            addEnabled("--dist-sync-interval", QuadtrixOptionKeys.DIST_SYNC_INTERVAL, profile.distSyncInterval.toString())
            addEnabled("--dist-gradient-bits", QuadtrixOptionKeys.DIST_GRADIENT_BITS, profile.distGradientBits.toString())
            addEnabled("--dist-shards", QuadtrixOptionKeys.DIST_SHARDS, profile.distShards.ifBlank { "auto" })
            addEnabled("--dist-rpc-timeout-sec", QuadtrixOptionKeys.DIST_RPC_TIMEOUT_SEC, profile.distRpcTimeoutSec.toString())
            addEnabled("--dist-reprobe-interval", QuadtrixOptionKeys.DIST_REPROBE_INTERVAL, profile.distReprobeInterval.toString())
            if (has(QuadtrixOptionKeys.DIST_COORDINATOR_COMPUTE)) {
                args += listOf("--dist-coordinator-compute", if (profile.distCoordinatorCompute) "1" else "0")
            }
            if (has(QuadtrixOptionKeys.DIST_COORDINATOR_ONLY) && profile.distCoordinatorOnly) args += "--dist-coordinator-only"
            QuadtrixWorkerSpecs.enabledEndpoints(profile.distWorkers).joinToString(",").takeIf { has(QuadtrixOptionKeys.DIST_WORKERS) && it.isNotBlank() }?.let {
                args += listOf("--dist-workers", it)
            }
        }

        if (has(QuadtrixOptionKeys.EXPORT_GGUF) && ggufFile != null) {
            args += listOf("--export-gguf", ggufFile.absolutePath)
            addEnabled("--gguf-outtype", QuadtrixOptionKeys.GGUF_OUTTYPE, profile.ggufOuttype)
            addEnabledOptional("--gguf-name", QuadtrixOptionKeys.GGUF_NAME, profile.ggufName)
            if (has(QuadtrixOptionKeys.SAVE_GGUF_AFTER_TRAIN) && profile.saveGgufAfterTrain) args += "--save-gguf-after-train"
        }

        if (has(QuadtrixOptionKeys.NO_GENERATE_AFTER_TRAIN) && profile.noGenerateAfterTrain) args += "--no-generate-after-train"
        return QuadtrixCommandSpec(args, modelFile, modelDir, ggufFile, workersFile)
    }

    fun convertToGgufArgs(
        executable: File,
        rootDir: File,
        profile: QuadtrixProfileEntity,
        checkpointPath: String
    ): Pair<List<String>, File> {
        val profileName = QuadtrixPaths.safeName(profile.name)
        val modelDir = File(rootDir, "${QuadtrixPaths.MODELS}/$profileName").apply { mkdirs() }
        val source = File(checkpointPath).let { if (it.isAbsolute) it else File(modelDir, it.name) }
        val target = resolveGgufPath(modelDir, profile)
            ?: File(modelDir, source.nameWithoutExtension + ".gguf")
        val args = mutableListOf(
            executable.absolutePath,
            "--convert-to-gguf", source.absolutePath,
            "--export-gguf", target.absolutePath,
            "--gguf-outtype", profile.ggufOuttype
        )
        addOptional(args, "--qwen-tokenizer-json", profile.qwenTokenizerJsonPath)
        addOptional(args, "--gguf-name", profile.ggufName)
        return args to target
    }

    fun webArgs(executable: File, profile: QuadtrixProfileEntity): List<String> {
        val enabled = QuadtrixOptionKeys.parse(profile.enabledOptions)
        val args = mutableListOf(executable.absolutePath, "--web")
        if (QuadtrixOptionKeys.WEB_HOST in enabled) args += listOf("--web-host", profile.webHost)
        if (QuadtrixOptionKeys.WEB_PORT in enabled) args += listOf("--web-port", profile.webPort.toString())
        return args
    }

    fun chatArgs(
        executable: File,
        profile: QuadtrixProfileEntity,
        modelPath: String,
        prompt: String,
        tokens: Int
    ): List<String> = listOf(
        executable.absolutePath,
        profile.datasetPath,
        "--arch", profile.arch,
        "--tokenizer", profile.tokenizer,
        "--model-path", modelPath,
        "--block-size", profile.blockSize.toString(),
        "--n-embd", profile.nEmbd.toString(),
        "--n-head", profile.nHead.toString(),
        "--n-layer", profile.nLayer.toString(),
        "--seed", profile.seed.toString(),
        "--chat",
        "--chat-tokens", tokens.coerceAtLeast(1).toString()
    ).withQwenChatOptions(profile)

    private fun resolveModelPath(modelDir: File, profile: QuadtrixProfileEntity): File {
        val explicit = profile.modelPath.trim()
        if (explicit.isNotBlank() && File(explicit).isAbsolute) return File(explicit)
        val filename = explicit.ifBlank { profile.modelFilename.ifBlank { "web_model.bin" } }
        return File(modelDir, File(filename).name)
    }

    private fun resolveGgufPath(modelDir: File, profile: QuadtrixProfileEntity): File? {
        val explicit = profile.exportGgufPath.trim()
        if (explicit.isBlank() && !profile.saveGgufAfterTrain) return null
        if (explicit.isNotBlank() && File(explicit).isAbsolute) return File(explicit)
        val filename = explicit.ifBlank {
            profile.ggufName.ifBlank { profile.modelFilename.substringBeforeLast('.', profile.modelFilename) }
        }.let { if (it.endsWith(".gguf")) it else "$it.gguf" }
        return File(modelDir, File(filename).name)
    }

    private fun resolveTokenCacheDir(rootDir: File, profile: QuadtrixProfileEntity): File {
        val profileName = QuadtrixPaths.safeName(profile.name)
        return File(rootDir, "${QuadtrixPaths.TOKEN_CACHE}/$profileName").apply { mkdirs() }
    }

    private fun resolveResumePath(modelDir: File, resumePath: String): String {
        val file = File(resumePath)
        return if (file.isAbsolute) file.absolutePath else File(modelDir, file.name).absolutePath
    }

    private fun addOptional(args: MutableList<String>, flag: String, value: String?) {
        val normalized = value?.trim().orEmpty()
        if (normalized.isNotBlank()) args += listOf(flag, normalized)
    }

    private fun addPositive(args: MutableList<String>, flag: String, value: Int) {
        if (value > 0) args += listOf(flag, value.toString())
    }

    private fun List<String>.withQwenChatOptions(profile: QuadtrixProfileEntity): List<String> {
        if (profile.arch != "qwen3") return this
        val args = toMutableList()
        addOptional(args, "--qwen-tokenizer-json", profile.qwenTokenizerJsonPath)
        addPositive(args, "--n-kv-head", profile.nKvHead)
        addPositive(args, "--head-dim", profile.headDim)
        addPositive(args, "--intermediate-size", profile.intermediateSize)
        addOptional(args, "--rope-theta", profile.ropeTheta)
        addOptional(args, "--rms-norm-eps", profile.rmsNormEps)
        args += if (profile.tieWordEmbeddings) "--tie-word-embeddings" else "--no-tie-word-embeddings"
        return args
    }

}

object QuadtrixLogParser {
    data class TokenizationProgress(
        val stage: String,
        val doneChars: Long,
        val totalChars: Long,
        val tokens: Long,
        val elapsedSeconds: Long?,
        val etaSeconds: Long?,
        val percent: Double
    )

    data class WorkerProgress(
        val iter: Int,
        val microSteps: Int?,
        val loss: Double?,
        val gradBytes: Long?,
        val updatedAt: Long = System.currentTimeMillis()
    )

    private val iterRegex = Regex("\\[iter\\s+(\\d+)/(\\d+)]")
    private val qwenProgressRegex = Regex("\\[\\s*(\\d+)/(\\d+)]\\s+([0-9.]+)%")
    private val batchLossRegex = Regex("batch_loss=([0-9.Ee+-]+)")
    private val gradNormRegex = Regex("grad_norm=([0-9.Ee+-]+)")
    private val trainRegex = Regex("\\btrain=([0-9.Ee+-]+)")
    private val valRegex = Regex("\\bval=([0-9.Ee+-]+)")
    private val elapsedRegex = Regex("elapsed=([0-9]+)s")
    private val etaRegex = Regex("ETA=([0-9]+)s")
    private val doneRegex = Regex("\\[DONE]")
    private val ggufRegex = Regex("\\[GGUF].*?written to\\s+(.+)$")
    private val tokenProgressRegex = Regex("\\[TOKENIZE]\\s+(.+?)\\s+(\\d+)/(\\d+)\\s+chars\\s+tokens=(\\d+)\\s+elapsed=([0-9.]+)s\\s+ETA=([0-9.]+)s\\s+\\(([0-9.]+)%\\)")
    private val tokenReadRegex = Regex("\\[TOKENIZE]\\s+Reading records from\\s+(.+)\\s+format=([^\\s]+)")
    private val tokenCacheRegex = Regex("\\[DATA]\\s+Token cache.*:\\s+(.+)$")
    private val workerProgressRegex = Regex("\\[DIST]\\s+(?:Qwen3\\s+)?worker train step done iter=(\\d+)(?:\\s+micro_steps=(\\d+))?(?:\\s+loss=([0-9.Ee+-]+))?(?:\\s+grad_bytes=(\\d+))?", RegexOption.IGNORE_CASE)

    fun parseMetric(line: String, profileName: String, runId: Long?): QuadtrixMetricEntity? {
        val iterMatch = iterRegex.find(line)
        val qwenMatch = qwenProgressRegex.find(line)
        if (iterMatch == null && qwenMatch == null) return null
        return QuadtrixMetricEntity(
            runId = runId,
            profileName = profileName,
            iter = (iterMatch?.groupValues?.getOrNull(1) ?: qwenMatch?.groupValues?.getOrNull(1))?.toIntOrNull() ?: return null,
            maxIter = (iterMatch?.groupValues?.getOrNull(2) ?: qwenMatch?.groupValues?.getOrNull(2))?.toIntOrNull() ?: 0,
            batchLoss = batchLossRegex.firstDouble(line),
            trainLoss = trainRegex.firstDouble(line),
            valLoss = valRegex.firstDouble(line),
            gradNorm = gradNormRegex.firstDouble(line),
            elapsedSeconds = elapsedRegex.firstLong(line),
            etaSeconds = etaRegex.firstLong(line)
        )
    }

    fun isDone(line: String): Boolean = doneRegex.containsMatchIn(line)

    fun parseGgufPath(line: String): String? =
        ggufRegex.find(line)?.groupValues?.getOrNull(1)?.trim()

    fun parseWorkerProgress(line: String): WorkerProgress? {
        val match = workerProgressRegex.find(line) ?: return null
        return WorkerProgress(
            iter = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null,
            microSteps = match.groupValues.getOrNull(2)?.toIntOrNull(),
            loss = match.groupValues.getOrNull(3)?.toDoubleOrNull(),
            gradBytes = match.groupValues.getOrNull(4)?.toLongOrNull()
        )
    }

    fun parseTokenization(line: String): TokenizationProgress? {
        tokenProgressRegex.find(line)?.let { match ->
            return TokenizationProgress(
                stage = match.groupValues[1].trim(),
                doneChars = match.groupValues[2].toLongOrNull() ?: 0L,
                totalChars = match.groupValues[3].toLongOrNull() ?: 0L,
                tokens = match.groupValues[4].toLongOrNull() ?: 0L,
                elapsedSeconds = match.groupValues[5].toDoubleOrNull()?.toLong(),
                etaSeconds = match.groupValues[6].toDoubleOrNull()?.toLong(),
                percent = match.groupValues[7].toDoubleOrNull() ?: 0.0
            )
        }
        tokenReadRegex.find(line)?.let { match ->
            return TokenizationProgress(
                stage = "reading ${match.groupValues[2]}",
                doneChars = 0,
                totalChars = 0,
                tokens = 0,
                elapsedSeconds = null,
                etaSeconds = null,
                percent = 0.0
            )
        }
        tokenCacheRegex.find(line)?.let { match ->
            return TokenizationProgress(
                stage = "cache ${match.groupValues[1].trim()}",
                doneChars = 0,
                totalChars = 0,
                tokens = 0,
                elapsedSeconds = null,
                etaSeconds = null,
                percent = 100.0
            )
        }
        return null
    }

    fun progressText(metric: QuadtrixMetricEntity): String {
        val loss = metric.batchLoss?.let { " loss=${"%.4f".format(it)}" }.orEmpty()
        val eta = metric.etaSeconds?.let { " ETA=${formatDuration(it)}" }.orEmpty()
        return "iter ${metric.iter}/${metric.maxIter}$loss$eta"
    }

    fun formatDuration(seconds: Long): String {
        if (seconds < 60) return "${seconds}s"
        val minutes = seconds / 60
        if (minutes < 180) return "${minutes}m"
        val hours = minutes / 60
        val days = hours / 24
        return if (days > 0) "${hours}h (${days}d)" else "${hours}h"
    }

    private fun Regex.firstDouble(line: String): Double? =
        find(line)?.groupValues?.getOrNull(1)?.toDoubleOrNull()

    private fun Regex.firstLong(line: String): Long? =
        find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()
}

data class Qwen3CheckpointHeader(
    val vocabSize: Int,
    val nEmbd: Int,
    val nHead: Int,
    val nKvHead: Int,
    val nLayer: Int,
    val blockSize: Int,
    val intermediateSize: Int,
    val headDim: Int,
    val ropeTheta: Float,
    val rmsNormEps: Float,
    val tieWordEmbeddings: Boolean
)

object QuadtrixCheckpointInspector {
    private val qwen3Magic = byteArrayOf(
        'Q'.code.toByte(),
        'T'.code.toByte(),
        'R'.code.toByte(),
        'X'.code.toByte(),
        'Q'.code.toByte(),
        'W'.code.toByte(),
        '3'.code.toByte(),
        0.toByte()
    )
    private const val HEADER_BYTES = 8 + 4 + 9 * 4 + 2 * 4 + 4

    fun readQwen3Header(file: File): Qwen3CheckpointHeader? {
        if (!file.isFile || file.length() < HEADER_BYTES) return null
        val bytes = file.inputStream().use { input ->
            val buffer = ByteArray(HEADER_BYTES)
            var offset = 0
            while (offset < HEADER_BYTES) {
                val read = input.read(buffer, offset, HEADER_BYTES - offset)
                if (read < 0) return null
                offset += read
            }
            buffer
        }
        if (!bytes.copyOfRange(0, 8).contentEquals(qwen3Magic)) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(8)
        if (buffer.int != 1) return null
        val fields = IntArray(9) { buffer.int }
        val ropeTheta = buffer.float
        val rmsNormEps = buffer.float
        val tie = buffer.int != 0
        return Qwen3CheckpointHeader(
            vocabSize = fields[0],
            nEmbd = fields[1],
            nHead = fields[2],
            nKvHead = fields[3],
            nLayer = fields[4],
            blockSize = fields[5],
            intermediateSize = fields[6],
            headDim = fields[7],
            ropeTheta = ropeTheta,
            rmsNormEps = rmsNormEps,
            tieWordEmbeddings = tie
        )
    }
}
