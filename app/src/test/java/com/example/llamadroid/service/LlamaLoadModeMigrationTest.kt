package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Locale

class LlamaLoadModeMigrationTest {

    @Test
    fun `load modes retain the six stable persisted values`() {
        assertEquals(
            listOf("auto", "none", "mmap", "mlock", "mmap+mlock", "dio"),
            LlamaLoadMode.entries.map(LlamaLoadMode::value)
        )
    }

    @Test
    fun `generated command promotes legacy flags to one last-wins canonical pair`() {
        val args = ProcessController().getCommand(
            "/bin/llama-server",
            LlamaConfig(
                modelPath = "/models/main.gguf",
                loadMode = LlamaLoadMode.AUTO.value,
                customFlags = "--mmap --no-mmap --load-mode mlock --load-mode=dio --other value"
            )
        )

        assertEquals(1, args.count { it == "--load-mode" })
        assertEquals("dio", args[args.indexOf("--load-mode") + 1])
        assertFalse(
            args.any {
                it in setOf("--mmap", "--no-mmap", "--mlock", "--direct-io", "--no-direct-io")
            }
        )
        assertEquals("value", args[args.indexOf("--other") + 1])
    }

    @Test
    fun `malformed managed tokens stay visible and report errors`() {
        val result = resolveManagedLlamaCustomFlags(
            args = ProcessController().splitCommandLine("--load-mode invalid --mmap --lora --keep"),
            configuredLoadMode = LlamaLoadMode.AUTO
        )

        assertEquals(LlamaLoadMode.MMAP, result.loadMode)
        assertEquals(listOf("--load-mode", "invalid", "--lora", "--keep"), result.filteredArgs)
        assertTrue(result.errors.size >= 2)
    }

    @Test
    fun `typed lora stack emits repeated plain and scaled flags in order`() {
        val specs = listOf(
            LlamaLoraSpec("/models/first.lora"),
            LlamaLoraSpec("/models/second.lora", 0.5f),
            LlamaLoraSpec("/models/first.lora"),
            LlamaLoraSpec("/models/zero.lora", 0f),
            LlamaLoraSpec("/models/negative.lora", -1.25f)
        )

        assertEquals(
            listOf(
                "--lora", "/models/first.lora",
                "--lora-scaled", "/models/second.lora:0.5",
                "--lora", "/models/first.lora",
                "--lora-scaled", "/models/zero.lora:0",
                "--lora-scaled", "/models/negative.lora:-1.25"
            ),
            buildLlamaLoraArgs(specs)
        )
    }

    @Test
    fun `legacy comma lora values coexist with selected stack and preserve suppression rules`() {
        val selected = listOf(LlamaLoraSpec("/models/selected.lora"))
        val scaledOnly = migrateLegacyLlamaManagedSettings(
            args = ProcessController().splitCommandLine("--lora-scaled /models/custom.lora:0.5"),
            configuredLoadMode = LlamaLoadMode.MMAP,
            selectedLoras = selected
        )
        assertEquals(
            selected + LlamaLoraSpec("/models/custom.lora", 0.5f),
            scaledOnly.loras
        )

        val plainWithDuplicates = migrateLegacyLlamaManagedSettings(
            args = ProcessController().splitCommandLine("--lora /models/custom.lora,/models/custom.lora"),
            configuredLoadMode = LlamaLoadMode.MMAP,
            selectedLoras = selected
        )
        assertEquals(
            listOf(
                LlamaLoraSpec("/models/custom.lora"),
                LlamaLoraSpec("/models/custom.lora")
            ),
            plainWithDuplicates.loras
        )
        assertTrue(plainWithDuplicates.filteredArgs.isEmpty())
        assertTrue(plainWithDuplicates.errors.isEmpty())
    }

    @Test
    fun `legacy repeated and comma loras preserve full order`() {
        val migrated = migrateLegacyLlamaManagedSettings(
            args = ProcessController().splitCommandLine(
                "--lora /models/a.lora,/models/b.lora " +
                    "--lora-scaled '/models/c.lora:0.25,/models/d.lora:-1' " +
                    "--lora /models/a.lora"
            ),
            configuredLoadMode = LlamaLoadMode.MMAP,
            selectedLoras = emptyList()
        )

        assertEquals(
            listOf(
                LlamaLoraSpec("/models/a.lora"),
                LlamaLoraSpec("/models/b.lora"),
                LlamaLoraSpec("/models/c.lora", 0.25f),
                LlamaLoraSpec("/models/d.lora", -1f),
                LlamaLoraSpec("/models/a.lora")
            ),
            migrated.loras
        )
        assertTrue(migrated.filteredArgs.isEmpty())
        assertTrue(migrated.errors.isEmpty())
    }

