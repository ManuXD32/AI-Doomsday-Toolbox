package com.example.llamadroid.screenshots.fixtures

import android.content.Context
import java.io.File

/** Copies the committed demo images (`src/test/resources/screenshots/media`) into app storage. */
object MediaFixtures {
    val names = listOf(
        "aurora_igloo", "beach_cabana", "blossom_tea", "butterfly_atrium", "coral_tide", "firefly_porch",
        "gingerbread", "ice_cream", "pumpkin_den", "raincloud", "starlit_toys", "garden_keeper"
    )

    fun demoBytes(name: String): ByteArray =
        checkNotNull(MediaFixtures::class.java.getResourceAsStream("/screenshots/media/$name.jpg")) {
            "Missing demo image $name"
        }.use { it.readBytes() }

    /** Copies one demo image to `<files>/<dir>/<fileName>` with the given mtime and returns it. */
    fun copy(context: Context, name: String, dir: String, fileName: String = "$name.jpg", modified: Long? = null): File {
        val target = File(context.filesDir, "$dir/$fileName").apply { parentFile?.mkdirs() }
        target.writeBytes(demoBytes(name))
        if (modified != null) target.setLastModified(modified)
        return target
    }

    /** Fills `sd_output` with all demo images (newest first by mtime). */
    fun seedSdGallery(context: Context) {
        val now = System.currentTimeMillis()
        names.forEachIndexed { index, name ->
            copy(context, name, "sd_output", "txt2img_${1000 + index}.jpg", now - index * 3_600_000L)
        }
    }

    /** Puts the first demo image into the txt2img state holder as a finished generation. */
    fun showLatestResult(context: Context, locale: com.example.llamadroid.screenshots.harness.ShotLocale) {
        val file = File(context.filesDir, "sd_output/txt2img_1000.jpg")
        com.example.llamadroid.service.SDModeStateHolder.txt2img.apply {
            updatePrompt(locale.pick(
                "cozy aurora igloo room, soft pastel colors, kawaii illustration",
                "habitación iglú con aurora boreal, colores pastel suaves, ilustración kawaii"
            ))
            updateState(com.example.llamadroid.service.SDGenerationState.Complete(file.absolutePath))
        }
    }

    fun reset() {
        com.example.llamadroid.service.SDModeStateHolder.txt2img.apply { clearGallery(); reset() }
    }
}
