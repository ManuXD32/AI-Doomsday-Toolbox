package com.example.llamadroid

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeBuildMetadataTest {
    @Test
    fun `native build metadata excludes app and bundled support repositories`() {
        val metadataFile = File("src/main/assets/native_build_commits.txt")
        assertTrue("native build metadata asset should exist", metadataFile.exists())
        val metadata = metadataFile.readText()
        val excludedProjects = listOf(
            "AI-Doomsday-Toolbox",
            "Quadtrix.cpp",
            "Quadtrix",
            "x264",
            "freetype",
            "fribidi",
            "harfbuzz",
            "libexpat",
            "fontconfig",
            "libass",
            "OpenCL-Headers",
            "opencl headers"
        )

        excludedProjects.forEach { project ->
            assertFalse("$project should not be listed in native build metadata", metadata.contains(project, ignoreCase = true))
        }
    }
}
