package com.example.llamadroid.screenshots.fixtures

import android.app.Application
import com.example.llamadroid.audio.library.AudioLibraryItemEntity
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.screenshots.harness.ShotLocale
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.RandomAccessFile

/** Generated-audio library (speech, music, sound effects) with placeholder wav files. */
object AudioFixtures {
    private class Clip(val key: String, val title: String, val kind: String, val model: String, val voice: String?, val seconds: Int, val ageHours: Long)

    fun titles(l: ShotLocale): List<String> = clips(l).map { it.title }

    private fun clips(l: ShotLocale) = listOf(
        Clip("water_report", l.pick("Water report - morning briefing", "Informe del agua - resumen matinal"), "speech", "Supertonic", "Ava", 72, 1),
        Clip("rain_roof", l.pick("Rain on a tin roof", "Lluvia sobre un tejado de chapa"), "sfx", "Stable Audio Open", null, 12, 3),
        Clip("piano_loop", l.pick("Calm piano loop", "Bucle tranquilo de piano"), "music", "Stable Audio Open", null, 47, 5),
        Clip("first_aid", l.pick("First-aid checklist read aloud", "Lista de primeros auxilios leída en voz alta"), "speech", "Supertonic", "Noah", 138, 8),
        Clip("campfire", l.pick("Crackling campfire", "Fogata crepitante"), "sfx", "Stable Audio Open", null, 20, 20),
        Clip("field_guide", l.pick("Field guide chapter 3", "Capítulo 3 de la guía de campo"), "speech", "Supertonic", "Ava", 264, 30)
    )

    fun seed(app: Application, l: ShotLocale) {
        val dir = File(app.filesDir, "audio_outputs").apply { mkdirs() }
        val now = System.currentTimeMillis()
        runBlocking {
            val dao = AppDatabase.getDatabase(app).audioLibraryDao()
            clips(l).forEach { clip ->
                val file = File(dir, "${clip.key}.wav")
                RandomAccessFile(file, "rw").use { it.setLength(clip.seconds * 48_000L) }
                val created = now - clip.ageHours * 3_600_000L
                dao.insertItem(
                    AudioLibraryItemEntity(
                        id = "demo:${clip.key}", originKey = "demo:${clip.key}", title = clip.title, audioPath = file.absolutePath,
                        durationMs = clip.seconds * 1000L, sizeBytes = file.length(), createdAt = created, updatedAt = created,
                        modelName = clip.model, voiceName = clip.voice, language = l.tag, kind = clip.kind
                    )
                )
            }
        }
    }
}
