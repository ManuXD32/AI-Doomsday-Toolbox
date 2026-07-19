package com.example.llamadroid.data.model

import com.example.llamadroid.data.db.ModelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ModelLibraryManagerFilenameTest {

    @Test
    fun canonicalFilename_sanitizesWithoutCreatingUniqueSuffixes() {
        assertEquals(
            "gemma-4-E2B-it-Q4_K_M.gguf",
            ModelLibraryManager.canonicalFilename("gemma-4-E2B-it-Q4_K_M.gguf")
        )
        assertEquals(
            "folder_model.gguf",
            ModelLibraryManager.canonicalFilename("folder/model.gguf")
        )
        assertEquals(
            "model.bin",
            ModelLibraryManager.canonicalFilename("   ")
        )
    }

    @Test
    fun canonicalLibrarySupport_onlyCoversFileBasedNativeFamilies() {
        assertFalse(ModelLibraryManager.supportsCanonicalLibrary(ModelType.LLM))
        assertFalse(ModelLibraryManager.supportsCanonicalLibrary(ModelType.WHISPER))
        assertFalse(ModelLibraryManager.supportsCanonicalLibrary(ModelType.SD_DIFFUSION))
        assertFalse(ModelLibraryManager.supportsCanonicalLibrary(ModelType.ONNX_IMAGE_GEN))
        assertFalse(ModelLibraryManager.supportsCanonicalLibrary(ModelType.QUADTRIX))
    }

    @Test
    fun runtimeMirrorSupport_matchesFamiliesThatNeedFilesystemPaths() {
        assertFalse(ModelLibraryManager.requiresRuntimeMirror(ModelType.LLM))
        assertFalse(ModelLibraryManager.requiresRuntimeMirror(ModelType.VISION_PROJECTOR))
        assertFalse(ModelLibraryManager.requiresRuntimeMirror(ModelType.SD_CHECKPOINT))
        assertFalse(ModelLibraryManager.requiresRuntimeMirror(ModelType.ONNX_TTS))
        assertFalse(ModelLibraryManager.requiresRuntimeMirror(ModelType.QUADTRIX))
    }
}
