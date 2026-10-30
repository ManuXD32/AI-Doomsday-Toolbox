package com.example.llamadroid.service.filestation.volume

import com.example.llamadroid.service.filestation.http.Clock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class TrashItem(
    val id: String,
    val vol: String,
    val name: String,
    val originalPath: String,
    val deletedAt: Long,
    val size: Long,
    val isDir: Boolean,
)

/**
 * Per-volume trash at `<vol>/.filestation-trash/<id>/<name>` with `index.json` beside the id
 * folders. A document's `.attachments.<name>/` folder travels with it (VolumeRegistry.transfer).
 * Disabled (READ_ONLY) on read-only volumes.
 */
class TrashStore(
    private val registry: VolumeRegistry,
    private val clock: Clock = Clock { System.currentTimeMillis() },
) {
    private val indexPath = FsPaths.join(FsPaths.TRASH_DIR, "index.json")

    fun isEnabled(volId: String): Boolean = registry.get(volId)?.readOnly == false

    @Synchronized
    fun trash(volId: String, path: String): TrashItem {
        val vol = registry.requireWritable(volId)
        val norm = FsPaths.normalize(path)
        if (norm.isEmpty()) throw FsException(FsErrorCode.INVALID_PATH, "cannot delete a volume root")
        if (FsPaths.isSameOrInside(norm, FsPaths.TRASH_DIR)) {
            throw FsException(FsErrorCode.INVALID_PATH, "already in the trash")
        }
        val entry = vol.stat(norm)
        val size = if (entry.isDir) vol.walk(norm).filter { !it.isDir }.sumOf { maxOf(it.size, 0L) }
        else maxOf(entry.size, 0L)
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val dir = FsPaths.join(FsPaths.TRASH_DIR, id)
        vol.mkdirs(dir)
        try {
            registry.transfer(volId, norm, volId, dir, move = true, policy = ConflictPolicy.OVERWRITE)
        } catch (t: Throwable) {
            runCatching { vol.delete(dir) }
            throw t
        }
        val item = TrashItem(id, volId, entry.name, norm, clock.nowMillis(), size, entry.isDir)
        writeIndex(vol, readIndex(vol, volId) + item)
        return item
    }

    @Synchronized
    fun list(volId: String): List<TrashItem> {
        val vol = registry.get(volId) ?: throw FsException(FsErrorCode.NOT_FOUND, "unknown volume: $volId")
        return readIndex(vol, volId).filter { exists(vol, FsPaths.join(FsPaths.join(FsPaths.TRASH_DIR, it.id), it.name)) }
    }

    /** Restores [ids] to their original folders (recreated if missing). Skipped items stay in the trash. */
    @Synchronized
    fun restore(volId: String, ids: List<String>, policy: ConflictPolicy = ConflictPolicy.RENAME): List<FsEntry> {
        val vol = registry.requireWritable(volId)
        val items = readIndex(vol, volId)
        val restored = ArrayList<FsEntry>()
        val done = HashSet<String>()
        for (id in ids) {
            val item = items.firstOrNull { it.id == id } ?: continue
            val dir = FsPaths.join(FsPaths.TRASH_DIR, id)
            val parent = FsPaths.parent(item.originalPath)
            if (parent.isNotEmpty()) vol.mkdirs(parent)
            val entry = registry.transfer(
                volId, FsPaths.join(dir, item.name), volId, parent, move = true, policy = policy,
            ) ?: continue
            restored += entry
            done += id
            runCatching { vol.delete(dir) }
        }
        if (done.isNotEmpty()) writeIndex(vol, items.filter { it.id !in done })
        return restored
    }

    /** Permanently removes [ids] (null = everything, including orphans). Returns the number of items removed. */
    @Synchronized
    fun empty(volId: String, ids: List<String>? = null): Int {
        val vol = registry.requireWritable(volId)
        val items = readIndex(vol, volId)
        if (ids == null) {
            if (exists(vol, FsPaths.TRASH_DIR)) vol.delete(FsPaths.TRASH_DIR)
            return items.size
        }
        val gone = items.filter { it.id in ids }
        for (item in gone) {
            val dir = FsPaths.join(FsPaths.TRASH_DIR, item.id)
            if (exists(vol, dir)) vol.delete(dir)
        }
        if (gone.isNotEmpty()) writeIndex(vol, items - gone.toSet())
        return gone.size
    }

    /** Deletes items older than [retentionDays] (<= 0 keeps everything). Returns the number purged. */
    @Synchronized
    fun purge(volId: String, retentionDays: Int): Int {
        if (retentionDays <= 0) return 0
        val vol = registry.get(volId) ?: return 0
        if (vol.readOnly) return 0
        val cutoff = clock.nowMillis() - retentionDays * 86_400_000L
        val old = readIndex(vol, volId).filter { it.deletedAt < cutoff }.map { it.id }
        return if (old.isEmpty()) 0 else empty(volId, old)
    }

    /** Purges every writable volume using the registry's retention setting. */
    fun purgeAll(): Int = registry.list().sumOf { runCatching { purge(it.id, registry.trashRetentionDays) }.getOrDefault(0) }

    private fun exists(vol: FileStationVolume, path: String): Boolean = try {
        vol.stat(path); true
    } catch (e: FsException) {
        if (e.code == FsErrorCode.NOT_FOUND) false else throw e
    }

    private fun readIndex(vol: FileStationVolume, volId: String): List<TrashItem> {
        if (!exists(vol, indexPath)) return emptyList()
        return try {
            val text = vol.openRead(indexPath).use { it.readBytes().toString(Charsets.UTF_8) }
            val arr = JSONArray(text)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TrashItem(
                    id = o.getString("id"), vol = volId, name = o.getString("name"),
                    originalPath = o.getString("originalPath"), deletedAt = o.getLong("deletedAt"),
                    size = o.optLong("size", 0), isDir = o.optBoolean("isDir", false),
                )
            }
        } catch (_: org.json.JSONException) {
            emptyList()
        }
    }

    private fun writeIndex(vol: FileStationVolume, items: List<TrashItem>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("originalPath", it.originalPath)
                .put("deletedAt", it.deletedAt).put("size", it.size).put("isDir", it.isDir))
        }
        vol.mkdirs(FsPaths.TRASH_DIR)
        vol.openWrite(indexPath, append = false).use { it.write(arr.toString().toByteArray(Charsets.UTF_8)) }
    }
}
