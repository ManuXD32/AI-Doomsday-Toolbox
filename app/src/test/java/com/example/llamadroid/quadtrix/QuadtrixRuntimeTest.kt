package com.example.llamadroid.quadtrix

import com.example.llamadroid.data.db.QuadtrixProfileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class QuadtrixRuntimeTest {
    @Test
    fun trainingArgsIncludeQuantizedResumeDistributedAndParquetOptions() {
        val root = createTempDir(prefix = "quadtrix-test")
        val exe = File(root, "libquadtrix_trainer_baseline.so").apply { writeText("") }
        val profile = QuadtrixProfileEntity(
            name = "General dist",
            datasetPath = "/storage/emulated/0/data/train.jsonl",
            modelFilename = "phone.bin",
            strictQuantizedWeights = true,
            skipInitialEval = true,
            resume = true,
            resumePath = "last_model.bin",
            parquetTextColumn = "text",
            distMode = "data-parallel",
            workerToken = "shared",
            distWorkers = "10.0.0.2:9091, 10.0.0.3:9091",
            distCoordinatorCompute = false,
            tokenCacheMode = "rebuild",
            tokenizationMode = "records",
            tokenizeLogIntervalSec = 3,
            enabledOptions = QuadtrixOptionKeys.serialize(
                QuadtrixOptionKeys.defaultEnabled + setOf(
                    QuadtrixOptionKeys.WEIGHT_STORAGE,
                    QuadtrixOptionKeys.STRICT_QUANTIZED_WEIGHTS,
                    QuadtrixOptionKeys.SKIP_INITIAL_EVAL,
                    QuadtrixOptionKeys.RESUME,
                    QuadtrixOptionKeys.RESUME_FROM,
                    QuadtrixOptionKeys.PARQUET_TEXT_COLUMN,
                    QuadtrixOptionKeys.DIST_MODE,
                    QuadtrixOptionKeys.DIST_WORKERS,
                    QuadtrixOptionKeys.DIST_COORDINATOR_COMPUTE,
                    QuadtrixOptionKeys.TOKEN_CACHE,
                    QuadtrixOptionKeys.TOKENIZATION_MODE,
                    QuadtrixOptionKeys.TOKENIZE_LOG_INTERVAL_SEC
                )
            )
        )

        val spec = QuadtrixCommandBuilder.trainingArgs(exe, root, profile)
        val args = spec.args

        assertEquals(exe.absolutePath, args.first())
        assertTrue(args.containsAll(listOf("--arch", "qwen3")))
        assertTrue(args.containsAll(listOf("--tokenizer", "qwen3")))
        assertTrue(args.containsAll(listOf("--weight-storage", "int8")))
        assertTrue(args.contains("--strict-quantized-weights"))
        assertTrue(args.contains("--skip-initial-eval"))
        assertTrue(args.containsAll(listOf("--resume-from", File(spec.modelDir, "last_model.bin").absolutePath)))
        assertTrue(args.containsAll(listOf("--parquet-text-column", "text")))
        assertTrue(args.containsAll(listOf("--dist-mode", "data-parallel")))
        assertTrue(args.containsAll(listOf("--token-cache", "rebuild")))
        assertTrue(args.containsAll(listOf("--tokenization-mode", "records")))
        assertTrue(args.containsAll(listOf("--tokenize-log-interval-sec", "3")))
        assertTrue(args.containsAll(listOf("--dist-coordinator-compute", "0")))
        assertTrue(args.contains("--no-generate-after-train"))
        assertEquals("1 10.0.0.2:9091\n1 10.0.0.3:9091", spec.workersFile.readText())
    }

    @Test
    fun tokenizeOnlyArgsBuildQwenTokenCacheAndExit() {
        val root = createTempDir(prefix = "quadtrix-tokenize")
        val exe = File(root, "trainer").apply { writeText("") }
        val profile = QuadtrixProfileEntity(
            name = "token cache",
            datasetPath = "/storage/emulated/0/data/train.txt",
            tokenCacheMode = "auto",
            tokenCacheDir = "qwen-cache",
            tokenizationMode = "whole",
            tokenizeLogIntervalSec = 5,
            enabledOptions = QuadtrixOptionKeys.serialize(
                QuadtrixOptionKeys.defaultEnabled + setOf(
                    QuadtrixOptionKeys.TOKEN_CACHE,
                    QuadtrixOptionKeys.TOKEN_CACHE_DIR,
                    QuadtrixOptionKeys.TOKENIZATION_MODE,
                    QuadtrixOptionKeys.TOKENIZE_LOG_INTERVAL_SEC
                )
            )
        )

        val spec = QuadtrixCommandBuilder.trainingArgs(exe, root, profile, tokenizeOnly = true)
        val args = spec.args

        assertTrue(args.contains("--tokenize-only"))
        assertTrue(args.containsAll(listOf("--token-cache-dir", File(root, "token_cache/token_cache").absolutePath)))
        assertFalse(args.contains("qwen-cache"))
        assertTrue(args.containsAll(listOf("--tokenization-mode", "whole")))
    }

    @Test
    fun qwenDefaultsUseEmbeddedTokenizerAndInternalCache() {
        val root = createTempDir(prefix = "quadtrix-embedded-tokenizer")
        val exe = File(root, "trainer").apply { writeText("") }
        val profile = QuadtrixProfileEntity(
            name = "embedded qwen",
            datasetPath = "/storage/emulated/0/data/train.txt",
            qwenTokenizerJsonPath = "/storage/emulated/0/old-tokenizer.json",
            tokenCacheDir = "/sdcard/custom-cache",
            enabledOptions = QuadtrixOptionKeys.serialize(
                QuadtrixOptionKeys.defaultEnabled + setOf(QuadtrixOptionKeys.TOKEN_CACHE)
            )
        )

        val args = QuadtrixCommandBuilder.trainingArgs(exe, root, profile).args

        assertFalse(args.contains("--qwen-tokenizer-json"))
        assertFalse(args.contains("/storage/emulated/0/old-tokenizer.json"))
        assertFalse(args.contains("/sdcard/custom-cache"))
        assertTrue(args.containsAll(listOf("--tokenizer", "qwen3")))
        assertTrue(args.containsAll(listOf("--token-cache-dir", File(root, "token_cache/embedded_qwen").absolutePath)))
    }

    @Test
    fun trainingArgsIncludeQwenAndGgufOptions() {
        val root = createTempDir(prefix = "quadtrix-qwen")
        val exe = File(root, "trainer").apply { writeText("") }
        val profile = QuadtrixProfileEntity(
            name = "qwen phone",
            datasetPath = "/storage/emulated/0/data/train.jsonl",
            modelFilename = "qwen.bin",
            qwenTokenizerJsonPath = "/storage/emulated/0/qwen-tokenizer.json",
            nKvHead = 2,
            headDim = 64,
            intermediateSize = 768,
            ropeTheta = "1000000.0",
            rmsNormEps = "0.000001",
            tieWordEmbeddings = false,
            exportGgufPath = "qwen-phone.gguf",
            saveGgufAfterTrain = true,
            ggufOuttype = "q8_0",
            ggufName = "qwen-phone",
            enabledOptions = QuadtrixOptionKeys.serialize(
                QuadtrixOptionKeys.defaultEnabled + setOf(
                    QuadtrixOptionKeys.QWEN_TOKENIZER_JSON,
                    QuadtrixOptionKeys.N_KV_HEAD,
                    QuadtrixOptionKeys.HEAD_DIM,
                    QuadtrixOptionKeys.INTERMEDIATE_SIZE,
                    QuadtrixOptionKeys.ROPE_THETA,
                    QuadtrixOptionKeys.RMS_NORM_EPS,
                    QuadtrixOptionKeys.TIE_WORD_EMBEDDINGS,
                    QuadtrixOptionKeys.EXPORT_GGUF,
                    QuadtrixOptionKeys.GGUF_OUTTYPE,
                    QuadtrixOptionKeys.GGUF_NAME,
                    QuadtrixOptionKeys.SAVE_GGUF_AFTER_TRAIN
                )
            )
        )

        val spec = QuadtrixCommandBuilder.trainingArgs(exe, root, profile)
        val args = spec.args

        assertTrue(args.containsAll(listOf("--qwen-tokenizer-json", "/storage/emulated/0/qwen-tokenizer.json")))
        assertTrue(args.containsAll(listOf("--n-kv-head", "2")))
        assertTrue(args.containsAll(listOf("--head-dim", "64")))
        assertTrue(args.containsAll(listOf("--intermediate-size", "768")))
        assertTrue(args.contains("--no-tie-word-embeddings"))
        assertTrue(args.containsAll(listOf("--export-gguf", File(spec.modelDir, "qwen-phone.gguf").absolutePath)))
        assertTrue(args.containsAll(listOf("--gguf-outtype", "q8_0")))
        assertTrue(args.containsAll(listOf("--gguf-name", "qwen-phone")))
        assertTrue(args.contains("--save-gguf-after-train"))
    }

    @Test
    fun convertToGgufArgsUseCheckpointAndTarget() {
        val root = createTempDir(prefix = "quadtrix-convert")
        val exe = File(root, "trainer").apply { writeText("") }
        val profile = QuadtrixProfileEntity(
            name = "convert me",
            exportGgufPath = "converted.gguf",
            ggufOuttype = "q4_0",
            ggufName = "converted"
        )

        val (args, target) = QuadtrixCommandBuilder.convertToGgufArgs(exe, root, profile, "last_model.bin")

        assertTrue(args.containsAll(listOf("--convert-to-gguf", File(root, "models/convert_me/last_model.bin").absolutePath)))
        assertTrue(args.containsAll(listOf("--export-gguf", target.absolutePath)))
        assertTrue(args.containsAll(listOf("--gguf-outtype", "q4_0")))
        assertTrue(args.containsAll(listOf("--gguf-name", "converted")))
    }

    @Test
    fun workerArgsUseWorkerOnlySurface() {
        val root = createTempDir(prefix = "quadtrix-worker")
        val exe = File(root, "trainer").apply { writeText("") }
        val profile = QuadtrixProfileEntity(
            name = "worker",
            workerPort = 9092,
            workerToken = "tok",
            threads = 6,
            enabledOptions = QuadtrixOptionKeys.serialize(
                setOf(
                    QuadtrixOptionKeys.WORKER_PORT,
                    QuadtrixOptionKeys.WORKER_TOKEN,
                    QuadtrixOptionKeys.THREADS
                )
            )
        )

        val args = QuadtrixCommandBuilder.trainingArgs(exe, root, profile, workerOnly = true).args

        assertTrue(args.containsAll(listOf("--worker-only", "--worker-port", "9092", "--worker-token", "tok", "--threads", "6")))
    }

    @Test
    fun disabledOptionsAreNotEmitted() {
        val root = createTempDir(prefix = "quadtrix-disabled")
        val exe = File(root, "trainer").apply { writeText("") }
        val profile = QuadtrixProfileEntity(
            name = "minimal",
            datasetPath = "/storage/emulated/0/data/train.txt",
            enabledOptions = QuadtrixOptionKeys.serialize(setOf(QuadtrixOptionKeys.ARCH))
        )

        val args = QuadtrixCommandBuilder.trainingArgs(exe, root, profile).args

        assertTrue(args.containsAll(listOf("--arch", "qwen3")))
        assertFalse(args.contains("--batch-size"))
        assertFalse(args.contains("--threads"))
        assertFalse(args.contains("--model-path"))
        assertFalse(args.contains("--no-generate-after-train"))
    }

    @Test
    fun workerSpecsKeepNamesAndOnlyEnableActiveEndpoints() {
        val workers = listOf(
            QuadtrixWorkerSpec(enabled = true, name = "Pixel", endpoint = "10.0.0.2:9091"),
            QuadtrixWorkerSpec(enabled = false, name = "Tablet", endpoint = "10.0.0.3:9091")
        )
        val raw = QuadtrixWorkerSpecs.serialize(workers)

        assertEquals(workers, QuadtrixWorkerSpecs.parse(raw))
        assertEquals(listOf("10.0.0.2:9091"), QuadtrixWorkerSpecs.enabledEndpoints(raw))
        assertEquals("1 10.0.0.2:9091\n0 10.0.0.3:9091", QuadtrixWorkerSpecs.workerFileText(raw))
    }

    @Test
    fun logParserExtractsTrainingMetrics() {
        val metric = QuadtrixLogParser.parseMetric(
            "elapsed=20096s ETA=372401s [iter 257/5000] batch_loss=2.9571 grad_norm=0.9114 grad_accum=20",
            "General_dist",
            4L
        )

        requireNotNull(metric)
        assertEquals(257, metric.iter)
        assertEquals(5000, metric.maxIter)
        assertEquals(2.9571, metric.batchLoss!!, 0.0001)
        assertEquals(0.9114, metric.gradNorm!!, 0.0001)
        assertEquals(20096L, metric.elapsedSeconds)
        assertEquals(372401L, metric.etaSeconds)
    }

    @Test
    fun logParserExtractsQwenProgressMetricsAndDone() {
        val metric = QuadtrixLogParser.parseMetric(
            "[    2/5] 40.0% train=4.6254 val=4.8402 elapsed=15s ETA=23s",
            "qwen",
            7L
        )

        requireNotNull(metric)
        assertEquals(2, metric.iter)
        assertEquals(5, metric.maxIter)
        assertEquals(4.6254, metric.trainLoss!!, 0.0001)
        assertEquals(4.8402, metric.valLoss!!, 0.0001)
        assertEquals(15L, metric.elapsedSeconds)
        assertEquals(23L, metric.etaSeconds)
        assertTrue(QuadtrixLogParser.isDone("[DONE] Qwen3 training finished in 2.1s"))
        assertEquals("/tmp/qwen.gguf", QuadtrixLogParser.parseGgufPath("[GGUF] Qwen3 GGUF written to /tmp/qwen.gguf"))
    }

    @Test
    fun logParserExtractsTokenizationProgress() {
        val progress = QuadtrixLogParser.parseTokenization(
            "[TOKENIZE] distributed / distribuida 131072/262144 chars tokens=4096 elapsed=2.5s ETA=2.5s (50.0%)"
        )

        requireNotNull(progress)
        assertEquals("distributed / distribuida", progress.stage)
        assertEquals(131072L, progress.doneChars)
        assertEquals(262144L, progress.totalChars)
        assertEquals(4096L, progress.tokens)
        assertEquals(50.0, progress.percent, 0.0001)
    }

    @Test
    fun logParserExtractsWorkerProgress() {
        val progress = QuadtrixLogParser.parseWorkerProgress(
            "[DIST] Qwen3 worker train step done iter=42 micro_steps=8 loss=3.125 grad_bytes=2048"
        )

        requireNotNull(progress)
        assertEquals(42, progress.iter)
        assertEquals(8, progress.microSteps)
        assertEquals(3.125, progress.loss!!, 0.0001)
        assertEquals(2048L, progress.gradBytes)
    }

    @Test
    fun qwen3CheckpointInspectorReadsHeaderShape() {
        val file = File(createTempDir(prefix = "quadtrix-header"), "checkpoint.bin")
        val bytes = ByteBuffer.allocate(8 + 4 + 9 * 4 + 2 * 4 + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(byteArrayOf('Q'.code.toByte(), 'T'.code.toByte(), 'R'.code.toByte(), 'X'.code.toByte(), 'Q'.code.toByte(), 'W'.code.toByte(), '3'.code.toByte(), 0.toByte()))
            .putInt(1)
            .putInt(151936)
            .putInt(512)
            .putInt(8)
            .putInt(2)
            .putInt(12)
            .putInt(1024)
            .putInt(1536)
            .putInt(64)
            .putInt(0)
            .putFloat(1000000.0f)
            .putFloat(0.000001f)
            .putInt(1)
            .array()
        file.writeBytes(bytes)

        val header = QuadtrixCheckpointInspector.readQwen3Header(file)

        requireNotNull(header)
        assertEquals(512, header.nEmbd)
        assertEquals(8, header.nHead)
        assertEquals(2, header.nKvHead)
        assertEquals(12, header.nLayer)
        assertEquals(1024, header.blockSize)
        assertEquals(1536, header.intermediateSize)
        assertEquals(64, header.headDim)
        assertTrue(header.tieWordEmbeddings)
    }
}
