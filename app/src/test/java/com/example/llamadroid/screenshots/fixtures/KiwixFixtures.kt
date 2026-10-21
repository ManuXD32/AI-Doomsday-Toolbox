package com.example.llamadroid.screenshots.fixtures

import android.app.Application
import android.content.ComponentName
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ZimEntity
import com.example.llamadroid.screenshots.harness.ShotLocale
import com.example.llamadroid.service.KiwixService
import com.example.llamadroid.service.ZimShareService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.RandomAccessFile

/**
 * Offline library with a handful of ZIMs (sparse placeholder files with realistic sizes) and the Kiwix
 * server / ZIM file server shown as running. The screens bind to the services, so Robolectric hands them
 * real service instances whose state flows are set by reflection (no sockets, no processes).
 */
object KiwixFixtures {
    private const val GIB = 1024L * 1024L * 1024L

    private data class Zim(
        val id: String, val file: String, val title: String, val description: String, val language: String,
        val sizeBytes: Long, val articles: Long, val media: Long, val date: String, val publisher: String
    )

    private fun zims(l: ShotLocale): List<Zim> = if (l == ShotLocale.ES) listOf(
        Zim("zim-wiki-es", "wikipedia_es_all_mini_2026-09.zim", "Wikipedia (Español)", "La enciclopedia libre, versión compacta", "es", 3 * GIB + GIB / 2, 1_960_000, 0, "2026-09-12", "Kiwix"),
        Zim("zim-wikimed-es", "wikipedia_es_medicine_maxi_2026-08.zim", "WikiMed (Español)", "Medicina y salud de Wikipedia", "es", 2 * GIB + GIB / 3, 41_300, 8_200, "2026-08-27", "Kiwix"),
        Zim("zim-ifixit-es", "ifixit_es_all_2026-07.zim", "iFixit (Español)", "Guías de reparación para casi todo", "es", 3 * GIB + GIB / 5, 33_700, 96_000, "2026-07-30", "Kiwix"),
        Zim("zim-outdoors", "outdoors.stackexchange.com_en_all_2026-08.zim", "Outdoors Stack Exchange", "Preguntas y respuestas sobre naturaleza y supervivencia", "en", GIB + GIB / 8, 24_100, 3_400, "2026-08-14", "Stack Exchange")
    ) else listOf(
        Zim("zim-wiki-en", "wikipedia_en_all_mini_2026-09.zim", "Wikipedia (English)", "The free encyclopedia, compact edition", "en", 11 * GIB + GIB / 3, 6_950_000, 0, "2026-09-12", "Kiwix"),
        Zim("zim-wikimed-en", "wikipedia_en_medicine_maxi_2026-08.zim", "WikiMed (English)", "Medical and health articles from Wikipedia", "en", 2 * GIB + GIB / 2, 73_800, 12_100, "2026-08-27", "Kiwix"),
        Zim("zim-ifixit-en", "ifixit_en_all_2026-07.zim", "iFixit (English)", "Repair guides for almost everything", "en", 3 * GIB + GIB / 3, 47_900, 142_000, "2026-07-30", "Kiwix"),
        Zim("zim-outdoors", "outdoors.stackexchange.com_en_all_2026-08.zim", "Outdoors Stack Exchange", "Questions and answers about the outdoors and survival", "en", GIB + GIB / 8, 24_100, 3_400, "2026-08-14", "Stack Exchange")
    )

    fun seedZims(app: Application, locale: ShotLocale) {
        val dir = File(app.getExternalFilesDir(null), "zim_downloads").apply { mkdirs() }
        runBlocking {
            val dao = AppDatabase.getDatabase(app).zimDao()
            zims(locale).forEach { z ->
                val file = File(dir, z.file)
                RandomAccessFile(file, "rw").use { it.setLength(z.sizeBytes) } // sparse placeholder
                dao.insertZim(
                    ZimEntity(
                        id = z.id, filename = z.file, path = file.absolutePath, title = z.title,
                        description = z.description, language = z.language, sizeBytes = z.sizeBytes,
                        articleCount = z.articles, mediaCount = z.media, date = z.date,
                        creator = z.publisher, publisher = z.publisher
                    )
                )
            }
        }
    }

    private fun setFlow(service: Any, name: String, value: Any?) {
        @Suppress("UNCHECKED_CAST")
        val flow = service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service) as MutableStateFlow<Any?>
        flow.value = value
    }

    /** Kiwix server card shows the "running" variant (Stop button). */
    fun runKiwixServer(app: Application) {
        val service = Robolectric.buildService(KiwixService::class.java).create().get()
        setFlow(service, "_isRunning", true)
        setFlow(service, "_serverUrl", "http://192.168.1.10:8888")
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, KiwixService::class.java), service.onBind(null))
    }

    /** ZIM file-share tab with URLs + QR codes. */
    fun runZimShare(app: Application) {
        val service = Robolectric.buildService(ZimShareService::class.java).create().get()
        setFlow(service, "_isRunning", true)
        setFlow(service, "_serverUrls", listOf("wlan0" to "http://192.168.1.10:8090", "eth0" to "http://10.0.0.14:8090"))
        setFlow(service, "_activeDownloads", 1)
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, ZimShareService::class.java), service.onBind(null))
    }
}
