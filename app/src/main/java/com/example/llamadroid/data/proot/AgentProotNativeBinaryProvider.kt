package com.example.llamadroid.data.proot

import android.content.Context
import java.io.File

/**
 * Locates executables shipped in the arm64 APK native library directory.
 *
 * These files are intentionally never copied from network/storage into filesDir. Android's
 * target-29 execution restriction is the reason the PRoot executable and broker are packaged
 * as signed native artifacts. A missing artifact is a release/configuration error, not a
 * reason to download an executable at runtime.
 */
object AgentProotNativeBinaryProvider {
    enum class Binary(val fileName: String) {
        PROOT("libproot.so"),
        BROKER("libproot_broker.so"),
        LOADER("libproot_loader.so")
    }

    fun locate(context: Context, binary: Binary): File? {
        val nativeRoot = context.applicationInfo.nativeLibraryDir?.let(::File) ?: return null
        val file = File(nativeRoot, binary.fileName).canonicalFile
        if (!file.path.startsWith(nativeRoot.canonicalPath + File.separator)) return null
        return file.takeIf { it.isFile && it.canExecute() }
    }

    fun requireProot(context: Context): File = locate(context, Binary.PROOT)
        ?: error("Packaged PRoot executable is unavailable for this ABI")

    fun requireBroker(context: Context): File = locate(context, Binary.BROKER)
        ?: error("Packaged PRoot process broker is unavailable for this ABI")

    fun locateBroker(context: Context): File? = locate(context, Binary.BROKER)

    fun locateLoader(context: Context): File? = locate(context, Binary.LOADER)
}
