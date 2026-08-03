package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test

class DistributedLlamaArgumentsTest {
    @Test
    fun oneFitTargetIsBroadcastAndPerDeviceTargetsKeepOrder() {
        assertEquals("7168", DistributedLlamaArguments.normalizeFitTarget("7168", 2))
        assertEquals("2048,8192", DistributedLlamaArguments.normalizeFitTarget("2048, 8192", 2))
        assertEquals(listOf("RPC0", "RPC1"), DistributedService.distributedDeviceSlots(listOf("a:50052", "b:50052")))
    }

    @Test
    fun fitAndTensorListsRejectWrongLengthsAndMalformedValues() {
        assertIllegalArgument {
            DistributedLlamaArguments.normalizeFitTarget("1,2,3", 2)
        }
        assertIllegalArgument {
            DistributedLlamaArguments.normalizeFitTarget("oops", 2)
        }
        assertIllegalArgument {
            DistributedLlamaArguments.normalizeTensorSplit("3", 2)
        }
        assertIllegalArgument {
            DistributedLlamaArguments.normalizeTensorSplit("0,0", 2)
        }
    }

    @Test
    fun localCommandBuilderStripsDistributedCustomFlags() {
        val args = ProcessController().getCommand(
            "/bin/llama-server",
            LlamaConfig(
                modelPath = "/models/local.gguf",
                customFlags = "--rpc worker:50052 --fit on --fit-target 7168 --tensor-split 3,1 --temp 0.4"
            )
        )
        assertTrue("local command must not include --rpc", "--rpc" !in args)
        assertTrue("local command must not include --fit", "--fit" !in args)
        assertTrue("local command must not include --fit-target", "--fit-target" !in args)
        assertTrue("local command must not include tensor split", "--tensor-split" !in args && "-ts" !in args)
        assertTrue("unrelated local custom flags remain", "--temp" in args)
    }

    @Test
    fun distributedCommandEmitsFitOffWithoutTargetAndPreservesTypedValues() {
        val args = ProcessController().getCommand(
            "/bin/llama-server",
            LlamaConfig(
                modelPath = "/models/main.gguf",
                rpcWorkers = listOf("a:50052", "b:50052"),
                nGpuLayersArgument = "auto",
                fitEnabled = true,
                fitTargetMiB = "2048,8192",
                tensorSplit = "3,1",
                customFlags = "--fit off --fit-target 9999 --tensor-split 1,1"
            )
        )
        assertEquals("on", args[args.indexOf("--fit") + 1])
        assertEquals("2048,8192", args[args.indexOf("--fit-target") + 1])
        assertEquals("3,1", args[args.indexOf("-ts") + 1])
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        }
    }
}
