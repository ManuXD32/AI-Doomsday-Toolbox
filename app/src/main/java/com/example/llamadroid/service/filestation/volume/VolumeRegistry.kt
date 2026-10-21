package com.example.llamadroid.service.filestation.volume

import com.example.llamadroid.service.filestation.http.VolumeRegistryApi

enum class ConflictPolicy {
    RENAME, OVERWRITE, SKIP;

    companion object {
        /** Parses the API value; null/blank -> RENAME, unknown -> INVALID_PATH. */
        fun parse(value: String?): ConflictPolicy = when (value?.lowercase()) {
            null, "", "rename" -> RENAME
            "overwrite" -> OVERWRITE
            "skip" -> SKIP
            else -> throw FsException(FsErrorCode.INVALID_PATH, "unknown conflict policy: $value")
        }
    }
}

/** Live set of volumes plus cross-volume transfer and attachment-aware rename. */
class VolumeRegistry(
    volumes: List<FileStationVolume> = emptyList(),
    @Volatile override var trashRetentionDays: Int = 0,
    @Volatile override var showHidden: Boolean = false,
) : VolumeRegistryApi {
    @Volatile private var current: List<FileStationVolume> = volumes

    fun setVolumes(volumes: List<FileStationVolume>) { current = volumes.toList() }

    override fun list(): List<FileStationVolume> = current

    override fun get(id: String): FileStationVolume? = current.firstOrNull { it.id == id }

    override fun requireWritable(id: String): FileStationVolume {
        val v = get(id) ?: throw FsException(FsErrorCode.NOT_FOUND, "unknown volume: $id")
        if (v.readOnly) throw FsException(FsErrorCode.READ_ONLY, "volume is read-only: $id")
        return v
    }

    private fun require(id: String): FileStationVolume =
        get(id) ?: throw FsException(FsErrorCode.NOT_FOUND, "unknown volume: $id")

    /**
     * Renames [path] on [volId]; a document (non-folder, non-hidden) also takes its
     * `.attachments.<name>/` folder along. Returns the new entry.
     */
    fun rename(volId: String, path: String, newName: String): FsEntry {
        val vol = requireWritable(volId)
        val norm = FsPaths.normalize(path)
        val entry = vol.stat(norm)
        val newPath = vol.rename(norm, newName)
        if (!entry.isDir && !FsPaths.isHiddenName(entry.name)) {
            val oldAtt = FsPaths.attachmentsDirFor(norm)
            if (isDirectory(vol, oldAtt)) {
                // A stale attachments folder under the new name is left alone (rename then clashes silently).
                runCatching { vol.rename(oldAtt, FsPaths.ATTACHMENTS_PREFIX + FsPaths.name(newPath)) }
            }
        }
        return vol.stat(newPath)
    }

    /**
     * Moves or copies [srcPath] of [srcVolId] into folder [dstDir] of [dstVolId] under [policy].
     * Tries the provider fast path (same volume, unchanged name) then streams recursively.
     * Returns the resulting entry, or null when skipped. [progress] receives bytes copied so far;
     * [cancelled] is polled between buffers. A document's attachments folder travels with it.
     */
    fun transfer(
        srcVolId: String,
        srcPath: String,
        dstVolId: String,
        dstDir: String,
        move: Boolean,
        policy: ConflictPolicy = ConflictPolicy.RENAME,
        progress: (Long) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): FsEntry? {
        val dstVol = requireWritable(dstVolId)
        val srcVol = if (move) requireWritable(srcVolId) else require(srcVolId)
        val src = FsPaths.normalize(srcPath)
        val dst = FsPaths.normalize(dstDir)
        if (src.isEmpty()) throw FsException(FsErrorCode.INVALID_PATH, "cannot transfer a volume root")
        val srcEntry = srcVol.stat(src)
        if (!dstVol.stat(dst).isDir) throw FsException(FsErrorCode.NOT_FOUND, "destination is not a folder: $dst")
        val sameVol = srcVolId == dstVolId
        if (srcEntry.isDir && sameVol && FsPaths.isSameOrInside(dst, src)) {
            throw FsException(FsErrorCode.INVALID_PATH, "cannot put a folder into itself")
        }
        val tracker = Tracker(progress, cancelled)
        val result = transferNamed(srcVol, src, srcEntry, dstVol, dst, srcEntry.name, move, policy, tracker)
        if (result != null && !srcEntry.isDir && !FsPaths.isHiddenName(srcEntry.name)) {
            val att = FsPaths.attachmentsDirFor(src)
            val attEntry = statOrNull(srcVol, att)
            if (attEntry != null && attEntry.isDir) {
                val newAtt = FsPaths.ATTACHMENTS_PREFIX + result.name
                if (!(sameVol && FsPaths.join(dst, newAtt) == att)) {
                    transferNamed(srcVol, att, attEntry, dstVol, dst, newAtt, move, ConflictPolicy.OVERWRITE, tracker)
                }
            }
        }
        return result
    }

    private class Tracker(val onProgress: (Long) -> Unit, val cancelled: () -> Boolean) {
        var done = 0L
        fun add(n: Int) { done += n; onProgress(done) }
        fun check() { if (cancelled()) throw FsException(FsErrorCode.IO, "cancelled") }
    }

    private fun transferNamed(
        srcVol: FileStationVolume, src: String, srcEntry: FsEntry,
        dstVol: FileStationVolume, dstDir: String, wantedName: String,
        move: Boolean, policy: ConflictPolicy, tracker: Tracker,
    ): FsEntry? {
        val sameVol = srcVol.id == dstVol.id
        var name = wantedName
        var target = FsPaths.join(dstDir, name)
        if (sameVol && target == src) {
            if (move) return srcEntry
            // Copy onto itself always renames.
            name = FsPaths.conflictName(name) { statOrNull(dstVol, FsPaths.join(dstDir, it)) != null }
            target = FsPaths.join(dstDir, name)
        } else {
            val existing = statOrNull(dstVol, target)
            if (existing != null) {
                when (policy) {
                    ConflictPolicy.SKIP -> return null
                    ConflictPolicy.RENAME -> {
                        name = FsPaths.conflictName(name) { statOrNull(dstVol, FsPaths.join(dstDir, it)) != null }
                        target = FsPaths.join(dstDir, name)
                    }
                    ConflictPolicy.OVERWRITE -> {
                        if (sameVol && FsPaths.isSameOrInside(src, target)) {
                            throw FsException(FsErrorCode.INVALID_PATH, "cannot overwrite a parent of the source")
                        }
                        dstVol.delete(target)
                    }
                }
            }
        }
        if (sameVol && name == srcEntry.name) {
            val fast = if (move) srcVol.fastMove(src, dstDir) else srcVol.fastCopy(src, dstDir)
            if (fast) return dstVol.stat(target)
        }
        val needed = sizeOf(srcVol, src, srcEntry)
        val free = dstVol.freeBytes()
        if (free >= 0 && needed > free) throw FsException(FsErrorCode.NO_SPACE, "not enough free space")
        try {
            copyTree(srcVol, src, srcEntry, dstVol, target, tracker)
        } catch (t: Throwable) {
            runCatching { dstVol.delete(target) }
            throw t
        }
        if (move) srcVol.delete(src)
        return dstVol.stat(target)
    }

    private fun copyTree(
        srcVol: FileStationVolume, src: String, srcEntry: FsEntry,
        dstVol: FileStationVolume, dst: String, tracker: Tracker,
    ) {
        tracker.check()
        if (srcEntry.isDir) {
            dstVol.mkdirs(dst)
            for (child in srcVol.list(src)) {
                copyTree(srcVol, child.path, child, dstVol, FsPaths.join(dst, child.name), tracker)
            }
            return
        }
        val buf = ByteArray(64 * 1024)
        srcVol.openRead(src).use { input ->
            dstVol.openWrite(dst, append = false).use { out ->
                while (true) {
                    tracker.check()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    tracker.add(n)
                }
            }
        }
    }

    private fun sizeOf(vol: FileStationVolume, path: String, entry: FsEntry): Long =
        if (!entry.isDir) maxOf(entry.size, 0L)
        else vol.walk(path).filter { !it.isDir }.sumOf { maxOf(it.size, 0L) }

    private fun statOrNull(vol: FileStationVolume, path: String): FsEntry? =
        try { vol.stat(path) } catch (e: FsException) {
            if (e.code == FsErrorCode.NOT_FOUND) null else throw e
        }

    private fun isDirectory(vol: FileStationVolume, path: String): Boolean = statOrNull(vol, path)?.isDir == true
}
