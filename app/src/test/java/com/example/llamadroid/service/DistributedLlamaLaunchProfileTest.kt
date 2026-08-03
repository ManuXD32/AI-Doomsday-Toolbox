package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DistributedLlamaLaunchProfileTest {
    @Test
    fun profileRoundTripAndCommandKeepEveryDistributedArgument() {
        val profile = DistributedLlamaLaunchResolver.resolve(baseRequest())
        val decoded = requireNotNull(
            DistributedLlamaLaunchProfile.decode(DistributedLlamaLaunchProfile.encode(profile))
        )
        assertEquals(profile, decoded)

        val args = ProcessController().getCommand("/bin/llama-server", decoded.config)
        assertEquals("worker-a:50052", args[args.indexOf("--rpc") + 1])
        assertEquals("on", args[args.indexOf("--fit") + 1])
        assertEquals("7168", args[args.indexOf("--fit-target") + 1])
        assertTrue("--spec-draft-n-max" in args)
        assertTrue("--threads" in args || "-t" in args)
        assertTrue("--ctx-size" in args || "-c" in args)
    }

    @Test
    fun masterDedicatedMtpKeepsAllTargetLayersOnWorkersWhenMasterTargetRamIsZero() {
        val profile = DistributedLlamaLaunchResolver.resolve(
            baseRequest().copy(
                masterTargetRamMiB = 0,
                speculativeMode = LlamaSpeculativeMode.DRAFT_MTP,
                speculativePlacement = DistributedSpeculativePlacement.MASTER_DEDICATED,
                draftModelPath = null
            )
        )
        assertEquals(12, profile.config.nGpuLayers)
        assertNull(profile.config.draftDeviceId)
        assertEquals(DistributedSpeculativePlacement.MASTER_DEDICATED, profile.speculativePlacement)
    }

    @Test
    fun dedicatedWorkerGetsNoTargetTensorShare() {
        val profile = DistributedLlamaLaunchResolver.resolve(
            baseRequest().copy(
                workers = listOf(
                    DistributedWorkerLaunchSpec("worker-a:50052", 8192),
                    DistributedWorkerLaunchSpec("worker-b:50052", 8192)
                ),
                speculativePlacement = DistributedSpeculativePlacement.WORKER_DEDICATED,
                speculativeWorkerIndex = 1,
                draftModelSizeMiB = 1024
            )
        )
        val split = requireNotNull(profile.config.tensorSplit).split(',').map(String::toFloat)
        assertTrue(split[0] > 0f)
        assertEquals(0f, split[1])
        assertEquals("RPC1", profile.config.draftDeviceId)
    }

    @Test
    fun localCommandCannotAccidentallyInheritDistributedProfile() {
        val localArgs = ProcessController().getCommand(
            "/bin/llama-server",
            LlamaConfig(modelPath = "/models/local.gguf", contextSize = 2048)
        )
        assertFalse("--rpc" in localArgs)
        assertFalse("--fit" in localArgs)
    }

    @Test
    fun residentWorkerUsesOneDeviceWithoutTensorSplit() {
        val workers = listOf(
            DistributedWorkerLaunchSpec("worker-a:50052", 8192, workerId = 10),
            DistributedWorkerLaunchSpec("worker-b:50052", 8192, workerId = 11)
        )
        val profile = DistributedLlamaLaunchResolver.resolve(
            baseRequest().copy(
                workers = workers,
                mainPlacement = MainModelPlacement(
                    mode = MainModelPlacementMode.RESIDENT,
                    devices = listOf(DistributedDeviceRef(DistributedDeviceKind.WORKER, 11, "worker-b:50052"))
                )
            )
        )
        assertEquals(listOf("RPC0"), profile.config.targetDevices)
        assertEquals("none", profile.config.splitMode)
        assertEquals("12", profile.config.nGpuLayersArgument)
        assertNull(profile.config.tensorSplit)
    }

    @Test
    fun distributedMtpKeepsSelectedDraftDeviceOrder() {
        val workers = listOf(
            DistributedWorkerLaunchSpec("worker-a:50052", 8192, workerId = 10),
            DistributedWorkerLaunchSpec("worker-b:50052", 8192, workerId = 11)
        )
        val profile = DistributedLlamaLaunchResolver.resolve(
            baseRequest().copy(
                workers = workers,
                speculativeMode = LlamaSpeculativeMode.DRAFT_MTP,
                draftPlacement = DraftModelPlacement(
                    DraftModelPlacementMode.DISTRIBUTED,
                    listOf(
                        DistributedDeviceRef(DistributedDeviceKind.WORKER, 11, "worker-b:50052"),
                        DistributedDeviceRef(DistributedDeviceKind.WORKER, 10, "worker-a:50052")
                    )
                )
            )
        )
        assertEquals("RPC1,RPC0", profile.config.draftDeviceId)
    }

    @Test
    fun mmprojPlacementOnlyControlsLocalOffload() {
        val cpu = DistributedLlamaLaunchResolver.resolve(
            baseRequest().copy(mmprojPath = "/models/mmproj.gguf", mmprojPlacement = MmprojPlacement.MASTER_CPU)
        )
        assertEquals("/models/mmproj.gguf", cpu.config.mmprojPath)
        assertEquals(false, cpu.config.mmprojOffload)
        val args = ProcessController().getCommand("/bin/llama-server", cpu.config)
        assertTrue("--no-mmproj-offload" in args)
    }

    @Test
    fun resolvedLaunchRoundTripPreservesExactArgv() {
        val profile = DistributedLlamaLaunchResolver.resolve(baseRequest())
        val launch = ResolvedDistributedLaunch(
            profile = profile,
            binaryPath = "/bin/llama-server",
            argv = listOf("/bin/llama-server", "--rpc", "worker-a:50052"),
            endpointHost = "0.0.0.0",
            endpointPort = 8080,
            workerDeviceOrder = mapOf("worker-a:50052" to "RPC0")
        )
        assertEquals(launch, ResolvedDistributedLaunch.decode(ResolvedDistributedLaunch.encode(launch)))
    }

    @Test
    fun fourAndSixGiBContributionsAllocateFortyAndSixtyPercentExactly() {
        val master = DistributedDeviceRef(DistributedDeviceKind.MASTER_CPU)
        val worker = DistributedDeviceRef(DistributedDeviceKind.WORKER, 10, "worker-a:50052")
        val allocations = DistributedLlamaLaunchResolver.allocateLayersByRam(
            layers = 10,
            devices = listOf(master, worker)
        ) { ref -> if (ref.kind == DistributedDeviceKind.MASTER_CPU) 4096 else 6144 }

        assertEquals(listOf(4, 6), allocations.map { it.assignedLayers })
        assertEquals(10, allocations.sumOf { it.assignedLayers })

        val rounded = DistributedLlamaLaunchResolver.allocateLayersByRam(
            layers = 7,
            devices = listOf(master, worker, worker.copy(workerId = 11, workerAddress = "worker-b:50052"))
        ) { 1 }
        assertEquals(listOf(3, 2, 2), rounded.map { it.assignedLayers })
    }

    @Test
    fun masterParticipationUsesExactAcceleratorLayersInsteadOfAuto() {
        val worker = DistributedWorkerLaunchSpec("worker-a:50052", 6144, workerId = 10)
        val masterRef = DistributedDeviceRef(DistributedDeviceKind.MASTER_CPU)
        val workerRef = DistributedDeviceRef(DistributedDeviceKind.WORKER, 10, worker.address)
        val profile = DistributedLlamaLaunchResolver.resolve(
            baseRequest().copy(
                modelLayers = 10,
                transformerBlocks = 9,
                masterTargetRamMiB = 4096,
                workers = listOf(worker),
                fitEnabled = false,
                fitTargetMiB = null,
                mainPlacement = MainModelPlacement(
                    devices = listOf(masterRef, workerRef),
                    ramContributionsMiB = mapOf(masterRef.stableKey() to 4096, workerRef.stableKey() to 6144)
                )
            )
        )
        assertEquals(listOf(4, 6), profile.mainLayerAllocations.map { it.assignedLayers })
        assertEquals("6", profile.config.nGpuLayersArgument)
        val args = ProcessController().getCommand("/bin/llama-server", profile.config)
        assertEquals("6", args[args.indexOf("-ngl") + 1])
        assertFalse("auto" in args)
        assertFalse("--fit" in args)
        assertFalse("--cache-prompt" in args)
        assertFalse("--cache-idle-slots" in args)
        assertFalse("--sleep-idle-seconds" in args)
    }

    @Test
    fun kvPlacementSelectsOnlyItsVerifiedFlags() {
        val cpu = DistributedLlamaLaunchResolver.resolve(
            baseRequest().copy(kvPlacement = DistributedKvPlacement(DistributedKvPlacementMode.MASTER_CPU))
        )
        val cpuArgs = ProcessController().getCommand("/bin/llama-server", cpu.config)
        assertTrue("--no-kv-offload" in cpuArgs)
        assertFalse("--kv-offload" in cpuArgs)

        val distributed = DistributedLlamaLaunchResolver.resolve(baseRequest())
        val distributedArgs = ProcessController().getCommand("/bin/llama-server", distributed.config)
        assertTrue("--kv-offload" in distributedArgs)
        assertFalse("--no-kv-offload" in distributedArgs)

        val workerRef = DistributedDeviceRef(DistributedDeviceKind.WORKER, workerAddress = "worker-a:50052")
        val hosted = DistributedLlamaLaunchResolver.resolve(
            baseRequest().copy(
                kvPlacement = DistributedKvPlacement(
                    DistributedKvPlacementMode.ACCELERATOR_DEVICE,
                    workerRef
                )
            )
        )
        assertEquals("row", hosted.config.splitMode)
        assertEquals(0, hosted.config.mainGpu)
    }

    private fun baseRequest() = DistributedLlamaResolveRequest(
        modelPath = "/models/main.gguf",
        modelSizeMiB = 1024,
        modelLayers = 12,
        workers = listOf(DistributedWorkerLaunchSpec("worker-a:50052", 8192)),
        masterTargetRamMiB = 2048,
        host = "0.0.0.0",
        threads = 6,
        batchSize = 768,
        contextSize = 16384,
        temperature = 0.4f,
        kvCacheEnabled = true,
        kvCacheTypeK = "q8_0",
        kvCacheTypeV = "q8_0",
        kvCacheReuse = 128,
        fitEnabled = true,
        fitTargetMiB = "7168",
        nGpuLayersArgument = "auto",
        speculativeMode = LlamaSpeculativeMode.DRAFT_SIMPLE,
        draftModelPath = "/models/draft.gguf",
        draftModelSizeMiB = 512,
        draftMax = 5,
        draftMin = 1,
        draftPMin = 0.2f,
        draftThreads = 3,
        draftThreadsBatch = 4,
        draftDeviceMode = LlamaDraftDeviceMode.AUTO.value,
        draftGpuLayers = "all",
        mtpDraftMax = 3,
        mtpDraftMin = 0,
        mtpDraftPMin = 0f,
        ngramModNMatch = 24,
        ngramModNMin = 48,
        ngramModNMax = 64,
        ngramSimpleSizeN = 12,
        ngramSimpleSizeM = 48,
        ngramSimpleMinHits = 1,
        ngramMapKSizeN = 12,
        ngramMapKSizeM = 48,
        ngramMapKMinHits = 1,
        ngramMapK4VSizeN = 12,
        ngramMapK4VSizeM = 48,
        ngramMapK4VMinHits = 1,
        speculativePlacement = DistributedSpeculativePlacement.LOCAL,
        speculativeWorkerIndex = null,
        parallel = 2,
        cacheRam = 1024,
        customFlags = "--mlock",
        commandTemplate = null,
        customCommand = null,
        flashAttention = true
    )
}
