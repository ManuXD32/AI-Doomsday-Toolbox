package com.example.llamadroid.data.model.library

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelArtifactInstallationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun copyKeepsSourceAndRelativeDirectoryAssets() {
        val source = temporary.newFolder("source")
        File(source, "tokenizer/config.json").apply { parentFile.mkdirs(); writeText("fixture") }
        val destination = File(temporary.root, "bundle/encoder")
        copyArtifactWithoutOverwrite(source, destination)
        assertEquals("fixture", File(destination, "tokenizer/config.json").readText())
        assertTrue(File(source, "tokenizer/config.json").exists())
        assertTrue(destination.parentFile.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test fun collidingDestinationNeverReplacesExistingWeights() {
        val source = temporary.newFile("new.gguf").apply { writeText("new payload") }
        val destination = temporary.newFile("model.gguf").apply { writeText("existing payload") }
        val failure = runCatching { copyArtifactWithoutOverwrite(source, destination) }.exceptionOrNull()
        assertTrue(failure is ModelLibraryException)
        assertEquals("existing payload", destination.readText())
        assertEquals("new payload", source.readText())
    }

    @Test fun retryAcceptsOnlyAnIdenticalPreviousCopy() {
        val source = temporary.newFile("source.bin").apply { writeText("payload") }
        val destination = File(temporary.root, "bundle/model.bin")
        copyArtifactWithoutOverwrite(source, destination)
        copyArtifactWithoutOverwrite(source, destination, acceptIdentical = true)
        source.writeText("changed")
        assertTrue(runCatching { copyArtifactWithoutOverwrite(source, destination, acceptIdentical = true) }.isFailure)
        assertEquals("payload", destination.readText())
    }
}
