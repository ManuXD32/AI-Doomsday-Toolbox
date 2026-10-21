package com.example.llamadroid.service.filestation.volume

/** One DocumentsContract child row (pure data so it can be mapped and tested on the JVM). */
data class SafRow(
    val docId: String,
    val name: String,
    val mime: String,
    val size: Long,
    val mtime: Long,
    val flags: Int,
) {
    val isDir: Boolean get() = mime == DIR_MIME

    companion object {
        const val DIR_MIME = "vnd.android.document/directory"
        const val FLAG_SUPPORTS_COPY = 128
        const val FLAG_SUPPORTS_MOVE = 256
    }
}

object SafRows {
    /** Maps a cursor row to an [FsEntry] below [parentPath]. */
    fun toEntry(parentPath: String, row: SafRow): FsEntry {
        val size = if (row.isDir || row.size < 0) -1L else row.size
        return FsEntry(
            name = row.name,
            path = FsPaths.join(parentPath, row.name),
            isDir = row.isDir,
            size = size,
            mtime = row.mtime,
            mime = if (row.isDir) "inode/directory"
            else row.mime.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                ?: FsPaths.guessMime(row.name),
            etag = "\"$size-${row.mtime}\"",
        )
    }

    /** Filesystem path of an ExternalStorageProvider docId ("primary:Docs/a" -> base + "/Docs/a"), else null. */
    fun externalStoragePath(docId: String, primaryRoot: String): String? {
        val colon = docId.indexOf(':')
        if (colon <= 0) return null
        val volume = docId.substring(0, colon)
        val rel = docId.substring(colon + 1)
        val base = if (volume == "primary") primaryRoot else "/storage/$volume"
        if (volume != "primary" && !volume.matches(Regex("[A-Za-z0-9-]+"))) return null
        return if (rel.isEmpty()) base else "$base/$rel"
    }
}

/**
 * LRU path -> docId cache (default ~4096). Writes invalidate a path subtree; [clear] drops
 * everything. Thread-safe.
 */
class SafPathCache(private val capacity: Int = 4096) {
    private val map = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > capacity
    }

    @Synchronized fun get(path: String): String? = map[path]

    @Synchronized fun put(path: String, docId: String) { map[path] = docId }

    @Synchronized fun invalidateSubtree(path: String) {
        if (path.isEmpty()) { map.clear(); return }
        val it = map.keys.iterator()
        while (it.hasNext()) {
            val k = it.next()
            if (k == path || k.startsWith("$path/")) it.remove()
        }
    }

    @Synchronized fun clear() = map.clear()

    @Synchronized fun size(): Int = map.size
}
