package com.example.llamadroid.harness.transfer

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Rebase structural project links before independently named copies are published. */
internal object HarnessTransferProjectLinks {
    fun prepare(projects: File, folderMapper: (String) -> String) {
        val root = projects.toPath().toAbsolutePath().normalize()
        fun destination(path: Path): Path {
            require(path.startsWith(root) && path != root) { "TRANSFER_PROJECT_LINK_ESCAPE" }
            val relative = root.relativize(path)
            val folder = folderMapper(relative.getName(0).toString())
            require(folder.isNotBlank() && folder !in setOf(".", "..") && '/' !in folder && '\\' !in folder) {
                "TRANSFER_PROJECT_LINK_INVALID"
            }
            var result = root.resolve(folder)
            for (index in 1 until relative.nameCount) result = result.resolve(relative.getName(index))
            return result
        }
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (attributes.isSymbolicLink) {
                    val original = Files.readSymbolicLink(path)
                    require(!original.isAbsolute) { "TRANSFER_PROJECT_LINK_ESCAPE" }
                    val target = path.parent.resolve(original).normalize()
                    val mapped = destination(path).parent.relativize(destination(target))
                    if (mapped != original) {
                        Files.delete(path)
                        Files.createSymbolicLink(path, mapped)
                    }
                }
                return FileVisitResult.CONTINUE
            }
        })
    }
}
