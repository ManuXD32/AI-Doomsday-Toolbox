package com.example.llamadroid.service

import org.junit.Assert.*
import org.junit.Test

class VideoLaunchProfileCompatibilityTest {
    @Test fun olderProfilesKeepVideoDisabledAndExistingProjector() {
        val profile = LlamaServerLaunchProfile.decode(
            """{"schemaVersion":4,"modelPath":"/models/llm.gguf","mmprojPath":"/models/vision.gguf","visionEnabled":true}"""
        )!!
        assertFalse(profile.videoEnabled)
        assertEquals("/models/vision.gguf", profile.mmprojPath)
        assertTrue(NativeLlamaVideoSupport.commandArgs(profile.toLlamaConfig()).isEmpty())
    }

    @Test fun videoPolicyRoundTripsWithoutPersistingRuntimePaths() {
        val profile = LlamaServerLaunchProfile(
            modelPath = "/models/video.gguf", mmprojPath = "/models/projector.gguf",
            videoEnabled = true, videoFps = 0.8f, videoTimestampIntervalMs = 5000,
            mediaPath = "/private/current-install/media",
            videoFfmpegDir = "/private/current-install/ffmpeg"
        )
        val encoded = LlamaServerLaunchProfile.encode(profile)
        val decoded = LlamaServerLaunchProfile.decode(encoded)!!
        assertTrue(decoded.videoEnabled)
        assertEquals(0.8f, decoded.videoFps, 0.001f)
        assertFalse(encoded.contains("videoFfmpegDir"))
        assertFalse(encoded.contains("mediaPath"))
        val args = NativeLlamaVideoSupport.commandArgs(decoded.toLlamaConfig())
        assertEquals("0.8", args[args.indexOf("--video-fps") + 1])
        assertEquals("5000", args[args.indexOf("--video-timestamp-interval") + 1])
    }

    @Test fun runtimeEncodingRetainsPathsAndReplacesStaleManagedFlags() {
        val profile = LlamaServerLaunchProfile(
            modelPath = "/models/video.gguf",
            mmprojPath = "/models/projector.gguf",
            videoEnabled = true,
            mediaPath = "/private/current-install/media",
            videoFfmpegDir = "/private/current-install/ffmpeg",
            customFlags = "--media-path /private/stale/media --video-ffmpeg-dir /private/stale/ffmpeg " +
                "--video-fps 0.25 --video-timestamp-interval 250"
        )

        val durable = LlamaServerLaunchProfile.encode(profile)
        assertFalse(durable.contains("current-install"))
        assertFalse(durable.contains("stale"))

        val runtime = requireNotNull(
            LlamaServerLaunchProfile.decode(LlamaServerLaunchProfile.encodeForRuntime(profile))
        )
        assertEquals("/private/current-install/media", runtime.mediaPath)
        assertEquals("/private/current-install/ffmpeg", runtime.videoFfmpegDir)
        assertFalse(runtime.customFlags.orEmpty().contains("/private/stale"))
        assertFalse(runtime.customFlags.orEmpty().contains("0.25"))
        assertFalse(runtime.customFlags.orEmpty().contains("250"))
    }
}
