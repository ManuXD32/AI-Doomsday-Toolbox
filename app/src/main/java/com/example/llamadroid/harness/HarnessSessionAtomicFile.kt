package com.example.llamadroid.harness

import android.content.Context
import com.example.llamadroid.harness.runtime.HarnessRuntimePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

private const val DSH_HOME_GUEST = "/root/.dsh"
private const val DSH_SESSION_DIRECTORY = "sessions"
private val NOFOLLOW = arrayOf(LinkOption.NOFOLLOW_LINKS)

/**
 * Publish one JSONL session generation inside the app-owned DSH home. This is
 * deliberately separate from workspace.atomicPublish: the only permitted
 * destination is the captured Session's own `sessions/<project>/<id>` folder.
 */
internal suspend fun atomicPublishHarnessSession(
    context: Context,
    sessionId: String,
    sourceGuestPath: String,
    destinationGuestPath: String,
): JSONObject = withContext(Dispatchers.IO) {
    require(validDeletionId(sessionId)) { "SESSION_ATOMIC_PUBLISH_INVALID_SESSION" }
    val root = HarnessRuntimePaths.harnessHome(context).canonicalFile.toPath()
    require(!Files.isSymbolicLink(root) && Files.isDirectory(root, *NOFOLLOW)) {
        "SESSION_ATOMIC_PUBLISH_HOME_INVALID"
    }
    val source = resolveDshSessionPath(root, sourceGuestPath)
    val destination = resolveDshSessionPath(root, destinationGuestPath)
    require(source.parent == destination.parent) { "SESSION_ATOMIC_PUBLISH_PARENT_MISMATCH" }
    val parent = requireNotNull(destination.parent)
    val relativeParent = root.relativize(parent)
    require(relativeParent.nameCount == 3 &&
        relativeParent.getName(0).toString() == DSH_SESSION_DIRECTORY &&
        relativeParent.getName(2).toString() == sessionId) {
        "SESSION_ATOMIC_PUBLISH_SCOPE"
    }
    verifyDshParent(root, parent)
    verifyDshSource(source)
    verifyDshDestination(destination)
    when (HarnessAtomicFileNative.publishNoReplace(root.toString(), source.toString(), destination.toString())) {
        0 -> {
            require(Files.isRegularFile(destination, *NOFOLLOW) && Files.isReadable(destination)) {
                "SESSION_ATOMIC_PUBLISH_INVALID_RESULT"
            }
            JSONObject().put("published", true)
        }
        HarnessAtomicFileNative.EEXIST -> error("SESSION_ATOMIC_PUBLISH_EXISTS")
        HarnessAtomicFileNative.ENOSYS, HarnessAtomicFileNative.EOPNOTSUPP ->
            error("SESSION_ATOMIC_PUBLISH_UNSUPPORTED")
        HarnessAtomicFileNative.EACCES, HarnessAtomicFileNative.EPERM ->
            error("SESSION_ATOMIC_PUBLISH_PERMISSION_DENIED")
        HarnessAtomicFileNative.EINVAL -> error("SESSION_ATOMIC_PUBLISH_INVALID")
        else -> error("SESSION_ATOMIC_PUBLISH_FAILED")
    }
}

private fun resolveDshSessionPath(root: Path, guestPath: String): Path {
    require(guestPath.startsWith("$DSH_HOME_GUEST/") && '\u0000' !in guestPath) {
        "SESSION_ATOMIC_PUBLISH_PATH"
    }
    val relative = guestPath.removePrefix("$DSH_HOME_GUEST/")
    val segments = relative.split('/')
    require(segments.isNotEmpty() && segments.all { it.isNotEmpty() && it != "." && it != ".." }) {
        "SESSION_ATOMIC_PUBLISH_PATH"
    }
    val candidate = root.resolve(Paths.get(relative)).normalize()
    require(candidate.startsWith(root) && candidate != root) { "SESSION_ATOMIC_PUBLISH_PATH" }
    return candidate
}

private fun verifyDshParent(root: Path, parent: Path) {
    require(parent.startsWith(root) && parent != root) { "SESSION_ATOMIC_PUBLISH_PARENT" }
    var current = root
    for (part in root.relativize(parent)) {
        current = current.resolve(part.toString())
        require(!Files.isSymbolicLink(current) && Files.isDirectory(current, *NOFOLLOW)) {
            "SESSION_ATOMIC_PUBLISH_PARENT"
        }
    }
}

private fun verifyDshSource(source: Path) {
    require(!Files.isSymbolicLink(source) && Files.isRegularFile(source, *NOFOLLOW)) {
        "SESSION_ATOMIC_PUBLISH_SOURCE"
    }
    require(Files.isReadable(source)) { "SESSION_ATOMIC_PUBLISH_SOURCE" }
}

private fun verifyDshDestination(destination: Path) {
    if (!Files.exists(destination, *NOFOLLOW) && !Files.isSymbolicLink(destination)) return
    require(!Files.isSymbolicLink(destination) && Files.isRegularFile(destination, *NOFOLLOW)) {
        "SESSION_ATOMIC_PUBLISH_DESTINATION"
    }
}
