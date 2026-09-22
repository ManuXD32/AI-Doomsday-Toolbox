package com.example.llamadroid.harness

import android.util.Log

/**
 * Tiny native seam for the one filesystem operation that cannot safely run
 * through PRoot's `link2symlink` translation. The native implementation uses
 * Linux `renameat2(RENAME_NOREPLACE)` and returns the errno instead of guessing
 * with a Java move/copy fallback.
 */
internal object HarnessAtomicFileNative {
    const val EEXIST = 17
    const val EINVAL = 22
    const val EACCES = 13
    const val EPERM = 1
    const val ENOSYS = 38
    const val EOPNOTSUPP = 95

    private const val TAG = "HarnessAtomicFile"
    private val loaded: Boolean

    init {
        loaded = runCatching {
            System.loadLibrary("cpufeatures")
            true
        }.getOrElse {
            Log.e(TAG, "Atomic publication native library unavailable", it)
            false
        }
    }

    /** Return zero on publication, or the exact Linux errno on failure. */
    fun publishNoReplace(rootPath: String, sourcePath: String, destinationPath: String): Int {
        if (!loaded) return ENOSYS
        return runCatching { nativePublishNoReplace(rootPath, sourcePath, destinationPath) }
            .getOrElse { ENOSYS }
    }

    private external fun nativePublishNoReplace(rootPath: String, sourcePath: String, destinationPath: String): Int
}
