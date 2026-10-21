package com.example.llamadroid.service.filestation

import androidx.core.net.toUri
import android.content.Context
import android.content.res.AssetManager
import com.example.llamadroid.service.filestation.http.AssetSource
import com.example.llamadroid.service.filestation.volume.FileStationVolume
import com.example.llamadroid.service.filestation.volume.SafVolume
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/** [AssetSource] over the bundled `file_station_webui/` asset folder; lengths are measured once and cached. */
class AndroidAssetSource(
    private val assets: AssetManager,
    private val root: String = "file_station_webui",
) : AssetSource {
    private val lengths = ConcurrentHashMap<String, Long>()

    override fun open(path: String): InputStream? = try {
        assets.open("$root/$path")
    } catch (_: FileNotFoundException) {
        null
    } catch (_: java.io.IOException) {
        null
    }

    override fun length(path: String): Long {
        lengths[path]?.let { return it }
        val len = try {
            assets.openFd("$root/$path").use { it.length }
        } catch (_: java.io.IOException) {
            // Compressed assets have no file descriptor; count the bytes once.
            val stream = open(path) ?: return -1
            stream.use { s ->
                val buf = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    total += n
                }
                total
            }
        }
        lengths[path] = len
        return len
    }
}

/** Result of turning persisted [VolumeConfig]s into live volumes. */
class VolumeBuildResult(val volumes: List<FileStationVolume>, val lostLabels: List<String>)

object FileStationVolumes {
    /**
     * One [SafVolume] per config whose persisted tree permission is still held. Volumes with lost
     * permission or an unusable URI are skipped and reported in [VolumeBuildResult.lostLabels].
     */
    fun build(context: Context, configs: List<VolumeConfig>): VolumeBuildResult {
        val persisted = context.contentResolver.persistedUriPermissions
        val volumes = ArrayList<FileStationVolume>()
        val lost = ArrayList<String>()
        for (c in configs) {
            val uri = runCatching { c.treeUri.toUri() }.getOrNull()
            val granted = uri != null && persisted.any {
                it.uri == uri && it.isReadPermission && (c.readOnly || it.isWritePermission)
            }
            if (!granted) { lost += c.label; continue }
            runCatching { SafVolume(context.applicationContext, uri!!, c.id, c.label, c.readOnly) }
                .onSuccess { volumes += it }
                .onFailure { lost += c.label }
        }
        return VolumeBuildResult(volumes, lost)
    }
}
