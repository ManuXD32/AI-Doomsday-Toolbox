package com.example.llamadroid.ui.components

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.sd.SdVideoComponentPaths
import com.example.llamadroid.sd.SdVideoComponentRole
import com.example.llamadroid.sd.SdVideoFamily
import com.example.llamadroid.sd.SdVideoFamilyProfiles
import com.example.llamadroid.sd.SdVideoWorkflow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoModelSelectorSupportTest {
    @org.junit.Test
    fun `file availability distinguishes present from removed selections`() {
        val model = java.io.File.createTempFile("video-picker", ".gguf")
        try {
            org.junit.Assert.assertTrue(videoModelFileAvailable(model.path))
            model.delete()
            org.junit.Assert.assertFalse(videoModelFileAvailable(model.path))
            org.junit.Assert.assertFalse(videoModelFileAvailable(""))
        } finally { model.delete() }
    }

    @Test
    fun `selector entries retain exact paths and flag duplicate filenames`() {
        val first = model("/models/first/shared.gguf")
        val second = model("/models/second/shared.gguf")

        val entries = videoModelSelectorEntries(listOf(first, second))

        assertEquals(listOf(first, second), entries.map { it.model })
        assertTrue(entries.all { it.hasDuplicateFilename })
        assertEquals("shared.gguf", videoModelDisplayName(first.path, listOf(first, second)))
        assertEquals(second, selectedVideoModel(second.path, listOf(first, second)))
    }

    @Test
    fun `unmatched saved path still has a filename display`() {
        val installed = model("/models/installed.gguf")

        assertEquals(
            "missing.gguf",
            videoModelDisplayName("/models/missing.gguf", listOf(installed))
        )
    }

    @Test
    fun `video groups include workflow alternatives and optional components`() {
        val groups = videoComponentRequirementGroups(
            profile = SdVideoFamilyProfiles.WAN,
            workflow = SdVideoWorkflow.IMAGE_TO_VIDEO,
            paths = SdVideoComponentPaths()
        )

        assertEquals(
            listOf(
                VideoComponentRequirementKind.REQUIRED,
                VideoComponentRequirementKind.REQUIRED,
                VideoComponentRequirementKind.CHOOSE_ONE,
                VideoComponentRequirementKind.REQUIRED,
                VideoComponentRequirementKind.OPTIONAL
            ),
            groups.map { it.kind }
        )
        assertEquals(
            listOf(SdVideoComponentRole.VAE, SdVideoComponentRole.TAE),
            groups[2].roles
        )
        assertEquals(
            listOf(SdVideoComponentRole.CLIP_VISION),
            groups[3].roles
        )
        assertTrue(SdVideoComponentRole.CONTROL_NET in groups.last().roles)
    }

    @Test
    fun `profile change keeps selected unsupported component in incompatible group`() {
        val groups = videoComponentRequirementGroups(
            profile = SdVideoFamilyProfiles.WAN,
            workflow = SdVideoWorkflow.TEXT_TO_VIDEO,
            paths = SdVideoComponentPaths(fullModelPath = "/models/animatediff.safetensors")
        )

        val incompatible = groups.single { it.kind == VideoComponentRequirementKind.INCOMPATIBLE }
        assertEquals(listOf(SdVideoComponentRole.FULL_MODEL), incompatible.roles)
    }

    @Test
    fun `unknown profile explains selection and does not claim optional or incompatible`() {
        val groups = videoComponentRequirementGroups(
            profile = null,
            workflow = SdVideoWorkflow.TEXT_TO_VIDEO,
            paths = SdVideoComponentPaths(fullModelPath = "/models/custom.safetensors")
        )

        assertEquals(1, groups.size)
        assertEquals(VideoComponentRequirementKind.UNKNOWN, groups.single().kind)
        assertTrue(SdVideoComponentRole.FULL_MODEL in groups.single().roles)
        assertTrue(SdVideoComponentRole.DIFFUSION_MODEL in groups.single().roles)
        assertFalse(groups.any { it.kind == VideoComponentRequirementKind.INCOMPATIBLE })
        assertFalse(groups.any { it.kind == VideoComponentRequirementKind.OPTIONAL })
    }

    private fun model(path: String) = ModelEntity(
        filename = path.substringAfterLast('/'),
        path = path,
        sizeBytes = 1L,
        type = ModelType.LLM,
        repoId = "test"
    )
}
