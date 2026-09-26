package com.example.llamadroid.harness

import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

private val NOFOLLOW = arrayOf(LinkOption.NOFOLLOW_LINKS)

internal data class HarnessAtomicPublicationPaths(
    val root: Path,
    val source: Path,
    val destination: Path,
)

/**
 * Resolve the two guest paths against the captured project root without
 * following a parent or final-entry symlink. The caller must still perform the
 * no-replace publication in one native operation after this validation.
 */
internal fun resolveHarnessAtomicPublicationPaths(
    scope: HarnessWorkspaceScope,
    sourceGuestPath: String,
    destinationGuestPath: String,
): HarnessAtomicPublicationPaths {
    require(scope.workspace.backend in setOf("LOCAL_PROOT", "LOCAL_SANDBOX")) {
        "ATOMIC_LOCAL_WORKSPACE_REQUIRED"
    }
    val root = Paths.get(requireNotNull(scope.localRoot) { "ATOMIC_LOCAL_WORKSPACE_REQUIRED" }.canonicalPath)
    require(!Files.isSymbolicLink(root) && Files.isDirectory(root, *NOFOLLOW)) { "ATOMIC_WORKSPACE_ROOT_INVALID" }
    val source = atomicPath(root, scope.workspace.guestPath, sourceGuestPath)
    val destination = atomicPath(root, scope.workspace.guestPath, destinationGuestPath)
    verifyAtomicParent(root, requireNotNull(source.parent))
    verifyAtomicParent(root, requireNotNull(destination.parent))
    verifyAtomicSource(source)
    verifyAtomicDestination(destination)
    return HarnessAtomicPublicationPaths(root, source, destination)
}

private fun atomicPath(root: Path, guestRoot: String, guestPath: String): Path {
    require(guestPath.isNotBlank() && '\u0000' !in guestPath) { "ATOMIC_PATH_INVALID" }
    val normalizedRoot = guestRoot.trimEnd('/')
    val relative = when {
        guestPath == normalizedRoot -> ""
        guestPath.startsWith("$normalizedRoot/") -> guestPath.removePrefix("$normalizedRoot/")
        guestPath.startsWith('/') -> throw IllegalArgumentException("ATOMIC_PATH_OUTSIDE_SCOPE")
        else -> guestPath
    }
    val segments = relative.split('/')
    require(segments.all { it.isNotEmpty() && it != "." && it != ".." }) { "ATOMIC_PATH_INVALID" }
    require(relative.isNotEmpty()) { "ATOMIC_PATH_INVALID" }
    val candidate = root.resolve(relative).normalize()
    require(candidate.startsWith(root) && candidate != root) { "ATOMIC_PATH_OUTSIDE_SCOPE" }
    return candidate
}

private fun verifyAtomicParent(root: Path, parent: Path) {
    require(parent.startsWith(root)) { "ATOMIC_PARENT_INVALID" }
    if (parent == root) return
    var current = root
    for (part in root.relativize(parent)) {
        current = current.resolve(part.toString())
        require(!Files.isSymbolicLink(current)) { "ATOMIC_PARENT_SYMLINK" }
        require(Files.isDirectory(current, *NOFOLLOW)) { "ATOMIC_PARENT_NOT_DIRECTORY" }
    }
}

private fun verifyAtomicSource(source: Path) {
    require(!Files.isSymbolicLink(source)) { "ATOMIC_SOURCE_SYMLINK" }
    require(Files.exists(source, *NOFOLLOW)) { "ATOMIC_SOURCE_MISSING" }
    require(Files.isRegularFile(source, *NOFOLLOW)) { "ATOMIC_SOURCE_NOT_REGULAR" }
    require(Files.isReadable(source)) { "ATOMIC_SOURCE_NOT_READABLE" }
}

private fun verifyAtomicDestination(destination: Path) {
    if (!Files.exists(destination, *NOFOLLOW) && !Files.isSymbolicLink(destination)) return
    require(!Files.isSymbolicLink(destination)) { "ATOMIC_DESTINATION_SYMLINK" }
    require(Files.isRegularFile(destination, *NOFOLLOW)) { "ATOMIC_DESTINATION_NOT_REGULAR" }
}

/**
 * Publish a dsh-fs-local staging file through Android's scoped native
 * primitive. Upstream has already opened, written, and synced the source;
 * this method deliberately does not rename, copy, or delete on its own.
 */
suspend fun HarnessWorkspaceAccess.atomicPublish(
    scope: HarnessSessionScope,
    sourceGuestPath: String,
    destinationGuestPath: String,
): JSONObject = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val paths = resolveHarnessAtomicPublicationPaths(scope, sourceGuestPath, destinationGuestPath)
    when (HarnessAtomicFileNative.publishNoReplace(
        paths.root.toString(), paths.source.toString(), paths.destination.toString()
    )) {
        0 -> {
            require(Files.isRegularFile(paths.destination, *NOFOLLOW)) { "ATOMIC_DESTINATION_NOT_REGULAR" }
            require(Files.isReadable(paths.destination)) { "ATOMIC_DESTINATION_NOT_READABLE" }
            JSONObject().put("published", true)
        }
        HarnessAtomicFileNative.EEXIST -> error("ATOMIC_PUBLISH_EXISTS")
        HarnessAtomicFileNative.ENOSYS, HarnessAtomicFileNative.EOPNOTSUPP -> error("ATOMIC_PUBLISH_UNSUPPORTED")
        HarnessAtomicFileNative.EACCES, HarnessAtomicFileNative.EPERM -> error("ATOMIC_PUBLISH_PERMISSION_DENIED")
        HarnessAtomicFileNative.EINVAL -> error("ATOMIC_PUBLISH_INVALID")
        else -> error("ATOMIC_PUBLISH_FAILED")
    }
}
