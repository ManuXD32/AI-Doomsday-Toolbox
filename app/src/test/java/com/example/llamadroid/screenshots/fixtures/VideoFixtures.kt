package com.example.llamadroid.screenshots.fixtures

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.llamadroid.screenshots.harness.ShotLocale
import org.json.JSONObject
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import java.io.File
import java.io.RandomAccessFile

/**
 * Generated-video gallery: metadata JSON + placeholder mp4 files under `filesDir/video_gen_output`,
 * with the first frame supplied through the Robolectric media-retriever shadow from the demo images.
 */
object VideoFixtures {
    private class Clip(val folder: String, val image: String, val prompt: String, val model: String, val frames: Int, val fps: Int, val w: Int, val h: Int, val steps: Int, val ageHours: Long)

    private fun clips(l: ShotLocale) = listOf(
        Clip("txt2vid", "aurora_igloo.jpg", l.pick(
            "A cozy igloo room with northern lights glowing through the window, snowflakes drifting, slow camera push-in",
            "Una acogedora habitación iglú con auroras boreales brillando por la ventana, copos de nieve a la deriva, lento acercamiento de cámara"
        ), "wan2.2-ti2v-5b-Q4_K_M.gguf", 49, 16, 832, 480, 20, 2),
        Clip("img2vid", "firefly_porch.jpg", l.pick(
            "Fireflies drifting outside a lantern-lit window at night while a rocking chair sways gently",
            "Luciérnagas a la deriva junto a una ventana iluminada por faroles de noche mientras una mecedora se balancea suavemente"
        ), "wan2.2-ti2v-5b-Q4_K_M.gguf", 33, 16, 704, 480, 24, 5),
        Clip("txt2vid", "coral_tide.jpg", l.pick(
            "A bright underwater room with a coral view through the arched window, bubbles floating upward",
            "Una luminosa habitación submarina con vista a los corales por la ventana arqueada, burbujas flotando hacia arriba"
        ), "ltx-video-2b-Q8_0.gguf", 65, 24, 768, 512, 28, 9)
    )

    fun firstPromptStart(l: ShotLocale): String = clips(l)[0].prompt.take(24)

    private fun demoBitmap(name: String): Bitmap {
        val stream = checkNotNull(VideoFixtures::class.java.classLoader?.getResourceAsStream("screenshots/media/$name")) {
            "Missing demo image screenshots/media/$name"
        }
        return stream.use { checkNotNull(BitmapFactory.decodeStream(it)) { "Cannot decode $name" } }
    }

    fun seedGallery(app: Application, l: ShotLocale) {
        val root = File(app.filesDir, "video_gen_output")
        val now = System.currentTimeMillis()
        clips(l).forEachIndexed { index, clip ->
            val dir = File(root, clip.folder).apply { mkdirs() }
            val base = "demo_video_${index + 1}"
            val mp4 = File(dir, "$base.mp4")
            RandomAccessFile(mp4, "rw").use { it.setLength(3L * 1024 * 1024 + index * 411_000L) }
            val meta = File(dir, "$base.json")
            val json = JSONObject().apply {
                put("mode", clip.folder)
                put("prompt", clip.prompt)
                put("diffusionModelPath", "/models/${clip.model}")
                put("diffusionModelName", clip.model)
                put("videoFrames", clip.frames)
                put("fps", clip.fps)
                put("width", clip.w)
                put("height", clip.h)
                put("steps", clip.steps)
                put("cfgScale", 6.0)
                put("createdAt", now - clip.ageHours * 3_600_000L)
                put("mp4Path", mp4.absolutePath)
                put("aviPath", File(dir, "$base.avi").absolutePath)
                put("metadataPath", meta.absolutePath)
                put("generationDurationMs", 412_000L + index * 63_000L)
            }
            meta.writeText(json.toString())
            ShadowMediaMetadataRetriever.addFrame(mp4.absolutePath, 0L, demoBitmap(clip.image))
        }
    }
}