    @Test
    fun `lora strength formatting is finite and locale independent`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("0.5", formatLlamaLoraStrength(0.5f))
            assertEquals("-1.25", formatLlamaLoraStrength(-1.25f))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `lora paths validate comma and scaled delimiter boundaries`() {
        assertIllegalArgument {
            buildLlamaLoraArgs(listOf(LlamaLoraSpec("/models/a,b.lora")))
        }
        assertIllegalArgument {
            buildLlamaLoraArgs(listOf(LlamaLoraSpec("/models/a:b.lora", 0.5f)))
        }
        assertIllegalArgument {
            buildLlamaLoraArgs(listOf(LlamaLoraSpec("/models/nan.lora", Float.NaN)))
        }
        assertIllegalArgument {
            buildLlamaLoraArgs(
                listOf(LlamaLoraSpec("/models/infinite.lora", Float.POSITIVE_INFINITY))
            )
        }
    }

    @Test
    fun `template placeholders expose first lora full stack and load mode args`() {
        val config = LlamaConfig(
            modelPath = "/models/main.gguf",
            host = "127.0.0.1",
            loadMode = LlamaLoadMode.DIO.value,
            loras = listOf(
                LlamaLoraSpec("/models/first.lora"),
                LlamaLoraSpec("/models/second.lora", 0.5f)
            )
        )

        val args = ProcessController().renderCommandTemplate(
            template = "{binary} --host {host} -m {model} {lora} {lora_args} {load_mode_args}",
            binaryPath = "/bin/llama-server",
            config = config
        )

        assertEquals("/bin/llama-server", args.first())
        assertEquals(1, args.count { it == "--load-mode" })
        assertEquals("dio", args[args.indexOf("--load-mode") + 1])
        val plainIndex = args.indexOf("--lora")
        val scaledIndex = args.indexOf("--lora-scaled")
        assertTrue(args.indexOf("/models/first.lora") < plainIndex)
        assertEquals("/models/first.lora", args[plainIndex + 1])
        assertEquals("/models/second.lora:0.5", args[scaledIndex + 1])
    }

    @Test
    fun `exact legacy single lora template expands to the ordered stack`() {
        val args = ProcessController().renderCommandTemplate(
            template = "{binary} --model {model} --lora {lora}",
            binaryPath = "/bin/llama-server",
            config = LlamaConfig(
                modelPath = "/models/main.gguf",
                loras = listOf(
                    LlamaLoraSpec("/models/first.lora"),
                    LlamaLoraSpec("/models/second.lora", 0.5f)
                )
            )
        )

        assertEquals(
            listOf(
                "/bin/llama-server", "--model", "/models/main.gguf",
                "--lora", "/models/first.lora",
                "--lora-scaled", "/models/second.lora:0.5",
                "--load-mode", "mmap"
            ),
            args
        )
    }

    @Test
    fun `schemas one through three migrate noMmap and legacy lora path to schema four`() {
        val fixtures = listOf(
            """{"schemaVersion":1,"modelPath":"/models/one.gguf","loraPath":"/models/one.lora","noMmap":true}""" to LlamaLoadMode.NONE,
            """{"schemaVersion":2,"modelPath":"/models/two.gguf","loraPath":"/models/two.lora"}""" to LlamaLoadMode.MMAP,
            """{"schemaVersion":3,"modelPath":"/models/three.gguf","loraPath":"/models/three.lora","noMmap":false}""" to LlamaLoadMode.MMAP
        )

        fixtures.forEach { (json, expectedMode) ->
            val profile = requireNotNull(LlamaServerLaunchProfile.decode(json))
            assertEquals(LlamaServerLaunchProfile.SCHEMA_VERSION, profile.schemaVersion)
            assertEquals(expectedMode.value, profile.loadMode)
            assertEquals(expectedMode == LlamaLoadMode.NONE, profile.noMmap)
            assertEquals(
                listOf(LlamaLoraSpec(profile.loraPath.orEmpty())),
                profile.loras
            )
        }
    }

    @Test
    fun `missing and explicit empty lora arrays have distinct migration meaning`() {
        val missing = requireNotNull(
            LlamaServerLaunchProfile.decode(
                """{"schemaVersion":3,"loraPath":"/models/legacy.lora"}"""
            )
        )
        val empty = requireNotNull(
            LlamaServerLaunchProfile.decode(
                """{"schemaVersion":3,"loraPath":"/models/legacy.lora","loras":[]}"""
            )
        )

        assertEquals(listOf(LlamaLoraSpec("/models/legacy.lora")), missing.loras)
        assertEquals("/models/legacy.lora", missing.loraPath)
        assertTrue(empty.loras.isEmpty())
        assertNull(empty.loraPath)
    }

    private fun assertIllegalArgument(action: () -> Unit) {
        try {
            action()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
