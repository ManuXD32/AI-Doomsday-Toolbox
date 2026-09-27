package com.example.llamadroid.service

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Readers never touch a writer's staging file; rename publishes one complete metadata snapshot. */
internal fun writeLlamaServerMetadata(file: File, json: String) {
    val parent = requireNotNull(file.parentFile)
    check(parent.isDirectory || parent.mkdirs())
    val staging = File.createTempFile(".${file.name}.", ".pending", parent)
    try {
        FileOutputStream(staging).use { stream ->
            stream.write(json.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        Files.move(staging.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        // This is only our unpublished temporary metadata, never another owner's runtime state.
        // A failed write leaves the previous complete snapshot intact.
        staging.delete()
    }
}
